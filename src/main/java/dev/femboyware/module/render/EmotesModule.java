package dev.femboyware.module.render;

import com.mojang.math.Axis;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.femboyware.FemboyWare;
import dev.femboyware.gui.EmoteWheelScreen;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.module.client.FakePlayerModule;
import dev.femboyware.module.hud.NowPlaying;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.KeySetting;
import dev.femboyware.setting.NumberSetting;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import dev.femboyware.util.Mc;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback;
import net.minecraft.client.CameraType;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.larp.CinematicCameraModule;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

/**
 * Larp emotes picked from a wheel. They pose your own player model, and with Client Sync on other
 * femboyware users on the server see them too (and you see theirs).
 */
public class EmotesModule extends Module {
	public enum Emote {
		BAND_SPREAD("Band Spread"),
		MONEY_RAIN("Money Rain"),
		FLEX("Flex"),
		BITCOIN_RAIN("Bitcoin Rain"),
		WATCH_FLEX("Watch Flex"),
		WATCH_CHECK("Watch Check"),
		WATCH_SHINE("Watch Shine"),
		SCOTCH_POUR("Wine Pour"),
		PHONE_FLEX("Phone Flex"),
		LEDGER_FLEX("Ledger Flex"),
		MAYBACH_BOUNCE("Maybach Bounce"),
		CHAMPAGNE("Champagne Spray"),
		MONEY_COUNTER("Money Counter"),
		BITCOIN_FLIP("Bitcoin Flip"),
		HODL("HODL"),
		SUPPORT_DESK("Support Desk"),
		MAYBACH_LAPTOP("Maybach Laptop"),
		LEDGER_PLUG("Ledger Plug");

		public final String label;

		Emote(String label) {
			this.label = label;
		}
	}

	public enum Camera { OFF, BACK, FRONT, CINEMATIC }

	/** The songs emotes play, in this order, round and round. Sounds and art are in assets/femboyware. */
	private enum Song {
		BLUE_BANDS("blue_bands", "blue bands", "Weiland", 129.7, false),
		QUEEN_ST("queen_st", "Queen St", "twentythree", 128.8, true),
		VYZEE("vyzee", "VYZEE (slowed)", "SOPHIE", 247.9, true);

		final Identifier sound;
		final String title, artist, art;
		final double length;

		Song(String id, String title, String artist, double length, boolean hasArt) {
			this.sound = Identifier.fromNamespaceAndPath("femboyware", "emote." + id);
			this.title = title;
			this.artist = artist;
			this.length = length;
			this.art = hasArt ? "/assets/femboyware/textures/songs/" + id + ".png" : null;
		}
	}

	public static final Identifier BILL = Identifier.fromNamespaceAndPath("femboyware", "textures/emotes/bill.png");
	public static final Identifier BTC = Identifier.fromNamespaceAndPath("femboyware", "textures/larp/coin_btc.png");
	private static final Identifier CASH_SIDE = Identifier.fromNamespaceAndPath("femboyware", "textures/larp/cash_side.png");

	private final KeySetting wheelKey = add(new KeySetting("Wheel Key", "Hold to open the emote wheel, let go over an emote to play it", GLFW.GLFW_KEY_B));
	private final EnumSetting<Camera> camera = add(new EnumSetting<>("Camera", "Third person while you emote, so you can see it, or Cinematic for film-style camera moves", Camera.FRONT));
	private final BoolSetting stopOnMove = add(new BoolSetting("Stop On Move", "Walking or jumping ends the emote", true));
	private final BoolSetting music = add(new BoolSetting("Music", "Play a song while you emote, the next one each time, shown on the Island", true));
	private final NumberSetting volume = add(new NumberSetting("Music Volume", "How loud the emote songs are", 0.7, 0.05, 1, 0.05)).visibleWhen(music::get);
	private final BoolSetting fakesCopy = add(new BoolSetting("Fake Players Copy", "Every fake player does your emote with you", true));

	private Emote playing;
	private int startTick;
	private CameraType previousCamera;
	private Song song;
	private SoundInstance songSound;
	private int songTick;
	private final CashRain rain = new CashRain();
	private long lastRainFrame;
	private float rainOwed;
	private int lastBeat = -1;

	/**
	 * Someone else's emote, from Client Sync: what, when it started on our clock, where their Maybach
	 * is for the bounce, and their own rain timing.
	 */
	public static final class Shared {
		public final Emote emote;
		public final int startTick;
		public final Vec3 showCar;
		public final float showCarYaw;
		float rainOwed;
		int lastBeat = -1;

		public Shared(Emote emote, int startTick, Vec3 showCar, float showCarYaw) {
			this.emote = emote;
			this.startTick = startTick;
			this.showCar = showCar;
			this.showCarYaw = showCarYaw;
		}
	}

	private static dev.femboyware.module.client.ClientSyncModule sync() {
		return FemboyWare.getModules().get(dev.femboyware.module.client.ClientSyncModule.class);
	}

	/** For Client Sync: how many ticks into your emote you are. */
	public int playingTicks() {
		return playing == null || mc.player == null ? 0 : mc.player.tickCount - startTick;
	}

	/** For Client Sync: where your Maybach is parked for the bounce (null if not bouncing). */
	public Vec3 showCarAt() {
		return onMaybach(playing) || playing == Emote.MONEY_COUNTER || playing == Emote.SUPPORT_DESK ? showCarAt : null;
	}

	/** Whether this emote has you sat up on the Maybach's bonnet. */
	public static boolean onMaybach(Emote emote) {
		return emote == Emote.MAYBACH_BOUNCE || emote == Emote.MAYBACH_LAPTOP;
	}

	public float showCarYaw() {
		return showCarYaw;
	}

	/** For Client Sync: the song your emote's playing right now, by name, or null. */
	public String songName() {
		return playing != null && song != null && songSound != null ? song.name() : null;
	}

	/** The sound for an emote song by its name, or null if there's no such song. */
	public static Identifier songSound(String name) {
		for (Song s : Song.values()) if (s.name().equals(name)) return s.sound;
		return null;
	}

	/** How loud emote songs are, for other people's songs too. */
	public float musicVolume() {
		return volume.get().floatValue();
	}

	/** Whether you're doing an emote yourself, so you only hear your own song. */
	public boolean emoting() {
		return isEnabled() && playing != null;
	}

	public EmotesModule() {
		super("Emotes", "An emote wheel with larp emotes like the band spread", Category.LARP);
		LivingEntityFeatureRendererRegistrationCallback.EVENT.register((type, renderer, helper, context) -> {
			if (renderer instanceof AvatarRenderer<?> player) helper.register(new Feature(asContext(player)));
		});
	}

	@SuppressWarnings("unchecked")
	private static RenderLayerParent<AvatarRenderState, PlayerModel> asContext(AvatarRenderer<?> renderer) {
		return (RenderLayerParent<AvatarRenderState, PlayerModel>) (Object) renderer;
	}

	@Override
	public String getInfo() {
		return playing == null ? null : playing.label;
	}

	public int wheelKey() {
		return wheelKey.get();
	}

	public Emote playing() {
		return playing;
	}

	@Override
	public void onKey(int key) {
		if (wheelKey.is(key)) Mc.setScreen(new EmoteWheelScreen(this));
	}

	public void play(Emote emote) {
		if (mc.player == null) return;
		if (emote == Emote.LEDGER_PLUG && dev.femboyware.module.larp.FbiRaidModule.assetsFrozen()) {
			dev.femboyware.util.ChatUtil.info("Your Ledger was seized by the FBI");
			return;
		}
		boolean wasPlaying = playing != null;
		playing = emote;
		startTick = mc.player.tickCount;
		// Switching straight from one emote to another keeps the song going
		if (!wasPlaying && music.get()) playSong();
		lastBeat = -1;
		if (onMaybach(emote)) {
			// Right under you, pointing the way you face, so you're sat on its bonnet facing out the front
			float yaw = mc.player.getYRot();
			double rad = Math.toRadians(yaw);
			showCarAt = mc.player.position().add(-Math.sin(rad) * -HOOD_SEAT, 0, Math.cos(rad) * -HOOD_SEAT);
			showCarYaw = yaw;
		}
		if (emote == Emote.MONEY_COUNTER || emote == Emote.SUPPORT_DESK) {
			// The counting table (or the desk) just in front of you, facing you
			float yaw = mc.player.getYRot();
			double rad = Math.toRadians(yaw);
			showCarAt = mc.player.position().add(-Math.sin(rad) * COUNTER_REACH, 0, Math.cos(rad) * COUNTER_REACH);
			showCarYaw = yaw;
		}
		if (camera.is(Camera.CINEMATIC)) {
			FemboyWare.getModules().get(CinematicCameraModule.class).startForEmote();
			return;
		}
		if (camera.is(Camera.OFF) || previousCamera != null) return;
		previousCamera = mc.options.getCameraType();
		mc.options.setCameraType(camera.is(Camera.FRONT) ? CameraType.THIRD_PERSON_FRONT : CameraType.THIRD_PERSON_BACK);
	}

	public void stop() {
		playing = null;
		stopSong();
		FemboyWare.getModules().get(CinematicCameraModule.class).stopForEmote();
		if (previousCamera != null) mc.options.setCameraType(previousCamera);
		previousCamera = null;
	}

	@Override
	public void onTick() {
		if (playing == null) return;
		if (playing == Emote.CHAMPAGNE) {
			int ticks = mc.player.tickCount - startTick;
			if (ticks == SHAKE_TICKS) dev.femboyware.module.larp.LarpSounds.play(dev.femboyware.module.larp.LarpSounds.CHAMPAGNE_POP, 1f);
			if (ticks > SHAKE_TICKS && (ticks - SHAKE_TICKS) % 28 == 2) dev.femboyware.module.larp.LarpSounds.play(dev.femboyware.module.larp.LarpSounds.CHAMPAGNE_SPRAY, 0.6f);
		}
		if ((playing == Emote.SUPPORT_DESK || playing == Emote.MAYBACH_LAPTOP) && (mc.player.tickCount - startTick) % 48 == 4) {
			dev.femboyware.module.larp.LarpSounds.play(dev.femboyware.module.larp.LarpSounds.TYPING, 0.45f);
		}
		if (playing == Emote.LEDGER_PLUG) {
			int ticks = mc.player.tickCount - startTick;
			if (ticks == PLUG_IN) dev.femboyware.module.larp.LarpSounds.play(dev.femboyware.module.larp.LarpSounds.LEDGER_BEEP, 1f);
			if (ticks == PLUG_DONE) dev.femboyware.module.larp.LarpSounds.play(dev.femboyware.module.larp.LarpSounds.LEDGER_SUCCESS, 1f);
		}
		if (playing == Emote.MONEY_COUNTER && (mc.player.tickCount - startTick) % 50 == 0) {
			dev.femboyware.module.larp.LarpSounds.play(dev.femboyware.module.larp.LarpSounds.MONEY_COUNTER, 0.5f);
		}
		if (stopOnMove.get() && moving()) {
			stop();
			return;
		}
		// When a song ends, the next one follows. A just-started stream can take a moment to show as playing
		if (music.get() && songSound != null && mc.player.tickCount - songTick > 40 && !mc.getSoundManager().isActive(songSound)) playSong();
		else if (!music.get() && songSound != null) stopSong();
		else if (music.get() && songSound == null) playSong();
	}

	/** Starts the next song in order (blue bands, Queen St, VYZEE, round again) and shows it on the Island. */
	private void playSong() {
		stopSong();
		Song[] songs = Song.values();
		Song next = song == null ? songs[0] : songs[(song.ordinal() + 1) % songs.length];
		song = next;
		songSound = new SimpleSoundInstance(next.sound, SoundSource.MASTER, volume.get().floatValue(), 1, SoundInstance.createUnseededRandom(),
			false, 0, SoundInstance.Attenuation.NONE, 0, 0, 0, true);
		mc.getSoundManager().play(songSound);
		songTick = mc.player.tickCount;
		NowPlaying.set(next.title, next.artist, next.length, next.art);
	}

	private void stopSong() {
		if (songSound != null) mc.getSoundManager().stop(songSound);
		songSound = null;
		NowPlaying.clear();
	}

	private boolean moving() {
		return mc.options.keyUp.isDown() || mc.options.keyDown.isDown() || mc.options.keyLeft.isDown()
			|| mc.options.keyRight.isDown() || mc.options.keyJump.isDown();
	}

	@Override
	protected void onDisable() {
		stop();
		stopFakes();
		rain.clear();
	}

	// ---------------------------------------------------------------- the fake players' own emote

	/** What the fake players are doing on their own (while you aren't leading them), when it started, and whether each does a different one. */
	private Emote fakeEmote;
	private int fakeStart;
	private boolean fakeMix;
	/** Cash timing for each emote the fake players are doing. */
	private final java.util.Map<Emote, float[]> fakeOwed = new java.util.EnumMap<>(Emote.class);
	private final java.util.Map<Emote, int[]> fakeBeat = new java.util.EnumMap<>(Emote.class);

	public boolean hasFakes() {
		return !FemboyWare.getModules().get(FakePlayerModule.class).fakeEntities().isEmpty();
	}

	public boolean fakesEmoting() {
		return fakeEmote != null || fakeMix;
	}

	/** Every fake player does {@code emote}, together. The Maybach's just for you, so for that they each flex their own way. */
	public void playFakes(Emote emote) {
		if (mc.player == null) return;
		if (!isEnabled()) setEnabled(true);
		fakeMix = onMaybach(emote);
		fakeEmote = fakeMix ? null : emote;
		fakeStart = mc.player.tickCount;
		fakeOwed.clear();
		fakeBeat.clear();
	}

	/** Every fake player does a different flex. */
	public void playFakesMix() {
		playFakes(Emote.MAYBACH_BOUNCE);
	}

	public void stopFakes() {
		fakeEmote = null;
		fakeMix = false;
	}

	/** For Client Sync: what one of your fake players is doing and how far in, or null if nothing. */
	public Object[] fakeDoing(int entityId) {
		if (!isEnabled() || mc.player == null) return null;
		if (playing != null && fakesCopy.get()) {
			Emote e = onMaybach(playing) ? CREW_MOVES[Math.floorMod(entityId, CREW_MOVES.length)] : playing;
			return new Object[]{e, mc.player.tickCount - startTick};
		}
		if (fakesEmoting()) return new Object[]{fakeEmoteFor(entityId), mc.player.tickCount - fakeStart};
		return null;
	}

	private Emote fakeEmoteFor(int entityId) {
		return fakeMix ? CREW_MOVES[Math.floorMod(entityId, CREW_MOVES.length)] : fakeEmote;
	}

	/** Handles {@code .emote [name|stop]} and {@code .emote fakes <name|mix|stop>}. */
	public void command(String[] args) {
		String names = java.util.Arrays.stream(Emote.values()).map(e -> e.name().toLowerCase(java.util.Locale.ROOT)).collect(java.util.stream.Collectors.joining(", "));
		if (args.length < 2) {
			dev.femboyware.util.ChatUtil.info("Usage: " + dev.femboyware.manager.CommandManager.PREFIX + "emote <emote|stop>, or " + dev.femboyware.manager.CommandManager.PREFIX
				+ "emote fakes <emote|mix|stop>. Emotes: " + names);
			return;
		}
		boolean fakes = args[1].equalsIgnoreCase("fakes") || args[1].equalsIgnoreCase("fake");
		String what = fakes ? (args.length > 2 ? String.join("_", java.util.Arrays.copyOfRange(args, 2, args.length)) : "") : String.join("_", java.util.Arrays.copyOfRange(args, 1, args.length));
		what = what.toLowerCase(java.util.Locale.ROOT).replace(' ', '_');
		if (fakes && !hasFakes()) {
			dev.femboyware.util.ChatUtil.info("No fake players out: spawn some with Fake Player first");
			return;
		}
		if (what.equals("stop") || what.equals("off")) {
			if (fakes) stopFakes();
			else stop();
			dev.femboyware.util.ChatUtil.info(fakes ? "Fake players stopped" : "Stopped");
			return;
		}
		if (fakes && (what.equals("mix") || what.equals("random"))) {
			playFakesMix();
			dev.femboyware.util.ChatUtil.info("Your fake players are each flexing their own way");
			return;
		}
		Emote found = null;
		for (Emote e : Emote.values()) {
			if (e.name().equalsIgnoreCase(what) || e.label.replace(' ', '_').equalsIgnoreCase(what)) found = e;
		}
		if (found == null) {
			dev.femboyware.util.ChatUtil.info("No emote called " + what + ". Emotes: " + names + (fakes ? ", mix" : ""));
			return;
		}
		if (fakes) {
			playFakes(found);
			dev.femboyware.util.ChatUtil.info(onMaybach(found) ? "The Maybach's just for you, so your fake players are each flexing their own way" : "Your fake players are doing " + found.label);
		} else {
			if (!isEnabled()) setEnabled(true);
			play(found);
		}
	}

	/**
	 * How far into the emote {@code state}'s player is, in ticks, or -1 when it isn't emoting: you,
	 * and the fake players copying you. Everyone runs off your clock so they move in step.
	 */
	private float elapsed(AvatarRenderState state) {
		if (mc.player == null) return -1;
		Shared peer = state.id == mc.player.getId() ? null : sync().emoteOf(state.id);
		if (peer != null) {
			float partial = state.ageInTicks - (float) Math.floor(state.ageInTicks);
			return Math.max(0, mc.player.tickCount - peer.startTick + partial);
		}
		if (!isEnabled()) return -1;
		float partial = state.ageInTicks - (float) Math.floor(state.ageInTicks);
		if (state.id == mc.player.getId()) return playing == null ? -1 : Math.max(0, mc.player.tickCount - startTick + partial);
		if (!FemboyWare.getModules().get(FakePlayerModule.class).isFake(state.id)) return -1;
		// Copying you while you emote, or doing their own
		if (playing != null && fakesCopy.get()) return Math.max(0, mc.player.tickCount - startTick + partial);
		if (fakesEmoting()) return Math.max(0, mc.player.tickCount - fakeStart + partial);
		return -1;
	}

	/** Flexes the fake players do while you're sat on the Maybach. */
	private static final Emote[] CREW_MOVES = {Emote.FLEX, Emote.BAND_SPREAD, Emote.WATCH_FLEX, Emote.WATCH_SHINE, Emote.PHONE_FLEX, Emote.MONEY_RAIN};

	/** What this player's doing: your emote, except fake players don't climb on the Maybach with you, they each do their own thing. */
	private Emote emoteFor(AvatarRenderState state) {
		Shared peer = mc.player == null || state.id == mc.player.getId() ? null : sync().emoteOf(state.id);
		if (peer != null) return peer.emote;
		if (mc.player != null && state.id != mc.player.getId() && !(playing != null && fakesCopy.get()) && fakesEmoting()) return fakeEmoteFor(state.id);
		if (onMaybach(playing) && mc.player != null && state.id != mc.player.getId()) return CREW_MOVES[Math.floorMod(state.id, CREW_MOVES.length)];
		return playing;
	}

	/** Read by PlayerModelMixin after vanilla posed the model. */
	public void pose(PlayerModel model, AvatarRenderState state) {
		float t = elapsed(state);
		if (t < 0) return;
		switch (emoteFor(state)) {
			case BAND_SPREAD -> poseBandSpread(model, t);
			case MONEY_RAIN -> poseMoneyRain(model, t);
			case FLEX -> poseFlex(model, t);
			case BITCOIN_RAIN -> poseBitcoinRain(model, t);
			case WATCH_FLEX -> poseWatchFlex(model, t);
			case WATCH_CHECK -> poseWatchCheck(model, t);
			case WATCH_SHINE -> poseWatchShine(model, t);
			case SCOTCH_POUR -> poseScotchPour(model, t);
			case PHONE_FLEX, LEDGER_FLEX -> poseHoldUp(model, t);
			case MAYBACH_BOUNCE -> poseMaybachBounce(model, t);
			case CHAMPAGNE -> poseChampagne(model, t);
			case MONEY_COUNTER -> poseMoneyCounter(model, t);
			case BITCOIN_FLIP -> poseBitcoinFlip(model, t);
			case HODL -> poseHodl(model, t);
			case SUPPORT_DESK -> poseSupportDesk(model, t);
			case MAYBACH_LAPTOP -> poseMaybachLaptop(model, t);
			case LEDGER_PLUG -> poseLedgerPlug(model, t);
		}
	}

	/** When the Ledger goes into the laptop, and when it's through and the coins start popping out. */
	private static final int PLUG_IN = 22, PLUG_DONE = 32;

	/**
	 * The laptop held open on the left forearm, the Ledger in the right hand: it comes across and
	 * plugs into the laptop's side, then you nod along while the coins come out.
	 */
	private static void poseLedgerPlug(PlayerModel model, float t) {
		float in = ease(t / 6f);
		float reach = ease((t - 8) / (PLUG_IN - 8f));
		blend(model.leftArm, in, -1.2f, 0.45f, 0);
		blend(model.rightArm, in, -1.3f + 0.05f * reach, -0.05f - 0.5f * reach, 0);
		float nod = t > PLUG_DONE ? 0.08f * Mth.sin((t - PLUG_DONE) * 0.6f) : 0;
		model.head.xRot = Mth.lerp(in, model.head.xRot, 0.45f + nod);
		model.head.yRot = Mth.lerp(in, model.head.yRot, 0.1f);
	}

	/** Stood at the desk typing: hands on the keyboard pecking away, eyes flicking between the two screens. */
	private static void poseSupportDesk(PlayerModel model, float t) {
		float in = ease(t / 5f);
		blend(model.rightArm, in, -0.78f + typing(t, 0), -0.32f, 0.04f);
		blend(model.leftArm, in, -0.78f + typing(t, 1), 0.32f, -0.04f);
		// Mostly the left screen, a look across to the queue now and then
		float look = Mth.clamp(Mth.sin(t * 0.06f) * 3, -1, 1);
		model.head.xRot = Mth.lerp(in, model.head.xRot, 0.05f);
		model.head.yRot = Mth.lerp(in, model.head.yRot, 0.28f * look);
	}

	/**
	 * One hand's typing at {@code t} ticks: a short press down onto a key every few ticks, the hands
	 * taking turns, in bursts of a few seconds with a pause between to read the screen. The arm's
	 * pitch offset, in radians: 0 resting on the keys, a touch lifted between presses.
	 */
	private static float typing(float t, int hand) {
		float cycle = (t + hand * 3) % 80;
		// Typing for three seconds, then a second to read; eased in and out so the hands settle and lift
		float burst = cycle < 60 ? Mth.clamp(cycle / 6, 0, 1) * Mth.clamp((60 - cycle) / 6, 0, 1) : 0;
		// Presses at about two and a half a second a hand, the left half a beat behind the right
		float phase = (t * 0.125f + hand * 0.5f) % 1;
		float press = phase < 0.3f ? Mth.sin((float) Math.PI * phase / 0.3f) : 0;
		return burst * (-0.025f + 0.04f * press);
	}

	/** Sat on the Maybach's bonnet with the laptop on your lap, typing, riding the bounce. */
	private static void poseMaybachLaptop(PlayerModel model, float t) {
		float in = ease(t / 6f);
		blend(model.rightLeg, in, -1.25f, 0.1f, 0.03f);
		blend(model.leftLeg, in, -1.25f, -0.1f, -0.03f);
		blend(model.rightArm, in, -0.85f + typing(t, 0), -0.3f, 0.04f);
		blend(model.leftArm, in, -0.85f + typing(t, 1), 0.3f, -0.04f);
		model.head.xRot = Mth.lerp(in, model.head.xRot, 0.45f + 0.04f * Mth.sin(t * 0.3f));
		float hips = seatHeight(t / 20f) + 0.12f - 0.75f;
		lift(model, in * hips * 16 / 0.9375f);
	}

	// ---------------------------------------------------------------- money counter, bitcoin flip, HODL

	/** How far in front of you the counting table stands. */
	private static final float COUNTER_REACH = 0.62f;
	/** Ticks per coin flip. */
	private static final int FLIP_TICKS = 24;

	/** Head down over the machine, the right hand feeding bills into the hopper and the left squaring up the stack. */
	private static void poseMoneyCounter(PlayerModel model, float t) {
		float raise = ease(t / 5f);
		float feed = Mth.sin(t * 0.6f);
		blend(model.rightArm, raise, -0.95f + feed * 0.12f, -0.2f, 0.05f);
		blend(model.leftArm, raise, -0.8f - feed * 0.05f, 0.35f, -0.05f);
		model.head.xRot = Mth.lerp(raise, model.head.xRot, 0.55f);
		model.head.yRot = Mth.lerp(raise, model.head.yRot, 0.08f * Mth.sin(t * 0.15f));
	}

	/** Right fist out in front, thumbing a coin up; it dips as it flicks, and the head follows the coin. */
	private static void poseBitcoinFlip(PlayerModel model, float t) {
		float raise = ease(t / 5f);
		float p = (t % FLIP_TICKS) / FLIP_TICKS;
		float flick = p < 0.1f ? Mth.sin(p / 0.1f * Mth.PI) : 0;
		blend(model.rightArm, raise, -1.25f - flick * 0.25f, -0.15f, 0);
		blend(model.leftArm, raise, -0.35f, 0.1f, 0.05f);
		model.head.xRot = Mth.lerp(raise, model.head.xRot, -0.35f * Mth.sin(p * Mth.PI) + 0.1f);
	}

	/** How high over the fist the flipped coin is, in blocks, and how far round it has spun, at {@code t}. */
	private static float[] flip(float t) {
		float p = (t % FLIP_TICKS) / FLIP_TICKS;
		return new float[]{4 * 0.9f * p * (1 - p), p * Mth.TWO_PI * 2.5f};
	}

	/** Both hands up holding a big coin over your head, bouncing it to the beat. */
	private static void poseHodl(PlayerModel model, float t) {
		float raise = ease(t / 6f);
		float bob = Mth.sin(t * 0.5f) * 0.06f;
		blend(model.rightArm, raise, -2.95f + bob, 0, 0.32f);
		blend(model.leftArm, raise, -2.95f + bob, 0, -0.32f);
		model.head.xRot = Mth.lerp(raise, model.head.xRot, -0.35f);
	}

	/** How high the HODL coin is lifted at {@code t}, in model pixels. */
	private static float hodlLift(float t) {
		return ease(t / 6f) * (1 + Mth.sin(t * 0.5f) * 0.6f);
	}

	// ---------------------------------------------------------------- band spread

	private static final int BILLS = 7;
	private static final float BILL_LENGTH = 0.36f, BILL_WIDTH = 0.16f;

	/** Both arms up in front, each fist holding a fan of hundreds, bouncing to a beat. */
	private static void poseBandSpread(PlayerModel model, float t) {
		float raise = ease(t / 5f);
		float beat = Mth.sin(t * 0.55f);
		blend(model.rightArm, raise, -1.3f + beat * 0.1f, 0.32f, 0.08f);
		blend(model.leftArm, raise, -1.3f - beat * 0.1f, -0.32f, -0.08f);
		model.head.xRot += raise * 0.08f * Mth.sin(t * 1.1f);
	}

	// ---------------------------------------------------------------- money rain, flex, bitcoin rain

	/** Ticks per throw in Money Rain, and per bill counted off in Flex. */
	private static final int THROW_TICKS = 10, FLICK_TICKS = 6;

	/** Arms take turns throwing bills up over your head. */
	private static void poseMoneyRain(PlayerModel model, float t) {
		float raise = ease(t / 4f);
		blend(model.rightArm, raise, throwAngle(t / THROW_TICKS), 0.1f, 0.28f);
		blend(model.leftArm, raise, throwAngle(t / THROW_TICKS + 0.5f), -0.1f, -0.28f);
		model.head.xRot = Mth.lerp(raise, model.head.xRot, -0.3f);
	}

	/** One throw: a quick swing up to overhead, then a slower drop back. {@code cycle}'s whole part counts throws. */
	private static float throwAngle(float cycle) {
		float p = cycle - (float) Math.floor(cycle);
		float up = p < 0.3f ? ease(p / 0.3f) : 1 - ease((p - 0.3f) / 0.7f) * 0.9f;
		return -0.7f - 2.2f * up;
	}

	/** A brick of cash held in the left hand, the right hand counting bills off it, head down to watch. */
	private static void poseFlex(PlayerModel model, float t) {
		float raise = ease(t / 5f);
		float p = (t % FLICK_TICKS) / FLICK_TICKS;
		float flick = Mth.sin(p * Mth.PI);
		blend(model.leftArm, raise, -1.0f, 0.45f, -0.05f);
		blend(model.rightArm, raise, -1.2f - 0.3f * flick, -0.55f + 0.15f * flick, 0.05f);
		model.head.xRot = Mth.lerp(raise, model.head.xRot, 0.4f);
		model.head.yRot = Mth.lerp(raise, model.head.yRot, 0.15f);
	}

	/** Both arms up to the sky and head tipped back while the coins come down, bouncing to the beat. */
	private static void poseBitcoinRain(PlayerModel model, float t) {
		float raise = ease(t / 6f);
		float beat = Mth.sin(t * 0.5f);
		blend(model.rightArm, raise, -2.75f + beat * 0.12f, 0, -0.5f);
		blend(model.leftArm, raise, -2.75f - beat * 0.12f, 0, 0.5f);
		model.head.xRot = Mth.lerp(raise, model.head.xRot, -0.6f);
	}

	/** Left arm out to the side with the watch facing forward and turning in the light, right hand pointing at it. */
	private static void poseWatchFlex(PlayerModel model, float t) {
		float raise = ease(t / 6f);
		float twist = 0.28f * Mth.sin(t * 0.22f);
		blend(model.leftArm, raise, 0, Mth.HALF_PI + twist, -Mth.HALF_PI - 0.3f);
		blend(model.rightArm, raise, -1.45f + 0.05f * Mth.sin(t * 0.5f), -0.75f, 0);
		model.head.yRot = Mth.lerp(raise, model.head.yRot, 0.55f);
		model.head.xRot = Mth.lerp(raise, model.head.xRot, 0.1f);
	}

	/** A casual look at the time: wrist up in front of the chest, head down to read it, other hand tugging the sleeve. */
	private static void poseWatchCheck(PlayerModel model, float t) {
		float raise = ease(t / 5f);
		blend(model.leftArm, raise, -1.55f, 0.75f + 0.08f * Mth.sin(t * 0.3f), 0);
		blend(model.rightArm, raise, -1.0f, -0.65f, 0);
		model.head.xRot = Mth.lerp(raise, model.head.xRot, 0.55f);
		model.head.yRot = Mth.lerp(raise, model.head.yRot, 0.35f);
	}

	/** Wrist held up high, turning it so the ice catches the light. */
	private static void poseWatchShine(PlayerModel model, float t) {
		float raise = ease(t / 5f);
		blend(model.leftArm, raise, -2.55f, 0.6f * Mth.sin(t * 0.35f), -0.25f);
		blend(model.rightArm, raise, -0.35f, 0, 0.12f);
		model.head.xRot = Mth.lerp(raise, model.head.xRot, -0.35f);
		model.head.yRot = Mth.lerp(raise, model.head.yRot, 0.3f);
	}

	/** Left arm out in front with the watch face up; the right hand tips a bottle of scotch over it. */
	private static void poseScotchPour(PlayerModel model, float t) {
		float raise = ease(t / 6f);
		// The left forearm across in front, turned so the watch faces up; the right arm raised over
		// it, so the bottle's neck tips down from above onto the watch
		blend(model.leftArm, raise, -1.6f, 0.4f, -1.8f);
		blend(model.rightArm, raise, -2.8f + 0.05f * Mth.sin(t * 0.4f), -0.8f, 0);
		model.head.xRot = Mth.lerp(raise, model.head.xRot, 0.5f);
		model.head.yRot = Mth.lerp(raise, model.head.yRot, 0.1f);
	}

	/** Holding the phone or the Ledger up to show whoever's in front, the other hand pointing at it. */
	private static void poseHoldUp(PlayerModel model, float t) {
		float raise = ease(t / 5f);
		blend(model.rightArm, raise, -1.75f + 0.05f * Mth.sin(t * 0.5f), -0.25f, 0.08f * Mth.sin(t * 0.3f));
		blend(model.leftArm, raise, -1.35f, 0.45f, 0);
		model.head.xRot = Mth.lerp(raise, model.head.xRot, 0.1f);
	}

	/** Where along the show car's bonnet you sit (its own space, + forward), near the front. */
	private static final float HOOD_SEAT = 2.1f;

	/** For CameraMixin: how far up to move your camera, sat on the Maybach's bonnet (0 otherwise). */
	public float cameraLift(float tickProgress) {
		if (!isEnabled() || !onMaybach(playing) || mc.player == null) return 0;
		float t = mc.player.tickCount - startTick + tickProgress;
		return ease(t / 6f) * (seatHeight(t / 20f) + 0.12f - 0.75f);
	}

	/** How high the show car's bonnet is under you at {@code seconds}, riding its hydraulics. */
	private static float seatHeight(float seconds) {
		float pitch = 0.05f * Mth.sin(seconds * 1.3f);
		return 1.155f + dev.femboyware.module.larp.CarsModule.showLift(seconds) + HOOD_SEAT * pitch;
	}

	/**
	 * Sat on the Maybach's bonnet, legs hanging off the front, riding the bounce and throwing bills
	 * up hand over hand like Money Rain.
	 */
	private static void poseMaybachBounce(PlayerModel model, float t) {
		float in = ease(t / 6f);
		blend(model.rightLeg, in, -1.25f, 0.14f, 0.05f);
		blend(model.leftLeg, in, -1.25f, -0.14f, -0.05f);
		blend(model.rightArm, in, throwAngle(t / THROW_TICKS), 0.1f, 0.28f);
		blend(model.leftArm, in, throwAngle(t / THROW_TICKS + 0.5f), -0.1f, -0.28f);
		model.head.xRot = Mth.lerp(in, model.head.xRot, -0.25f + 0.12f * Mth.sin(t * 0.83f));
		// Up from the ground to the bonnet (hips sat on it), in model pixels (the player's drawn at 15/16 size)
		float hips = seatHeight(t / 20f) + 0.12f - 0.75f;
		lift(model, in * hips * 16 / 0.9375f);
	}

	/** Ticks of shaking before the cork goes. */
	public static final int SHAKE_TICKS = 30;

	/** Shaking the bottle hard in front of the chest, then up it goes, spraying side to side. */
	private static void poseChampagne(PlayerModel model, float t) {
		float in = ease(t / 4f);
		if (t < SHAKE_TICKS) {
			blend(model.rightArm, in, -1.0f + 0.35f * Mth.sin(t * 2.4f), -0.5f, 0);
			blend(model.leftArm, in, -0.9f, 0.5f, 0);
			model.head.xRot = Mth.lerp(in, model.head.xRot, 0.3f);
		} else {
			float up = ease((t - SHAKE_TICKS) / 4f), sweep = 0.5f * Mth.sin((t - SHAKE_TICKS) * 0.16f);
			blend(model.rightArm, in, Mth.lerp(up, -1.0f, -2.3f), Mth.lerp(up, -0.5f, sweep), 0);
			blend(model.leftArm, in, -0.35f, 0, -0.35f);
			model.head.xRot = Mth.lerp(in, model.head.xRot, -0.2f);
			model.head.yRot = Mth.lerp(in, model.head.yRot, sweep * 0.6f);
		}
	}

	/** This frame's champagne sprays: the bottle's tip and which way it points, camera-relative. */
	private record Spray(Vector3f tip, Vector3f along, Vec3 owner) {}

	private final List<Spray> sprays = new ArrayList<>();

	/** A droplet of champagne in flight, in world space. */
	private static final class Droplet {
		double x, y, z, vx, vy, vz;
		float age, life, size;
	}

	private final List<Droplet> droplets = new ArrayList<>();
	private final java.util.Random sprayRandom = new java.util.Random();
	private long lastSprayFrame;
	private float sprayOwed;

	/** Spawns droplets from each spraying bottle and moves and draws them all. */
	private void drawChampagne(Render3DEvent event) {
		long now = System.nanoTime();
		float dt = lastSprayFrame == 0 ? 0 : Math.min(0.1f, (now - lastSprayFrame) / 1e9f);
		lastSprayFrame = now;
		Vec3 cam = event.camera().pos;
		sprayOwed += dt * 70;
		int spawn = (int) sprayOwed;
		sprayOwed -= spawn;
		for (Spray spray : sprays) {
			Vec3 tip = cam.add(spray.tip.x, spray.tip.y, spray.tip.z);
			if (tip.distanceToSqr(spray.owner) > 16) continue;
			for (int i = 0; i < spawn && droplets.size() < 900; i++) {
				Droplet d = new Droplet();
				d.x = tip.x;
				d.y = tip.y;
				d.z = tip.z;
				float speed = 5 + sprayRandom.nextFloat() * 3;
				d.vx = spray.along.x * speed + sprayRandom.nextGaussian() * 0.9;
				d.vy = spray.along.y * speed + sprayRandom.nextGaussian() * 0.9;
				d.vz = spray.along.z * speed + sprayRandom.nextGaussian() * 0.9;
				d.life = 0.7f + sprayRandom.nextFloat() * 0.6f;
				d.size = 0.03f + sprayRandom.nextFloat() * 0.05f;
				droplets.add(d);
			}
		}
		sprays.clear();
		if (droplets.isEmpty()) return;
		com.mojang.blaze3d.vertex.VertexConsumer consumer = dev.femboyware.util.FxRender.textured(event, GLOW, true, false);
		for (java.util.Iterator<Droplet> it = droplets.iterator(); it.hasNext(); ) {
			Droplet d = it.next();
			d.age += dt;
			if (d.age > d.life) {
				it.remove();
				continue;
			}
			d.vy -= 7 * dt;
			d.vx *= 1 - 0.8 * dt;
			d.vz *= 1 - 0.8 * dt;
			d.x += d.vx * dt;
			d.y += d.vy * dt;
			d.z += d.vz * dt;
			float fade = 1 - d.age / d.life;
			int color = dev.femboyware.util.FxRender.fade(dev.femboyware.util.FxRender.mix(0xFFFFFFFF, 0xFFFFE7A0, d.age / d.life), fade);
			dev.femboyware.util.FxRender.billboard(event, consumer, (float) (d.x - cam.x), (float) (d.y - cam.y), (float) (d.z - cam.z), d.size * (1 + d.age), 0, color);
		}
	}

	/** Where the bounce emote parked its car, and which way, fixed when it starts. */
	private Vec3 showCarAt;
	private float showCarYaw;

	/** Moves the whole body up by {@code pixels}, for hops; the feet leave the ground rather than sink into it. */
	private static void lift(PlayerModel model, float pixels) {
		for (ModelPart part : new ModelPart[]{model.head, model.body, model.rightArm, model.leftArm, model.rightLeg, model.leftLeg}) part.y -= pixels;
	}

	// ---------------------------------------------------------------- pour stream and sparkles

	/** Where this frame's scotch stream runs (camera-relative, from the bottle's neck to the watch), and watch sparkles. */
	private record Pour(Vector3f from, Vector3f to, Vec3 owner, float age) {}

	private record Sparkle(Vector3f at, Vec3 owner, float age) {}

	private final List<Pour> pours = new ArrayList<>();
	private final List<Sparkle> sparkles = new ArrayList<>();
	private static final Identifier GLOW = Identifier.fromNamespaceAndPath("femboyware", "textures/fx/glow.png");

	/** Wine: white like the champagne spray, the splashes pure white. */
	private static final int WINE = 0xF0FFF4D6, WINE_LIGHT = 0xFFFFFFFF;
	/** Splashes and drips from the pour, in world space, falling under gravity. */
	private final List<Droplet> wineDrops = new ArrayList<>();
	private final java.util.Random wineRandom = new java.util.Random();
	private long lastPourFrame;
	private float pourOwed;

	/**
	 * The wine stream: from the bottle's neck it arcs out and falls under gravity, thick at the top
	 * and breaking up into drops near the bottom. Where it lands wanders as the bottle moves (now on
	 * the watch, now on the arm, now just past it), and it splashes there, with drops running off
	 * and falling to the ground and the odd stray drop flung off the stream.
	 */
	private void drawPour(Render3DEvent event, Vec3 cam, Pour pour) {
		com.mojang.blaze3d.vertex.VertexConsumer c = dev.femboyware.util.FxRender.textured(event, GLOW, false, false);
		float age = pour.age;
		// Where it lands, wandering round the watch: a slow drift plus a quicker wobble
		float sx = 0.13f * Mth.sin(age * 0.11f) + 0.05f * Mth.sin(age * 0.47f + 1.3f);
		float sz = 0.11f * Mth.sin(age * 0.083f + 2.1f) + 0.045f * Mth.sin(age * 0.39f);
		// Every so often the bottle tips further and it overshoots past the arm
		float overshoot = Math.max(0, Mth.sin(age * 0.05f) - 0.7f) * 1.2f;
		Vector3f land = new Vector3f(pour.to.x + sx, pour.to.y, pour.to.z + sz);
		Vector3f out = new Vector3f(land).sub(pour.from);
		out.y = 0;
		if (out.lengthSquared() > 1e-6f) land.add(new Vector3f(out).normalize().mul(overshoot));
		if (overshoot > 0) land.y -= overshoot * 1.6f;
		// The stream's curve: out from the neck first, then dropping, like it's falling
		Vector3f start = pour.from, mid = new Vector3f(Mth.lerp(0.55f, start.x, land.x), start.y + 0.02f, Mth.lerp(0.55f, start.z, land.z));
		int blobs = 22;
		for (int i = 0; i <= blobs; i++) {
			float t = i / (float) blobs;
			// Past two thirds of the way down it starts breaking into separate drops
			if (t > 0.66f && ((i + (int) (age * 0.9f)) % 3 == 0)) continue;
			float a = (1 - t) * (1 - t), b2 = 2 * (1 - t) * t, d = t * t;
			float x = a * start.x + b2 * mid.x + d * land.x, y = a * start.y + b2 * mid.y + d * land.y, z = a * start.z + b2 * mid.z + d * land.z;
			float jitter = 0.006f * t * Mth.sin(age * 3.1f + i * 1.7f);
			float size = Mth.lerp(t, 0.032f, 0.02f);
			dev.femboyware.util.FxRender.billboard(event, c, x + jitter, y, z - jitter, size, 0, WINE);
		}

		// Splashes where it lands, drips running off, and the odd stray drop off the stream
		long now = System.nanoTime();
		float dt = lastPourFrame == 0 ? 0 : Math.min(0.1f, (now - lastPourFrame) / 1e9f);
		lastPourFrame = now;
		pourOwed += dt * 45;
		Vec3 landWorld = cam.add(land.x, land.y, land.z);
		while (pourOwed >= 1 && wineDrops.size() < 400) {
			pourOwed--;
			Droplet drop = new Droplet();
			int kind = wineRandom.nextInt(10);
			if (kind < 6) {
				// A splash, out and up from where it hits
				double angle = wineRandom.nextDouble() * Math.PI * 2, speed = 0.6 + wineRandom.nextDouble() * 1.1;
				drop.x = landWorld.x;
				drop.y = landWorld.y + 0.02;
				drop.z = landWorld.z;
				drop.vx = Math.cos(angle) * speed;
				drop.vz = Math.sin(angle) * speed;
				drop.vy = 0.5 + wineRandom.nextDouble() * 1.0;
				drop.life = 0.35f + wineRandom.nextFloat() * 0.3f;
				drop.size = 0.012f + wineRandom.nextFloat() * 0.014f;
			} else if (kind < 9) {
				// A drip running off and falling to the ground
				drop.x = landWorld.x + (wineRandom.nextDouble() - 0.5) * 0.18;
				drop.y = landWorld.y - 0.08;
				drop.z = landWorld.z + (wineRandom.nextDouble() - 0.5) * 0.18;
				drop.vy = -0.4 - wineRandom.nextDouble() * 0.6;
				drop.life = 1.4f;
				drop.size = 0.016f + wineRandom.nextFloat() * 0.01f;
			} else {
				// A stray drop flung off the side of the stream
				float t = 0.3f + wineRandom.nextFloat() * 0.5f;
				float a = (1 - t) * (1 - t), b2 = 2 * (1 - t) * t, d = t * t;
				drop.x = cam.x + a * start.x + b2 * mid.x + d * land.x;
				drop.y = cam.y + a * start.y + b2 * mid.y + d * land.y;
				drop.z = cam.z + a * start.z + b2 * mid.z + d * land.z;
				drop.vx = (wineRandom.nextDouble() - 0.5) * 1.2;
				drop.vz = (wineRandom.nextDouble() - 0.5) * 1.2;
				drop.vy = -0.2;
				drop.life = 0.9f;
				drop.size = 0.012f;
			}
			wineDrops.add(drop);
		}
		double floor = pour.owner.y;
		for (java.util.Iterator<Droplet> it = wineDrops.iterator(); it.hasNext(); ) {
			Droplet d = it.next();
			d.age += dt;
			d.vy -= 9 * dt;
			d.x += d.vx * dt;
			d.y += d.vy * dt;
			d.z += d.vz * dt;
			if (d.age > d.life || d.y < floor) {
				it.remove();
				continue;
			}
			float fade = 1 - d.age / d.life;
			dev.femboyware.util.FxRender.billboard(event, c, (float) (d.x - cam.x), (float) (d.y - cam.y), (float) (d.z - cam.z), d.size,
				0, dev.femboyware.util.FxRender.fade(dev.femboyware.util.FxRender.mix(WINE_LIGHT, WINE, d.age / d.life), 0.4f + 0.6f * fade));
		}
		// A wet sheen where it's hitting
		dev.femboyware.util.FxRender.billboard(event, c, land.x, land.y + 0.01f, land.z, 0.07f, age * 0.2f, 0x70F7D57A);
	}

	private void drawPoursAndSparkles(Render3DEvent event) {
		Vec3 cam = event.camera().pos;
		if (pours.isEmpty() && !wineDrops.isEmpty()) {
			wineDrops.clear();
			lastPourFrame = 0;
		}
		if (!pours.isEmpty() || !sparkles.isEmpty()) {
			com.mojang.blaze3d.vertex.VertexConsumer consumer = dev.femboyware.util.FxRender.textured(event, GLOW, true, false);
			for (Pour pour : pours) {
				if (cam.add(pour.from.x, pour.from.y, pour.from.z).distanceToSqr(pour.owner) > 16) continue;
				drawPour(event, cam, pour);
			}
			for (Sparkle sparkle : sparkles) {
				if (cam.add(sparkle.at.x, sparkle.at.y, sparkle.at.z).distanceToSqr(sparkle.owner) > 16) continue;
				for (int i = 0; i < 5; i++) {
					float twinkle = Math.max(0, Mth.sin(sparkle.age * 0.6f + i * 1.7f));
					if (twinkle < 0.2f) continue;
					float ox = 0.18f * Mth.sin(i * 2.4f + sparkle.age * 0.05f), oy = 0.14f * Mth.cos(i * 1.7f), oz = 0.18f * Mth.cos(i * 2.4f);
					dev.femboyware.util.FxRender.billboard(event, consumer, sparkle.at.x + ox, sparkle.at.y + oy, sparkle.at.z + oz, 0.07f * twinkle, i, dev.femboyware.util.FxRender.fade(0xFFFFFFFF, twinkle));
				}
			}
		}
		pours.clear();
		sparkles.clear();
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		drawShowCars(event);
	}

	/** See-through effects go in the late pass, after solid things are drawn, so the jet and the hangar don't paint over them. */
	@Override
	public void onRender3DLate(Render3DEvent event) {
		renderEffects(event);
	}

	/** The Maybach for the bounce emote, yours and other people's: solid, so drawn with the other solid things. */
	public void drawShowCars(Render3DEvent event) {
		if (mc.player == null || mc.level == null) return;
		float tickDelta = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		boolean own = isEnabled() && playing != null;
		if (own && onMaybach(playing) && showCarAt != null) {
			float seconds = (mc.player.tickCount - startTick + tickDelta) / 20f;
			FemboyWare.getModules().get(dev.femboyware.module.larp.CarsModule.class).drawShowCar(event, showCarAt, showCarYaw, seconds);
		}
		if (own && playing == Emote.MONEY_COUNTER && showCarAt != null) {
			dev.femboyware.module.larp.Cash.drawCounterScene(event, showCarAt, showCarYaw, (mc.player.tickCount - startTick + tickDelta) / 20f);
		}
		if (own && playing == Emote.SUPPORT_DESK && showCarAt != null) {
			dev.femboyware.module.larp.SupportDesk.draw(event, showCarAt, showCarYaw, (mc.player.tickCount - startTick + tickDelta) / 20f);
		}
		java.util.Map<Integer, Shared> peers = sync().sharedEmotes();
		for (Shared peer : peers.values()) {
			if (peer.emote == Emote.MONEY_COUNTER && peer.showCar != null) {
				dev.femboyware.module.larp.Cash.drawCounterScene(event, peer.showCar, peer.showCarYaw, (mc.player.tickCount - peer.startTick + tickDelta) / 20f);
				continue;
			}
			if (peer.emote == Emote.SUPPORT_DESK && peer.showCar != null) {
				dev.femboyware.module.larp.SupportDesk.draw(event, peer.showCar, peer.showCarYaw, (mc.player.tickCount - peer.startTick + tickDelta) / 20f);
				continue;
			}
			if (!onMaybach(peer.emote) || peer.showCar == null) continue;
			float seconds = (mc.player.tickCount - peer.startTick + tickDelta) / 20f;
			FemboyWare.getModules().get(dev.femboyware.module.larp.CarsModule.class).drawShowCar(event, peer.showCar, peer.showCarYaw, seconds);
		}
	}

	/**
	 * The see-through effects that aren't part of the player model: wine, champagne and the falling
	 * cash and coins, yours and other people's. Client Sync calls this (and {@link #drawShowCars})
	 * while this module is off, so other people's emotes still show.
	 */
	public void renderEffects(Render3DEvent event) {
		drawPoursAndSparkles(event);
		drawChampagne(event);
		if (mc.player == null || mc.level == null) return;
		boolean own = isEnabled() && playing != null;
		java.util.Map<Integer, Shared> peers = sync().sharedEmotes();
		long now = System.nanoTime();
		float dt = lastRainFrame == 0 ? 0 : Math.min(0.1f, (now - lastRainFrame) / 1e9f);
		lastRainFrame = now;
		if (own) {
			float[] owed = {rainOwed};
			int[] beat = {lastBeat};
			spawnRain(playing, mc.player.tickCount - startTick, emoters(), mc.player, owed, beat, dt);
			rainOwed = owed[0];
			lastBeat = beat[0];
		}
		// The fake players doing their own, grouped by what each is doing
		if (isEnabled() && fakesEmoting() && !(own && fakesCopy.get())) {
			java.util.Map<Emote, List<Player>> groups = new java.util.EnumMap<>(Emote.class);
			for (Player fake : FemboyWare.getModules().get(FakePlayerModule.class).fakeEntities()) {
				groups.computeIfAbsent(fakeEmoteFor(fake.getId()), e -> new ArrayList<>()).add(fake);
			}
			groups.forEach((emote, group) -> spawnRain(emote, mc.player.tickCount - fakeStart, group, null,
				fakeOwed.computeIfAbsent(emote, e -> new float[1]), fakeBeat.computeIfAbsent(emote, e -> new int[]{-1}), dt));
		}
		peers.forEach((id, peer) -> {
			if (!(mc.level.getEntity(id) instanceof Player player)) return;
			float[] owed = {peer.rainOwed};
			int[] beat = {peer.lastBeat};
			spawnRain(peer.emote, mc.player.tickCount - peer.startTick, List.of(player), player, owed, beat, dt);
			peer.rainOwed = owed[0];
			peer.lastBeat = beat[0];
		});
		rain.render(event, BILL);
	}

	/** Who's emoting: you, and the fake players copying you. */
	private List<Player> emoters() {
		List<Player> emoters = new ArrayList<>();
		emoters.add(mc.player);
		if (fakesCopy.get()) emoters.addAll(FemboyWare.getModules().get(FakePlayerModule.class).fakeEntities());
		return emoters;
	}

	/**
	 * Cash and coins for one emote: {@code emoters} doing {@code emote} {@code ticks} in, with
	 * {@code seated} the one up on the Maybach. {@code owed} and {@code beat} carry over between frames.
	 */
	private void spawnRain(Emote emote, int ticks, List<? extends Player> emoters, Player seated, float[] owed, int[] beatState, float dt) {
		float tickDelta = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		float rainOwed = owed[0];
		int lastBeat = beatState[0];
		// Fewer each with more people, so a crowd doesn't bury the screen
		float perEmoter = 1f / (float) Math.sqrt(emoters.size());
		switch (emote) {
			case MONEY_RAIN, MAYBACH_BOUNCE -> {
				rainOwed += dt * 7 * perEmoter;
				// On the Maybach you're up on its bonnet, so the bills come from up there
				double seat = emote == Emote.MAYBACH_BOUNCE ? seatHeight((ticks + tickDelta) / 20f) - 0.75 + 0.12 : 0;
				int beat = ticks / (THROW_TICKS / 2);
				boolean threw = beat != lastBeat && ticks > 4;
				lastBeat = beat;
				for (Player player : emoters) {
					// Only you are up on the Maybach; the crew dances on the ground round it
					Vec3 ground = player.getPosition(tickDelta), feet = ground.add(0, player == seated ? seat : 0, 0);
					for (int i = 0; i < (int) rainOwed; i++) rain.fall(CashRain.Kind.BILL, ground, 2.4, 3.2 + (player == seated ? seat : 0));
					if (threw) {
						// Alternate hands, a little fistful of bills tossed up out of each
						int side = beat % 2 == 0 ? 1 : -1;
						Vec3 hand = feet.add(right(player, tickDelta).scale(0.35 * side)).add(0, 2.1, 0);
						for (int i = 0; i < 3; i++) {
							Vec3 velocity = new Vec3((Math.random() - 0.5) * 1.6, 2.2 + Math.random() * 1.2, (Math.random() - 0.5) * 1.6);
							rain.throwFrom(CashRain.Kind.BILL, hand, velocity, ground.y);
						}
					}
				}
			}
			case LEDGER_PLUG -> {
				int beat = ticks / 7;
				boolean popped = beat != lastBeat && ticks >= PLUG_DONE;
				lastBeat = beat;
				if (popped) {
					for (Player player : emoters) {
						Vec3 feet = player.getPosition(tickDelta);
						Vec3 forward = forward(player, tickDelta);
						Vec3 screen = feet.add(forward.scale(0.55)).add(right(player, tickDelta).scale(-0.12)).add(0, 1.45, 0);
						for (int i = 0; i < 3; i++) {
							Vec3 velocity = forward.scale(0.3).add((Math.random() - 0.5) * 1.0, 2.0 + Math.random() * 1.0, (Math.random() - 0.5) * 1.0);
							rain.throwFrom(CashRain.Kind.COIN, screen, velocity, feet.y);
						}
					}
				}
			}
			case BITCOIN_RAIN -> {
				rainOwed += dt * 14 * perEmoter;
				for (Player player : emoters) {
					Vec3 feet = player.getPosition(tickDelta);
					for (int i = 0; i < (int) rainOwed; i++) rain.fall(CashRain.Kind.COIN, feet, 2.6, 4.5);
				}
			}
			case FLEX -> {
				int beat = ticks / FLICK_TICKS;
				boolean flicked = beat != lastBeat && ticks > 6;
				lastBeat = beat;
				if (flicked) {
					for (Player player : emoters) {
						// A bill flicked off the top of the stack, out in front
						Vec3 feet = player.getPosition(tickDelta);
						Vec3 forward = forward(player, tickDelta);
						Vec3 stack = feet.add(0, 1.3, 0).add(forward.scale(0.45)).add(right(player, tickDelta).scale(-0.1));
						Vec3 velocity = forward.scale(1.0 + Math.random() * 0.6).add((Math.random() - 0.5) * 0.8, 1.4 + Math.random() * 0.6, (Math.random() - 0.5) * 0.8);
						rain.throwFrom(CashRain.Kind.BILL, stack, velocity, feet.y);
					}
				}
			}
			default -> {
			}
		}
		rainOwed -= (int) rainOwed;
		owed[0] = rainOwed;
		beatState[0] = lastBeat;
	}

	private static Vec3 forward(Player player, float tickDelta) {
		double yaw = Math.toRadians(Mth.lerp(tickDelta, player.yBodyRotO, player.yBodyRot));
		return new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
	}

	/** Toward the player's right hand. */
	private static Vec3 right(Player player, float tickDelta) {
		double yaw = Math.toRadians(Mth.lerp(tickDelta, player.yBodyRotO, player.yBodyRot));
		return new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
	}

	/** How wide the fans are open, in radians: they flick open after the arms come up, then shake. */
	private static float spread(float t) {
		return ease((t - 3) / 7f) * 1.9f + 0.08f * Mth.sin(t * 0.9f);
	}

	private static void blend(ModelPart part, float amount, float xRot, float yRot, float zRot) {
		part.xRot = Mth.lerp(amount, part.xRot, xRot);
		part.yRot = Mth.lerp(amount, part.yRot, yRot);
		part.zRot = Mth.lerp(amount, part.zRot, zRot);
	}

	private static float ease(float t) {
		t = Mth.clamp(t, 0, 1);
		return 1 - (1 - t) * (1 - t) * (1 - t);
	}

	private static RenderType cutout(Identifier texture) {
		//? if >=26.1 {
		/*return RenderTypes.entityCutout(texture);
		*///?} else {
		return RenderTypes.entityCutoutNoCull(texture);
		//?}
	}

	private class Feature extends RenderLayer<AvatarRenderState, PlayerModel> {
		Feature(RenderLayerParent<AvatarRenderState, PlayerModel> context) {
			super(context);
		}

		@Override
		public void submit(PoseStack matrices, SubmitNodeCollector queue, int light, AvatarRenderState state, float headYaw, float headPitch) {
			float t = elapsed(state);
			if (t < 0) return;
			switch (emoteFor(state)) {
				case BAND_SPREAD -> {
					float spread = spread(t);
					fan(matrices, queue, getParentModel().rightArm, -1, BILLS, spread, light);
					fan(matrices, queue, getParentModel().leftArm, 1, BILLS, spread, light);
				}
				case MONEY_RAIN, MAYBACH_BOUNCE -> {
					// A small fistful in each hand to throw
					fan(matrices, queue, getParentModel().rightArm, -1, 3, 0.7f, light);
					fan(matrices, queue, getParentModel().leftArm, 1, 3, 0.7f, light);
				}
				case WATCH_FLEX, WATCH_CHECK, WATCH_SHINE, SCOTCH_POUR -> {
					// Wear one for the flex if Cosmetics isn't already putting one on
					if (!FemboyWare.getModules().get(CosmeticsModule.class).showsWatch(state)) {
						CosmeticsModule.submitWatch(matrices, queue, getParentModel().leftArm, CosmeticsModule.Watch.ICED, light, state.ageInTicks);
					}
					Vec3 owner = new Vec3(state.x, state.y, state.z);
					Vector3f watchAt = at(matrices, getParentModel().leftArm, 0.235f, 0.515f, 0);
					if (emoteFor(state) != Emote.SCOTCH_POUR) sparkles.add(new Sparkle(watchAt, owner, state.ageInTicks));
					else {
						matrices.pushPose();
						getParentModel().rightArm.translateAndRotate(matrices);
						queue.submitCustomGeometry(matrices, cutout(BOTTLE), (pose, c) -> drawBottle(pose, c, light));
						matrices.popPose();
						if (t > 6) pours.add(new Pour(at(matrices, getParentModel().rightArm, 0, 0.63f, 0.62f), watchAt, owner, state.ageInTicks));
					}
				}
				case CHAMPAGNE -> {
					matrices.pushPose();
					getParentModel().rightArm.translateAndRotate(matrices);
					queue.submitCustomGeometry(matrices, cutout(CHAMPAGNE_BOTTLE), (pose, c) -> drawChampagneBottle(pose, c, light));
					matrices.popPose();
					if (t > SHAKE_TICKS + 2) {
						Vector3f tip = at(matrices, getParentModel().rightArm, 0, 1.03f, 0);
						Vector3f base = at(matrices, getParentModel().rightArm, 0, 0.6f, 0);
						sprays.add(new Spray(tip, new Vector3f(tip).sub(base).normalize(), new Vec3(state.x, state.y, state.z)));
					}
				}
				case PHONE_FLEX, LEDGER_FLEX -> {
					matrices.pushPose();
					getParentModel().rightArm.translateAndRotate(matrices);
					boolean phone = emoteFor(state) == Emote.PHONE_FLEX;
					queue.submitCustomGeometry(matrices, cutout(phone ? PHONE_SCREEN : LEDGER_SCREEN), (pose, c) -> drawHeldDevice(pose, c, phone, true));
					queue.submitCustomGeometry(matrices, cutout(DEVICE_BODY), (pose, c) -> drawHeldDevice(pose, c, phone, false));
					matrices.popPose();
				}
				case MONEY_COUNTER -> fan(matrices, queue, getParentModel().rightArm, -1, 3, 0.35f, light);
				case MAYBACH_LAPTOP -> {
					matrices.pushPose();
					// On the thighs: in the right leg's space, across to the middle between the legs, on top of the leg
					getParentModel().rightLeg.translateAndRotate(matrices);
					matrices.translate(0.119f, 0.1f, -0.125f);
					laptop(matrices, queue, light, 0.46f);
					matrices.popPose();
				}
				case LEDGER_PLUG -> {
					// The laptop open on the left forearm, reaching across in front of you
					matrices.pushPose();
					getParentModel().leftArm.translateAndRotate(matrices);
					matrices.translate(-0.2f, 0.28f, -0.125f);
					laptop(matrices, queue, light, 0.36f);
					matrices.popPose();
					// The Ledger in the right hand
					matrices.pushPose();
					getParentModel().rightArm.translateAndRotate(matrices);
					queue.submitCustomGeometry(matrices, cutout(LEDGER_SCREEN), (pose, c) -> drawHeldDevice(pose, c, false, true));
					queue.submitCustomGeometry(matrices, cutout(DEVICE_BODY), (pose, c) -> drawHeldDevice(pose, c, false, false));
					matrices.popPose();
				}
				case BITCOIN_FLIP -> {
					float[] f = flip(t);
					matrices.pushPose();
					getParentModel().rightArm.translateAndRotate(matrices);
					// Arm held out in front: -z is up, so the coin rises off the fist, stood on its edge facing forward and spinning
					matrices.translate(-0.0625f, 0.66f, -0.2f - f[0]);
					matrices.mulPose(Axis.ZP.rotation(f[1]));
					matrices.mulPose(Axis.XP.rotation(Mth.HALF_PI));
					queue.submitCustomGeometry(matrices, cutout(BTC), (pose, c) -> drawCoin(pose, c, 0.14f, light));
					matrices.popPose();
				}
				case HODL -> {
					// Model space: the hands meet over the head, the coin stood up between them, facing forward
					matrices.pushPose();
					matrices.translate(0, (-13.5f - hodlLift(t)) / 16f, 0);
					queue.submitCustomGeometry(matrices, cutout(BTC), (pose, c) -> drawCoin(pose, c, 0.34f, light));
					matrices.popPose();
				}
				case FLEX -> {
					matrices.pushPose();
					getParentModel().leftArm.translateAndRotate(matrices);
					queue.submitCustomGeometry(matrices, cutout(BILL), (pose, consumer) -> drawStack(pose, consumer, true, light));
					queue.submitCustomGeometry(matrices, cutout(CASH_SIDE), (pose, consumer) -> drawStack(pose, consumer, false, light));
					matrices.popPose();
				}
				default -> {
				}
			}
		}

		/** A laptop with its base {@code length} long from here along +y, lying on -z, and the lid open at the far end. */
		private void laptop(PoseStack matrices, SubmitNodeCollector queue, int light, float length) {
			queue.submitCustomGeometry(matrices, cutout(LAPTOP_BODY), (pose, c) -> laptopBox(pose, c, light, -0.19f, 0, -0.025f, 0.19f, length, 0));
			matrices.pushPose();
			matrices.translate(0, length, -0.025f);
			matrices.mulPose(Axis.XP.rotation(0.25f));
			queue.submitCustomGeometry(matrices, cutout(LAPTOP_BODY), (pose, c) -> laptopBox(pose, c, light, -0.19f, 0, -0.3f, 0.19f, 0.016f, 0));
			queue.submitCustomGeometry(matrices, cutout(LAPTOP_SCREEN), (pose, c) -> laptopScreen(pose, c));
			matrices.popPose();
		}

		/** A point in a limb's space, moved into camera-relative world space. */
		private Vector3f at(PoseStack matrices, ModelPart limb, float x, float y, float z) {
			matrices.pushPose();
			limb.translateAndRotate(matrices);
			Vector3f point = matrices.last().pose().transformPosition(new Vector3f(x, y, z));
			matrices.popPose();
			return point;
		}

		private void fan(PoseStack matrices, SubmitNodeCollector queue, ModelPart arm, int side, int bills, float spread, int light) {
			matrices.pushPose();
			arm.translateAndRotate(matrices);
			queue.submitCustomGeometry(matrices, cutout(BILL), (pose, consumer) -> drawFan(pose, consumer, side, bills, spread, light));
			matrices.popPose();
		}
	}

	/**
	 * A fan of bills pinched in the fist. Arm space: the arm runs down +y to the fist at about 0.6, so
	 * with the arm held out in front, -z points up and the fan opens across x, facing whoever's ahead.
	 */
	private static void drawFan(PoseStack.Pose pose, VertexConsumer c, int side, int bills, float spread, int light) {
		float fistX = side * 0.0625f, fistY = 0.6f, fistZ = -0.02f;
		for (int i = 0; i < bills; i++) {
			float angle = side * (-spread / 2 + spread * i / (bills - 1));
			Vector3f along = new Vector3f(Mth.sin(angle), 0, -Mth.cos(angle));
			Vector3f across = new Vector3f(Mth.cos(angle), 0, Mth.sin(angle)).mul(BILL_WIDTH / 2);
			// Stacked a hair apart down the arm so the bills don't flicker through each other
			Vector3f base = new Vector3f(fistX, fistY + i * 0.004f, fistZ).add(new Vector3f(along).mul(-0.04f));
			Vector3f tip = new Vector3f(base).add(new Vector3f(along).mul(BILL_LENGTH));
			Vector3f normal = new Vector3f(along).cross(across).normalize();
			billVertex(c, pose, new Vector3f(base).sub(across), 0, 0, light, normal);
			billVertex(c, pose, new Vector3f(base).add(across), 0, 1, light, normal);
			billVertex(c, pose, new Vector3f(tip).add(across), 1, 1, light, normal);
			billVertex(c, pose, new Vector3f(tip).sub(across), 1, 0, light, normal);
		}
	}

	/**
	 * A brick of hundreds on the back of the left hand: bill faces on top and bottom ({@code faces}),
	 * striped paper edges with a band round the sides otherwise. Arm space, like the fans.
	 */
	private static void drawStack(PoseStack.Pose pose, VertexConsumer c, boolean faces, int light) {
		float cx = 0.0625f, x1 = cx - 0.17f, x2 = cx + 0.17f, y1 = 0.5f, y2 = 0.66f, z1 = -0.215f, z2 = -0.125f;
		if (faces) {
			face(c, pose, light, new Vector3f(x1, y1, z1), new Vector3f(x2, y1, z1), new Vector3f(x2, y2, z1), new Vector3f(x1, y2, z1), new Vector3f(0, 0, -1));
			face(c, pose, light, new Vector3f(x1, y2, z2), new Vector3f(x2, y2, z2), new Vector3f(x2, y1, z2), new Vector3f(x1, y1, z2), new Vector3f(0, 0, 1));
			return;
		}
		face(c, pose, light, new Vector3f(x1, y1, z2), new Vector3f(x2, y1, z2), new Vector3f(x2, y1, z1), new Vector3f(x1, y1, z1), new Vector3f(0, -1, 0));
		face(c, pose, light, new Vector3f(x1, y2, z1), new Vector3f(x2, y2, z1), new Vector3f(x2, y2, z2), new Vector3f(x1, y2, z2), new Vector3f(0, 1, 0));
		face(c, pose, light, new Vector3f(x1, y1, z2), new Vector3f(x1, y1, z1), new Vector3f(x1, y2, z1), new Vector3f(x1, y2, z2), new Vector3f(-1, 0, 0));
		face(c, pose, light, new Vector3f(x2, y1, z1), new Vector3f(x2, y1, z2), new Vector3f(x2, y2, z2), new Vector3f(x2, y2, z1), new Vector3f(1, 0, 0));
	}

	private static final Identifier LAPTOP_BODY = Identifier.fromNamespaceAndPath("femboyware", "textures/larp/laptop_body.png");
	private static final Identifier LAPTOP_SCREEN = Identifier.fromNamespaceAndPath("femboyware", "textures/larp/laptop_screen.png");

	/** A box with the texture on each face. */
	private static void laptopBox(PoseStack.Pose pose, VertexConsumer c, int light, float x1, float y1, float z1, float x2, float y2, float z2) {
		face(c, pose, light, new Vector3f(x1, y1, z1), new Vector3f(x2, y1, z1), new Vector3f(x2, y2, z1), new Vector3f(x1, y2, z1), new Vector3f(0, 0, -1));
		face(c, pose, light, new Vector3f(x1, y2, z2), new Vector3f(x2, y2, z2), new Vector3f(x2, y1, z2), new Vector3f(x1, y1, z2), new Vector3f(0, 0, 1));
		face(c, pose, light, new Vector3f(x1, y1, z2), new Vector3f(x2, y1, z2), new Vector3f(x2, y1, z1), new Vector3f(x1, y1, z1), new Vector3f(0, -1, 0));
		face(c, pose, light, new Vector3f(x1, y2, z1), new Vector3f(x2, y2, z1), new Vector3f(x2, y2, z2), new Vector3f(x1, y2, z2), new Vector3f(0, 1, 0));
		face(c, pose, light, new Vector3f(x1, y1, z2), new Vector3f(x1, y1, z1), new Vector3f(x1, y2, z1), new Vector3f(x1, y2, z2), new Vector3f(-1, 0, 0));
		face(c, pose, light, new Vector3f(x2, y1, z1), new Vector3f(x2, y1, z2), new Vector3f(x2, y2, z2), new Vector3f(x2, y2, z1), new Vector3f(1, 0, 0));
	}

	/** The lid's screen, facing you (toward -y in the lid's space), glowing at full brightness, drawn both ways round. */
	private static void laptopScreen(PoseStack.Pose pose, VertexConsumer c) {
		int light = 0xF000F0;
		float x1 = -0.175f, x2 = 0.175f, top = -0.287f, bottom = -0.012f, y = -0.002f;
		face(c, pose, light, new Vector3f(x2, y, top), new Vector3f(x1, y, top), new Vector3f(x1, y, bottom), new Vector3f(x2, y, bottom), new Vector3f(0, -1, 0));
		face(c, pose, light, new Vector3f(x1, y, top), new Vector3f(x2, y, top), new Vector3f(x2, y, bottom), new Vector3f(x1, y, bottom), new Vector3f(0, -1, 0));
	}

	/** A coin of radius {@code r} standing in the xy plane, the texture on both faces with a gold rim between. */
	private static void drawCoin(PoseStack.Pose pose, VertexConsumer c, float r, int light) {
		float d = r * 0.09f;
		face(c, pose, light, new Vector3f(-r, -r, -d), new Vector3f(r, -r, -d), new Vector3f(r, r, -d), new Vector3f(-r, r, -d), new Vector3f(0, 0, -1));
		face(c, pose, light, new Vector3f(r, -r, d), new Vector3f(-r, -r, d), new Vector3f(-r, r, d), new Vector3f(r, r, d), new Vector3f(0, 0, 1));
	}

	/** A quad with the texture's top-left at {@code a}, its top edge running from a to b. */
	private static void face(VertexConsumer c, PoseStack.Pose pose, int light, Vector3f a, Vector3f b, Vector3f cc, Vector3f d, Vector3f n) {
		billVertex(c, pose, a, 0, 0, light, n);
		billVertex(c, pose, b, 1, 0, light, n);
		billVertex(c, pose, cc, 1, 1, light, n);
		billVertex(c, pose, d, 0, 1, light, n);
	}

	private static final Identifier BOTTLE = Identifier.fromNamespaceAndPath("femboyware", "textures/larp/bottle.png");
	private static final Identifier CHAMPAGNE_BOTTLE = Identifier.fromNamespaceAndPath("femboyware", "textures/larp/champagne.png");

	/** A champagne bottle gripped by its body, the neck pointing on out along the arm, where it'll spray. */
	private static void drawChampagneBottle(PoseStack.Pose pose, VertexConsumer c, int light) {
		float[] glass = {0, 0, 0.5f, 1}, label = {0.5f, 0.3f, 1, 0.65f}, foil = {0.5f, 0.03f, 1, 0.2f};
		prop(c, pose, light, -0.075f, 0.5f, -0.075f, 0.075f, 0.83f, 0.075f, glass);
		prop(c, pose, light, -0.077f, 0.6f, -0.077f, 0.077f, 0.72f, 0.077f, label);
		prop(c, pose, light, -0.05f, 0.83f, -0.05f, 0.05f, 0.88f, 0.05f, glass);
		prop(c, pose, light, -0.032f, 0.88f, -0.032f, 0.032f, 1.0f, 0.032f, glass);
		prop(c, pose, light, -0.036f, 0.9f, -0.036f, 0.036f, 1.02f, 0.036f, foil);
	}
	private static final Identifier PHONE_SCREEN = Identifier.fromNamespaceAndPath("femboyware", "textures/larp/phone_screen.png");
	private static final Identifier LEDGER_SCREEN = Identifier.fromNamespaceAndPath("femboyware", "textures/larp/ledger_screen.png");
	private static final Identifier DEVICE_BODY = Identifier.fromNamespaceAndPath("femboyware", "textures/larp/camera.png");

	/**
	 * A bottle in the right hand, neck pointing out along the arm's +z, which is down and forward
	 * with the arm raised, so it pours. Glass and label from the bottle texture.
	 */
	private static void drawBottle(PoseStack.Pose pose, VertexConsumer c, int light) {
		float[] glass = {0, 0, 0.5f, 1}, label = {0.5f, 0.3f, 1, 0.65f}, cap = {0.5f, 0.05f, 1, 0.16f};
		// A big wine bottle: a long body with the label round it, a shoulder, a long neck and the foil
		prop(c, pose, light, -0.095f, 0.535f, -0.12f, 0.095f, 0.725f, 0.3f, glass);
		prop(c, pose, light, -0.097f, 0.533f, 0.0f, 0.097f, 0.727f, 0.17f, label);
		prop(c, pose, light, -0.065f, 0.565f, 0.3f, 0.065f, 0.695f, 0.36f, glass);
		prop(c, pose, light, -0.034f, 0.596f, 0.36f, 0.034f, 0.664f, 0.6f, glass);
		prop(c, pose, light, -0.037f, 0.593f, 0.5f, 0.037f, 0.667f, 0.61f, cap);
	}

	/**
	 * A phone (portrait) or a Ledger (landscape) held up in the right hand facing forward, screen
	 * side out. Right arm space: with the arm out in front, +y is forward and -z up. {@code screen}
	 * draws the glowing face, otherwise the body round it.
	 */
	private static void drawHeldDevice(PoseStack.Pose pose, VertexConsumer c, boolean phone, boolean screen) {
		float x1 = phone ? -0.1f : -0.17f, x2 = phone ? 0.1f : 0.1f, z1 = phone ? -0.42f : -0.16f, z2 = -0.02f, y1 = 0.64f, y2 = 0.67f;
		int full = 0xF000F0;
		if (screen) {
			float inset = 0.012f, y = y2 + 0.001f;
			Vector3f n = new Vector3f(0, 1, 0);
			billVertex(c, pose, new Vector3f(x1 + inset, y, z1 + inset), 0, 0, full, n);
			billVertex(c, pose, new Vector3f(x2 - inset, y, z1 + inset), 1, 0, full, n);
			billVertex(c, pose, new Vector3f(x2 - inset, y, z2 - inset), 1, 1, full, n);
			billVertex(c, pose, new Vector3f(x1 + inset, y, z2 - inset), 0, 1, full, n);
			return;
		}
		float[] black = {0.05f, 0.05f, 0.45f, 0.45f}, steel = {0.05f, 0.55f, 0.45f, 0.95f};
		prop(c, pose, full, x1, y1, z1, x2, y2, z2, black);
		// The Ledger's steel swivel cover round its end
		if (!phone) prop(c, pose, full, 0.06f, y1 - 0.006f, z1 - 0.01f, 0.16f, y2 + 0.006f, z2 + 0.01f, steel);
	}

	/** A box with every face showing one piece of texture. */
	private static void prop(VertexConsumer c, PoseStack.Pose pose, int light, float x1, float y1, float z1, float x2, float y2, float z2, float[] uv) {
		float[][][] faces = {
			{{x1, y1, z1}, {x2, y1, z1}, {x2, y2, z1}, {x1, y2, z1}},
			{{x2, y1, z2}, {x1, y1, z2}, {x1, y2, z2}, {x2, y2, z2}},
			{{x1, y1, z2}, {x1, y1, z1}, {x1, y2, z1}, {x1, y2, z2}},
			{{x2, y1, z1}, {x2, y1, z2}, {x2, y2, z2}, {x2, y2, z1}},
			{{x1, y1, z2}, {x2, y1, z2}, {x2, y1, z1}, {x1, y1, z1}},
			{{x1, y2, z1}, {x2, y2, z1}, {x2, y2, z2}, {x1, y2, z2}}};
		Vector3f[] normals = {new Vector3f(0, 0, -1), new Vector3f(0, 0, 1), new Vector3f(-1, 0, 0), new Vector3f(1, 0, 0), new Vector3f(0, -1, 0), new Vector3f(0, 1, 0)};
		float[][] corners = {{uv[0], uv[1]}, {uv[2], uv[1]}, {uv[2], uv[3]}, {uv[0], uv[3]}};
		for (int f = 0; f < 6; f++) {
			for (int k = 0; k < 4; k++) billVertex(c, pose, new Vector3f(faces[f][k][0], faces[f][k][1], faces[f][k][2]), corners[k][0], corners[k][1], light, normals[f]);
		}
	}

	private static void billVertex(VertexConsumer c, PoseStack.Pose pose, Vector3f p, float u, float v, int light, Vector3f n) {
		c.addVertex(pose, p.x, p.y, p.z).setColor(0xFFFFFFFF).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, n.x, n.y, n.z);
	}
}
