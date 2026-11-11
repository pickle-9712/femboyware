package dev.femboyware.module.larp;

import java.util.Locale;
import java.util.Random;
import net.minecraft.resources.Identifier;

/** The coins in the fake wallet, with a starting price in dollars. */
public enum Coin {
	BTC("Bitcoin", 104_250, 8, 0xFFF7931A),
	ETH("Ethereum", 3_880, 6, 0xFF627EEA),
	SOL("Solana", 228, 4, 0xFF9945FF),
	USDT("Tether", 1, 2, 0xFF26A17B),
	XRP("XRP", 2.31, 2, 0xFFB0B6BE),
	DOGE("Dogecoin", 0.41, 2, 0xFFC2A633),
	LTC("Litecoin", 112, 4, 0xFF345D9D);

	public final String fullName;
	public final double basePrice;
	/** Decimal places its amounts are shown with. */
	public final int decimals;
	public final int color;
	public final Identifier icon;

	Coin(String fullName, double basePrice, int decimals, int color) {
		this.fullName = fullName;
		this.basePrice = basePrice;
		this.decimals = decimals;
		this.color = color;
		this.icon = Identifier.fromNamespaceAndPath("femboyware", "textures/larp/coin_" + name().toLowerCase(Locale.ROOT) + ".png");
	}

	public static Coin parse(String typed) {
		String upper = typed.toUpperCase(Locale.ROOT);
		for (Coin coin : values()) {
			if (coin.name().equals(upper) || coin.fullName.equalsIgnoreCase(typed)) return coin;
		}
		return null;
	}

	public String amount(double amount) {
		return String.format(Locale.US, "%,." + decimals + "f", amount);
	}

	/** A made-up address in this coin's usual format. */
	public String randomAddress(Random random) {
		return switch (this) {
			case BTC -> "bc1q" + chars(random, "023456789acdefghjklmnpqrstuvwxyz", 38);
			case ETH, USDT -> "0x" + chars(random, "0123456789abcdef", 40);
			case SOL -> chars(random, BASE58, 44);
			case XRP -> "r" + chars(random, BASE58, 33);
			case DOGE -> "D" + chars(random, BASE58, 33);
			case LTC -> "ltc1q" + chars(random, "023456789acdefghjklmnpqrstuvwxyz", 38);
		};
	}

	private static final String BASE58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

	private static String chars(Random random, String alphabet, int length) {
		StringBuilder out = new StringBuilder(length);
		for (int i = 0; i < length; i++) out.append(alphabet.charAt(random.nextInt(alphabet.length())));
		return out.toString();
	}

	/** {@code bc1qxy2k...0wlh}: the start and end of an address. */
	public static String shorten(String address) {
		return address.length() <= 14 ? address : address.substring(0, 8) + "..." + address.substring(address.length() - 5);
	}
}
