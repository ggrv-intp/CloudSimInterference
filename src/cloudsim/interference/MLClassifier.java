package cloudsim.interference;

import java.io.*;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Paths;
import java.awt.Frame;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import java.util.logging.Logger;

import cloudsim.interference.Interference;
import cloudsim.interference.util;

import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.lists.CloudletList;
import org.rosuda.JRI.REXP;
import org.rosuda.JRI.RList;
import org.rosuda.JRI.RMainLoopCallbacks;
import org.rosuda.JRI.RVector;
import org.rosuda.JRI.Rengine;
import org.rosuda.REngine.*;

public class MLClassifier {

	static class LoggingConsole implements RMainLoopCallbacks {
		private Logger log;

		LoggingConsole(Logger log) {
			this.log = log;
		}

		public void rWriteConsole(Rengine re, String text, int oType) {
			log.info(String.format("rWriteConsole: %s", text));
		}

		public void rBusy(Rengine re, int which) {
			log.info(String.format("rBusy: %s", which));
		}

		public void rShowMessage(Rengine re, String message) {
			log.info(String.format("rShowMessage: %s", message));
		}

		public String rReadConsole(Rengine re, String prompt, int addToHistory) {
			return null;
		}

		public String rChooseFile(Rengine re, int newFile) {
			return null;
		}

		public void rFlushConsole(Rengine re) {
		}

		public void rLoadHistory(Rengine re, String filename) {
		}

		public void rSaveHistory(Rengine re, String filename) {
		}
	}

	Logger log = Logger.getLogger("test"); // with log
	Rengine re = new Rengine(new String[] { "--no-save" }, false, new LoggingConsole(log)); // with log

	// Rengine re = new Rengine(new String[] { "--no-save" }, false, null);
	String project_folder = null;

	private int firstTime;
	private int firstTimeK;

	public MLClassifier() {
		this.firstTime = 1; // 0 treina sempre a primeira exec ---- 1 usa sempre o modelo já salvo (rda)
		this.firstTimeK = 1;

		String hostname = "Unknown";

		try {
			InetAddress addr;
			addr = InetAddress.getLocalHost();
			hostname = addr.getHostName();
		} catch (UnknownHostException ex) {
			System.out.println("Hostname can not be resolved");
		}

		// Log.printLine("=======" + hostname);
		// usar R para classificar ....

		String envFolder = System.getenv("INTP_R_FOLDER");
		String envLibPaths = System.getenv("INTP_R_LIBPATHS");
		if (envFolder != null && !envFolder.isEmpty()) {
			project_folder = envFolder.endsWith("/") ? envFolder : envFolder + "/";
			if (envLibPaths != null && !envLibPaths.isEmpty()) {
				re.eval(".libPaths('" + envLibPaths + "')");
			}
		}
		if (hostname.equals("vinicius-desktop")) {
			project_folder = "/home/vinicius/git/CloudSimInterference/R/"; // ubuntu
			re.eval(".libPaths('/home/vinicius/R/x86_64-pc-linux-gnu-library/3.6')"); // ubuntu
		}
		if (hostname.equals("DESKTOP-OTD5GFM")) {
			project_folder = "C:/Users/vinim/git/CloudSimInterference/R/"; // windows
			re.eval(".libPaths('c:/users/vinim/Documents/R/win-library/3.5')"); // windows
		}
		if (hostname.equals("pantana01")) {
			project_folder = "/home/student/vinicius/CloudSimInterference/R/"; // pantanal
			re.eval(".libPaths('/home/student/R/x86_64-pc-linux-gnu-library/3.6')"); // pantanal
		}

		// //ubuntuSERV
		// Rengine re = new Rengine(new String[] {"--no-save"}, false, null);

		// re.eval(".libPaths('c:/users/Nadia/Documents/R/win-library/3.5')"); //windows
		// re.eval(".libPaths('/home/vinicius/R/x86_64-pc-linux-gnu-library/3.6')");
		// //ubuntu

		// //ubuntuSERV
		// re.eval("install.packages('e1071')");
		// re.eval("install.packages('caret')");
		// re.eval("install.packages('stringr')");
		// re.eval("install.packages('dplyr')");
		// re.eval("install.packages('fossil')");
	}

	public MLCResult getMLClass(Interference interf, int start, int finish) {
		long startT = System.currentTimeMillis();

		re.eval("library(\"e1071\")");
		re.eval("library(\"caret\")");
		re.eval("library(\"stringr\")");
		re.eval("library(\"dplyr\")");
		re.eval("library(\"fossil\")");

		re.eval("firstTime<-" + firstTime);
		re.eval("firstTimeK<-" + firstTimeK);
		re.eval("project_folder_inside <- \"" + project_folder + "\"");
		re.eval("training_dataset_folder <- \"" + project_folder + "forced/\"");
		re.eval("source(\"" + project_folder + "input_dataset.R\")");
		re.eval("total <- input_dataset(training_dataset_folder)");
		re.eval("source(\"" + project_folder + "misc.R\")");
		re.eval("source(\"" + project_folder + "cpd.R\")");
		re.eval("source(\"" + project_folder + "kmeans.R\")");
		re.eval("source(\"" + project_folder + "svm.R\")");

		// Generic over feature width (7 for T1/A, 15 for B): take the column
		// names from the loaded training frame `total` so names + order match
		// the .rda exactly (this also removes the legacy netp/nets swap), and
		// build each row data.frame from the trace's actual width.
		// width = number of FEATURES in the training frame (7 for T1/A, 15 for B),
		// not the trace buffer length (always 15 after the int[15] widening — the
		// trailing slots are zero-padding for 7-col traces and must be ignored).
		re.eval("feat_cols <- setdiff(names(total), \"category\")");
		int width = re.eval("length(feat_cols)").asInt();
		re.eval("teste <- setNames(as.data.frame(matrix(0, ncol = length(feat_cols))), feat_cols)");

		int[] aux = new int[width];

		for (int i = start; i < finish; i++) {
			StringBuilder sb = new StringBuilder("aux <- data.frame(");
			for (int j = 0; j < width; j++) {
				aux[j] = interf.getIntByLine(i)[j];
				sb.append("as.integer(").append(aux[j]).append(")");
				if (j < width - 1) sb.append(",");
			}
			sb.append(")");
			re.eval(sb.toString());
			re.eval("aux <- setNames(aux, feat_cols)");
			re.eval("teste <- rbind(teste, aux)");
		}

		REXP hh = re.eval("abc <-svm_classifier_level(teste,1,nrow(teste))");
		
		RVector ff = hh.asVector();

		Map<String, String> gg = new HashMap<String, String>();

		// System.out.println(ff.at(2).toString());

		for (int i = 0; i < ff.at(1).asStringArray().length; i++) {
			gg.put(ff.at(1).asStringArray()[i], ff.at(2).asStringArray()[i]);
		}

		MLCResult result = new MLCResult(gg);

		re.stop();
		long totalTime = System.currentTimeMillis() - startT;
		Log.printLine(totalTime / 1000 / 60 + " min - " + totalTime / 1000 % 60 + " sec");
		return (result);

	}

	public List<Integer> getIntervalsOCPM(List<Interference> cloudletTraces, int start, int finish) {
		long startT = System.currentTimeMillis();
		List<Integer> result = new ArrayList<Integer>();

		re.eval("library(\"gtable\")");
		re.eval("library(\"dplyr\")");
		re.eval("library(\"scales\")");
		re.eval("library(\"ocp\")");
		re.eval("library(\"reshape2\")");

		re.eval("source(\"" + project_folder + "PCAtraces.R\")");

		re.eval("cTraces <- list()");

		for (int k = 0; k < cloudletTraces.size(); k++) {
			long startT1 = System.currentTimeMillis();
			Log.print("[R] reading cloudlet #" + (k + 1) + " ");
			re.eval("teste <- as.data.frame(matrix(0, ncol = 7))");
			re.eval("teste <- setNames(teste, c(\"nets\",\"netp\",\"blk\",\"mbw\",\"llcmr\",\"llcocc\",\"cpu\"))");

			int[] aux = new int[7];

			for (int i = start; i < finish; i++) {

				for (int j = 0; j < 7; j++) {
					aux[j] = cloudletTraces.get(k).getIntByLine(i)[j];
					// System.out.print(a.getIntByLine(i)[j] + " ");

				}
				re.eval("aux <- data.frame(as.integer(" + aux[0] + "),as.integer(" + aux[1] + "),as.integer(" + aux[2]
						+ "),as.integer(" + aux[3] + "),as.integer(" + aux[4] + "),as.integer(" + aux[5]
						+ "),as.integer(" + aux[6] + "))");
				re.eval("aux <- setNames(aux, c(\"nets\",\"netp\",\"blk\",\"mbw\",\"llcmr\",\"llcocc\",\"cpu\"))");
				re.eval("teste <- rbind(teste, aux)");
				// System.out.print("\n");

			}

			re.eval("cTraces[[" + (k + 1) + "]] <- teste");
			long totalTime1 = System.currentTimeMillis() - startT1;
			Log.print("(" + totalTime1 / 1000 / 60 + " min - " + totalTime1 / 1000 % 60 + " sec)\n");

		}

		long totalTime = System.currentTimeMillis() - startT;
		Log.printLine("[R] total time to read ctraces: " + totalTime / 1000 / 60 + " min - " + totalTime / 1000 % 60
				+ " sec");

		long startT2 = System.currentTimeMillis();
		
		Log.printLine("[R] running pca and ocp ... ");
		REXP rReturn = re.eval("intervals <-cTracesPCA(cTraces," + start + "," + finish + ")");
		long totalTime2 = System.currentTimeMillis() - startT2;
		Log.printLine("[R] pca and ocp (" + totalTime2 / 1000 / 60 + " min - " + totalTime2 / 1000 % 60 + " sec)");
		RVector rVector = rReturn.asVector();
		

		// information from R
		// System.out.println(rVector);
		// size of REAL/Double ArrayList
		// System.out.println(rVector.size());

		for (int i = 0; i < rVector.size(); i++) {
			result.add((int) rVector.at(i).asDouble());
			// System.out.println((int) rVector.at(i).asDouble());
		}

		// for (int i = 0; i < result.size(); i++) {
		// System.out.println(result.get(i));
		// }

		re.stop();

		return result;

	}

	public MLCResult getMLClass(Interference interf) {

		re.eval("library(\"e1071\")");
		re.eval("library(\"caret\")");
		re.eval("library(\"stringr\")");
		re.eval("library(\"dplyr\")");
		re.eval("library(\"fossil\")");

		re.eval("firstTime<-" + firstTime);
		re.eval("firstTimeK<-" + firstTimeK);
		re.eval("project_folder_inside <- \"" + project_folder + "\"");
		re.eval("training_dataset_folder <- \"" + project_folder + "forced/\"");
		re.eval("source(\"" + project_folder + "input_dataset.R\")");
		re.eval("total <- input_dataset(training_dataset_folder)");
		re.eval("source(\"" + project_folder + "misc.R\")");
		re.eval("source(\"" + project_folder + "cpd.R\")");
		re.eval("source(\"" + project_folder + "kmeans.R\")");
		re.eval("source(\"" + project_folder + "svm.R\")");

		re.eval("teste <- as.data.frame(matrix(0, ncol = 7))");
		re.eval("teste <- setNames(teste, c(\"nets\",\"netp\",\"blk\",\"mbw\",\"llcmr\",\"llcocc\",\"cpu\"))");

		int[] aux = new int[7];
		for (int i = 1; i < interf.getSize(); i++) {

			for (int j = 0; j < 7; j++) {
				aux[j] = interf.getIntByLine(i)[j];
				// System.out.print(a.getIntByLine(i)[j] + " ");

			}
			re.eval("aux <- data.frame(as.integer(" + aux[0] + "),as.integer(" + aux[1] + "),as.integer(" + aux[2]
					+ "),as.integer(" + aux[3] + "),as.integer(" + aux[4] + "),as.integer(" + aux[5] + "),as.integer("
					+ aux[6] + "))");
			re.eval("aux <- setNames(aux, c(\"nets\",\"netp\",\"blk\",\"mbw\",\"llcmr\",\"llcocc\",\"cpu\"))");
			re.eval("teste <- rbind(teste, aux)");
			// System.out.print("\n");

		}

		REXP hh = re.eval("abc <-svm_classifier_level(teste,1,nrow(teste))");
		RVector ff = hh.asVector();

		Map<String, String> gg = new HashMap<String, String>();

		for (int i = 0; i < ff.at(1).asStringArray().length; i++) {
			gg.put(ff.at(1).asStringArray()[i], ff.at(2).asStringArray()[i]);
		}

		MLCResult result = new MLCResult(gg);

		re.stop();
		return (result);

	}

}
