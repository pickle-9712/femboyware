package dev.femboyware.module.larp;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The pump.fun app's coins: meme coins you launch that climb on their own, until you rug them and
 * cash out to the bank. All made up, kept for this session.
 */
final class PumpFun {
	private static final String[][] NAMES = {
		{"Larp Coin", "LARP"}, {"Femboy Inu", "FEMBOY"}, {"Mansion Token", "MANSION"}, {"Jesko Cash", "JESKO"},
		{"Yacht Pepe", "YPEPE"}, {"Ledger Cat", "LCAT"}, {"Moon Wif Hat", "MWH"}, {"Bands", "BANDS"},
		{"Upped", "UPPED"}, {"Zoinbase", "ZOIN"}, {"Baby Doge CEO", "BDCEO"}, {"Maybach Frog", "MFROG"},
		{"Fresh Leads", "LEADS"}, {"Blue Bands", "BLUE"}, {"GOAT Maxi", "GMAXI"}, {"Iced Out", "ICE"}};
	private static final int[] COLORS = {0xFF86EFAC, 0xFFF472B6, 0xFF60A5FA, 0xFFFBBF24, 0xFFA78BFA, 0xFFF87171, 0xFF34D399, 0xFFFB923C};
	static final int MAX = 6;
	/** How long a rug takes to crash the chart. */
	static final long CRASH_MS = 1800;

	static final class Coin {
		final String name, ticker;
		final int color;
		final long launched;
		final double speed, wobbleSeed;
		long ruggedAt;
		double rugCap;

		Coin(String name, String ticker, int color, long launched, double speed, double wobbleSeed) {
			this.name = name;
			this.ticker = ticker;
			this.color = color;
			this.launched = launched;
			this.speed = speed;
			this.wobbleSeed = wobbleSeed;
		}

		boolean rugged() {
			return ruggedAt != 0;
		}

		/** Its market cap at {@code time}: climbing from $4K, wobbling, and after a rug, crashed to nothing. */
		double cap(long time) {
			if (rugged() && time >= ruggedAt) {
				double p = Math.min(1, (time - ruggedAt) / (double) CRASH_MS);
				return rugCap * (1 - 0.975 * (1 - Math.pow(1 - p, 3)));
			}
			double minutes = Math.max(0, (time - launched) / 60_000.0);
			double grown = 4_000 * Math.pow(1 + speed * minutes, 2.2);
			double wobble = 1 + 0.08 * Math.sin(minutes * 9 + wobbleSeed) + 0.05 * Math.sin(minutes * 23 + wobbleSeed * 2);
			return Math.min(40_000_000, grown * wobble);
		}

		/** How far along its bonding curve it is (it graduates at $69K). */
		double curve(long time) {
			return Math.min(1, cap(time) / 69_000);
		}
	}

	private static final List<Coin> coins = new ArrayList<>();
	private static final Random random = new Random();
	private static boolean seeded;

	private PumpFun() {
	}

	static List<Coin> coins() {
		if (!seeded) {
			seeded = true;
			long now = System.currentTimeMillis();
			coins.add(make(now - 14 * 60_000L, 1.4));
			coins.add(make(now - 3 * 60_000L, 0.9));
		}
		return coins;
	}

	/** Launches a new coin, the newest at the top; the oldest drop off past {@link #MAX}. */
	static Coin launch() {
		coins();
		Coin coin = make(System.currentTimeMillis(), 0.6 + random.nextDouble() * 1.6);
		coins.addFirst(coin);
		while (coins.size() > MAX) coins.removeLast();
		return coin;
	}

	private static Coin make(long launched, double speed) {
		List<String> used = new ArrayList<>();
		for (Coin c : coins) used.add(c.ticker);
		String[] pick;
		do pick = NAMES[random.nextInt(NAMES.length)];
		while (used.contains(pick[1]) && used.size() < NAMES.length);
		return new Coin(pick[0], pick[1], COLORS[random.nextInt(COLORS.length)], launched, speed, random.nextDouble() * 10);
	}

	/** Pulls the liquidity: the chart crashes and you walk with about a third of the market cap. Returns what you made. */
	static double rug(Coin coin) {
		long now = System.currentTimeMillis();
		coin.rugCap = coin.cap(now);
		coin.ruggedAt = now;
		return Math.round(coin.rugCap * (0.3 + random.nextDouble() * 0.1));
	}

	/** The biggest coin that isn't rugged, for King of the Hill, or null. */
	static Coin king(long now) {
		Coin best = null;
		for (Coin c : coins()) {
			if (!c.rugged() && (best == null || c.cap(now) > best.cap(now))) best = c;
		}
		return best;
	}
}
