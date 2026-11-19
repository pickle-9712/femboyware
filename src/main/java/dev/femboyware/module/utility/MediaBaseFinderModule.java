package dev.femboyware.module.utility;

import dev.femboyware.event.PacketEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.util.ChatUtil;
import dev.femboyware.util.DonutGoliaths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.Level;
import net.minecraft.world.scores.PlayerTeam;

/**
 * DonutSMP: after you land on a goliath (with /rtp), says which media (YouTubers and streamers,
 * the 📹 rank) live on it, which is where their base most likely is. Ported from the Anubis
 * client's Media Base Finder (GPL-3.0, by 4ldenz, https://github.com/4ldenz/Anubis-client).
 */
public class MediaBaseFinderModule extends Module {
	private static final String MEDIA_PREFIX = "📹", MEDIA_TEAM = "09-";
	private static final int SETTLE_TICKS = 60, SCAN_TICKS = 10;
	private static final long RTP_WINDOW_MS = 60_000;

	private final BoolSetting reportEmpty = add(new BoolSetting("Report Empty", "Also say when a goliath has no media", true));
	private final BoolSetting title = add(new BoolSetting("Title", "A big title on screen after an /rtp lands you near media", true));

	private final Queue<UUID> modeUpdates = new ConcurrentLinkedQueue<>();
	private final Set<String> proven = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
	private Set<String> here = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
	private String goliath = "-";
	private int settle, ticks, lastGoliath = Integer.MIN_VALUE;
	private boolean reported, afterRtp;
	private volatile long rtpAt;
	private ClientLevel lastLevel;

	public MediaBaseFinderModule() {
		super("Media Base Finder", "Says which DonutSMP media live on the goliath you land on", Category.MISC);
	}

	@Override
	protected void onEnable() {
		arrive();
	}

	@Override
	protected void onDisable() {
		modeUpdates.clear();
		proven.clear();
		here = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		lastLevel = null;
	}

	private void arrive() {
		modeUpdates.clear();
		proven.clear();
		here = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		settle = SETTLE_TICKS;
		reported = false;
		goliath = label();
		afterRtp = rtpAt != 0 && System.currentTimeMillis() - rtpAt < RTP_WINDOW_MS;
	}

	private int goliathId() {
		if (mc.level == null || mc.player == null || mc.level.dimension() != Level.OVERWORLD) return Integer.MIN_VALUE;
		DonutGoliaths.Goliath g = DonutGoliaths.at(mc.player.getX(), mc.player.getZ());
		return g == null ? -1 : g.region() * 1000 + g.index();
	}

	private String label() {
		if (mc.level == null || mc.player == null || mc.level.dimension() != Level.OVERWORLD) return "this world";
		DonutGoliaths.Goliath g = DonutGoliaths.at(mc.player.getX(), mc.player.getZ());
		return g == null ? "this goliath" : g.label();
	}

	@Override
	public void onPacketSend(PacketEvent event) {
		String command = event.getPacket() instanceof ServerboundChatCommandPacket plain ? plain.command()
			: event.getPacket() instanceof ServerboundChatCommandSignedPacket signed ? signed.command() : null;
		if (command == null) return;
		String text = command.trim().toLowerCase(Locale.ROOT);
		if (text.equals("rtp") || text.startsWith("rtp ")) rtpAt = System.currentTimeMillis();
	}

	@Override
	public void onPacketReceive(PacketEvent event) {
		// A game mode update for someone (without them joining) means they're on your shard
		if (!(event.getPacket() instanceof ClientboundPlayerInfoUpdatePacket info)) return;
		if (!info.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE) || info.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER)) return;
		for (ClientboundPlayerInfoUpdatePacket.Entry entry : info.entries()) if (entry != null && entry.profileId() != null) modeUpdates.offer(entry.profileId());
	}

	@Override
	public void onTick() {
		ClientPacketListener connection = mc.getConnection();
		if (connection == null || mc.player == null || mc.level == null) {
			lastLevel = null;
			return;
		}
		int now = goliathId();
		if (mc.level != lastLevel || now != lastGoliath) {
			boolean first = lastLevel == null;
			lastLevel = mc.level;
			lastGoliath = now;
			if (!first) arrive();
		}
		UUID id;
		while ((id = modeUpdates.poll()) != null) {
			PlayerInfo info = connection.getPlayerInfo(id);
			if (info != null && info.getTeam() != null && isMedia(info.getTeam())) proven.add(info.getProfile().name());
		}
		if (settle > 0) {
			settle--;
			return;
		}
		if (reported && ++ticks % SCAN_TICKS != 0) return;
		Set<String> found = scan(connection);
		goliath = label();
		if (!reported) {
			reported = true;
			here = found;
			rtpAt = 0;
			if (found.isEmpty()) {
				if (reportEmpty.get()) ChatUtil.info("No media on " + goliath);
			} else announce(new ArrayList<>(found), found.size(), true);
			return;
		}
		List<String> arrived = new ArrayList<>();
		for (String name : found) if (!here.contains(name)) arrived.add(name);
		here = found;
		if (!arrived.isEmpty()) announce(arrived, found.size(), false);
	}

	private Set<String> scan(ClientPacketListener connection) {
		Set<String> found = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		String self = mc.player.getGameProfile().name();
		for (PlayerTeam team : mc.level.getScoreboard().getPlayerTeams()) {
			if (!isMedia(team)) continue;
			for (String member : team.getPlayers())
				if (!member.equalsIgnoreCase(self) && (connection.getPlayerInfo(member) == null || proven.contains(member))) found.add(member);
		}
		return found;
	}

	private static boolean isMedia(PlayerTeam team) {
		return team.getName().startsWith(MEDIA_TEAM) || team.getPlayerPrefix().getString().contains(MEDIA_PREFIX);
	}

	private void announce(List<String> names, int total, boolean summary) {
		String list = names.size() > 8 ? String.join(", ", names.subList(0, 8)) + " +" + (names.size() - 8) : String.join(", ", names);
		String area = area();
		String bases = total + (total == 1 ? " media base" : " media bases") + (area == null ? "" : " within " + area + " blocks");
		ChatUtil.info(summary ? bases + " on " + goliath + ": " + list : list + " joined " + goliath + ", now " + bases);
		if (summary && afterRtp && title.get()) {
			//? if >=26.1 {
			/*var titles = mc.gui.hud;
			*///?} else {
			var titles = mc.gui;
			//?}
			titles.setTimes(5, 60, 15);
			titles.setSubtitle(Component.literal(area == null ? "on this goliath" : "within " + area + " blocks"));
			titles.setTitle(Component.literal("§d" + total + (total == 1 ? " Media Base" : " Media Bases")));
			mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING, 1.4f));
		}
	}

	private String area() {
		if (mc.player == null || mc.level == null || mc.level.dimension() != Level.OVERWORLD) return null;
		DonutGoliaths.Goliath at = DonutGoliaths.at(mc.player.getX(), mc.player.getZ());
		if (at == null) return null;
		int[] c = DonutGoliaths.CELLS[at.region()][at.index()];
		String w = blocks(c[2] - c[0]), l = blocks(c[3] - c[1]);
		return w.equals(l) ? w : w + " x " + l;
	}

	private static String blocks(int squares) {
		return String.format(Locale.ROOT, "%,d", squares * DonutGoliaths.SQUARE);
	}
}
