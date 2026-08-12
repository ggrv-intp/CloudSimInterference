package cloudsim.interference;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Degradation {

	
	public static HashMap<String, Double> deg_cpu = new HashMap<String, Double>();
	public static HashMap<String, Double> deg_mem = new HashMap<String, Double>();
	public static HashMap<String, Double> deg_disk = new HashMap<String, Double>();
	public static HashMap<String, Double> deg_cache = new HashMap<String, Double>();
	public static HashMap<String, Double> deg_net = new HashMap<String, Double>();
	public static HashMap<String, Double> deg_regime = new HashMap<String, Double>();

	/**
	 * Which degradation table is in force.
	 *
	 * This fork ships values 2-8% hotter than Ludwig/Meyer's published Table 2,
	 * and not by a constant factor (network low is +7.6%, memory high +2.9%).
	 * The published set was already in this file as a commented-out second copy
	 * of all five getters, so the two tables are a deliberate upstream choice
	 * rather than drift -- which makes "which one" a switch worth having.
	 *
	 * Default is the fork's, so every existing result is reproduced untouched.
	 * -Diada.degTable=paper selects Table 2. The one value that is not verbatim
	 * from the paper is memory/moderate: the commented block said 1.64 where the
	 * paper prints 1.62, and the paper wins here since that is the citable
	 * number.
	 */
	private static final boolean PAPER_TABLE =
			"paper".equalsIgnoreCase(System.getProperty("iada.degTable", "fork"));

	/** Fill a level map and read one level out of it, matching the original style. */
	private static double table(HashMap<String, Double> m, String level,
			double abs, double low, double mod, double hig) {
		m.put("abs", abs);
		m.put("low", low);
		m.put("mod", mod);
		m.put("hig", hig);

		return m.get(level);
	}

	public static double getCpu(String level) {
		return PAPER_TABLE ? table(deg_cpu, level, 1.00, 1.03, 1.15, 1.33)
		                   : table(deg_cpu, level, 1.00, 1.05, 1.17, 1.38);
	}

	public static double getMem(String level) {
		return PAPER_TABLE ? table(deg_mem, level, 1.00, 1.07, 1.62, 1.74)
		                   : table(deg_mem, level, 1.00, 1.10, 1.67, 1.79);
	}

	public static double getDisk(String level) {
		return PAPER_TABLE ? table(deg_disk, level, 1.00, 1.12, 1.82, 2.25)
		                   : table(deg_disk, level, 1.00, 1.21, 1.92, 2.31);
	}

	public static double getCache(String level) {
		return PAPER_TABLE ? table(deg_cache, level, 1.00, 1.07, 1.18, 1.26)
		                   : table(deg_cache, level, 1.00, 1.12, 1.24, 1.32);
	}

	public static double getNet(String level) {
		return PAPER_TABLE ? table(deg_net, level, 1.00, 1.05, 1.32, 1.57)
		                   : table(deg_net, level, 1.00, 1.13, 1.43, 1.62);
	}

	// Approach B's oversubscription "regime" class. Calibrated from the W5
	// victim-delta study (results/p2-15metric-xdeploy-1of3-w5): regime/sched-
	// pressure interference, when present, saturates (Cliff's |delta|->1 for
	// schedlat/membw_est primaries), so the multipliers are strong — above mem,
	// just under disk. abs<0.147 low<0.33 mod<0.474 hig>=0.474 (|cliffs| bins).
	//
	// The shape is worth sweeping, because the default ramp and the reasoning
	// above disagree. 1.20/1.55/1.95 is near-linear -- penalty proportional to
	// level -- whereas "saturates" argues for a concave ramp that front-loads
	// the penalty, since once the run queue is oversubscribed the latency is
	// already gone. Every canonical class is convex instead (memory jumps
	// 1.10 -> 1.67 at moderate), so all three shapes are live hypotheses and
	// the honest move is to measure rather than to argue.
	//
	// -Diada.regimeRamp=low,mod,hig overrides; default reproduces the committed
	// values. Malformed input falls back to the default rather than guessing.
	private static final double[] REGIME_RAMP = parseRamp(
			System.getProperty("iada.regimeRamp"), new double[] { 1.20, 1.55, 1.95 });

	private static double[] parseRamp(String spec, double[] fallback) {
		if (spec == null || spec.trim().isEmpty()) {
			return fallback;
		}
		String[] parts = spec.split(",");
		if (parts.length != 3) {
			return fallback;
		}
		double[] out = new double[3];
		for (int i = 0; i < 3; i++) {
			try {
				out[i] = Double.parseDouble(parts[i].trim());
			} catch (NumberFormatException e) {
				return fallback;
			}
		}
		return out;
	}

	public static double getRegime(String level) {
		deg_regime.put("abs", 1.00);
		deg_regime.put("low", REGIME_RAMP[0]);
		deg_regime.put("mod", REGIME_RAMP[1]);
		deg_regime.put("hig", REGIME_RAMP[2]);

		return deg_regime.get(level);
	}

	
	
/*	
 		public static double getCpu(String level) {
		deg_cpu.put("abs", 1.00);
		deg_cpu.put("low", 1.03);
		deg_cpu.put("mod", 1.15);
		deg_cpu.put("hig", 1.33);

		return deg_cpu.get(level);
	}

	public static double getMem(String level) {
		deg_mem.put("abs", 1.00);
		deg_mem.put("low", 1.07);
		deg_mem.put("mod", 1.64);
		deg_mem.put("hig", 1.74);

		return deg_mem.get(level);
	}

	public static double getDisk(String level) {
		deg_disk.put("abs", 1.00);
		deg_disk.put("low", 1.12);
		deg_disk.put("mod", 1.82);
		deg_disk.put("hig", 2.25);

		return deg_disk.get(level);
	}

	public static double getCache(String level) {
		deg_cache.put("abs", 1.00);
		deg_cache.put("low", 1.07);
		deg_cache.put("mod", 1.18);
		deg_cache.put("hig", 1.26);

		return deg_cache.get(level);
	}

	public static double getNet(String level) {
		deg_net.put("abs", 1.00);
		deg_net.put("low", 1.05);
		deg_net.put("mod", 1.32);
		deg_net.put("hig", 1.57);

		return deg_net.get(level);
	}
*/
}
