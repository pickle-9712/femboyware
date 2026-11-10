package dev.femboyware.util;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.util.Locale;
import java.util.Optional;

/**
 * Text with {@code &} colour codes, like {@code &c&lSTAFF &f{name}}, both ways: codes {@code 0-9 a-f},
 * {@code &#rrggbb} for any colour, {@code &l &o &n &m &k} for bold, italic, underline, strikethrough and
 * obfuscated, and {@code &r} to reset. A colour code also resets the formatting after it, as in vanilla.
 */
public final class LegacyText {
	private static final String CODES = "0123456789abcdef";
	private static final int[] COLORS = {
		0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
		0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF
	};

	private LegacyText() {
	}

	public static MutableComponent parse(String text) {
		MutableComponent out = Component.empty();
		Style style = Style.EMPTY;
		StringBuilder run = new StringBuilder();
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '&' && i + 1 < text.length()) {
				char code = Character.toLowerCase(text.charAt(i + 1));
				Style next = null;
				int skip = 1;
				if (code == '#' && i + 8 <= text.length() && isHex(text, i + 2, i + 8)) {
					next = Style.EMPTY.withColor(Integer.parseInt(text.substring(i + 2, i + 8), 16));
					skip = 7;
				} else if (CODES.indexOf(code) >= 0) {
					next = Style.EMPTY.withColor(COLORS[CODES.indexOf(code)]);
				} else {
					switch (code) {
						case 'r' -> next = Style.EMPTY;
						case 'l' -> next = style.withBold(true);
						case 'o' -> next = style.withItalic(true);
						case 'n' -> next = style.withUnderlined(true);
						case 'm' -> next = style.withStrikethrough(true);
						case 'k' -> next = style.withObfuscated(true);
						default -> {
						}
					}
				}
				if (next != null) {
					flush(out, run, style);
					style = next;
					i += skip;
					continue;
				}
			}
			run.append(c);
		}
		flush(out, run, style);
		return out;
	}

	private static boolean isHex(String text, int from, int to) {
		for (int i = from; i < to; i++) {
			if (Character.digit(text.charAt(i), 16) < 0) return false;
		}
		return true;
	}

	private static void flush(MutableComponent out, StringBuilder run, Style style) {
		if (run.isEmpty()) return;
		out.append(Component.literal(run.toString()).setStyle(style));
		run.setLength(0);
	}

	/** The same text written with {@code &} codes; each styled piece starts from a reset, so it reads back exactly. */
	public static String write(Component text) {
		StringBuilder out = new StringBuilder();
		text.visit((style, piece) -> {
			if (piece.isEmpty()) return Optional.empty();
			out.append(codes(style)).append(piece);
			return Optional.empty();
		}, Style.EMPTY);
		return out.toString();
	}

	private static String codes(Style style) {
		StringBuilder out = new StringBuilder("&r");
		TextColor color = style.getColor();
		if (color != null) {
			int rgb = color.getValue() & 0xFFFFFF;
			int named = -1;
			for (int i = 0; i < COLORS.length; i++) {
				if (COLORS[i] == rgb) named = i;
			}
			out.append(named >= 0 ? "&" + CODES.charAt(named) : String.format(Locale.ROOT, "&#%06x", rgb));
		}
		if (style.isBold()) out.append("&l");
		if (style.isItalic()) out.append("&o");
		if (style.isUnderlined()) out.append("&n");
		if (style.isStrikethrough()) out.append("&m");
		if (style.isObfuscated()) out.append("&k");
		return out.toString();
	}
}
