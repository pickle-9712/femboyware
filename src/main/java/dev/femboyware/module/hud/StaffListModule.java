package dev.femboyware.module.hud;

import dev.femboyware.gui.Glass;
import dev.femboyware.gui.Theme;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.StringSetting;
import dev.femboyware.util.ChatUtil;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameType;
import net.minecraft.world.scores.PlayerTeam;

/**
 * Who on the server is staff: anyone in the tab list whose rank (team prefix or tab name) reads
 * like Owner, Admin, Mod, Helper and so on, plus any names you add. Shows their rank, and whether
 * they're in spectator (often how staff watch), and can tell you when one joins or leaves.
 */
public class StaffListModule extends HudElement {
	/** Rank words that mean staff, as whole words. */
	private static final Pattern RANKS = Pattern.compile("\\b(owner|co-?owner|founder|admin(istrator)?|sr\\.? ?admin|manager|head|mod(erator)?|sr\\.? ?mod|jr\\.? ?mod|trial ?mod|t-?mod|helper|staff|support|dev(eloper)?|builder|janitor|guard)\\b");

	private final StringSetting extra = add(new StringSetting("Extra Names", "More staff names, comma separated", ""));
	private final StringSetting extraRanks = add(new StringSetting("Extra Ranks", "More rank words that mean staff, comma separated", ""));
	private final BoolSetting notify = add(new BoolSetting("Notify", "Say in chat when staff join or leave", true));
	private final BoolSetting background = add(new BoolSetting("Background", "A glass box behind it", true));

	private record Staff(String name, String rank, boolean spectating) {
	}

	private List<Staff> staff = new ArrayList<>();
	private final Map<String, String> seen = new LinkedHashMap<>();
	private boolean first = true;

	public StaffListModule() {
		super("Staff List", "Staff online on the server", 1, 30);
	}

	@Override
	protected void onEnable() {
		seen.clear();
		first = true;
	}

	@Override
	public void onTick() {
		if (mc.getConnection() == null || mc.player == null) {
			staff = new ArrayList<>();
			seen.clear();
			first = true;
			return;
		}
		if (mc.player.tickCount % 10 != 0) return;
		List<String> names = new ArrayList<>(), ranks = new ArrayList<>();
		for (String n : extra.get().split(",")) if (!n.isBlank()) names.add(n.trim().toLowerCase(Locale.ROOT));
		for (String r : extraRanks.get().split(",")) if (!r.isBlank()) ranks.add(r.trim().toLowerCase(Locale.ROOT));
		List<Staff> found = new ArrayList<>();
		for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
			String name = info.getProfile().name();
			String rank = rank(info, ranks);
			if (rank == null && names.contains(name.toLowerCase(Locale.ROOT))) rank = "Staff";
			if (rank == null) continue;
			found.add(new Staff(name, rank, info.getGameMode() == GameType.SPECTATOR));
		}
		found.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
		staff = found;
		Map<String, String> now = new LinkedHashMap<>();
		for (Staff s : found) now.put(s.name, s.rank);
		if (notify.get() && !first) {
			for (var e : now.entrySet()) if (!seen.containsKey(e.getKey())) ChatUtil.info("Staff joined: " + e.getValue() + " " + e.getKey());
			for (var e : seen.entrySet()) if (!now.containsKey(e.getKey())) ChatUtil.info("Staff left: " + e.getValue() + " " + e.getKey());
		}
		first = false;
		seen.clear();
		seen.putAll(now);
	}

	/** Their staff rank as written, or null if they're not staff. */
	private static String rank(PlayerInfo info, List<String> extraRanks) {
		StringBuilder text = new StringBuilder();
		PlayerTeam team = info.getTeam();
		if (team != null) text.append(team.getPlayerPrefix().getString()).append(' ').append(team.getPlayerSuffix().getString()).append(' ');
		Component display = info.getTabListDisplayName();
		if (display != null) text.append(display.getString());
		String all = text.toString().replaceAll("[\\[\\]()<>|:»«•✦★]", " ").trim();
		String lower = all.toLowerCase(Locale.ROOT);
		var m = RANKS.matcher(lower);
		if (m.find()) return capitalise(all.substring(m.start(), m.end()));
		for (String r : extraRanks) if (lower.contains(r)) return capitalise(r);
		return null;
	}

	private static String capitalise(String s) {
		return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
	}

	private List<String[]> rows(boolean editing) {
		List<String[]> rows = new ArrayList<>();
		List<Staff> list = staff;
		if (list.isEmpty() && editing) list = List.of(new Staff("Notch", "Admin", false), new Staff("jeb_", "Mod", true));
		for (Staff s : list) rows.add(new String[]{s.rank, s.name + (s.spectating ? " (spectating)" : "")});
		return rows;
	}

	@Override
	public int width(boolean editing) {
		List<String[]> rows = rows(editing);
		int widest = mc.font.width("Staff (0)");
		for (String[] r : rows) widest = Math.max(widest, mc.font.width(r[0]) + 6 + mc.font.width(r[1]));
		return widest + (background.get() ? 12 : 0);
	}

	@Override
	public int height(boolean editing) {
		return (rows(editing).size() + 1) * 10 + (background.get() ? 8 : 0);
	}

	@Override
	protected void draw(GuiGraphics context, int left, int top, boolean editing) {
		List<String[]> rows = rows(editing);
		if (background.get()) Glass.draw(context, left, top, left + width(editing), top + height(editing), 4, backgroundColor(Theme.list()), false);
		int pad = background.get() ? 6 : 0, y = top + (background.get() ? 4 : 0);
		context.drawString(mc.font, "Staff (" + rows.size() + ")", left + pad, y, accentColor());
		y += 10;
		for (String[] r : rows) {
			context.drawString(mc.font, r[0], left + pad, y, 0xFFFF5A5A);
			context.drawString(mc.font, r[1], left + pad + mc.font.width(r[0]) + 6, y, textColor());
			y += 10;
		}
	}
}
