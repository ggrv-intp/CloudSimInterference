# Retraining the IADA classifier

`R/retrain.R` regenerates the six `.rda` artifacts shipped in `R/`
(SVM + per-class K-Means) from a custom dataset. The output is a
drop-in replacement for the bundled models — `MLClassifier.java`
loads it through the existing `INTP_R_FOLDER` path without code
changes.

This document explains how to lay out the dataset, run the script,
and interpret the metrics it prints.

> **Why this exists.** The shipped classifier was trained by Meyer
> (2021) on profiles collected in **LXC containers** under
> **Node-Tiers** synthetic stressors. If your environment differs
> (Docker, bare metal, VM, a different hardware era, a different
> stressor like `stress-ng`), the classifier is operating outside
> its training distribution. Numeric results can still be produced
> but they conflate "the variant produces low-quality profiles" with
> "the classifier doesn't generalise to this domain". Retraining on
> a dataset drawn from the new domain is the methodologically
> correct fix.

---

## Quick start

```bash
# From the CloudSimInterference repo root:
Rscript R/retrain.R \
    --dataset-root /path/to/new-dataset \
    --output-dir   R/
```

Six new `.rda` files are written to `--output-dir` (default `R/`).
Any pre-existing files at those paths are backed up to
`<name>.rda.bak-<timestamp>` before being overwritten.

A `--dry-run` mode is supported: parses the CLI, loads + validates
the dataset, and exits without fitting or saving anything. Useful
to confirm that a candidate dataset directory is well-formed.

```bash
Rscript R/retrain.R --dataset-root /path/to/new-dataset --dry-run
```

---

## CLI

| Flag             | Default | Notes                                                                |
| ---------------- | ------- | -------------------------------------------------------------------- |
| `--dataset-root` | —       | **required**. See "Dataset layout" below.                            |
| `--output-dir`   | `R/`    | Where to write the six `.rda` files. Will be created if missing.     |
| `--k`            | `3`     | Number of K-Means centers per class. Paper-default is 3.             |
| `--cv-folds`     | `5`     | K for the K-fold cross-validation accuracy + F1 estimate.            |
| `--cv-repeats`   | `10`    | Number of repeated CV passes.                                        |
| `--seed`         | `42`    | RNG seed for fold assignment and `kmeans(nstart=20)`.                |
| `--dry-run`      | off     | Parse args + load dataset, then exit. No fit, no save.               |
| `--help`         | off     | Print usage and exit.                                                |

---

## Dataset layout

Two layouts are recognised. You can mix them in the same root if you
want (the script merges all files it finds), but the per-class
subdirectory layout is the recommended one for new collections.

### (a) Per-class subdirectories — recommended

```
<dataset-root>/
├── cpu/        *.csv     → class "cpu"
├── memory/     *.csv     → class "mem"
├── disk/       *.csv     → class "disk"
├── network/    *.csv     → class "net"
└── cache/      *.csv     → class "cache"
```

You can drop any number of CSVs into each subdir; they are
concatenated row-wise within the class.

### (b) Legacy `forced/` flat layout (Meyer 2021 paper)

The script also accepts the flat layout used by the upstream
`R/forced/` directory:

```
<dataset-root>/
├── cpu100.csv      → class "cpu"
├── memory100.csv   → class "mem"
├── disk100.csv     → class "disk"
├── net100.csv      → class "net"
├── cache100.csv    → class "cache"
└── cache_miss.csv  ← loaded but excluded from training
                     (matches upstream `input_dataset.R` behaviour)
```

You can also place the flat files under a `forced/` subdirectory.

### CSV format

Every CSV must be:

- `;`-separated (semicolon)
- **no header row**
- exactly 7 numeric columns in the order:
  ```
  netp;nets;blk;mbw;llcmr;llcocc;cpu
  ```

Rows containing non-numeric values are dropped silently after a
warning.

---

## Generating a compatible dataset from IntP profilers

The `intp-comparison` repo (branch `iada-closed-loop`) ships a
pipeline that collects fresh profiles with `stress-ng` workloads and
converts them into Meyer-format CSVs:

```bash
# In intp-comparison:
bash bench/iada/scripts/setup-iada.sh --auto-clone
source ~/.iada-env

# Run a cross-env-campaign as you would normally, then convert:
python3 bench/convert-profiler-to-meyer.py \
    --campaign-dir results/<your-campaign>/bench-full \
    --output-root /path/to/new-dataset \
    --layout per-class
```

The resulting `/path/to/new-dataset` is consumable directly by
`Rscript R/retrain.R --dataset-root /path/to/new-dataset`.

---

## Metrics printed at the end of training

The script reports three numbers and compares each one against the
Meyer 2021 paper reference (Table 3):

| Metric                          | Paper reference | What it means                                            |
| ------------------------------- | --------------- | -------------------------------------------------------- |
| SVM accuracy (CV mean)          | 0.97            | Fraction of held-out samples whose class is recovered.   |
| SVM macro-F1 (CV mean)          | 0.98            | Mean of per-class F1; protects against majority-class bias. |
| K-Means Rand Index (per class)  | ~0.82 aggregate | Agreement between the cluster labels and a level partition obtained by binning each class's level-defining feature into `k` quantile buckets. |

If accuracy or macro-F1 land more than ~5% below the paper reference,
suspect either:

- **Insufficient samples** per class (<200 is risky for a 7-feature
  polynomial SVM)
- **Noisy labels**: a class CSV containing profiles that actually
  exhibited a different bottleneck. Cross-check by inspecting the
  raw collection logs.
- **A feature column collapsed to zero**: e.g. `mbw`/`llcocc` from a
  VM guest with no PMU/RDT access. Those rows do not constitute
  retraining; they constitute a different domain altogether.

---

## Drop-in compatibility

The six files written by this script:

| File             | Variable name in `.rda` | Type                       |
| ---------------- | ----------------------- | -------------------------- |
| `svm_model.rda`  | `modelo_svm`            | `e1071::svm` object        |
| `cpuk.rda`       | `cl_cpu`                | `stats::kmeans` object     |
| `memk.rda`       | `cl_mem`                | `stats::kmeans` object     |
| `diskk.rda`      | `cl_disk`               | `stats::kmeans` object     |
| `netk.rda`       | `cl_net`                | `stats::kmeans` object     |
| `cachek.rda`     | `cl_cache`              | `stats::kmeans` object     |

`MLClassifier.java` `load()`s these via the existing
`firstTime==1` / `firstTimeK==1` branch — i.e. on the path that
**uses** the saved models rather than retraining them every
simulation. No Java rebuild is required after a retrain.

To verify the saved artifacts before launching CloudSim:

```bash
Rscript -e '
load("R/svm_model.rda")
load("R/cpuk.rda")
cat("modelo_svm class:", class(modelo_svm), "\n")
cat("modelo_svm levels:", levels(modelo_svm$decision.values |> attr("dimnames") |> _[[2]]), "\n")
cat("cl_cpu class:",     class(cl_cpu), "\n")
cat("cl_cpu centers:\n")
print(cl_cpu$centers)
'
```

---

## Caveat: domain-specific output

The artifacts produced by a retrain are valid **only for the domain
of the training data**. Models trained on Docker profiles must not be
applied to VM or bare-metal experiments and vice versa. The
`bench/iada/scripts/run-iada-from-bench.sh` wrapper in
`intp-comparison` (branch `iada-closed-loop`) hard-blocks
cross-domain runs without an explicit acknowledgement
(`IADA_M2_ACK_DOMAIN_TRANSFER=1`); see
[`bench/iada/docs/iada-campaign.md`](https://github.com/ggrv-intp/intp-comparison/blob/main/bench/iada/docs/iada-campaign.md)
in that repo for the full methodological framing.
