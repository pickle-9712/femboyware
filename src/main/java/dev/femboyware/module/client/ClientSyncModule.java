package dev.femboyware.module.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.femboyware.FemboyWare;
import dev.femboyware.event.PacketEvent;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.manager.PeerLink;
import dev.femboyware.mixin.EntitySharedFlagInvoker;
import dev.femboyware.mixin.MoveEntityPacketAccessor;
import dev.femboyware.mixin.RotateHeadPacketAccessor;
import dev.femboyware.mixin.WalkAnimationStateAccessor;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.module.larp.CarsModule;
import dev.femboyware.module.larp.HangarModule;
import dev.femboyware.module.larp.HelicopterModule;
import dev.femboyware.module.larp.JetModule;
import dev.femboyware.module.render.EmotesModule;
import dev.femboyware.module.utility.ServerLarpModule;
import dev.femboyware.setting.BoolSetting;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Lets femboyware users on the same server see each other's larp. Through the relay on
 * femboyware.xyz everyone shares their emote, and while Server Larp has them frozen server-side,
 * where they really are and how they move (walking, sneaking, gliding, what they hold, swings),
 * plus their jet, helicopter, cars and hangar. Their player is moved here to where they really are,
 * and put back where the server has them when they stop.
 */
public class ClientSyncModule extends Module {
	private final BoolSetting share = add(new BoolSetting("Share", "Other femboyware users here see your emotes, and while larping where you really are and your jet, helicopter, cars and hangar", true));
	private final BoolSetting showOthers = add(new BoolSetting("Show Others", "See other femboyware users' emotes, larping and vehicles, their captains and valets, and stand on their props while larping", true));
	private final BoolSetting hearOthers = add(new BoolSetting("Hear Others", "Hear other people's emote songs when you're close, fading as you walk away; while you emote you only hear yours", true));

	/** Ticks between updates while something's moving (emoting, larping, a car driving in), and while only parked things are out. */
	private static final int FAST_TICKS = 2, SLOW_TICKS = 20;
	/** How far behind their messages others are drawn, so there's always one to move toward. */
	private static final long DELAY = 150_000_000L, TICK = 50_000_000L;
	private static final long KEEP = 2_000_000_000L;
	private static final double PROP_RANGE = 400;

	private PeerLink link;
	private ClientLevel level;
	private int sendTimer;
	/** Whether the last update said something, so going quiet is said once. */
	private boolean sharing;
	private boolean wasSwinging;
	private int swings;

	private final ConcurrentLinkedQueue<Snap> incoming = new ConcurrentLinkedQueue<>();
	private final Map<UUID, Peer> peers = new HashMap<>();
	/** Entity ids of players moved to where they're larping; server news about them is held meanwhile. */
	private final Set<Integer> overridden = ConcurrentHashMap.newKeySet();
	private final Map<Integer, List<Packet<?>>> held = new ConcurrentHashMap<>();
	private final Map<Integer, EmotesModule.Shared> emotes = new HashMap<>();
	private long lastFrame;

	/** One update from someone: what they're doing at a moment on their clock, moved onto ours. */
	private record Snap(UUID uuid, long ts, long time, String dim, boolean gone, String emote, int emoteTicks, double[] showCar, String song,
						double[] body, String pose, int flags, int swings, String main, String off, String seatIn, double[] seat,
						double[] jet, int[] jetLook, double[] heli, int[] heliLook, List<double[]> cars, int[] carLook,
						double[] hangar, int[] hangarLook, List<double[]> valets, List<SharedFake> fakes, JsonObject looks, double[] yacht, int[] yachtLook, double[] mansion, int[] mansionLook, List<SharedSchem> schems, JsonObject tv, JsonObject raid, long interval) {
		/** The same update, at {@code time} on our clock. */
		Snap at(long time) {
			return new Snap(uuid, ts, time, dim, gone, emote, emoteTicks, showCar, song, body, pose, flags, swings, main, off, seatIn, seat,
				jet, jetLook, heli, heliLook, cars, carLook, hangar, hangarLook, valets, fakes, looks, yacht, yachtLook, mansion, mansionLook, schems, tv, raid, interval);
		}
	}

	/** A schematic someone's larping: its file's SHA-256, its name, where its origin is, and how it's turned and mirrored. */
	private record SharedSchem(String sha256, String name, int x, int y, int z, int rotation, int mirror) {
	}

	/** One of someone's fake players: its name, where it is and how it moves (x, y, z, yaw, head, body, walk speed, walk position, sneaking), and its emote. */
	private record SharedFake(String name, double[] v, String emote, int emoteTicks, UUID uuid, boolean accountSkin, String horns) {
	}

	private static final class Peer {
		final UUID uuid;
		final ArrayDeque<Snap> snaps = new ArrayDeque<>();
		long offset = Long.MAX_VALUE;
		long lastTs;
		long heard;
		/** The player we moved, where the server has them, and what they were like before. */
		AbstractClientPlayer entity;
		Vec3 serverPos;
		float serverYaw, serverPitch, serverHead, serverBody;
		Pose serverPose;
		boolean serverCrouch, serverGlide, serverSwim;
		ItemStack serverMain, serverOff;
		String shownMain, shownOff;
		int swings = Integer.MIN_VALUE;
		float walkSpeed;
		final float[] heliSpin = new float[2];
		String seatedIn;
		/** Their captains and valets, here as stand-ins moved to match theirs. */
		RemotePlayer jetPilot, heliPilot;
		final List<RemotePlayer> valets = new ArrayList<>();
		final List<Float> valetWalk = new ArrayList<>();
		/** Their fake players, here as stand-ins with the same names and skins. */
		final List<RemotePlayer> fakes = new ArrayList<>();
		final List<Float> fakeWalk = new ArrayList<>();
		/** Their props as they are this tick, for bumping into. */
		double[] solidJet, solidHeli, solidHangar, solidYacht, solidMansion;
		List<double[]> solidCars;
		/** Their emote song, playing here quietly or loud depending how close they are. */
		PeerSong song;
		String songName;
		int songStarted;
		float songVolume;
		/** Where the sound of their emote comes from. */
		Player emoter;

		Peer(UUID uuid) {
			this.uuid = uuid;
		}
	}

	public ClientSyncModule() {
		super("Client Sync", "See other femboyware users' emotes, jets and cars, and where they really are while they larp; they see yours", Category.LARP);
		setEnabled(true);
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (!isEnabled() || nullCheck()) return;
			try {
				afterTick();
			} catch (RuntimeException e) {
				FemboyWare.LOGGER.error("Client Sync failed", e);
			}
		});
	}

	/** The connection to everyone else here, or null (off, singleplayer, or not sharing). */
	public PeerLink link() {
		return isEnabled() && share.get() ? link : null;
	}

	@Override
	public String getInfo() {
		if (link == null) return null;
		return link.connected() ? String.valueOf(peers.size()) : "...";
	}

	@Override
	protected void onDisable() {
		if (link != null) {
			if (sharing) link.send(gone());
			link.stop();
		}
		link = null;
		sharing = false;
		// Other people's schematics, TVs and raids go with them
		FemboyWare.getModules().get(dev.femboyware.module.larp.TvModule.class).clearPeers();
		FemboyWare.getModules().get(dev.femboyware.module.larp.FbiRaidModule.class).clearPeers();
		ServerLarpModule larp = FemboyWare.getModules().get(ServerLarpModule.class);
		for (String key : larp.peerSchematics()) larp.removePeerSchematic(key);
		wantedSchems = Set.of();
		releaseAll(true);
		peers.clear();
		emotes.clear();
		incoming.clear();
	}

	// ---------------------------------------------------------------- what other modules ask

	/** Someone's emote by their entity id, or null. */
	public EmotesModule.Shared emoteOf(int entityId) {
		return isEnabled() && showOthers.get() ? emotes.get(entityId) : null;
	}

	public Map<Integer, EmotesModule.Shared> sharedEmotes() {
		return isEnabled() && showOthers.get() ? emotes : Collections.emptyMap();
	}

	/** Whether this entity is someone else sat in their helicopter, so the model sits. */
	public boolean seatedInHelicopter(int entityId) {
		if (!isEnabled() || overridden.isEmpty()) return false;
		for (Peer peer : peers.values()) {
			if (peer.entity != null && peer.entity.getId() == entityId) return "heli".equals(peer.seatedIn);
		}
		return false;
	}

	// ---------------------------------------------------------------- the connection

	/** This server's address, the room everyone on it meets in; null in singleplayer. */
	private String serverAddress() {
		// For testing in singleplayer against a local relay (-Dfemboyware.syncServer=name)
		String forced = System.getProperty("femboyware.syncServer");
		if (forced != null && !forced.isBlank()) return forced;
		if (mc.getCurrentServer() == null || mc.getSingleplayerServer() != null) return null;
		String ip = mc.getCurrentServer().ip;
		return ip == null || ip.isBlank() ? null : ip.trim().toLowerCase(Locale.ROOT);
	}

	@Override
	public void onTick() {
		if (mc.level != level) {
			// Another world or server: everyone we knew is gone with the old one
			releaseAll(false);
			peers.clear();
			emotes.clear();
			level = mc.level;
		}
		String address = serverAddress();
		if (link != null && !link.server().equals(address)) {
			link.stop();
			link = null;
			sharing = false;
		}
		if (link == null && address != null) link = new PeerLink(address, this::receive);
		if (link == null) return;
		if (mc.player.swinging && !wasSwinging) swings++;
		wasSwinging = mc.player.swinging;
		if (--sendTimer <= 0) send();
	}

	private void send() {
		JsonObject state = share.get() ? state() : null;
		if (state == null) {
			sendTimer = SLOW_TICKS;
			if (sharing) link.send(gone());
			sharing = false;
			return;
		}
		boolean fast = state.has("l") || state.has("e") || state.has("fast");
		state.remove("fast");
		sendTimer = fast ? FAST_TICKS : SLOW_TICKS;
		state.addProperty("i", sendTimer * 50);
		link.send(state.toString());
		sharing = true;
	}

	private String gone() {
		JsonObject message = new JsonObject();
		message.addProperty("u", mc.player.getUUID().toString());
		message.addProperty("ts", System.currentTimeMillis());
		message.addProperty("x", 1);
		return message.toString();
	}

	/** Everything others should see of you right now, or null if there's nothing. */
	private JsonObject state() {
		JsonObject s = new JsonObject();
		boolean any = false;
		EmotesModule emote = FemboyWare.getModules().get(EmotesModule.class);
		if (emote.isEnabled() && emote.playing() != null) {
			JsonObject e = new JsonObject();
			e.addProperty("n", emote.playing().name());
			e.addProperty("t", emote.playingTicks());
			Vec3 car = emote.showCarAt();
			if (car != null) e.add("c", numbers(car.x, car.y, car.z, emote.showCarYaw()));
			String song = emote.songName();
			if (song != null) e.addProperty("m", song);
			s.add("e", e);
			any = true;
		}
		if (FemboyWare.getModules().get(ServerLarpModule.class).isLarping()) {
			JsonObject l = new JsonObject();
			var p = mc.player;
			l.add("b", numbers(p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot(), p.getYHeadRot(), p.yBodyRot, p.walkAnimation.speed(), p.walkAnimation.position()));
			l.addProperty("o", p.getPose().name());
			l.addProperty("f", (p.isShiftKeyDown() ? 1 : 0) | (p.isFallFlying() ? 2 : 0) | (p.isSwimming() ? 4 : 0));
			l.addProperty("s", swings);
			l.addProperty("h", BuiltInRegistries.ITEM.getKey(p.getMainHandItem().getItem()).toString());
			l.addProperty("oh", BuiltInRegistries.ITEM.getKey(p.getOffhandItem().getItem()).toString());
			float[] jetSeat = FemboyWare.getModules().get(JetModule.class).shareSeat();
			float[] heliSeat = FemboyWare.getModules().get(HelicopterModule.class).shareSeat();
			if (jetSeat != null) {
				l.addProperty("in", "jet");
				l.add("seat", numbers(jetSeat[0], jetSeat[1], jetSeat[2]));
			} else if (heliSeat != null) {
				l.addProperty("in", "heli");
				l.add("seat", numbers(heliSeat[0], heliSeat[1], heliSeat[2]));
			}
			s.add("l", l);
			any = true;
		}
		JetModule jet = FemboyWare.getModules().get(JetModule.class);
		double[] jetState = jet.shareState();
		if (jetState != null) {
			s.add("j", numbers(jetState));
			s.add("jc", ints(jet.shareLook()));
			any = true;
		}
		HelicopterModule heli = FemboyWare.getModules().get(HelicopterModule.class);
		double[] heliState = heli.shareState();
		if (heliState != null) {
			s.add("hl", numbers(heliState));
			s.add("hc", ints(heli.shareLook()));
			any = true;
		}
		CarsModule cars = FemboyWare.getModules().get(CarsModule.class);
		List<double[]> carState = cars.shareState();
		if (carState != null) {
			JsonArray list = new JsonArray();
			for (double[] car : carState) {
				list.add(numbers(car));
				if (car[6] < 0) s.addProperty("fast", true);
			}
			s.add("c", list);
			s.add("cc", ints(cars.shareLook()));
			any = true;
		}
		FakePlayerModule fakeModule = FemboyWare.getModules().get(FakePlayerModule.class);
		if (fakeModule.isEnabled() && !fakeModule.fakeEntities().isEmpty()) {
			// Your fake players: where they are, how they move, and what they're emoting
			JsonArray list = new JsonArray();
			for (var fake : fakeModule.fakeEntities()) {
				if (list.size() >= 24) break;
				JsonObject f = new JsonObject();
				f.addProperty("n", fake.getGameProfile().name());
				f.add("b", numbers(fake.getX(), fake.getY(), fake.getZ(), fake.getYRot(), fake.getYHeadRot(), fake.yBodyRot,
					fake.walkAnimation.speed(), fake.walkAnimation.position(), fake.isShiftKeyDown() ? 1 : 0));
				// Its id (its default skin comes from it), whether it wears its account's skin, and its own horns
				f.addProperty("id", fake.getUUID().toString());
				f.addProperty("as", fakeModule.usesAccountSkins());
				var horns = FemboyWare.getModules().get(dev.femboyware.module.render.CosmeticsModule.class).fakeHorns(fake);
				if (horns != null) f.addProperty("h", horns.name());
				Object[] doing = emote.fakeDoing(fake.getId());
				if (doing != null) {
					f.addProperty("e", ((EmotesModule.Emote) doing[0]).name());
					f.addProperty("t", (int) doing[1]);
				}
				list.add(f);
			}
			s.add("fp", list);
			s.addProperty("fast", true);
			any = true;
		}
		List<double[]> valets = cars.shareValets();
		if (!valets.isEmpty()) {
			JsonArray list = new JsonArray();
			for (double[] valet : valets) list.add(numbers(valet));
			s.add("v", list);
			s.addProperty("fast", true);
			any = true;
		}
		HangarModule hangar = FemboyWare.getModules().get(HangarModule.class);
		double[] hangarState = hangar.shareState();
		if (hangarState != null) {
			s.add("g", numbers(hangarState));
			s.add("gc", ints(hangar.shareLook()));
			any = true;
		}
		var yachtModule = FemboyWare.getModules().get(dev.femboyware.module.larp.YachtModule.class);
		double[] yachtState = yachtModule.shareState();
		if (yachtState != null) {
			s.add("yt", numbers(yachtState));
			s.add("yc", ints(yachtModule.shareLook()));
			any = true;
		}
		var mansionModule = FemboyWare.getModules().get(dev.femboyware.module.larp.MansionModule.class);
		double[] mansionState = mansionModule.shareState();
		if (mansionState != null) {
			s.add("mn", numbers(mansionState));
			s.add("mc", ints(mansionModule.shareLook()));
			any = true;
		}
		// The schematics you're larping, so others larping here build them too
		var schematics = FemboyWare.getModules().get(ServerLarpModule.class).shareSchematics();
		if (!schematics.isEmpty()) {
			JsonArray list = new JsonArray();
			for (var schematic : schematics) {
				if (list.size() >= 8) break;
				JsonObject o = new JsonObject();
				o.addProperty("h", schematic.sha256());
				o.addProperty("n", schematic.name());
				o.add("o", ints(schematic.origin().getX(), schematic.origin().getY(), schematic.origin().getZ()));
				o.addProperty("r", schematic.rotation());
				o.addProperty("m", schematic.mirror());
				list.add(o);
				upload(schematic);
			}
			s.add("sc", list);
			any = true;
		}
		// An FBI raid on your mansion, so everyone here watches it happen
		JsonObject raidState = FemboyWare.getModules().get(dev.femboyware.module.larp.FbiRaidModule.class).shareState();
		if (raidState != null) {
			s.add("fb", raidState);
			any = true;
		}
		// Your TV: where it is, what's on and where it's up to, and the file once for the others to fetch
		var tvModule = FemboyWare.getModules().get(dev.femboyware.module.larp.TvModule.class);
		JsonObject tvState = tvModule.shareState();
		if (tvState != null) {
			s.add("tv", tvState);
			String[] file = tvModule.shareFile();
			if (file != null) upload(file[0], java.nio.file.Path.of(file[1]), file[2]);
			any = true;
		}
		// What you're wearing: sent along with anything else, and on its own every so often
		JsonObject worn = wornState();
		if (worn != null) {
			s.add("w", worn);
			any = true;
		}
		if (!any) return null;
		s.addProperty("u", mc.player.getUUID().toString());
		s.addProperty("d", mc.level.dimension().identifier().toString());
		s.addProperty("ts", System.currentTimeMillis());
		return s;
	}

	/** Your cosmetics and Roblox hats, and whether your fake players wear them; null if you've neither on. */
	private JsonObject wornState() {
		var cosmetics = FemboyWare.getModules().get(dev.femboyware.module.render.CosmeticsModule.class);
		var hats = FemboyWare.getModules().get(dev.femboyware.module.render.RobloxHatsModule.class);
		var c = cosmetics.shareWorn();
		var h = hats.shareWorn();
		var fu = FemboyWare.getModules().get(dev.femboyware.module.render.FurryModule.class).shareWorn();
		if (c == null && h == null && fu == null) return null;
		JsonObject worn = new JsonObject();
		if (fu != null) {
			JsonObject o = new JsonObject();
			o.add("c", ints(fu.fur(), fu.belly(), fu.paws()));
			o.addProperty("sk", fu.skin());
			o.addProperty("e", fu.ears());
			o.addProperty("t", fu.tail());
			o.addProperty("ts", Math.round(fu.tailSize() * 100) / 100.0);
			o.addProperty("ha", fu.hideArmor());
			worn.add("fu", o);
		}
		if (c != null) {
			JsonObject o = new JsonObject();
			o.addProperty("k", c.korblox());
			o.addProperty("hd", c.headless());
			o.addProperty("h", c.horns().name());
			o.addProperty("s", Math.round(c.hornSize() * 100) / 100.0);
			o.addProperty("f", c.fire());
			o.addProperty("w", c.watch().name());
			o.addProperty("fk", cosmetics.sharesWithFakes());
			worn.add("c", o);
		}
		if (h != null) {
			JsonObject o = new JsonObject();
			JsonArray ids = new JsonArray();
			for (long id : h.ids()) if (ids.size() < 8) ids.add(id);
			o.add("i", ids);
			o.addProperty("s", Math.round(h.size() * 100) / 100.0);
			o.addProperty("r", Math.round(h.raise() * 100) / 100.0);
			o.addProperty("fk", hats.sharesWithFakes());
			worn.add("h", o);
		}
		return worn;
	}

	/** What someone's wearing, read from their update; cached per update so it's only read once. */
	private record Looks(dev.femboyware.module.render.CosmeticsModule.Worn cosmetics, boolean cosmeticsOnFakes,
						 dev.femboyware.module.render.RobloxHatsModule.Worn hats, boolean hatsOnFakes,
						 dev.femboyware.module.render.FurryModule.Worn furry) {
	}

	private final Map<Integer, dev.femboyware.module.render.FurryModule.Worn> furryById = new HashMap<>();

	public dev.femboyware.module.render.FurryModule.Worn furryOf(int entityId) {
		return isEnabled() && showOthers.get() ? furryById.get(entityId) : null;
	}

	private final Map<JsonObject, Looks> looksRead = new java.util.WeakHashMap<>();
	/** Who's wearing what this tick, by entity id: other players and their fake players. */
	private final Map<Integer, dev.femboyware.module.render.CosmeticsModule.Worn> cosmeticsById = new HashMap<>();
	private final Map<Integer, dev.femboyware.module.render.RobloxHatsModule.Worn> hatsById = new HashMap<>();

	public dev.femboyware.module.render.CosmeticsModule.Worn cosmeticsOf(int entityId) {
		return isEnabled() && showOthers.get() ? cosmeticsById.get(entityId) : null;
	}

	public dev.femboyware.module.render.RobloxHatsModule.Worn hatsOf(int entityId) {
		return isEnabled() && showOthers.get() ? hatsById.get(entityId) : null;
	}

	private Looks readLooks(JsonObject w) {
		return looksRead.computeIfAbsent(w, key -> {
			dev.femboyware.module.render.CosmeticsModule.Worn cosmetics = null;
			dev.femboyware.module.render.RobloxHatsModule.Worn hats = null;
			boolean cFakes = false, hFakes = false;
			try {
				if (key.has("c")) {
					JsonObject o = key.getAsJsonObject("c");
					var horns = dev.femboyware.module.render.CosmeticsModule.Horns.valueOf(o.get("h").getAsString());
					var watch = dev.femboyware.module.render.CosmeticsModule.Watch.valueOf(o.get("w").getAsString());
					cosmetics = new dev.femboyware.module.render.CosmeticsModule.Worn(o.get("k").getAsBoolean(), o.get("hd").getAsBoolean(), horns,
						o.get("s").getAsFloat(), o.get("f").getAsBoolean(), watch);
					cFakes = o.has("fk") && o.get("fk").getAsBoolean();
				}
			} catch (RuntimeException ignored) {
				cosmetics = null;
			}
			try {
				if (key.has("h")) {
					JsonObject o = key.getAsJsonObject("h");
					List<Long> ids = new ArrayList<>();
					for (JsonElement id : o.getAsJsonArray("i")) if (ids.size() < 8 && id.getAsLong() > 0) ids.add(id.getAsLong());
					if (!ids.isEmpty()) hats = new dev.femboyware.module.render.RobloxHatsModule.Worn(List.copyOf(ids), o.get("s").getAsFloat(), o.get("r").getAsFloat());
					hFakes = o.has("fk") && o.get("fk").getAsBoolean();
				}
			} catch (RuntimeException ignored) {
				hats = null;
			}
			dev.femboyware.module.render.FurryModule.Worn furry = null;
			try {
				if (key.has("fu")) {
					JsonObject o = key.getAsJsonObject("fu");
					int[] colors = ints(o.get("c"), 3);
					if (colors.length == 3) {
						furry = new dev.femboyware.module.render.FurryModule.Worn(colors[0] | 0xFF000000, colors[1] | 0xFF000000, colors[2] | 0xFF000000,
							o.get("sk").getAsBoolean(), o.get("e").getAsBoolean(), o.get("t").getAsBoolean(), o.get("ts").getAsFloat(), o.get("ha").getAsBoolean());
					}
				}
			} catch (RuntimeException ignored) {
				furry = null;
			}
			return new Looks(cosmetics, cFakes, hats, hFakes, furry);
		});
	}

	/** Puts what this peer's wearing on them (and on their fake players, if theirs wear it), for the cosmetics to draw. */
	private void wear(Peer peer, Snap a, Player entity) {
		if (a == null || a.looks == null) return;
		Looks looks = readLooks(a.looks);
		if (looks.hats != null) FemboyWare.getModules().get(dev.femboyware.module.render.RobloxHatsModule.class).ensureLoaded(looks.hats.ids());
		if (entity != null) {
			if (looks.furry != null) furryById.put(entity.getId(), looks.furry);
			if (looks.cosmetics != null) cosmeticsById.put(entity.getId(), looks.cosmetics);
			if (looks.hats != null) hatsById.put(entity.getId(), looks.hats);
		}
		List<SharedFake> shared = a.fakes == null ? List.of() : a.fakes;
		for (int i = 0; i < peer.fakes.size(); i++) {
			RemotePlayer fake = peer.fakes.get(i);
			if (looks.cosmetics != null && looks.cosmeticsOnFakes) {
				// Each of their fake players has its own horns
				var worn = looks.cosmetics;
				String horns = i < shared.size() ? shared.get(i).horns() : null;
				if (horns != null) {
					try {
						var style = dev.femboyware.module.render.CosmeticsModule.Horns.valueOf(horns);
						worn = new dev.femboyware.module.render.CosmeticsModule.Worn(worn.korblox(), worn.headless(), style, worn.hornSize(), worn.fire(), worn.watch());
					} catch (IllegalArgumentException ignored) {
					}
				}
				cosmeticsById.put(fake.getId(), worn);
			}
			if (looks.hats != null && looks.hatsOnFakes) hatsById.put(fake.getId(), looks.hats);
		}
	}

	private static JsonArray numbers(double... values) {
		JsonArray array = new JsonArray();
		for (double v : values) array.add(Math.round(v * 1000) / 1000.0);
		return array;
	}

	private static JsonArray ints(int... values) {
		JsonArray array = new JsonArray();
		for (int v : values) array.add(v);
		return array;
	}

	// ---------------------------------------------------------------- hearing from others

	/** On a network thread: reads an update and queues it for the next tick. */
	private void receive(String message) {
		JsonObject s = JsonParser.parseString(message).getAsJsonObject();
		UUID uuid = UUID.fromString(s.get("u").getAsString());
		if (s.has("tp")) {
			receiveTeleport(uuid, s.getAsJsonObject("tp"));
			return;
		}
		long ts = s.get("ts").getAsLong();
		if (s.has("x")) {
			incoming.add(new Snap(uuid, ts, System.nanoTime(), null, true, null, 0, null, null, null, null, 0, 0, null, null, null, null,
				null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, 0));
			return;
		}
		String emote = null, song = null;
		int emoteTicks = 0;
		double[] showCar = null;
		if (s.has("e")) {
			JsonObject e = s.getAsJsonObject("e");
			emote = e.get("n").getAsString();
			emoteTicks = e.get("t").getAsInt();
			showCar = doubles(e.get("c"), 4);
			song = e.has("m") ? e.get("m").getAsString() : null;
		}
		double[] body = null, seat = null;
		String pose = null, main = null, off = null, seatIn = null;
		int flags = 0, swingCount = 0;
		if (s.has("l")) {
			JsonObject l = s.getAsJsonObject("l");
			body = doubles(l.get("b"), 9);
			pose = l.has("o") ? l.get("o").getAsString() : null;
			flags = l.has("f") ? l.get("f").getAsInt() : 0;
			swingCount = l.has("s") ? l.get("s").getAsInt() : 0;
			main = l.has("h") ? l.get("h").getAsString() : null;
			off = l.has("oh") ? l.get("oh").getAsString() : null;
			seatIn = l.has("in") ? l.get("in").getAsString() : null;
			seat = doubles(l.get("seat"), 3);
			if (body == null) return;
		}
		List<double[]> cars = null;
		if (s.has("c")) {
			cars = new ArrayList<>();
			for (JsonElement car : s.getAsJsonArray("c")) {
				double[] c = doubles(car, 7);
				if (c != null) cars.add(c);
				if (cars.size() >= 32) break;
			}
		}
		List<double[]> valets = null;
		if (s.has("v")) {
			valets = new ArrayList<>();
			for (JsonElement valet : s.getAsJsonArray("v")) {
				double[] v = doubles(valet, 8);
				if (v != null) valets.add(v);
				if (valets.size() >= 16) break;
			}
		}
		List<SharedFake> fakes = null;
		if (s.has("fp")) {
			fakes = new ArrayList<>();
			for (JsonElement element : s.getAsJsonArray("fp")) {
				if (!element.isJsonObject() || fakes.size() >= 24) continue;
				JsonObject f = element.getAsJsonObject();
				double[] v = doubles(f.get("b"), 9);
				String name = f.has("n") ? f.get("n").getAsString() : null;
				if (v == null || name == null || !name.matches("[A-Za-z0-9_.]{1,17}")) continue;
				UUID id = null;
				try {
					if (f.has("id")) id = UUID.fromString(f.get("id").getAsString());
				} catch (IllegalArgumentException ignored) {
				}
				fakes.add(new SharedFake(name, v, f.has("e") ? f.get("e").getAsString() : null, f.has("t") ? f.get("t").getAsInt() : 0,
					id, !f.has("as") || f.get("as").getAsBoolean(), f.has("h") ? f.get("h").getAsString() : null));
			}
		}
		List<SharedSchem> schems = null;
		if (s.has("sc") && s.get("sc").isJsonArray()) {
			schems = new ArrayList<>();
			for (JsonElement element : s.getAsJsonArray("sc")) {
				if (!element.isJsonObject() || schems.size() >= 8) continue;
				JsonObject o = element.getAsJsonObject();
				int[] at = ints(o.get("o"), 3);
				String sha = o.has("h") ? o.get("h").getAsString() : "";
				if (at.length < 3 || !sha.matches("[0-9a-f]{64}")) continue;
				String name = o.has("n") ? o.get("n").getAsString() : "schematic";
				schems.add(new SharedSchem(sha, name.length() > 64 ? name.substring(0, 64) : name, at[0], at[1], at[2],
					o.has("r") ? Math.floorMod(o.get("r").getAsInt(), 4) : 0, o.has("m") ? Math.floorMod(o.get("m").getAsInt(), 3) : 0));
			}
		}
		long interval = s.has("i") ? Mth.clamp(s.get("i").getAsLong(), 50, 5000) : 1000;
		incoming.add(new Snap(uuid, ts, System.nanoTime(), s.has("d") ? s.get("d").getAsString() : null, false, emote, emoteTicks, showCar, song,
			body, pose, flags, swingCount, main, off, seatIn, seat,
			doubles(s.get("j"), 10), ints(s.get("jc"), 3), doubles(s.get("hl"), 13), ints(s.get("hc"), 2), cars, ints(s.get("cc"), 7),
			doubles(s.get("g"), 4), ints(s.get("gc"), 2), valets, fakes, s.has("w") && s.get("w").isJsonObject() ? s.getAsJsonObject("w") : null,
			doubles(s.get("yt"), 4), ints(s.get("yc"), 4), doubles(s.get("mn"), 4), ints(s.get("mc"), 3), schems,
			s.has("tv") && s.get("tv").isJsonObject() ? s.getAsJsonObject("tv") : null,
			s.has("fb") && s.get("fb").isJsonObject() ? s.getAsJsonObject("fb") : null, interval * 1_000_000L));
	}

	/** A list of at least {@code size} finite numbers, or null. */
	private static double[] doubles(JsonElement element, int size) {
		if (element == null || !element.isJsonArray() || element.getAsJsonArray().size() < size) return null;
		JsonArray array = element.getAsJsonArray();
		double[] out = new double[size];
		for (int i = 0; i < size; i++) {
			out[i] = array.get(i).getAsDouble();
			if (!Double.isFinite(out[i])) return null;
		}
		return out;
	}

	private static int[] ints(JsonElement element, int size) {
		if (element == null || !element.isJsonArray() || element.getAsJsonArray().size() < size) return new int[0];
		JsonArray array = element.getAsJsonArray();
		int[] out = new int[size];
		for (int i = 0; i < size; i++) out[i] = array.get(i).getAsInt();
		return out;
	}

	/** Takes in queued updates, and forgets anyone who's gone quiet. */
	private void drain() {
		long now = System.nanoTime();
		UUID self = mc.player.getUUID();
		for (Snap snap; (snap = incoming.poll()) != null; ) {
			if (snap.uuid.equals(self)) continue;
			if (snap.gone) {
				Peer peer = peers.remove(snap.uuid);
				if (peer != null) forget(peer, true);
				continue;
			}
			Peer peer = peers.computeIfAbsent(snap.uuid, Peer::new);
			if (snap.ts <= peer.lastTs) continue;
			peer.lastTs = snap.ts;
			peer.heard = now;
			// Their clock onto ours: the quickest any update has arrived, creeping up so a slow patch can't stick
			long offset = snap.time - snap.ts * 1_000_000L;
			peer.offset = peer.offset == Long.MAX_VALUE ? offset : Math.min(peer.offset + 1_000_000L, offset);
			peer.snaps.addLast(snap.at(snap.ts * 1_000_000L + peer.offset));
			while (peer.snaps.size() > 3 && peer.snaps.peekFirst().time < now - KEEP) peer.snaps.removeFirst();
		}
		peers.values().removeIf(peer -> {
			Snap last = peer.snaps.peekLast();
			long timeout = Math.max(3_000_000_000L, last == null ? 0 : last.interval * 3);
			if (now - peer.heard <= timeout) return false;
			forget(peer, true);
			return true;
		});
	}

	/** The update in effect at {@code time} and the next one, and how far between them, for drawing that moment. */
	private record Moment(Snap a, Snap b, double f) {
	}

	private static Moment at(Peer peer, long time) {
		Snap a = null, b = null;
		for (Snap snap : peer.snaps) {
			if (snap.time <= time) a = snap;
			else {
				b = snap;
				break;
			}
		}
		if (a == null) a = b;
		if (a == null) return null;
		if (b == null || b == a) return new Moment(a, a, 0);
		double f = Mth.clamp((time - a.time) / (double) Math.max(1, b.time - a.time), 0, 1);
		return new Moment(a, b, f);
	}

	/** {@code a} toward {@code b} by {@code f}, the values at {@code angles} the short way round in degrees. */
	private static double[] lerp(double[] a, double[] b, double f, int... angles) {
		if (a == null) return null;
		if (b == null || b.length != a.length || f <= 0) return a;
		double[] out = new double[a.length];
		for (int i = 0; i < a.length; i++) out[i] = a[i] + (b[i] - a[i]) * f;
		for (int i : angles) if (i < a.length) out[i] = a[i] + Mth.wrapDegrees(b[i] - a[i]) * f;
		return out;
	}

	private boolean here(Snap snap) {
		return snap.dim != null && snap.dim.equals(mc.level.dimension().identifier().toString());
	}

	// ---------------------------------------------------------------- moving people about

	/** After the world's ticked: emotes, and larping players moved to where they really are this tick. */
	private void afterTick() {
		drain();
		long now = System.nanoTime() - DELAY;
		Map<Integer, EmotesModule.Shared> nextEmotes = new HashMap<>();
		EmotesModule emotesModule = FemboyWare.getModules().get(EmotesModule.class);
		cosmeticsById.clear();
		hatsById.clear();
		furryById.clear();
		for (Peer peer : peers.values()) {
			Moment m = at(peer, now);
			Snap a = m == null ? null : m.a;
			boolean show = a != null && showOthers.get() && here(a);
			Player found = show ? mc.level.getPlayerByUUID(peer.uuid) : null;
			AbstractClientPlayer entity = found instanceof AbstractClientPlayer p && p != mc.player
				&& !FemboyWare.getModules().get(FakePlayerModule.class).isFake(p.getId()) ? p : null;
			EmotesModule.Shared shared = entity != null && a.emote != null ? emoteFor(entity.getId(), a, now) : null;
			if (shared != null) nextEmotes.put(entity.getId(), shared);
			if (entity != null && a.body != null) place(peer, entity, m);
			else release(peer, true);
			peer.solidJet = show ? a.jet : null;
			peer.solidHeli = show ? a.heli : null;
			peer.solidCars = show ? a.cars : null;
			peer.solidHangar = show ? a.hangar : null;
			peer.solidYacht = show ? a.yacht : null;
			peer.solidMansion = show ? a.mansion : null;
			tickActors(peer, show ? m : null, nextEmotes);
			if (show) wear(peer, a, found);
			peer.emoter = shared != null ? entity : null;
			tickSounds(peer, shared, show ? a.song : null, emotesModule);
		}
		emotes.clear();
		emotes.putAll(nextEmotes);
		tickRide();
		tickSchematics(now);
		tickTvs();
		tickRaids();
	}

	/** Other people's FBI raids here, played from where each one's up to. */
	private void tickRaids() {
		Map<UUID, JsonObject> raids = new HashMap<>();
		Map<UUID, Double> since = new HashMap<>();
		long nanos = System.nanoTime();
		if (showOthers.get()) {
			for (Peer peer : peers.values()) {
				Snap last = peer.snaps.peekLast();
				if (last == null || last.raid == null || !here(last)) continue;
				raids.put(peer.uuid, last.raid);
				since.put(peer.uuid, (nanos - last.time) / 1e9);
			}
		}
		FemboyWare.getModules().get(dev.femboyware.module.larp.FbiRaidModule.class).syncPeers(raids, since);
	}

	// ---------------------------------------------------------------- TVs

	/** Shared TV files being fetched, and ones that failed until when. */
	private final Set<String> loadingMedia = ConcurrentHashMap.newKeySet();
	private final Map<String, Long> retryMedia = new ConcurrentHashMap<>();

	private static java.nio.file.Path mediaFile(String sha, String name) {
		return net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("femboyware").resolve("shared-tv")
			.resolve(sha + "." + dev.femboyware.module.larp.TvModule.extensionFor(name));
	}

	/**
	 * Other people's TVs here: shown wherever they put theirs, playing their file (fetched once from
	 * the relay, then kept) at the moment they're at, worked out from their latest update.
	 */
	private void tickTvs() {
		var tvModule = FemboyWare.getModules().get(dev.femboyware.module.larp.TvModule.class);
		Map<UUID, dev.femboyware.module.larp.TvModule.PeerTv> tvs = new HashMap<>();
		long nanos = System.nanoTime();
		if (showOthers.get()) {
			for (Peer peer : peers.values()) {
				Snap last = peer.snaps.peekLast();
				if (last == null || last.tv == null || !here(last)) continue;
				var tv = dev.femboyware.module.larp.TvModule.parse(last.tv, (nanos - last.time) / 1e9);
				if (tv == null) continue;
				java.nio.file.Path file = null;
				if (tv.sha() != null) {
					java.nio.file.Path cached = mediaFile(tv.sha(), tv.name());
					if (java.nio.file.Files.isRegularFile(cached) && !loadingMedia.contains(tv.sha())) file = cached;
					else fetchMedia(tv.sha(), tv.name(), peer.uuid);
				}
				tvs.put(peer.uuid, new dev.femboyware.module.larp.TvModule.PeerTv(tv.p(), tv.sha(), tv.name(), tv.position(), tv.playing(), tv.loop(), tv.duration(), file,
					tv.live(), tv.liveSeq()));
			}
		}
		tvModule.syncPeers(tvs);
	}

	/** Downloads what's on someone's TV off the main thread, checking it's what they said it was. */
	private void fetchMedia(String sha, String name, UUID who) {
		PeerLink l = link;
		Long retry = retryMedia.get(sha);
		if (l == null || retry != null && retry > System.currentTimeMillis() || !loadingMedia.add(sha)) return;
		Thread thread = new Thread(() -> {
			try {
				byte[] data = l.downloadFile(sha);
				if (data == null) {
					// Not up yet: they're probably still uploading it
					retryMedia.put(sha, System.currentTimeMillis() + 4_000);
					return;
				}
				if (!sha.equals(sha256(data))) throw new java.io.IOException("file didn't match");
				java.nio.file.Path file = mediaFile(sha, name);
				java.nio.file.Files.createDirectories(file.getParent());
				java.nio.file.Path temp = file.resolveSibling(file.getFileName() + ".part");
				java.nio.file.Files.write(temp, data);
				java.nio.file.Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			} catch (Exception | OutOfMemoryError e) {
				retryMedia.put(sha, System.currentTimeMillis() + 30_000);
				FemboyWare.LOGGER.debug("Couldn't fetch a shared TV file", e);
			} finally {
				loadingMedia.remove(sha);
			}
		}, "femboyware tv download");
		thread.setDaemon(true);
		thread.start();
	}

	// ---------------------------------------------------------------- schematics

	/** Your schematics already on the relay (by room and hash), being put there, or that failed until when. */
	private final Set<String> uploaded = ConcurrentHashMap.newKeySet(), uploading = ConcurrentHashMap.newKeySet();
	/** Other people's schematics being fetched and read, the ones wanted right now, and ones to try again later. */
	private final Set<String> loadingSchems = ConcurrentHashMap.newKeySet();
	private volatile Set<String> wantedSchems = Set.of();
	private final Map<String, Long> retrySchems = new ConcurrentHashMap<>();

	/** Puts your schematic on the relay, once, so the others can fetch it. */
	private void upload(ServerLarpModule.SharedSchematic schematic) {
		if (schematic.file() != null) upload(schematic.sha256(), schematic.file(), schematic.name());
	}

	/** Puts a file of yours (a schematic or what's on your TV) on the relay, once, so the others can fetch it. */
	private void upload(String sha256, java.nio.file.Path file, String name) {
		PeerLink l = link;
		if (l == null || file == null) return;
		String key = l.server() + "|" + sha256;
		Long retry = retrySchems.get(key);
		if (uploaded.contains(key) || retry != null && retry > System.currentTimeMillis() || !uploading.add(key)) return;
		Thread thread = new Thread(() -> {
			try {
				l.uploadFile(sha256, java.nio.file.Files.readAllBytes(file));
				uploaded.add(key);
			} catch (java.io.IOException | RuntimeException | OutOfMemoryError e) {
				retrySchems.put(key, System.currentTimeMillis() + 60_000);
				mc.execute(() -> dev.femboyware.util.ChatUtil.info("Couldn't share " + name + " with Client Sync: " + e.getMessage()));
			} finally {
				uploading.remove(key);
			}
		}, "femboyware upload");
		thread.setDaemon(true);
		thread.start();
	}

	/**
	 * Other people's schematics: while you're both larping here, theirs are built in your larp like
	 * your own (fetched once from the relay, then kept), and go again when they take one away or
	 * leave. Not while you aren't larping: they're real blocks on your screen, and walking on blocks
	 * the server doesn't have would get you pulled back.
	 */
	private void tickSchematics(long now) {
		ServerLarpModule larp = FemboyWare.getModules().get(ServerLarpModule.class);
		Map<String, SharedSchem> want = new HashMap<>();
		Map<String, String> owner = new HashMap<>();
		if (larp.isLarping() && showOthers.get() && link != null) {
			for (Peer peer : peers.values()) {
				Snap a = peer.snaps.peekLast();
				if (a == null || a.schems == null || !here(a)) continue;
				for (SharedSchem schem : a.schems) {
					String key = peer.uuid + "|" + schem.sha256() + "|" + schem.x() + "," + schem.y() + "," + schem.z() + "," + schem.rotation() + "," + schem.mirror();
					want.put(key, schem);
					owner.put(key, nameOf(peer.uuid));
				}
			}
		}
		wantedSchems = Set.copyOf(want.keySet());
		for (String key : larp.peerSchematics()) if (!want.containsKey(key)) larp.removePeerSchematic(key);
		Set<String> present = larp.peerSchematics();
		long millis = System.currentTimeMillis();
		for (var entry : want.entrySet()) {
			String key = entry.getKey();
			Long retry = retrySchems.get(key);
			if (present.contains(key) || loadingSchems.contains(key) || retry != null && retry > millis) continue;
			loadSchematic(key, entry.getValue(), owner.get(key));
		}
	}

	/** Fetches someone's schematic (or finds it from before), reads it off the main thread, then builds it. */
	private void loadSchematic(String key, SharedSchem schem, String who) {
		PeerLink l = link;
		if (l == null || !loadingSchems.add(key)) return;
		var blocks = dev.femboyware.module.larp.LarpSchematics.blockLookup();
		var level = mc.level;
		Thread thread = new Thread(() -> {
			try {
				java.nio.file.Path dir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("femboyware").resolve("shared-schematics");
				java.nio.file.Files.createDirectories(dir);
				java.nio.file.Path file = dir.resolve(schem.sha256() + ".litematic");
				if (!java.nio.file.Files.exists(file) || !schem.sha256().equals(sha256(java.nio.file.Files.readAllBytes(file)))) {
					byte[] data = l.downloadFile(schem.sha256());
					if (data == null) {
						// Not up yet: they're probably still uploading it
						retrySchems.put(key, System.currentTimeMillis() + 5_000);
						return;
					}
					java.nio.file.Files.write(file, data);
				}
				var schematic = dev.femboyware.module.larp.LarpSchematics.read(file, new net.minecraft.core.BlockPos(schem.x(), schem.y(), schem.z()),
					net.minecraft.world.level.block.Rotation.values()[schem.rotation()], net.minecraft.world.level.block.Mirror.values()[schem.mirror()], blocks);
				mc.execute(() -> {
					if (mc.level != level || !wantedSchems.contains(key)) return;
					FemboyWare.getModules().get(ServerLarpModule.class).addPeerSchematic(key, schem.name(), schematic);
					dev.femboyware.util.ChatUtil.info(who + " is larping " + schem.name() + ": building it here too");
				});
			} catch (Exception | OutOfMemoryError e) {
				retrySchems.put(key, System.currentTimeMillis() + 30_000);
				FemboyWare.LOGGER.debug("Couldn't load a shared schematic", e);
			} finally {
				loadingSchems.remove(key);
			}
		}, "femboyware shared schematic");
		thread.setDaemon(true);
		thread.start();
	}

	private static String sha256(byte[] data) {
		try {
			return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data));
		} catch (java.security.NoSuchAlgorithmException e) {
			return "";
		}
	}

	private String nameOf(UUID uuid) {
		var info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(uuid);
		return info == null ? "Someone" : info.getProfile().name();
	}

	// ---------------------------------------------------------------- teleports

	/** Where someone using Client Sync really is while they larp, by name; null if they aren't larping (or aren't here). */
	public Vec3 larpPositionOf(String name) {
		Peer peer = peerNamed(name);
		Snap last = peer == null ? null : peer.snaps.peekLast();
		return last == null || last.body == null || !here(last) ? null : new Vec3(last.body[0], last.body[1], last.body[2]);
	}

	private Peer peerNamed(String name) {
		if (!isEnabled() || mc.getConnection() == null) return null;
		var info = mc.getConnection().getPlayerInfo(name);
		return info == null ? null : peers.get(info.getProfile().id());
	}

	/** Teleports someone larping here, in their larp; null if it was sent, or why it couldn't be. */
	public String teleport(String name, Vec3 to) {
		if (link == null || !link.connected()) return "Client Sync isn't connected, so you can only teleport yourself";
		Peer peer = peerNamed(name);
		if (peer == null) return name + " isn't using femboyware with Client Sync here";
		if (larpPositionOf(name) == null) return name + " isn't larping, so there's nothing to teleport (it only moves them in their larp)";
		sendTeleport(peer.uuid.toString(), to);
		return null;
	}

	/** Teleports everyone larping here, in their larps; how many that is. */
	public int teleportEveryone(Vec3 to) {
		if (link == null || !link.connected()) return 0;
		int larping = 0;
		for (Peer peer : peers.values()) {
			Snap last = peer.snaps.peekLast();
			if (last != null && last.body != null && here(last)) larping++;
		}
		if (larping > 0) sendTeleport("*", to);
		return larping;
	}

	private void sendTeleport(String target, Vec3 to) {
		JsonObject message = new JsonObject();
		message.addProperty("u", mc.player.getUUID().toString());
		message.addProperty("ts", System.currentTimeMillis());
		JsonObject tp = new JsonObject();
		tp.addProperty("to", target);
		tp.add("p", numbers(to.x, to.y, to.z));
		message.add("tp", tp);
		link.sendInOrder(message.toString());
	}

	/** On a network thread: someone teleported you (or everyone). */
	private void receiveTeleport(UUID from, JsonObject tp) {
		String target = tp.has("to") ? tp.get("to").getAsString() : "";
		double[] p = doubles(tp.get("p"), 3);
		if (p == null) return;
		mc.execute(() -> {
			if (mc.player == null || !isEnabled() || from.equals(mc.player.getUUID())) return;
			if (!target.equals("*") && !target.equals(mc.player.getUUID().toString())) return;
			FemboyWare.getModules().get(ServerLarpModule.class).teleportedBy(nameOf(from), new Vec3(p[0], p[1], p[2]));
		});
	}

	// ---------------------------------------------------------------- their captains and valets

	private int nextActorId = -40000;
	/** Stand-ins by entity id: captains (seated or not) and valets, for their skins and poses. */
	private final Set<Integer> captains = new java.util.HashSet<>(), seatedJetCaptains = new java.util.HashSet<>(),
		heliCaptains = new java.util.HashSet<>(), valetIds = new java.util.HashSet<>();

	private RemotePlayer spawnActor(String name, double[] at) {
		RemotePlayer actor = new RemotePlayer(mc.level, new com.mojang.authlib.GameProfile(UUID.randomUUID(), name));
		actor.setId(nextActorId--);
		actor.snapTo(at[0], at[1], at[2], (float) at[3], 0);
		mc.level.addEntity(actor);
		return actor;
	}

	private void removeActor(RemotePlayer actor) {
		if (actor == null) return;
		captains.remove(actor.getId());
		seatedJetCaptains.remove(actor.getId());
		heliCaptains.remove(actor.getId());
		valetIds.remove(actor.getId());
		fakeNames.remove(actor.getId());
		if (actor.level() == mc.level && !actor.isRemoved()) mc.level.removeEntity(actor.getId(), net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
	}

	/** Their captains come and go with their jet and helicopter, and their valets walk where theirs do. */
	private void tickActors(Peer peer, Moment m, Map<Integer, EmotesModule.Shared> nextEmotes) {
		tickFakes(peer, m, nextEmotes);
		double[] jet = m == null ? null : m.a.jet, heli = m == null ? null : m.a.heli;
		if (jet == null && peer.jetPilot != null) {
			removeActor(peer.jetPilot);
			peer.jetPilot = null;
		} else if (jet != null && (peer.jetPilot == null || peer.jetPilot.isRemoved())) {
			peer.jetPilot = spawnActor("Captain", JetModule.sharedPilot(jet));
			captains.add(peer.jetPilot.getId());
		}
		if (peer.jetPilot != null) {
			if (jet[9] > 0.5) seatedJetCaptains.add(peer.jetPilot.getId());
			else seatedJetCaptains.remove(peer.jetPilot.getId());
		}
		if (heli == null && peer.heliPilot != null) {
			removeActor(peer.heliPilot);
			peer.heliPilot = null;
		} else if (heli != null && (peer.heliPilot == null || peer.heliPilot.isRemoved())) {
			peer.heliPilot = spawnActor("Captain", HelicopterModule.sharedPilot(heli));
			captains.add(peer.heliPilot.getId());
			heliCaptains.add(peer.heliPilot.getId());
		}
		List<double[]> valets = m == null || m.a.valets == null ? List.of() : m.a.valets;
		while (peer.valets.size() > valets.size()) {
			removeActor(peer.valets.removeLast());
			peer.valetWalk.removeLast();
		}
		for (int i = 0; i < valets.size(); i++) {
			double[] v = valets.get(i);
			if (m.b.valets != null && m.b.valets.size() == valets.size()) v = lerp(v, m.b.valets.get(i), m.f, 3, 4, 5);
			if (i >= peer.valets.size()) {
				RemotePlayer valet = spawnActor("Valet", v);
				valetIds.add(valet.getId());
				peer.valets.add(valet);
				peer.valetWalk.add(0f);
			}
			RemotePlayer valet = peer.valets.get(i);
			Vec3 from = valet.position();
			valet.setPos(v[0], v[1], v[2]);
			valet.xo = valet.xOld = from.x;
			valet.yo = valet.yOld = from.y;
			valet.zo = valet.zOld = from.z;
			valet.yRotO = valet.getYRot();
			valet.yHeadRotO = valet.getYHeadRot();
			valet.yBodyRotO = valet.yBodyRot;
			valet.setYRot((float) v[3]);
			valet.setYHeadRot((float) v[4]);
			valet.yBodyRot = (float) v[5];
			WalkAnimationStateAccessor walk = (WalkAnimationStateAccessor) valet.walkAnimation;
			walk.femboyware$setSpeedOld(peer.valetWalk.get(i));
			peer.valetWalk.set(i, (float) v[6]);
			walk.femboyware$setSpeed((float) v[6]);
			walk.femboyware$setPosition((float) v[7]);
		}
	}

	// ---------------------------------------------------------------- riding someone else's jet

	/** Whose jet you're riding in (null if none), where you're stood in its cabin this tick and last, and its heading last tick. */
	private Peer riding;
	private float[] rideSpot, rideSpotOld;
	private float rideYaw;

	/** Their jet as it's drawn at {@code time}, or null. */
	private static double[] jetAt(Peer peer, long time) {
		Moment m = at(peer, time);
		return m == null || m.a.jet == null ? null : lerp(m.a.jet, m.b.jet, m.f, 3, 4, 5);
	}

	/**
	 * While you're larping and stood in someone's cabin when they board, you go with the jet: carried
	 * along and turned with it, walking the aisle, until it's parked again. Blocks can't do this, since
	 * a flying jet tilts and rolls, so it's done like riding your own.
	 */
	private void tickRide() {
		var player = mc.player;
		boolean larping = FemboyWare.getModules().get(ServerLarpModule.class).isLarping();
		boolean ownFlight = FemboyWare.getModules().get(JetModule.class).shareSeat() != null;
		long time = System.nanoTime() - DELAY - TICK;
		if (riding != null) {
			double[] jet = peers.containsValue(riding) && showOthers.get() ? jetAt(riding, time) : null;
			if (jet == null || jet[9] < 0.5 || !larping || ownFlight) {
				// Parked again (or gone): you're left stood where you were, on its floor
				riding = null;
				player.setNoGravity(false);
				player.setDeltaMovement(Vec3.ZERO);
				player.resetFallDistance();
				return;
			}
			player.setYRot(player.getYRot() + Mth.wrapDegrees((float) jet[3] - rideYaw));
			rideYaw = (float) jet[3];
			rideSpotOld = rideSpot;
			var input = player.input.getMoveVector();
			if (input.lengthSquared() > 1) input = input.normalized();
			float speed = mc.options.keySprint.isDown() ? 0.26f : 0.18f;
			double facing = Math.toRadians(player.getYRot() - jet[3]);
			float fwdX = (float) -Math.sin(facing), fwdZ = (float) Math.cos(facing), leftX = (float) Math.cos(facing), leftZ = (float) Math.sin(facing);
			rideSpot = JetModule.cabinSpot(rideSpot[0] + (fwdX * input.y + leftX * input.x) * speed, rideSpot[2] + (fwdZ * input.y + leftZ * input.x) * speed);
			Vec3 at = JetModule.sharedSeat(jet, rideSpot);
			player.setDeltaMovement(Vec3.ZERO);
			player.setPos(at.x, at.y, at.z);
			player.setOnGround(true);
			player.resetFallDistance();
			return;
		}
		if (!larping || ownFlight || !showOthers.get()) return;
		for (Peer peer : peers.values()) {
			double[] jet = jetAt(peer, time);
			if (jet == null || jet[9] < 0.5) continue;
			org.joml.Vector3f local = JetModule.sharedLocal(jet, player.position());
			if (!JetModule.sharedInCabin(local)) continue;
			riding = peer;
			rideSpot = JetModule.cabinSpot(local.x, local.z);
			rideSpotOld = rideSpot;
			rideYaw = (float) jet[3];
			player.setNoGravity(true);
			dev.femboyware.util.ChatUtil.info("You're aboard " + nameOf(peer) + "'s jet: walk round the cabin while it flies");
			return;
		}
	}

	private String nameOf(Peer peer) {
		var info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(peer.uuid);
		return info == null ? "their" : info.getProfile().name();
	}

	/** Their fake players' stand-ins, by entity id, to the name whose skin they wear. */
	private final Map<Integer, String> fakeNames = new HashMap<>();

	/** Their fake players, here as stand-ins: same names and skins, walking where theirs walk, doing their emotes. */
	private void tickFakes(Peer peer, Moment m, Map<Integer, EmotesModule.Shared> nextEmotes) {
		List<SharedFake> list = m == null || m.a.fakes == null ? List.of() : m.a.fakes;
		// A different line-up (one added, removed or renamed): start them afresh
		boolean same = list.size() == peer.fakes.size();
		for (int i = 0; same && i < list.size(); i++) same = list.get(i).name().equals(peer.fakes.get(i).getGameProfile().name());
		if (!same) {
			for (RemotePlayer fake : peer.fakes) removeActor(fake);
			peer.fakes.clear();
			peer.fakeWalk.clear();
			for (SharedFake f : list) {
				// The same id as theirs, so it gets the same default skin and the same random horns
				UUID id = f.uuid() != null && mc.level.getEntity(f.uuid()) == null ? f.uuid() : UUID.randomUUID();
				RemotePlayer actor = new RemotePlayer(mc.level, new com.mojang.authlib.GameProfile(id, f.name()));
				actor.setId(nextActorId--);
				actor.snapTo(f.v()[0], f.v()[1], f.v()[2], (float) f.v()[3], 0);
				mc.level.addEntity(actor);
				if (f.accountSkin()) fakeNames.put(actor.getId(), f.name());
				peer.fakes.add(actor);
				peer.fakeWalk.add(0f);
			}
		}
		boolean between = m != null && m.b.fakes != null && m.b.fakes.size() == list.size();
		for (int i = 0; i < list.size(); i++) {
			SharedFake f = list.get(i);
			double[] v = between ? lerp(f.v(), m.b.fakes.get(i).v(), m.f, 3, 4, 5) : f.v();
			RemotePlayer actor = peer.fakes.get(i);
			Vec3 from = actor.position();
			actor.setPos(v[0], v[1], v[2]);
			actor.xo = actor.xOld = from.x;
			actor.yo = actor.yOld = from.y;
			actor.zo = actor.zOld = from.z;
			actor.yRotO = actor.getYRot();
			actor.yHeadRotO = actor.getYHeadRot();
			actor.yBodyRotO = actor.yBodyRot;
			actor.setYRot((float) v[3]);
			actor.setYHeadRot((float) v[4]);
			actor.yBodyRot = (float) v[5];
			actor.setShiftKeyDown(v[8] > 0.5);
			actor.setPose(v[8] > 0.5 ? Pose.CROUCHING : Pose.STANDING);
			WalkAnimationStateAccessor walk = (WalkAnimationStateAccessor) actor.walkAnimation;
			walk.femboyware$setSpeedOld(peer.fakeWalk.get(i));
			peer.fakeWalk.set(i, (float) v[6]);
			walk.femboyware$setSpeed((float) v[6]);
			walk.femboyware$setPosition((float) v[7]);
			if (f.emote() == null) continue;
			EmotesModule.Emote which;
			try {
				which = EmotesModule.Emote.valueOf(f.emote());
			} catch (IllegalArgumentException e) {
				continue;
			}
			// Same start as before unless it changed or restarted, so it doesn't stutter
			int start = mc.player.tickCount - f.emoteTicks() - (int) ((System.nanoTime() - DELAY - m.a.time) / TICK);
			EmotesModule.Shared current = emotes.get(actor.getId());
			nextEmotes.put(actor.getId(), current != null && current.emote == which && Math.abs(current.startTick - start) < 10 ? current
				: new EmotesModule.Shared(which, start, null, 0));
		}
	}

	/** Called from CameraMixin each frame: their captains moved to their seats exactly as their aircraft are drawn this frame. */
	public void placeFrame() {
		if (!isEnabled() || nullCheck() || peers.isEmpty()) return;
		long now = System.nanoTime() - DELAY - TICK;
		if (riding != null) {
			// You, exactly where their jet is this frame, so you don't judder inside it
			double[] jet = jetAt(riding, now);
			if (jet != null && rideSpot != null) {
				float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
				float[] spot = {Mth.lerp(partial, rideSpotOld[0], rideSpot[0]), rideSpot[1], Mth.lerp(partial, rideSpotOld[2], rideSpot[2])};
				Vec3 at = JetModule.sharedSeat(jet, spot);
				var player = mc.player;
				player.setPos(at.x, at.y, at.z);
				player.xo = player.xOld = at.x;
				player.yo = player.yOld = at.y;
				player.zo = player.zOld = at.z;
			}
		}
		for (Peer peer : peers.values()) {
			if (peer.jetPilot == null && peer.heliPilot == null) continue;
			Moment m = at(peer, now);
			if (m == null) continue;
			if (peer.jetPilot != null && m.a.jet != null) seat(peer.jetPilot, JetModule.sharedPilot(lerp(m.a.jet, m.b.jet, m.f, 3, 4, 5)));
			if (peer.heliPilot != null && m.a.heli != null) seat(peer.heliPilot, HelicopterModule.sharedPilot(lerp(m.a.heli, m.b.heli, m.f, 3, 4, 5)));
		}
	}

	private static void seat(RemotePlayer actor, double[] at) {
		float yaw = (float) at[3];
		actor.setPos(at[0], at[1], at[2]);
		actor.setOldPosAndRot();
		actor.setYRot(yaw);
		actor.yRotO = yaw;
		actor.yBodyRot = yaw;
		actor.yBodyRotO = yaw;
		actor.yHeadRot = yaw;
		actor.yHeadRotO = yaw;
		actor.setXRot(0);
	}

	/** For the skin mixin: their captains wear the uniform, their valets the suit. */
	public net.minecraft.world.entity.player.PlayerSkin skinFor(AbstractClientPlayer player, net.minecraft.world.entity.player.PlayerSkin textures) {
		// Someone with femboyware who's wearing their fursona skin
		var furry = furryOf(player.getId());
		if (furry != null && furry.skin()) return dev.femboyware.module.render.FurryModule.fursonaSkin(textures);
		if (player.getId() >= 0) return textures;
		if (captains.contains(player.getId())) return JetModule.CAPTAIN;
		if (valetIds.contains(player.getId())) return CarsModule.SUIT;
		String fakeName = fakeNames.get(player.getId());
		if (fakeName != null) {
			var skin = dev.femboyware.util.AccountSkins.get(fakeName);
			if (skin != null) return skin;
		}
		return textures;
	}

	/** Called from PlayerModelMixin: their captains sit at the controls. */
	public void posePilots(net.minecraft.client.model.player.PlayerModel model, net.minecraft.client.renderer.entity.state.AvatarRenderState state) {
		if (state.id >= 0) return;
		if (seatedJetCaptains.contains(state.id)) JetModule.poseSeatedPilot(model);
		else if (heliCaptains.contains(state.id)) HelicopterModule.poseSeatedPilot(model, true);
	}

	/** Called from LarpProps: other people's parked jets, helipads, cars and hangars as solid boxes. */
	public List<net.minecraft.world.phys.shapes.VoxelShape> propCollisions(net.minecraft.world.entity.Entity entity, net.minecraft.world.phys.AABB area) {
		if (!isEnabled() || !showOthers.get() || peers.isEmpty()) return List.of();
		List<net.minecraft.world.phys.shapes.VoxelShape> shapes = new ArrayList<>();
		for (Peer peer : peers.values()) {
			if (peer.solidJet != null) shapes.addAll(FemboyWare.getModules().get(JetModule.class).sharedCollisions(entity, area, peer.solidJet));
			if (peer.solidHeli != null) shapes.addAll(FemboyWare.getModules().get(HelicopterModule.class).sharedCollisions(entity, area, peer.solidHeli));
			if (peer.solidCars != null) shapes.addAll(FemboyWare.getModules().get(CarsModule.class).sharedCollisions(area, peer.solidCars));
			if (peer.solidHangar != null) shapes.addAll(FemboyWare.getModules().get(HangarModule.class).sharedCollisions(entity, area, peer.solidHangar));
			if (peer.solidYacht != null) shapes.addAll(FemboyWare.getModules().get(dev.femboyware.module.larp.YachtModule.class).sharedCollisions(entity, area, peer.solidYacht));
			if (peer.solidMansion != null) shapes.addAll(FemboyWare.getModules().get(dev.femboyware.module.larp.MansionModule.class).sharedCollisions(entity, area, peer.solidMansion));
		}
		return shapes;
	}

	// ---------------------------------------------------------------- hearing their emotes

	private final net.minecraft.util.RandomSource soundRandom = net.minecraft.util.RandomSource.create();
	/** Full volume this close to them, fading out to nothing this far away. */
	private static final double LOUD = 5, SILENT = 30;

	/** Their emote song, which turns its own volume up and down every tick with how close they are. */
	private final class PeerSong extends net.minecraft.client.resources.sounds.AbstractTickableSoundInstance {
		private final Peer peer;

		PeerSong(Identifier song, Peer peer) {
			super(net.minecraft.sounds.SoundEvent.createVariableRangeEvent(song), net.minecraft.sounds.SoundSource.MASTER, soundRandom);
			this.peer = peer;
			this.attenuation = Attenuation.NONE;
			this.relative = true;
			this.looping = false;
			this.volume = 0;
		}

		@Override
		public boolean canStartSilent() {
			return true;
		}

		@Override
		public void tick() {
			volume = peer.songVolume;
		}

		void end() {
			stop();
		}
	}

	/** How loud someone this far away is, 1 close up easing to 0. */
	private static float fade(double distance) {
		double t = Mth.clamp((distance - LOUD) / (SILENT - LOUD), 0, 1);
		return (float) ((1 - t) * (1 - t));
	}

	/**
	 * Their song and champagne, louder the closer they are. While you're doing an emote you only
	 * hear yours, so theirs carries on silently and is there at the right point when you stop.
	 */
	private void tickSounds(Peer peer, EmotesModule.Shared shared, String song, EmotesModule emotesModule) {
		Player emoter = peer.emoter;
		double distance = emoter == null ? Double.MAX_VALUE : emoter.distanceTo(mc.player);
		boolean muted = emotesModule.emoting() || !hearOthers.get();
		float loudness = muted || emoter == null ? 0 : fade(distance);
		// Far off, their song isn't kept going at all; it starts again from the top when you're back near
		if (song == null || distance > SILENT * 2) {
			stopSong(peer);
			peer.songName = null;
		} else if (!song.equals(peer.songName)) {
			stopSong(peer);
			peer.songName = song;
			Identifier sound = EmotesModule.songSound(song);
			if (sound != null) {
				peer.song = new PeerSong(sound, peer);
				peer.songStarted = mc.player.tickCount;
				mc.getSoundManager().play(peer.song);
			}
		} else if (peer.song != null && mc.player.tickCount - peer.songStarted > 40 && !mc.getSoundManager().isActive(peer.song)) {
			// Finished here before their next one started; wait for it rather than playing this one again
			peer.song = null;
		}
		peer.songVolume = loudness * emotesModule.musicVolume();
		if (shared != null && shared.emote == EmotesModule.Emote.CHAMPAGNE && loudness > 0.02f) {
			int ticks = mc.player.tickCount - shared.startTick;
			int shake = EmotesModule.SHAKE_TICKS;
			if (ticks == shake) dev.femboyware.module.larp.LarpSounds.play(dev.femboyware.module.larp.LarpSounds.CHAMPAGNE_POP, loudness);
			if (ticks > shake && (ticks - shake) % 28 == 2) dev.femboyware.module.larp.LarpSounds.play(dev.femboyware.module.larp.LarpSounds.CHAMPAGNE_SPRAY, 0.6f * loudness);
		}
	}

	private void stopSong(Peer peer) {
		if (peer.song != null) peer.song.end();
		peer.song = null;
	}

	/** Puts back someone we'd moved, takes away their stand-ins and stops their song. */
	private void forget(Peer peer, boolean restore) {
		release(peer, restore);
		removeActor(peer.jetPilot);
		removeActor(peer.heliPilot);
		for (RemotePlayer valet : peer.valets) removeActor(valet);
		for (RemotePlayer fake : peer.fakes) removeActor(fake);
		peer.fakes.clear();
		peer.fakeWalk.clear();
		peer.jetPilot = peer.heliPilot = null;
		peer.valets.clear();
		peer.valetWalk.clear();
		stopSong(peer);
	}

	/** Their emote: the one already going, unless it changed or restarted. */
	private EmotesModule.Shared emoteFor(int entityId, Snap a, long now) {
		EmotesModule.Emote which;
		try {
			which = EmotesModule.Emote.valueOf(a.emote);
		} catch (IllegalArgumentException e) {
			return null;
		}
		int start = mc.player.tickCount - a.emoteTicks - (int) ((now - a.time) / TICK);
		EmotesModule.Shared current = emotes.get(entityId);
		if (current != null && current.emote == which && Math.abs(current.startTick - start) < 10) return current;
		Vec3 car = a.showCar == null ? null : new Vec3(a.showCar[0], a.showCar[1], a.showCar[2]);
		return new EmotesModule.Shared(which, start, car, a.showCar == null ? 0 : (float) a.showCar[3]);
	}

	/** Moves a larping player to where they really are at this moment, remembering where the server has them first. */
	private void place(Peer peer, AbstractClientPlayer entity, Moment m) {
		if (peer.entity != entity) {
			release(peer, true);
			peer.entity = entity;
			peer.serverPos = entity.position();
			peer.serverYaw = entity.getYRot();
			peer.serverPitch = entity.getXRot();
			peer.serverHead = entity.getYHeadRot();
			peer.serverBody = entity.yBodyRot;
			peer.serverPose = entity.getPose();
			peer.serverCrouch = entity.isShiftKeyDown();
			peer.serverGlide = entity.isFallFlying();
			peer.serverSwim = entity.isSwimming();
			peer.serverMain = entity.getMainHandItem().copy();
			peer.serverOff = entity.getOffhandItem().copy();
			peer.shownMain = peer.shownOff = null;
			peer.swings = Integer.MIN_VALUE;
			overridden.add(entity.getId());
		}
		Snap a = m.a, b = m.b;
		double[] body = lerp(a.body, b.body, m.f, 3, 4, 5, 6);
		Vec3 pos = new Vec3(body[0], body[1], body[2]);
		peer.seatedIn = a.seatIn;
		// Aboard their jet or helicopter: exactly in their seat, wherever it's flown to by now
		if ("jet".equals(a.seatIn) && a.jet != null && a.seat != null) {
			pos = JetModule.sharedSeat(lerp(a.jet, b.jet, m.f, 3, 4, 5), toFloats(lerp(a.seat, b.seat, m.f)));
		} else if ("heli".equals(a.seatIn) && a.heli != null && a.seat != null) {
			pos = HelicopterModule.sharedSeat(lerp(a.heli, b.heli, m.f, 3, 4, 5), toFloats(lerp(a.seat, b.seat, m.f)));
		}
		Vec3 from = entity.position();
		entity.setPos(pos);
		entity.xo = entity.xOld = from.x;
		entity.yo = entity.yOld = from.y;
		entity.zo = entity.zOld = from.z;
		entity.yRotO = entity.getYRot();
		entity.xRotO = entity.getXRot();
		entity.yHeadRotO = entity.getYHeadRot();
		entity.yBodyRotO = entity.yBodyRot;
		entity.setYRot((float) body[3]);
		entity.setXRot((float) body[4]);
		entity.setYHeadRot((float) body[5]);
		entity.yBodyRot = (float) body[6];
		entity.setDeltaMovement(Vec3.ZERO);
		// Their own walk animation, rather than one worked out from how far they moved
		WalkAnimationStateAccessor walk = (WalkAnimationStateAccessor) entity.walkAnimation;
		walk.femboyware$setSpeedOld(peer.walkSpeed);
		peer.walkSpeed = (float) body[7];
		walk.femboyware$setSpeed(peer.walkSpeed);
		walk.femboyware$setPosition((float) body[8]);
		Pose pose = Pose.STANDING;
		try {
			if (a.pose != null) pose = Pose.valueOf(a.pose);
		} catch (IllegalArgumentException ignored) {
		}
		entity.setShiftKeyDown((a.flags & 1) != 0);
		((EntitySharedFlagInvoker) entity).femboyware$setSharedFlag(7, (a.flags & 2) != 0);
		((EntitySharedFlagInvoker) entity).femboyware$setSharedFlag(4, (a.flags & 4) != 0);
		entity.setPose(pose);
		if (peer.swings != Integer.MIN_VALUE && a.swings != peer.swings) entity.swing(InteractionHand.MAIN_HAND);
		peer.swings = a.swings;
		if (a.main != null && !a.main.equals(peer.shownMain)) {
			entity.setItemSlot(EquipmentSlot.MAINHAND, item(a.main));
			peer.shownMain = a.main;
		}
		if (a.off != null && !a.off.equals(peer.shownOff)) {
			entity.setItemSlot(EquipmentSlot.OFFHAND, item(a.off));
			peer.shownOff = a.off;
		}
	}

	private static float[] toFloats(double[] values) {
		float[] out = new float[values.length];
		for (int i = 0; i < values.length; i++) out[i] = (float) values[i];
		return out;
	}

	private static ItemStack item(String id) {
		Identifier key = Identifier.tryParse(id);
		if (key == null) return ItemStack.EMPTY;
		return BuiltInRegistries.ITEM.getOptional(key).map(ItemStack::new).orElse(ItemStack.EMPTY);
	}

	/** Puts a moved player back as the server has them, then plays what the server said about them meanwhile. */
	private void release(Peer peer, boolean restore) {
		AbstractClientPlayer entity = peer.entity;
		peer.entity = null;
		peer.seatedIn = null;
		if (entity == null) return;
		overridden.remove(entity.getId());
		List<Packet<?>> replay = held.remove(entity.getId());
		if (!restore || entity.isRemoved() || entity.level() != mc.level) return;
		entity.snapTo(peer.serverPos.x, peer.serverPos.y, peer.serverPos.z, peer.serverYaw, peer.serverPitch);
		entity.setOldPosAndRot();
		entity.setYHeadRot(peer.serverHead);
		entity.yHeadRotO = peer.serverHead;
		entity.yBodyRot = entity.yBodyRotO = peer.serverBody;
		entity.setShiftKeyDown(peer.serverCrouch);
		((EntitySharedFlagInvoker) entity).femboyware$setSharedFlag(7, peer.serverGlide);
		((EntitySharedFlagInvoker) entity).femboyware$setSharedFlag(4, peer.serverSwim);
		entity.setPose(peer.serverPose);
		entity.setItemSlot(EquipmentSlot.MAINHAND, peer.serverMain);
		entity.setItemSlot(EquipmentSlot.OFFHAND, peer.serverOff);
		ClientPacketListener connection = mc.getConnection();
		if (replay != null && connection != null) {
			for (Packet<?> packet : replay) {
				try {
					handle(packet, connection);
				} catch (RuntimeException e) {
					FemboyWare.LOGGER.debug("Client Sync couldn't replay a packet", e);
				}
			}
		}
	}

	private void releaseAll(boolean restore) {
		if (riding != null && mc.player != null) mc.player.setNoGravity(false);
		riding = null;
		for (Peer peer : peers.values()) forget(peer, restore);
		overridden.clear();
		held.clear();
	}

	@SuppressWarnings("unchecked")
	private static void handle(Packet<?> packet, ClientPacketListener connection) {
		((Packet<ClientPacketListener>) (Packet<?>) packet).handle(connection);
	}

	/** On the network thread: what the server says about a player we've moved waits until they stop larping. */
	@Override
	public void onPacketReceive(PacketEvent event) {
		if (overridden.isEmpty()) return;
		int id = entityOf(event.getPacket());
		if (id == Integer.MIN_VALUE || !overridden.contains(id)) return;
		event.cancel();
		List<Packet<?>> list = held.computeIfAbsent(id, k -> Collections.synchronizedList(new ArrayList<>()));
		// A player that's moved about a lot meanwhile only needs to end up right, so old moves can go
		if (list.size() < 2000) list.add(event.getPacket());
	}

	/** The entity a movement, equipment or data packet is for, or MIN_VALUE if it isn't one of those. */
	private static int entityOf(Packet<?> packet) {
		if (packet instanceof ClientboundMoveEntityPacket move) return ((MoveEntityPacketAccessor) move).femboyware$getEntityId();
		if (packet instanceof ClientboundRotateHeadPacket head) return ((RotateHeadPacketAccessor) head).femboyware$getEntityId();
		if (packet instanceof ClientboundTeleportEntityPacket teleport) return teleport.id();
		if (packet instanceof ClientboundEntityPositionSyncPacket sync) return sync.id();
		if (packet instanceof ClientboundSetEquipmentPacket equipment) return equipment.getEntity();
		if (packet instanceof ClientboundSetEntityDataPacket data) return data.id();
		//? if >=26.1 {
		/*if (packet instanceof ClientboundSetEntityMotionPacket motion) return motion.id();
		*///?} else {
		if (packet instanceof ClientboundSetEntityMotionPacket motion) return motion.getId();
		//?}
		return Integer.MIN_VALUE;
	}

	// ---------------------------------------------------------------- drawing their things

	@Override
	public void onRender3D(Render3DEvent event) {
		long frame = System.nanoTime();
		float dt = lastFrame == 0 ? 0 : Math.min(0.1f, (frame - lastFrame) / 1e9f);
		lastFrame = frame;
		if (!showOthers.get()) return;
		// Other people's emote props and cash, even with your own Emotes off
		EmotesModule emote = FemboyWare.getModules().get(EmotesModule.class);
		if (!emote.isEnabled() && !emotes.isEmpty()) emote.drawShowCars(event);
		// A tick behind their players, which are drawn between where they were last tick and this one
		long now = frame - DELAY - TICK;
		Vec3 cam = event.camera().pos;
		var tvModule = FemboyWare.getModules().get(dev.femboyware.module.larp.TvModule.class);
		for (Peer peer : peers.values()) {
			Moment m = at(peer, now);
			if (m == null || !here(m.a)) continue;
			Snap a = m.a, b = m.b;
			tvModule.drawingFor(peer.uuid);
			if (a.jet != null) {
				double[] jet = lerp(a.jet, b.jet, m.f, 3, 4, 5);
				if (near(cam, jet)) FemboyWare.getModules().get(JetModule.class).drawShared(event, jet, a.jetLook);
			}
			if (a.heli != null) {
				double[] heli = lerp(a.heli, b.heli, m.f, 3, 4, 5);
				if (near(cam, heli)) FemboyWare.getModules().get(HelicopterModule.class).drawShared(event, heli, a.heliLook, peer.heliSpin, dt);
			}
			if (a.cars != null) {
				List<double[]> cars = a.cars;
				if (b.cars != null && b.cars.size() == cars.size()) {
					cars = new ArrayList<>();
					for (int i = 0; i < a.cars.size(); i++) {
						double[] from = a.cars.get(i), to = b.cars.get(i);
						cars.add(from[0] == to[0] ? lerp(from, to, m.f, 4) : from);
					}
				}
				List<double[]> close = new ArrayList<>();
				for (double[] car : cars) if (cam.distanceToSqr(car[1], car[2], car[3]) < PROP_RANGE * PROP_RANGE) close.add(car);
				FemboyWare.getModules().get(CarsModule.class).drawShared(event, close, a.carLook);
			}
			if (a.hangar != null && near(cam, a.hangar)) FemboyWare.getModules().get(HangarModule.class).drawShared(event, a.hangar, a.hangarLook);
			if (a.yacht != null && near(cam, a.yacht)) FemboyWare.getModules().get(dev.femboyware.module.larp.YachtModule.class).drawShared(event, a.yacht, a.yachtLook);
			if (a.mansion != null && near(cam, a.mansion)) FemboyWare.getModules().get(dev.femboyware.module.larp.MansionModule.class).drawShared(event, a.mansion, a.mansionLook);
		}
		tvModule.drawingFor(null);
		tvModule.drawPeers(event);
		FemboyWare.getModules().get(dev.femboyware.module.larp.FbiRaidModule.class).drawPeers(event);
	}

	/** Their cash, coins, wine and champagne, after the solid things, while your own Emotes is off. */
	@Override
	public void onRender3DLate(Render3DEvent event) {
		if (!showOthers.get()) return;
		EmotesModule emote = FemboyWare.getModules().get(EmotesModule.class);
		if (!emote.isEnabled() && !emotes.isEmpty()) emote.renderEffects(event);
		// Their horn fires, with your own Cosmetics off
		var cosmetics = FemboyWare.getModules().get(dev.femboyware.module.render.CosmeticsModule.class);
		if (!cosmetics.isEnabled() && !cosmeticsById.isEmpty()) cosmetics.onRender3DLate(event);
	}

	private static boolean near(Vec3 cam, double[] s) {
		return cam.distanceToSqr(s[0], s[1], s[2]) < PROP_RANGE * PROP_RANGE;
	}
}
