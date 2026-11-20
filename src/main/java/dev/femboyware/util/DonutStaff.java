package dev.femboyware.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.scores.PlayerTeam;

/**
 * DonutSMP: whether you're on it, and who its staff are. Staff and developers carry a star in the
 * tab list (purple for staff, green for developers), so that's what's checked, not a list of names
 * that goes stale. Anyone seen with a star is remembered in femboyware/donut_staff.txt, so they're
 * still known while vanished (out of the tab list) or on another shard.
 */
public final class DonutStaff {
	private static final Path FILE = FabricLoader.getInstance().getGameDir().resolve("femboyware").resolve("donut_staff.txt");
	/** Lower-case name -> "Staff" or "Dev". */
	private static final Map<String, String> SEEN = new LinkedHashMap<>();
	private static boolean loaded;

	private DonutStaff() {
	}

	/** Whether the server you're on is DonutSMP. */
	public static boolean onDonut() {
		ServerData server = Minecraft.getInstance().getCurrentServer();
		return server != null && server.ip != null && server.ip.toLowerCase(Locale.ROOT).contains("donutsmp");
	}

	/** "Staff" for a purple star, "Dev" for a green one, or null; on DonutSMP only. Remembers who it finds. */
	public static String starRank(PlayerInfo info) {
		if (info == null || !onDonut()) return null;
		List<Component> parts = new ArrayList<>();
		PlayerTeam team = info.getTeam();
		if (team != null) {
			parts.add(team.getPlayerPrefix());
			parts.add(team.getPlayerSuffix());
		}
		if (info.getTabListDisplayName() != null) parts.add(info.getTabListDisplayName());
		String rank = null;
		for (Component part : parts) {
			String r = star(part);
			if (r != null) {
				rank = r;
				break;
			}
		}
		if (rank != null) remember(info.getProfile().name(), rank);
		return rank;
	}

	/** The rank of a name seen with a star before (this session or an earlier one), or null. */
	public static String knownRank(String name) {
		if (name == null) return null;
		load();
		synchronized (SEEN) {
			return SEEN.get(name.toLowerCase(Locale.ROOT));
		}
	}

	private static String star(Component component) {
		String[] found = {null};
		component.visit((style, text) -> {
			TextColor color = style.getColor();
			if (color == null) return Optional.empty();
			for (int i = 0; i < text.length(); ) {
				int cp = text.codePointAt(i);
				i += Character.charCount(cp);
				if (!symbol(cp)) continue;
				String r = rankOf(color.getValue());
				if (r != null) {
					found[0] = r;
					return Optional.of(Boolean.TRUE);
				}
			}
			return Optional.empty();
		}, Style.EMPTY);
		return found[0];
	}

	/** Stars and other icon glyphs (including a resource pack's private-use icons), not letters or brackets. */
	private static boolean symbol(int cp) {
		if (cp >= 0xE000 && cp <= 0xF8FF) return true;
		int type = Character.getType(cp);
		return type == Character.OTHER_SYMBOL || type == Character.MATH_SYMBOL;
	}

	private static String rankOf(int rgb) {
		float[] hsb = java.awt.Color.RGBtoHSB(rgb >> 16 & 0xFF, rgb >> 8 & 0xFF, rgb & 0xFF, null);
		if (hsb[1] < 0.3f || hsb[2] < 0.3f) return null;
		float hue = hsb[0] * 360;
		if (hue >= 255 && hue <= 310) return "Staff";
		if (hue >= 80 && hue <= 165) return "Dev";
		return null;
	}

	private static void remember(String name, String rank) {
		load();
		String key = name.toLowerCase(Locale.ROOT);
		synchronized (SEEN) {
			if (rank.equals(SEEN.get(key))) return;
			SEEN.put(key, rank);
		}
		save();
	}

	private static void load() {
		if (loaded) return;
		loaded = true;
		try {
			if (!Files.exists(FILE)) return;
			synchronized (SEEN) {
				for (String line : Files.readAllLines(FILE)) {
					String[] p = line.trim().split("\\s+");
					if (p.length >= 2) SEEN.put(p[0].toLowerCase(Locale.ROOT), p[1]);
				}
			}
		} catch (Exception ignored) {
		}
	}

	private static void save() {
		try {
			List<String> lines = new ArrayList<>();
			synchronized (SEEN) {
				SEEN.forEach((n, r) -> lines.add(n + " " + r));
			}
			Files.createDirectories(FILE.getParent());
			Files.write(FILE, lines);
		} catch (Exception ignored) {
		}
	}

	/** Whether {@code name} is in a comma-separated list of names, any case. */
	public static boolean listed(String name, String commaList) {
		if (name == null) return false;
		for (String n : commaList.split(",")) if (n.trim().equalsIgnoreCase(name)) return true;
		return false;
	}
}
