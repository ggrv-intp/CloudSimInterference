#!/usr/bin/env Rscript
# retrain.R — Domain-specific retraining of the IADA classifier.
#
# Regenerates the six .rda artifacts consumed by MLClassifier.java:
#   svm_model.rda   cpuk.rda   memk.rda   diskk.rda   netk.rda   cachek.rda
#
# Drop-in: the regenerated files keep the exact class names, feature
# ordering, and centroid layout the inference path expects. The Java
# side needs no changes.
#
# Usage:
#   Rscript R/retrain.R \
#       --dataset-root /path/to/dataset \
#       --output-dir   R/ \
#       [--k 3]              [--cv-folds 5]
#       [--cv-repeats 10]    [--seed 42]
#       [--dry-run]
#
# Expected --dataset-root layout (either is accepted):
#   (a) Per-class subdirs, any number of CSVs each:
#         cpu/      *.csv  → class "cpu"
#         memory/   *.csv  → class "mem"
#         disk/     *.csv  → class "disk"
#         network/  *.csv  → class "net"
#         cache/    *.csv  → class "cache"
#   (b) Legacy "forced/" flat layout (Meyer 2021 paper):
#         cpu100.csv, memory100.csv, disk100.csv, net100.csv, cache100.csv
#         (cache_miss.csv is loaded if present but excluded from training,
#          matching upstream input_dataset.R behaviour.)
#
# All CSVs must be ";"-separated, no header, 7 numeric columns in the
# order (netp, nets, blk, mbw, llcmr, llcocc, cpu).

suppressPackageStartupMessages({
  library(e1071)
  library(dplyr)
})

# ─── argparse (base-R, no extra dep) ─────────────────────────────────────────
parse_args <- function(argv) {
  opt <- list(
    dataset_root = NULL,
    output_dir   = NULL,
    k            = 3L,
    cv_folds     = 5L,
    cv_repeats   = 10L,
    seed         = 42L,
    dry_run      = FALSE,
    tier         = "T1"
  )
  i <- 1
  while (i <= length(argv)) {
    a <- argv[[i]]
    switch(a,
      "--dataset-root" = { opt$dataset_root <- argv[[i + 1]]; i <- i + 2 },
      "--output-dir"   = { opt$output_dir   <- argv[[i + 1]]; i <- i + 2 },
      "--k"            = { opt$k            <- as.integer(argv[[i + 1]]); i <- i + 2 },
      "--cv-folds"     = { opt$cv_folds     <- as.integer(argv[[i + 1]]); i <- i + 2 },
      "--cv-repeats"   = { opt$cv_repeats   <- as.integer(argv[[i + 1]]); i <- i + 2 },
      "--seed"         = { opt$seed         <- as.integer(argv[[i + 1]]); i <- i + 2 },
      "--tier"         = { opt$tier         <- argv[[i + 1]]; i <- i + 2 },
      "--dry-run"      = { opt$dry_run      <- TRUE;                       i <- i + 1 },
      "-h"             = { opt$help         <- TRUE;                       i <- i + 1 },
      "--help"         = { opt$help         <- TRUE;                       i <- i + 1 },
      stop(sprintf("unknown argument: %s", a))
    )
  }
  opt
}

usage <- function() {
  cat(paste(
    "Usage: Rscript R/retrain.R --dataset-root <PATH> [--output-dir R/]",
    "                          [--k 3] [--cv-folds 5] [--cv-repeats 10]",
    "                          [--seed 42] [--dry-run]",
    "",
    "See R/RETRAIN.md for dataset layout and metric interpretation.",
    sep = "\n"))
  cat("\n")
}

opt <- parse_args(commandArgs(trailingOnly = TRUE))
if (isTRUE(opt$help)) {
  usage()
  q(save = "no", status = 0)
}
if (is.null(opt$dataset_root)) stop("--dataset-root is required")
if (is.null(opt$output_dir))   opt$output_dir <- "R/"
if (!dir.exists(opt$output_dir)) dir.create(opt$output_dir, recursive = TRUE)

set.seed(opt$seed)

log_msg <- function(...) cat(sprintf("[retrain] %s\n", sprintf(...)))

FEATURES <- c("netp", "nets", "blk", "mbw", "llcmr", "llcocc", "cpu")
CLASSES  <- c("cpu", "mem", "disk", "net", "cache")

# Maps directory name → class label.
SUBDIR_TO_CLASS <- c(
  "cpu" = "cpu", "memory" = "mem", "disk" = "disk",
  "network" = "net", "net" = "net", "mem" = "mem", "cache" = "cache"
)

# Maps legacy flat-file basename → class label.
LEGACY_FILES <- c(
  "cpu100.csv"    = "cpu",
  "memory100.csv" = "mem",
  "disk100.csv"   = "disk",
  "net100.csv"    = "net",
  "cache100.csv"  = "cache"
  # cache_miss.csv intentionally omitted (excluded from rbind upstream).
)

# Per-class column index used by predict_<class>.kmeans for the
# "hig/mod/low" level assignment. Must match upstream R/kmeans.R.
LEVEL_COL <- list(cpu = 7L, mem = 4L, disk = 3L, net = 1L, cache = 6L)

# Approach B: full 15-metric fingerprint + a 6th "regime" (oversubscription)
# class. Level columns pick each class's discriminating VM-AVAILABLE metric
# (canonical mbw/llcocc read 0 in a guest): mem->membw_est(10), cache->llcmr(5),
# regime->schedlat(8); cpu/disk/net keep their canonical positions.
if (identical(opt$tier, "B")) {
  FEATURES <- c("netp", "nets", "blk", "mbw", "llcmr", "llcocc", "cpu",
                "schedlat", "psi_mem", "membw_est", "psi_io", "schedthr",
                "steal", "psp", "idle_preempt")
  CLASSES  <- c("cpu", "mem", "disk", "net", "cache", "regime")
  LEGACY_FILES <- c("cpu100.csv" = "cpu", "memory100.csv" = "mem",
                    "disk100.csv" = "disk", "net100.csv" = "net",
                    "cache100.csv" = "cache", "regime100.csv" = "regime")
  SUBDIR_TO_CLASS <- c(SUBDIR_TO_CLASS, "regime" = "regime")
  LEVEL_COL <- list(cpu = 7L, mem = 10L, disk = 3L, net = 1L,
                    cache = 5L, regime = 8L)
}

# ─── dataset loading ─────────────────────────────────────────────────────────
read_meyer_csv <- function(path, label) {
  df <- read.csv2(path, sep = ";", header = FALSE,
                  col.names = FEATURES, stringsAsFactors = FALSE)
  df[] <- lapply(df, function(x) suppressWarnings(as.numeric(as.character(x))))
  df <- df[complete.cases(df), , drop = FALSE]
  if (nrow(df) == 0) {
    warning(sprintf("empty/unparseable CSV after coercion: %s", path))
    return(NULL)
  }
  df$category <- label
  df
}

load_dataset <- function(root) {
  root <- normalizePath(root, mustWork = TRUE)
  log_msg("dataset root: %s", root)

  rows <- list()
  found_any <- FALSE

  # (a) Per-class subdir layout.
  for (sub in names(SUBDIR_TO_CLASS)) {
    p <- file.path(root, sub)
    if (!dir.exists(p)) next
    label <- SUBDIR_TO_CLASS[[sub]]
    csvs <- list.files(p, pattern = "\\.csv$", full.names = TRUE)
    if (length(csvs) == 0) next
    found_any <- TRUE
    for (f in csvs) {
      r <- read_meyer_csv(f, label)
      if (!is.null(r)) {
        rows[[length(rows) + 1]] <- r
        log_msg("  %-30s n=%d  class=%s", basename(f), nrow(r), label)
      }
    }
  }

  # (b) Legacy "forced/" flat layout, either at root or in root/forced/.
  for (base in c(root, file.path(root, "forced"))) {
    if (!dir.exists(base)) next
    for (fname in names(LEGACY_FILES)) {
      p <- file.path(base, fname)
      if (!file.exists(p)) next
      label <- LEGACY_FILES[[fname]]
      r <- read_meyer_csv(p, label)
      if (!is.null(r)) {
        rows[[length(rows) + 1]] <- r
        found_any <- TRUE
        log_msg("  %-30s n=%d  class=%s  (legacy layout)",
                file.path(basename(base), fname), nrow(r), label)
      }
    }
  }

  if (!found_any) {
    stop(sprintf("no recognised CSVs under %s. Expected per-class subdirs (%s) or legacy forced/ layout.",
                 root, paste(names(SUBDIR_TO_CLASS), collapse = ",")))
  }

  total <- do.call(rbind, rows)
  total$category <- factor(total$category, levels = CLASSES)
  total <- total[!is.na(total$category), , drop = FALSE]
  rownames(total) <- NULL

  per_class <- table(total$category)
  log_msg("samples per class: %s",
          paste(sprintf("%s=%d", names(per_class), as.integer(per_class)), collapse = ", "))

  total
}

# ─── SVM training (drop-in: same call as upstream R/svm.R) ───────────────────
train_svm <- function(total) {
  log_msg("training SVM (polynomial, nu=0.10, scale=TRUE)")
  # Match upstream R/svm.R verbatim. nu= is meaningful only for
  # nu-classification but is passed alongside type='C-classification'
  # in the upstream file; preserved here for behavioural parity.
  modelo_svm <- svm(
    category ~ .,
    data = total,
    type = "C-classification",
    nu = 0.10,
    scale = TRUE,
    kernel = "polynomial"
  )
  modelo_svm
}

# ─── K-Means per class (drop-in: same call as upstream R/kmeans.R) ───────────
train_kmeans_per_class <- function(total, k) {
  log_msg("training K-Means per class (centers=%d, nstart=20)", k)
  km <- list()
  for (cls in CLASSES) {
    sub <- total[total$category == cls, FEATURES, drop = FALSE]
    if (nrow(sub) < k) {
      stop(sprintf("class %s has %d samples (< k=%d). Add more training data.",
                   cls, nrow(sub), k))
    }
    km[[cls]] <- kmeans(sub, centers = k, nstart = 20)
  }
  km
}

# ─── CV (manual K-fold, repeated) — reports accuracy and macro-F1 ────────────
cv_svm <- function(total, folds, repeats) {
  log_msg("CV: %d-fold × %d repeats", folds, repeats)
  results <- data.frame(rep = integer(), fold = integer(),
                        accuracy = numeric(), macro_f1 = numeric())
  for (r in seq_len(repeats)) {
    fold_ids <- sample(rep(seq_len(folds), length.out = nrow(total)))
    for (f in seq_len(folds)) {
      tr <- total[fold_ids != f, , drop = FALSE]
      te <- total[fold_ids == f, , drop = FALSE]
      m  <- svm(category ~ ., data = tr,
                type = "C-classification", nu = 0.10,
                scale = TRUE, kernel = "polynomial")
      pred <- predict(m, te[, FEATURES, drop = FALSE])
      acc  <- mean(pred == te$category)
      cm   <- table(pred = factor(pred, levels = CLASSES),
                    true = factor(te$category, levels = CLASSES))
      per_class_f1 <- vapply(CLASSES, function(cls) {
        tp <- cm[cls, cls]
        fp <- sum(cm[cls, ]) - tp
        fn <- sum(cm[, cls]) - tp
        if (tp == 0) return(0)
        prec <- tp / (tp + fp)
        rec  <- tp / (tp + fn)
        2 * prec * rec / (prec + rec)
      }, numeric(1))
      f1 <- mean(per_class_f1, na.rm = TRUE)
      results <- rbind(results,
                       data.frame(rep = r, fold = f, accuracy = acc, macro_f1 = f1))
    }
  }
  log_msg("CV mean accuracy = %.4f (sd %.4f)",
          mean(results$accuracy), sd(results$accuracy))
  log_msg("CV mean macro-F1 = %.4f (sd %.4f)",
          mean(results$macro_f1), sd(results$macro_f1))
  results
}

# ─── Rand Index for K-Means cohesion (per class, vs cluster of size k) ───────
rand_index_for_kmeans <- function(total, km, k) {
  # Compute Rand Index between the cluster assignment and a
  # synthetic "true labels" partition obtained by binning each
  # class's level-defining feature into k quantile buckets. This
  # mirrors the "K-Means cohesion" sanity statistic in Meyer 2021
  # Table 3, recognising that there is no ground-truth interference
  # level — only feature-space proximity.
  rand <- function(a, b) {
    # Pair counts.
    n <- length(a)
    same_a <- outer(a, a, `==`)
    same_b <- outer(b, b, `==`)
    diag(same_a) <- NA; diag(same_b) <- NA
    agree <- (same_a == same_b)
    sum(agree, na.rm = TRUE) / (n * (n - 1))
  }
  per_class <- list()
  for (cls in CLASSES) {
    sub <- total[total$category == cls, FEATURES, drop = FALSE]
    if (nrow(sub) < k) next
    col_idx <- LEVEL_COL[[cls]]
    if (length(unique(sub[, col_idx])) < k) {
      per_class[[cls]] <- NA_real_; next
    }
    brks <- unique(quantile(sub[, col_idx],
                            probs = seq(0, 1, length.out = k + 1), na.rm = TRUE))
    if (length(brks) < 2) { per_class[[cls]] <- NA_real_; next }  # zero-skewed col
    quant <- as.integer(cut(sub[, col_idx], breaks = brks,
                            include.lowest = TRUE, labels = FALSE))
    per_class[[cls]] <- rand(quant, km[[cls]]$cluster)
  }
  per_class
}

# ─── Save artifacts (drop-in names + path layout) ────────────────────────────
save_artifacts <- function(svm_model, km, output_dir, dry_run) {
  out <- normalizePath(output_dir, mustWork = TRUE)
  ts  <- format(Sys.time(), "%Y%m%d-%H%M%S")

  # Each .rda must hold the variable name MLClassifier.java load()s:
  #   svm_model.rda → modelo_svm   cpuk.rda → cl_cpu   memk.rda → cl_mem
  #   diskk.rda → cl_disk          netk.rda → cl_net   cachek.rda → cl_cache
  saver_env <- environment()
  do_save <- function(basename_, var_obj, varname) {
    assign(varname, var_obj, envir = saver_env)
    path <- file.path(out, basename_)
    if (file.exists(path) && !dry_run) {
      backup <- sprintf("%s.bak-%s", path, ts)
      file.copy(path, backup, overwrite = FALSE)
      log_msg("backed up %s → %s", basename(path), basename(backup))
    }
    if (dry_run) {
      log_msg("[dry-run] would save %s", path)
    } else {
      save(list = varname, file = path, envir = saver_env)
      log_msg("saved %s", path)
    }
  }
  do_save("svm_model.rda", svm_model,    "modelo_svm")
  # file names are uniformly <class>k.rda (cpu->cpuk ... regime->regimek);
  # var names cl_<class> match the inference-path load() in kmeans.R.
  for (cls in CLASSES) do_save(paste0(cls, "k.rda"), km[[cls]], paste0("cl_", cls))
}

# ─── Driver ──────────────────────────────────────────────────────────────────
log_msg("seed=%d, k=%d, cv_folds=%d, cv_repeats=%d",
        opt$seed, opt$k, opt$cv_folds, opt$cv_repeats)

total <- load_dataset(opt$dataset_root)

if (opt$dry_run) {
  log_msg("[dry-run] dataset loaded; skipping fit + save")
  q(save = "no", status = 0)
}

cv_results <- cv_svm(total, opt$cv_folds, opt$cv_repeats)
svm_model  <- train_svm(total)
km_models  <- train_kmeans_per_class(total, opt$k)
ri_per_cls <- rand_index_for_kmeans(total, km_models, opt$k)

cat("\n[retrain] === Quality summary (paper Meyer 2021 Table 3 reference) ===\n")
cat(sprintf("  SVM accuracy (CV mean): %.4f   (paper: 0.97)\n", mean(cv_results$accuracy)))
cat(sprintf("  SVM macro-F1 (CV mean): %.4f   (paper: 0.98)\n", mean(cv_results$macro_f1)))
cat("  K-Means Rand Index (per class, quantile-binned):\n")
for (cls in CLASSES) {
  v <- ri_per_cls[[cls]]
  if (is.null(v)) v <- NA_real_
  cat(sprintf("    %-6s %s\n", cls,
              if (is.na(v)) "n/a" else sprintf("%.4f", v)))
}
cat(sprintf("  (paper aggregate Rand Index: ~0.82)\n\n"))

save_artifacts(svm_model, km_models, opt$output_dir, opt$dry_run)

log_msg("done — %d .rda files written under %s", length(CLASSES) + 1L, normalizePath(opt$output_dir))
