package cloudsim.interference;

import java.util.List;
import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;

/**
 * CloudletInterference object
 * 
 * It receives all interference metrics suffered by the Cloudlet (application)
 * life cycle.
 * 
 * @author Vinícius Meyer
 * @since 2020-01-10
 */

public class Interference {

	/**
	 * 
	 * @param netp   is the physical network
	 * @param nets   is the network queue
	 * @param blk    is the disk
	 * @param mbw    is the memory bandwidth
	 * @param llcmr  is the last-level cache miss rate
	 * @param llcocc is the last-level cache occupation
	 * @param cpu    is the CPU utilization
	 * @param interf is the array that keeps all variables
	 * @param aux    is an auxiliary array variable
	 * 
	 */

	private int netp;
	private int nets;
	private int blk;
	private int mbw;
	private int llcmr;
	private int llcocc;
	private int cpu;
	protected List<int[]> interf = new ArrayList<int[]>();

	public Interference() {

	}

	public Interference(String path) {

		this.interf = importCsvInt(path);

	}

	private List<int[]> importCsvInt(String csvFile) {

		BufferedReader br = null;
		String line = "";
		try {

			br = new BufferedReader(new FileReader(csvFile));
			while ((line = br.readLine()) != null) {
				int[] aux = new int[15];  // widen for Approach B 15-metric traces; 7-col traces fill the first 7 (fill loop is i<split.length)

				// use comma as separator
				String[] aux1 = line.split(";");
				
				for(int i = 0; i < aux1.length; ++i) {
				    aux[i] = Integer.parseInt(aux1[i]);
				}
				
				this.interf.add(aux);

			}

		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			if (br != null) {
				try {
					br.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
		return interf;

		
	}

	public List<int[]> getInterference() {
		return this.interf;
	}

	public void addInterference(int netp, int nets, int blk, int mbw, int llcmr, int llcocc, int cpu) {
		int[] aux = new int[7];
		this.netp = netp;
		this.nets = nets;
		this.blk = blk;
		this.mbw = mbw;
		this.llcmr = llcmr;
		this.llcocc = llcocc;
		this.cpu = cpu;

		aux[0] = netp;
		aux[1] = nets;
		aux[2] = blk;
		aux[3] = mbw;
		aux[4] = llcmr;
		aux[5] = llcocc;
		aux[6] = cpu;

		this.interf.add(aux);
	}

	public void printAll() {
		for (int i = 0; i < interf.size(); i++) {
			for (int j = 0; j < 7; j++) {
				System.out.print(interf.get(i)[j] + " ");
			}

			System.out.println("");

		}

	}

	public int getSize() {
		return interf.size();
	}

	public int[] getIntByLine(int number) {
		return interf.get(number);
	}
	
	public int getIntLength() {
			return interf.size();
	}
	

}
