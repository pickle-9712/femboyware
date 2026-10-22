package dev.femboyware.util;

public class Timer {
	private long last = System.currentTimeMillis();

	public boolean passed(double millis) {
		return System.currentTimeMillis() - last >= millis;
	}

	public void reset() {
		last = System.currentTimeMillis();
	}
}
