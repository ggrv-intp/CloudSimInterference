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

	
	
	public static double getCpu(String level) {
		deg_cpu.put("abs", 1.00);
		deg_cpu.put("low", 1.05);
		deg_cpu.put("mod", 1.17);
		deg_cpu.put("hig", 1.38);

		return deg_cpu.get(level);
	}

	public static double getMem(String level) {
		deg_mem.put("abs", 1.00);
		deg_mem.put("low", 1.10);
		deg_mem.put("mod", 1.67);
		deg_mem.put("hig", 1.79);

		return deg_mem.get(level);
	}

	public static double getDisk(String level) {
		deg_disk.put("abs", 1.00);
		deg_disk.put("low", 1.21);
		deg_disk.put("mod", 1.92);
		deg_disk.put("hig", 2.31);

		return deg_disk.get(level);
	}

	public static double getCache(String level) {
		deg_cache.put("abs", 1.00);
		deg_cache.put("low", 1.12);
		deg_cache.put("mod", 1.24);
		deg_cache.put("hig", 1.32);

		return deg_cache.get(level);
	}

	public static double getNet(String level) {
		deg_net.put("abs", 1.00);
		deg_net.put("low", 1.13);
		deg_net.put("mod", 1.43);
		deg_net.put("hig", 1.62);

		return deg_net.get(level);
	}

	// Approach B's oversubscription "regime" class. Calibrated from the W5
	// victim-delta study (results/p2-15metric-xdeploy-1of3-w5): regime/sched-
	// pressure interference, when present, saturates (Cliff's |delta|->1 for
	// schedlat/membw_est primaries), so the multipliers are strong — above mem,
	// just under disk. abs<0.147 low<0.33 mod<0.474 hig>=0.474 (|cliffs| bins).
	public static double getRegime(String level) {
		deg_regime.put("abs", 1.00);
		deg_regime.put("low", 1.20);
		deg_regime.put("mod", 1.55);
		deg_regime.put("hig", 1.95);

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
