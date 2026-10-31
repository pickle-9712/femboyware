package dev.femboyware.module.hud;

import dev.femboyware.FemboyWare;
import dev.femboyware.event.PacketEvent;
import dev.femboyware.gui.Glass;
import dev.femboyware.gui.Theme;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.util.ChatUtil;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityStatuses;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Counts up this visit to a server: time played, kills, deaths, totems and distance walked. Leaving
 * writes a line to femboyware/sessions.log, and the chat shows the last visit's summary on the next
 * join, since Minecraft clears the chat as you leave.
 */
public class SessionStatsModule extends HudElement {
	/** A player dying this soon after you hit them counts as your kill, in milliseconds. */
	private static final long KILL_WINDOW = 10_000;
	/** Moving further than this in a tick is a teleport, not walking. */
	private static final double MOST_PER_TICK = 5;
	private static final Path LOG = FabricLoader.getInstance().getGameDir().resolve("femboyware").resolve("sessions.log");

	private final BoolSetting time = add(new BoolSetting("Time", "How long you've been on", true));
	private final BoolSetting killsShown = add(new BoolSetting("Kills", "Players who died soon after you hit them", true));
	private final BoolSetting deathsShown = add(new BoolSetting("Deaths", "How many times you've died", true));
	private final BoolSetting ratio = add(new BoolSetting("K/D", "Kills per death", true));
	private final BoolSetting streak = add(new BoolSetting("Best Streak", "Most kills between deaths", true));
	private final BoolSetting totems = add(new BoolSetting("Totems", "Totems you've popped", true));
	private final BoolSetting walked = add(new BoolSetting("Distance", "How many blocks you've moved", true));
	private final BoolSetting summary = add(new BoolSetting("Summary", "Show the last visit's stats in chat when you next join", true));
	private final BoolSetting background = add(new BoolSetting("Background", "A glass box behind it", true));

	/** Who you've hit lately and when. Filled and read from different threads. */
	private final Map<UUID, Long> hit = new ConcurrentHashMap<>();
	private final AtomicInteger kills = new AtomicInteger();
	private final AtomicInteger pops = new AtomicInteger();
	private final AtomicInteger currentStreak = new AtomicInteger();
	private final AtomicInteger bestStreak = new AtomicInteger();
	private int deaths;
	private double distance;
	private long joined;
	private boolean wasDead;
	private double lastX, lastY, lastZ;
	private boolean hasLast;
	private String lastServer = "";
	private String waitingSummary;

	public SessionStatsModule() {
		super("Session Stats", "Time played, kills, deaths and distance this session", 99, 40);
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(this::start));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(this::finish));
	}

	private void start() {
		joined = System.currentTimeMillis();
		kills.set(0);
		pops.set(0);
		currentStreak.set(0);
		bestStreak.set(0);
		deaths = 0;
		distance = 0;
		hit.clear();
		hasLast = false;
		wasDead = false;
		lastServer = mc.getCurrentServerEntry() == null ? "Singleplayer" : mc.getCurrentServerEntry().address;
	}

	/** Called on leaving, whether or not the HUD element is showing. */
	private void finish() {
		if (joined == 0 || !isEnabled()) return;
		String line = String.format(Locale.ROOT, "%s, %d kills, %d deaths, best streak %d, %d totems, %.0f blocks", clock(), kills.get(), deaths, bestStreak.get(), pops.get(), distance);
		waitingSummary = summary.get() ? "Last session on " + lastServer + ": " + line : null;
		String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
		String row = stamp + " " + lastServer + ": " + line + System.lineSeparator();
		joined = 0;
		Thread writer = new Thread(() -> {
			try {
				Files.createDirectories(LOG.getParent());
				Files.writeString(LOG, row, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			} catch (IOException e) {
				FemboyWare.LOGGER.warn("Couldn't save session stats", e);
			}
		}, "femboyware session log");
		writer.setDaemon(true);
		writer.start();
	}

	@Override
	protected void onEnable() {
		if (joined == 0 && mc.world != null) start();
	}

	@Override
	public void onPostAttack(Entity target) {
		if (target instanceof PlayerEntity) hit.put(target.getUuid(), System.currentTimeMillis());
	}

	/** Runs on the network thread, as packets arrive. */
	@Override
	public void onPacketReceive(PacketEvent event) {
		if (!(event.getPacket() instanceof EntityStatusS2CPacket packet) || mc.world == null || mc.player == null) return;
		Entity entity = packet.getEntity(mc.world);
		if (!(entity instanceof PlayerEntity)) return;
		if (packet.getStatus() == EntityStatuses.USE_TOTEM_OF_UNDYING) {
			if (entity == mc.player) pops.incrementAndGet();
		} else if (packet.getStatus() == EntityStatuses.PLAY_DEATH_SOUND_OR_ADD_PROJECTILE_HIT_PARTICLES && entity != mc.player) {
			Long when = hit.remove(entity.getUuid());
			if (when != null && System.currentTimeMillis() - when <= KILL_WINDOW) {
				kills.incrementAndGet();
				bestStreak.accumulateAndGet(currentStreak.incrementAndGet(), Math::max);
			}
		}
	}

	@Override
	public void onTick() {
		if (joined == 0) start();
		if (waitingSummary != null) {
			ChatUtil.info(waitingSummary);
			waitingSummary = null;
		}
		boolean dead = mc.player.isDead();
		if (dead && !wasDead) {
			deaths++;
			currentStreak.set(0);
		}
		wasDead = dead;
		double dx = mc.player.getX() - lastX, dy = mc.player.getY() - lastY, dz = mc.player.getZ() - lastZ;
		double moved = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (hasLast && !dead && moved < MOST_PER_TICK) distance += moved;
		lastX = mc.player.getX();
		lastY = mc.player.getY();
		lastZ = mc.player.getZ();
		hasLast = true;
	}

	private String clock() {
		long seconds = joined == 0 ? 0 : (System.currentTimeMillis() - joined) / 1000;
		return seconds >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
			: String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
	}

	private List<String[]> lines() {
		List<String[]> lines = new ArrayList<>();
		if (time.get()) lines.add(new String[]{"Time", clock()});
		if (killsShown.get()) lines.add(new String[]{"Kills", Integer.toString(kills.get())});
		if (deathsShown.get()) lines.add(new String[]{"Deaths", Integer.toString(deaths)});
		if (ratio.get()) lines.add(new String[]{"K/D", String.format(Locale.ROOT, "%.2f", kills.get() / (double) Math.max(1, deaths))});
		if (streak.get()) lines.add(new String[]{"Streak", currentStreak.get() + " (best " + bestStreak.get() + ")"});
		if (totems.get()) lines.add(new String[]{"Totems", Integer.toString(pops.get())});
		if (walked.get()) lines.add(new String[]{"Walked", String.format(Locale.ROOT, "%.0f", distance)});
		return lines;
	}

	@Override
	public int width(boolean editing) {
		int widest = 0;
		for (String[] line : lines()) widest = Math.max(widest, mc.textRenderer.getWidth(line[0] + "  " + line[1]));
		return widest == 0 ? 0 : widest + 12;
	}

	@Override
	public int height(boolean editing) {
		return lines().size() * 10 + 6;
	}

	@Override
	protected void draw(DrawContext context, int left, int top, boolean editing) {
		List<String[]> lines = lines();
		if (lines.isEmpty()) return;
		if (background.get()) Glass.draw(context, left, top, left + width(editing), top + height(editing), 4, backgroundColor(Theme.list()), false);
		for (int i = 0; i < lines.size(); i++) {
			String[] line = lines.get(i);
			int y = top + 4 + i * 10;
			context.drawTextWithShadow(mc.textRenderer, line[0], left + 6, y, accentColor());
			context.drawTextWithShadow(mc.textRenderer, line[1], left + width(editing) - 6 - mc.textRenderer.getWidth(line[1]), y, textColor());
		}
	}
}
