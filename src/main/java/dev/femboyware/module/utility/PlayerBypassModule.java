package dev.femboyware.module.utility;

import dev.femboyware.util.playerbypass.*;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.setting.StringSetting;
import dev.femboyware.event.PacketEvent;
import dev.femboyware.util.RenderUtil;

import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongListIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.ARGB;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
/** Ported from the Anubis client (GPL-3.0, by 4ldenz, https://github.com/4ldenz/Anubis-client). */
public final class PlayerBypassModule extends Module {
	private static final int RENDER_DISTANCE_CHUNKS = 16;
	private static final double MARKER_Y = 64.0;
	private static final float LINE_WIDTH = 2.0F;
	private static final int MARK_FILL_ALPHA = 34;
	private static final int BASE_COLOR = -2516480;
	private static final int SUSPECT_COLOR = -5766;
	private static final int ACTIVITY_COLOR = -11205;
	private static final int MEMORY_SECONDS = 45;
	private static final float FALLOFF = 0.3F;
	private static final float MIN_SOURCE = 0.5F;
	private static final int MAX_FOUND = 256;
	private static final int ALERT_SPACING = 4;
	private static final int PIN_SPACING = 1;
	private static final float FROZEN_NEIGHBOUR = 0.5F;
	private static final int REPORT_SOURCES = 4;
	private static final Logger LOGGER = LoggerFactory.getLogger("femboyware");
	private final NumberSetting opacity = add(new NumberSetting("Opacity", "Opacity", 140.0F, 10.0F, 255.0F, 1.0F));
	private final BoolSetting tracers = add(new BoolSetting("Tracers", "Tracers", false));
	private final NumberSetting sensitivity = add(new NumberSetting("Sensitivity", "Sensitivity", 5.0F, 1.0F, 10.0F, 1.0F));
	private final ChunkAreas chunkAreas = new ChunkAreas();
	private final VisibleChunkCache visibleChunks = new VisibleChunkCache(16);
	private final BoolSetting notifications = add(new BoolSetting("Notifications", "Toast when a hidden player is spotted", true));
	private final BoolSetting sound = add(new BoolSetting("Sound", "Sound", true));
	private final BoolSetting showCoordinates = add(new BoolSetting("Show Coordinates", "Show Coordinates", true));
	private final BoolSetting disconnect = add(new BoolSetting("Auto Disconnect", "Auto Disconnect", false));
	private final BoolSetting autoRtp = add(new BoolSetting("Auto RTP", "Auto RTP", false));
	private final StringSetting rtpCommand = add(new StringSetting("RTP Command", "Your server's RTP command, without the slash, e.g. rtp", "rtp"));
	private final NumberSetting rtpScanTime = add(new NumberSetting("Scan Time (s)", "Scan Time (s)", 5.0F, 2.0F, 30.0F, 0.5F));
	private final NumberSetting rtpDelay = add(new NumberSetting("RTP Delay (s)", "RTP Delay (s)", 6.0F, 3.0F, 120.0F, 0.5F));
	private final NumberSetting rtpTimeout = add(new NumberSetting("RTP Timeout (s)", "RTP Timeout (s)", 10.0F, 3.0F, 60.0F, 1.0F));
	private final NumberSetting rtpMax = add(new NumberSetting("Max RTPs (0 = unlimited)", "Max RTPs (0 = unlimited)", 100.0F, 0.0F, 500.0F, 1.0F));
	private final BoolSetting rtpStopOnSuspect = add(new BoolSetting("Stop On Suspect", "Stop On Suspect", false));
	private final StringSetting rtpMenuItem = add(new StringSetting("Menu Item", "Clicked if the command opens a menu, e.g. overworld", ""));
	private final PlayerBypassAutoRtp autoRtpRunner = new PlayerBypassAutoRtp(
		this,
		this.autoRtp,
		this.rtpCommand,
		this.rtpScanTime,
		this.rtpDelay,
		this.rtpTimeout,
		this.rtpMax,
		this.rtpStopOnSuspect,
		this.rtpMenuItem,
		this.sound,
		this.showCoordinates
	);
	private final LongOpenHashSet peaks = new LongOpenHashSet();
	private final Map<ResourceKey<Level>, LongLinkedOpenHashSet> foundByDimension = new HashMap<>();
	private LongLinkedOpenHashSet found = new LongLinkedOpenHashSet();
	private int session = -1;
	private final LongOpenHashSet shown = new LongOpenHashSet();
	private final Long2FloatOpenHashMap field = new Long2FloatOpenHashMap();
	private final Long2FloatOpenHashMap raw = new Long2FloatOpenHashMap();
	private int ticks;

	public PlayerBypassModule() {
		super("Player Bypass", "Finds hidden players from light data and from chunks that keep ticking outside your simulation distance.", Category.MISC);
		this.sound.visibleWhen(() -> notifications.get() || this.autoRtp.get());
		this.showCoordinates.visibleWhen(() -> notifications.get() || this.disconnect.get() || this.autoRtp.get());
		dev.femboyware.setting.Setting<?>[] rtpSettings = new dev.femboyware.setting.Setting<?>[]{
			this.rtpCommand, this.rtpScanTime, this.rtpDelay, this.rtpTimeout, this.rtpMax, this.rtpStopOnSuspect, this.rtpMenuItem
		};

		for (dev.femboyware.setting.Setting<?> setting : rtpSettings) {
			setting.visibleWhen(this.autoRtp::get);
		}

	}

	@Override
	protected void onEnable() {
		this.visibleChunks.invalidate();
		PlayerBypassLightTracker.setSpottedListener(this::onChunkSpotted);
		this.autoRtpWas = this.autoRtp.get();
		if (this.autoRtpWas) {
			this.autoRtpRunner.start();
		}

		this.foundByDimension.clear();
		this.found = new LongLinkedOpenHashSet();
		this.session = SimulationSignals.session();
		SimulationSignals.clear();
		this.configure();
	}

	@Override
	protected void onDisable() {
		this.visibleChunks.invalidate();
		PlayerBypassLightTracker.setSpottedListener(null);
		this.autoRtpRunner.stopByUser();
		this.autoRtpRunner.forgetCooldown();
		this.autoRtp.set(false);
		SimulationSignals.configure(false, 0, 0, true, 30);
		this.peaks.clear();
		this.foundByDimension.clear();
		this.found = new LongLinkedOpenHashSet();
		this.shown.clear();
		this.field.clear();
		this.raw.clear();
	}

	private void onChunkSpotted(int chunkX, int chunkZ) {
		this.spotted((chunkX << 4) + 8, (chunkZ << 4) + 8, new ItemStack(Items.PLAYER_HEAD));
	}

	private void spotted(int x, int z, ItemStack icon) {
		boolean showPos = this.showCoordinates.get();
		if (!this.autoRtpRunner.baseSpotted(x, z, this.disconnect.get()) && this.notifications.get()) {
			ModuleChat.toast(icon, "Player Bypass", showPos ? Component.literal("at " + x + ", " + z) : Component.empty(), this.sound.get());
		}

		if (this.disconnect.get()) {
			ClientPacketListener connection = mc.getConnection();
			if (connection != null && connection.getConnection().isConnected()) {
				ModuleChat.send(this, ModuleChat.body().text("Auto Disconnect ").warn("triggered"));
				AutoReconnectModule.holdOff();
				connection.getConnection().disconnect(Component.literal("[Player Bypass] Player detected" + (showPos ? " at " + x + ", " + z : "")));
			}
		}
	}

	private void configure() {
		SimulationSignals.configure(true, 0, 0, true, 45);
	}

	private static boolean netherOrEnd(Level level) {
		ResourceKey<Level> dimension = level.dimension();
		return dimension.equals(Level.NETHER)
			|| dimension.equals(Level.END)
			|| level.dimensionTypeRegistration().is(BuiltinDimensionTypes.NETHER)
			|| level.dimensionTypeRegistration().is(BuiltinDimensionTypes.END)
			|| !level.dimensionType().hasSkyLight();
	}

	private float threshold() {
		return 8.0F * (float)Math.pow(2.0, (5.0 - this.sensitivity.get().floatValue()) / 2.5);
	}

	private boolean autoRtpWas;

	@Override
	public void onPacketReceive(PacketEvent event) {
		this.autoRtpRunner.onReceive(event.getPacket());
	}

	@Override
	public void onPacketSend(PacketEvent event) {
		this.autoRtpRunner.onSend(event.getPacket());
	}

	@Override
	public void onTick() {
		if (this.autoRtp.get() != this.autoRtpWas) {
			this.autoRtpWas = this.autoRtp.get();
			if (this.autoRtpWas) this.autoRtpRunner.start();
			else this.autoRtpRunner.stopByUser();
		}
		this.autoRtpRunner.onTick();
		if (mc.level != null && mc.player != null) {
			if (this.session != SimulationSignals.session()) {
				this.session = SimulationSignals.session();
				this.foundByDimension.clear();
				this.peaks.clear();
				this.shown.clear();
			}

			this.found = this.foundByDimension.computeIfAbsent(mc.level.dimension(), key -> new LongLinkedOpenHashSet());
			this.configure();
			SimulationSignals.tick();
			if (netherOrEnd(mc.level)) {
				SimulationSignals.dropPins();
				this.shown.clear();
			} else {
				this.drainPins();
				if (++this.ticks % 5 == 0) {
					this.collect(mc.player);
					this.alert();
					this.refreshShown(mc.player);
				}
			}
		}
	}

	private void collect(LocalPlayer self) {
		this.peaks.clear();
		this.field.clear();
		this.raw.clear();
		long now = SimulationSignals.now();
		int sim = SimulationSignals.simulation();
		int pcx = self.blockPosition().getX() >> 4;
		int pcz = self.blockPosition().getZ() >> 4;
		int start = SimulationSignals.ringStart();
		int view = SimulationSignals.view();
		int side = 2 * sim + 1;
		float[] weights = new float[side * side];
		float span = sim * 1.4143F + 1.0F;

		for (int dx = -sim; dx <= sim; dx++) {
			for (int dz = -sim; dz <= sim; dz++) {
				weights[(dx + sim) * side + dz + sim] = 1.0F - 0.3F * (float)Math.sqrt(dx * dx + dz * dz) / span;
			}
		}

		ObjectIterator var24 = SimulationSignals.chunks().long2ObjectEntrySet().iterator();

		while (var24.hasNext()) {
			Entry<SimulationSignals.ChunkSignal> entry = (Entry<SimulationSignals.ChunkSignal>)var24.next();
			long key = entry.getLongKey();
			int cx = ChunkPos.getX(key);
			int cz = ChunkPos.getZ(key);
			int d = SimulationSignals.distance(cx, cz, pcx, pcz);
			if (d >= start && d <= view) {
				float score = ((SimulationSignals.ChunkSignal)entry.getValue()).score(now);
				if (!(score < 0.5F)) {
					for (int dx = -sim; dx <= sim; dx++) {
						for (int dz = -sim; dz <= sim; dz++) {
							long area = V.chunkKey(cx + dx, cz + dz);
							this.field.addTo(area, score * weights[(dx + sim) * side + dz + sim]);
							this.raw.addTo(area, score);
						}
					}
				}
			}
		}

		LongIterator var25 = SimulationSignals.frozen().iterator();

		while (var25.hasNext()) {
			long frozen = (Long)var25.next();
			this.field.remove(frozen);
			this.raw.remove(frozen);
			int fx = ChunkPos.getX(frozen);
			int fz = ChunkPos.getZ(frozen);

			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					long near = V.chunkKey(fx + dx, fz + dz);
					if (this.field.containsKey(near)) {
						this.field.put(near, this.field.get(near) * 0.5F);
						this.raw.put(near, this.raw.get(near) * 0.5F);
					}
				}
			}
		}

		float threshold = this.threshold();
		ObjectIterator var29 = this.field.long2FloatEntrySet().iterator();

		while (var29.hasNext()) {
			it.unimi.dsi.fastutil.longs.Long2FloatMap.Entry entry = (it.unimi.dsi.fastutil.longs.Long2FloatMap.Entry)var29.next();
			long key = entry.getLongKey();
			if (!(this.raw.get(key) < threshold) && this.isPeak(key, entry.getFloatValue(), sim)) {
				this.peaks.add(key);
			}
		}
	}

	private boolean isPeak(long key, float value, int reach) {
		int cx = ChunkPos.getX(key);
		int cz = ChunkPos.getZ(key);

		for (int dx = -reach; dx <= reach; dx++) {
			for (int dz = -reach; dz <= reach; dz++) {
				if (dx != 0 || dz != 0) {
					long other = V.chunkKey(cx + dx, cz + dz);
					float near = this.field.get(other);
					if (near > value || near == value && other < key) {
						return false;
					}
				}
			}
		}

		return true;
	}

	private void drainPins() {
		SimulationSignals.Pin pin;
		while ((pin = SimulationSignals.pollPin()) != null) {
			BlockPos pos = pin.pos();
			long key = V.chunkKey(pos.getX() >> 4, pos.getZ() >> 4);
			LOGGER.info("[Player Bypass] {}", pin.detail());
			if (!this.near(key, 1)) {
				this.remember(key);
				ModuleChat.send(this, pin.detail());
				this.spotted(pos.getX(), pos.getZ(), new ItemStack(Items.PLAYER_HEAD));
			}
		}
	}

	private void alert() {
		long now = SimulationSignals.now();
		int spacing = Math.max(4, 2 * SimulationSignals.simulation());
		LongIterator var4 = this.peaks.iterator();

		while (var4.hasNext()) {
			long key = (Long)var4.next();
			if (!this.near(key, spacing)) {
				this.remember(key);
				int x = (ChunkPos.getX(key) << 4) + 8;
				int z = (ChunkPos.getZ(key) << 4) + 8;
				this.report(key, x, z, now);
				this.spotted(x, z, new ItemStack(Items.PLAYER_HEAD));
			}
		}
	}

	private boolean near(long key, int spacing) {
		LongListIterator var4 = this.found.iterator();

		while (var4.hasNext()) {
			long old = (Long)var4.next();
			if (SimulationSignals.distance(ChunkPos.getX(old), ChunkPos.getZ(old), ChunkPos.getX(key), ChunkPos.getZ(key)) <= spacing) {
				return true;
			}
		}

		return false;
	}

	private void remember(long key) {
		this.found.add(key);

		while (this.found.size() > 256) {
			this.found.removeFirstLong();
		}
	}

	private void refreshShown(LocalPlayer self) {
		this.shown.clear();
		int pcx = self.blockPosition().getX() >> 4;
		int pcz = self.blockPosition().getZ() >> 4;
		int reach = SimulationSignals.view() + SimulationSignals.simulation() + 2;
		LongListIterator var5 = this.found.iterator();

		while (var5.hasNext()) {
			long key = (Long)var5.next();
			if (SimulationSignals.distance(ChunkPos.getX(key), ChunkPos.getZ(key), pcx, pcz) <= reach) {
				this.shown.add(key);
			}
		}
	}

	private void report(long found, int x, int z, long now) {
		int sim = SimulationSignals.simulation();
		int fx = ChunkPos.getX(found);
		int fz = ChunkPos.getZ(found);
		List<Entry<SimulationSignals.ChunkSignal>> sources = new ArrayList<>();
		ObjectIterator lines = SimulationSignals.chunks().long2ObjectEntrySet().iterator();

		while (lines.hasNext()) {
			Entry<SimulationSignals.ChunkSignal> entry = (Entry<SimulationSignals.ChunkSignal>)lines.next();
			long key = entry.getLongKey();
			if (SimulationSignals.distance(ChunkPos.getX(key), ChunkPos.getZ(key), fx, fz) <= sim
				&& !(((SimulationSignals.ChunkSignal)entry.getValue()).score(now) < 0.5F)) {
				sources.add(entry);
			}
		}

		sources.sort((a, b) -> Float.compare(((SimulationSignals.ChunkSignal)b.getValue()).score(now), ((SimulationSignals.ChunkSignal)a.getValue()).score(now)));
		List<String> linesx = new ArrayList<>();
		linesx.add(
			"Found "
				+ x
				+ ", "
				+ z
				+ " from "
				+ sources.size()
				+ " chunks (sim "
				+ sim
				+ ", ring "
				+ SimulationSignals.ringStart()
				+ "-"
				+ SimulationSignals.view()
				+ ")"
		);

		for (int i = 0; i < Math.min(4, sources.size()); i++) {
			SimulationSignals.ChunkSignal signal = (SimulationSignals.ChunkSignal)sources.get(i).getValue();
			long key = sources.get(i).getLongKey();
			StringBuilder kinds = new StringBuilder();

			for (SimulationSignals.Kind kind : SimulationSignals.Kind.values()) {
				if (signal.count(kind) > 0) {
					kinds.append(kinds.isEmpty() ? "" : ", ").append(kind.title).append(" x").append(signal.count(kind));
				}
			}

			linesx.add(
				" chunk "
					+ ((ChunkPos.getX(key) << 4) + 8)
					+ ", "
					+ ((ChunkPos.getZ(key) << 4) + 8)
					+ String.format(Locale.ROOT, " score %.1f: ", signal.score(now))
					+ kinds
			);

			for (String recent : signal.recent()) {
				linesx.add("	" + recent);
			}
		}

		for (String line : linesx) {
			LOGGER.info("[Player Bypass] {}", line);
			ModuleChat.send(this, line);
		}
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		if (mc.level != null && mc.player != null) {
			LongOpenHashSet bases = new LongOpenHashSet();
			LongOpenHashSet suspects = new LongOpenHashSet();
			Long2ObjectMap<ChunkLightSnapshot> snapshots = PlayerBypassLightTracker.snapshots();
			if (!snapshots.isEmpty()) {
				int centerX = mc.player.blockPosition().getX() >> 4;
				int centerZ = mc.player.blockPosition().getZ() >> 4;
				if (true) {
					this.visibleChunks
						.update(
							PlayerBypassLightTracker.visibilityRevision(),
							centerX,
							centerZ,
							snapshots,
							PlayerBypassLightTracker::isFlagged,
							PlayerBypassLightTracker::isSuspect
						);
					bases.addAll(this.visibleChunks.bases());
					suspects.addAll(this.visibleChunks.suspects());
				} else {
					this.visibleChunks.invalidate();
					ObjectIterator bottom = snapshots.long2ObjectEntrySet().iterator();

					while (bottom.hasNext()) {
						Entry<ChunkLightSnapshot> entry = (Entry<ChunkLightSnapshot>)bottom.next();
						long key = entry.getLongKey();
						if (Math.abs(ChunkPos.getX(key) - centerX) <= 16
							&& Math.abs(ChunkPos.getZ(key) - centerZ) <= 16
							&& PlayerBypassLightTracker.isFlagged(key, (ChunkLightSnapshot)entry.getValue())) {
							(PlayerBypassLightTracker.isSuspect(key) ? suspects : bases).add(key);
						}
					}
				}
			}

			LongOpenHashSet activity = new LongOpenHashSet();
			if (!netherOrEnd(mc.level)) {
				LongIterator var13 = this.shown.iterator();

				while (var13.hasNext()) {
					long key = (Long)var13.next();
					if (!bases.contains(key) && !suspects.contains(key)) {
						activity.add(key);
					}
				}
			}

			if (!bases.isEmpty() || !suspects.isEmpty() || !activity.isEmpty()) {
				double bottom = mc.level.getMinY();
				double top = mc.level.getMaxY() + 1;
				this.mark(event, suspects, -5766, bottom, top);
				this.mark(event, activity, -11205, bottom, top);
				this.mark(event, bases, -2516480, bottom, top);
				if (this.tracers.get()) {
					this.chunkAreas.drawTracers(event, bases, 64.0, ARGB.opaque(-2516480));
					this.chunkAreas.drawTracers(event, suspects, 64.0, ARGB.opaque(-5766));
					this.chunkAreas.drawTracers(event, activity, 64.0, ARGB.opaque(-11205));
				}
			}
		}
	}

	private void mark(Render3DEvent event, LongOpenHashSet chunks, int color, double bottom, double top) {
		if (chunks.isEmpty()) return;
		float strength = Math.max(10, Math.min(255, this.opacity.get().intValue())) / 255.0F;
		int fill = ARGB.color(Math.round(34.0F * strength), color);
		int edge = ARGB.color(Math.round(255.0F * strength), color);
		LongIterator it = chunks.iterator();
		while (it.hasNext()) {
			long key = it.nextLong();
			double x0 = ChunkPos.getX(key) << 4;
			double z0 = ChunkPos.getZ(key) << 4;
			RenderUtil.drawBox(event, new AABB(x0, bottom, z0, x0 + 16.0, top, z0 + 16.0), fill, edge, 2.0F, true);
		}
	}
}
