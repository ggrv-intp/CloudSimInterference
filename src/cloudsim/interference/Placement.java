package cloudsim.interference;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ThreadLocalRandom;

import org.cloudbus.cloudsim.Log;

public class Placement {

	// S16/S5 (jsa-repo-fix-brief): once the best solution found reaches zero
	// interference cost (every host has at most one cloudlet, so there is
	// nothing left to reduce -- see Solution.getCostFromHost's `hostCost == 1
	// ? 0 : hostCost`), continued annealing cannot improve on it, only spend
	// wallclock. Default off, so existing runs are unaffected; bug-fix
	// candidate flagged in CONFORMANCE.md rather than made the default,
	// since some already-banked results (S15 etc.) were produced without it
	// and reproducing them exactly requires it to stay off unless asked for.
	private static final boolean EARLY_EXIT_ZERO =
			"on".equalsIgnoreCase(System.getProperty("iada.earlyExitZero", "off"));

	// public static void main(String[] args) {

	// System.out.println(randomInt(0, 16));

	// }

	public static Solution run(Solution solution, String algorithm) {
		if (algorithm.equals("HC")) {
			return HillClimbing(solution);
		}

		if (algorithm.equals("SA")) {
			return SimulatedAnnealing(solution);
		}

		if (algorithm.equals("SAO")) {
			return SimulatedAnnealingOptimized(solution);
		}

		if (algorithm.equals("GA")) {
			return GeneticAlgorithm(solution);
		}

		// since the first placement is done through RR algorithm, it returns itself.
		if (algorithm.equals("RR")) {
			return solution; // RoundRobin(solution)
		}

		return new Solution();

	}

	/**
	 * It executes the genetic algorithm and returns the best solution
	 * 
	 * @param initial initial solution
	 * @return best solution found
	 */

	public static Solution GeneticAlgorithm(Solution solution) {
		int epoch = 10;
		int bestIndex = -1;
		double bestCost = Double.MAX_VALUE;
		double currentCost = 0;

		List<Solution> population = fillPopulation(solution);

		for (int i = 0; i < epoch; i++) {
			for (int j = 0; j < population.size(); j++) {

				population.set(j, randomSwap(population.get(j)));
				population.set(j, randomSwap(population.get(j)));
				population.set(j, randomSwap(population.get(j)));
				population.set(j, randomSwap(population.get(j)));

				currentCost = population.get(j).getTotalInterferenceCost();
				if (currentCost < bestCost) {
					bestIndex = j;
					bestCost = currentCost;
				}
			}

		}

		// population.get(bestIndex).print();
		// Log.printLine("FIM "+ population.get(bestIndex).getTotalInterferenceCost());
		// System.exit(0);
		return population.get(bestIndex);
	}

	private static List<Solution> fillPopulation(Solution solution) {
		Solution currentSolution = new Solution();

		List<Solution> population = new ArrayList<Solution>();
		int crossOverN = (int) (solution.getSize() * 0.25);
		Log.printLine("percent GA: " + crossOverN);
		if (crossOverN < 1) {
			crossOverN = 1;
		}

		for (int i = 0; i < 100; i++) {
			currentSolution = solution.copy();
			for (int j = 0; j < crossOverN; j++) {
				currentSolution = randomSwap(currentSolution);
			}

			population.add(currentSolution);
		}

		return population;
	}

	/**
	 * It executes the simulated annealing algorithm and returns the best solution
	 * 
	 * @param initial initial solution
	 * @return best solution found
	 */
	public static Solution SimulatedAnnealing(Solution solution) {
		int temperature = 10000000;
		double coolingRate = 0.003;
		int numOp = 0;
		int noChange = 0;
		int maxNoChange = 10000;

		Solution best = new Solution();
		best = solution.copy();

		Solution currentSolution = new Solution();
		currentSolution = best.copy();

		while (temperature > 0.000001 && noChange < maxNoChange) {
			numOp++;
			Solution newSolution = currentSolution.copy();

			newSolution = randomSwap(newSolution); // generate a modified solution

			double currentCost = currentSolution.getTotalInterferenceCost();
			double newCost = newSolution.getTotalInterferenceCost();

			// -Diada.migCost=<v> (repair sketch, CONFORMANCE.md S4.3/N3): see
			// the identical comment in SimulatedAnnealingOptimized. Default 0
			// keeps this byte-identical to before.
			double migCost = Double.parseDouble(System.getProperty("iada.migCost", "0"));
			if (migCost > 0) {
				newCost += newSolution.getNumberOfMigrations(currentSolution) * migCost;
			}

			if (acceptanceProbability(currentCost, newCost, temperature) > Math.random()) {
				currentSolution = newSolution;
			}

			if (currentSolution.getTotalInterferenceCost() < best.getTotalInterferenceCost()) {
				best = currentSolution;
				noChange = 0;
			}
			noChange++;
			temperature *= 1 - coolingRate;
		}
		return best;

	}

	public static Solution SimulatedAnnealingOptimized(Solution solution) {
		int temperature = 10000000;
		double coolingRate = 0.003;
		int numOp = 0;
		int noChange = 0;
		int maxNoChange = 10000;
		int nCloudlets = solution.getSize();

		Solution best = new Solution();
		best = solution.copy();

		Solution currentSolution = new Solution();
		currentSolution = best.copy();

		while (temperature > 0.000001 && noChange < maxNoChange) {
			numOp++;

			// S16/S5: must be checked BEFORE calling randomSwap(), not just
			// after -- when every cloudlet is alone on its host (e.g.
			// -Diada.containerPes=48, 1 application per host), swapping()'s
			// own retry loop ("while (runninginOnlyOneHost(cloudlet1))
			// cloudlet1 = randomInt(...)") never finds a valid partner and
			// never returns, so a check placed after randomSwap() is never
			// reached at all. Checking currentSolution here (not just best)
			// catches this on the very first iteration, before the first
			// swap attempt.
			if (EARLY_EXIT_ZERO && currentSolution.getTotalInterferenceCost() == 0) {
				break;
			}

			Solution newSolution = currentSolution.copy();

			newSolution = randomSwap(newSolution); // generate a modified solution

			double currentCost = currentSolution.getTotalInterferenceCost();
			double newCost = newSolution.getTotalInterferenceCost();

			// -Diada.migCost=<v> (repair sketch, CONFORMANCE.md S4.3/N3): the
			// search was migration-blind -- migvalue=10 only ever entered the
			// REPORTED "interf with mig" line, never the objective a mutation
			// is accepted or rejected against. Default 0 keeps this call
			// byte-identical to before (the ratchet at the best-update check
			// below is untouched either way -- only the accept/reject step
			// gains the term).
			double migCost = Double.parseDouble(System.getProperty("iada.migCost", "0"));
			if (migCost > 0) {
				newCost += newSolution.getNumberOfMigrations(currentSolution) * migCost;
			}

			if (acceptanceProbability(currentCost, newCost, temperature) > Math.random()) {
				currentSolution = newSolution;
			}

			if (currentSolution.getTotalInterferenceCost() < best.getTotalInterferenceCost()) {
				if (currentSolution.getNumberOfMigrations(best) < nCloudlets) {
					nCloudlets=currentSolution.getNumberOfMigrations(best);
					
					best = currentSolution;
					noChange = 0;

				}
			}
			if (EARLY_EXIT_ZERO && best.getTotalInterferenceCost() == 0) {
				break;
			}
			noChange++;
			temperature *= 1 - coolingRate;
		}
		return best;

	}

	private static double acceptanceProbability(double currentCost, double newCost, int temperature) {

		if (newCost < currentCost) {
			return 1.0;
		}

		return Math.exp((currentCost - newCost) / (temperature));
	}

	/**
	 * It executes the hill climbing algorithm and returns the best local solution
	 * 
	 * @param solution initial solution
	 * @return best solution found
	 */
	public static Solution HillClimbing(Solution solution) {
		int iterations = 5000;
		int maxNoChange = 2000;
		int numOp = 0;
		int noChange = 0;

		Solution best = new Solution();

		best = solution;
		// best.printPlacement();

		while (iterations > 1 && noChange < maxNoChange) {
			numOp++;
			Solution newSolution = best.copy();

			// newSolution.print();
			newSolution = randomSwap(newSolution);
			// newSolution.print();

			// Log.printLine("RANDOM");
			double bestCost = best.getTotalInterferenceCost();
			double newCost = newSolution.getTotalInterferenceCost();

			if (newCost < bestCost) {
				// Log.printLine("====== new " + newCost + " current " + bestCost + " - " +
				// numOp + " - "+ (1-(newCost/bestCost)));

				best = newSolution.copy();
				noChange = 0;
			}

			noChange++;
			iterations--;
		}

		// best.printPlacement();

		return best;

	}

	/**
	 * Generate a random modification of the current solution. 50% of chance of
	 * swaping two cloudlets 50% of change of moving a cloudlet to a new PM
	 */

	private static Solution randomSwap(Solution solution1) {

		double rand = Math.random();

		if (rand > 0.5) {
			return swapping(solution1);
		} else {
			return moving(solution1);
		}
	}

	/**
	 * Swap two random cloudlets from two different Hosts(VMs)
	 */

	private static Solution swapping(Solution solution2) {

		int cloudlet1 = randomInt(1, solution2.getSize());
		int cloudlet2;

		while (solution2.runninginOnlyOneHost(cloudlet1)) {
			cloudlet1 = randomInt(1, solution2.getSize());
		}

		cloudlet2 = cloudlet1;

		while (cloudlet2 == cloudlet1) { // || !solution.hasSameCloudletSize(cloudlet1, cloudlet2)) {
			cloudlet2 = randomInt(1, solution2.getSize());

		}
		// Log.printLine("CL1: " + cloudlet1 + " CL2: " + cloudlet2);

		solution2.swapCloudletHost(cloudlet1, cloudlet2);

		return solution2;
	}

	/**
	 * Move a random cloudlet of the Host(VM) with highest cost to the Host(VM) with
	 * lowest cost
	 */
	private static Solution moving(Solution solution3) {

		// solution3.print();

		int highestHost = solution3.getHostWithHighestCost();
		int lowerHost = solution3.getHostWithLowerCost();

		// Log.printConcatLine("HIGHHOST: ", highestHost, " LOWERHOST: ", lowerHost);

		int cloudlet1 = solution3.randomCloudletInHost(1, solution3.getSize(), highestHost);
		int cloudlet2 = solution3.randomCloudletInHost(1, solution3.getSize(), lowerHost);

		// Log.printConcatLine("CL1: ", cloudlet1, " CL2: ", cloudlet2);

		solution3.swapCloudletHost(cloudlet1, cloudlet2);

		return solution3;
	}

	private static int randomInt(int min, int max) {
		return ThreadLocalRandom.current().nextInt(min, max + 1);
	}

}
