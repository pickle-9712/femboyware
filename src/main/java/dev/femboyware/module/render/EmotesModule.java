package dev.femboyware.module.render;

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
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

/** Larp emotes picked from a wheel. Only you see them: they pose your own player model. */
public class EmotesModule extends Module {
	public enum Emote {
		BAND_SPREAD("Band Spread");

		public final String label;

		Emote(String label) {
			this.label = label;
		}
	}

	public enum Camera { OFF, BACK, FRONT }

	/** The songs an emote plays, one picked at random. Sounds and art are in assets/femboyware. */
	private enum Song {
		QUEEN_ST("queen_st", "Queen St", "twentythree", 128.8, true),
		VYZEE("vyzee", "VYZEE (slowed)", "SOPHIE", 247.9, true),
		BLUE_BANDS("blue_bands", "blue bands", "Weiland", 129.7, false);

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

	private final KeySetting wheelKey = add(new KeySetting("Wheel Key", "Hold to open the emote wheel, let go over an emote to play it", GLFW.GLFW_KEY_B));
	private final EnumSetting<Camera> camera = add(new EnumSetting<>("Camera", "Switch to third person while you emote, so you can see it", Camera.FRONT));
	private final BoolSetting stopOnMove = add(new BoolSetting("Stop On Move", "Walking or jumping ends the emote", true));
	private final BoolSetting music = add(new BoolSetting("Music", "Play a random song while you emote, shown on the Island", true));
	private final NumberSetting volume = add(new NumberSetting("Music Volume", "How loud the emote songs are", 0.7, 0.05, 1, 0.05)).visibleWhen(music::get);
	private final BoolSetting fakesCopy = add(new BoolSetting("Fake Players Copy", "Every fake player does your emote with you", true));

	private Emote playing;
	private int startTick;
	private CameraType previousCamera;
	private Song song;
	private SoundInstance songSound;
	private int songTick;

	public EmotesModule() {
		super("Emotes", "An emote wheel with larp emotes like the band spread", Category.COSMETIC);
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
		boolean wasPlaying = playing != null;
		playing = emote;
		startTick = mc.player.tickCount;
		// Switching straight from one emote to another keeps the song going
		if (!wasPlaying && music.get()) playSong();
		if (camera.is(Camera.OFF) || previousCamera != null) return;
		previousCamera = mc.options.getCameraType();
		mc.options.setCameraType(camera.is(Camera.FRONT) ? CameraType.THIRD_PERSON_FRONT : CameraType.THIRD_PERSON_BACK);
	}

	public void stop() {
		playing = null;
		stopSong();
		if (previousCamera != null) mc.options.setCameraType(previousCamera);
		previousCamera = null;
	}

	@Override
	public void onTick() {
		if (playing == null) return;
		if (stopOnMove.get() && moving()) {
			stop();
			return;
		}
		// When a song ends, another random one follows. A just-started stream can take a moment to show as playing
		if (music.get() && songSound != null && mc.player.tickCount - songTick > 40 && !mc.getSoundManager().isActive(songSound)) playSong();
		else if (!music.get() && songSound != null) stopSong();
		else if (music.get() && songSound == null) playSong();
	}

	/** Starts a random song, never the one that just played, and shows it on the Island. */
	private void playSong() {
		stopSong();
		Song[] songs = Song.values();
		Song next;
		do next = songs[(int) (Math.random() * songs.length)];
		while (next == song && songs.length > 1);
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
	}

	/**
	 * How far into the emote {@code state}'s player is, in ticks, or -1 when it isn't emoting: you,
	 * and the fake players copying you. Everyone runs off your clock so they move in step.
	 */
	private float elapsed(AvatarRenderState state) {
		if (!isEnabled() || playing == null || mc.player == null) return -1;
		if (state.id != mc.player.getId() && !(fakesCopy.get() && FemboyWare.getModules().get(FakePlayerModule.class).isFake(state.id))) return -1;
		float partial = state.ageInTicks - (float) Math.floor(state.ageInTicks);
		return Math.max(0, mc.player.tickCount - startTick + partial);
	}

	/** Read by PlayerModelMixin after vanilla posed the model. */
	public void pose(PlayerModel model, AvatarRenderState state) {
		float t = elapsed(state);
		if (t < 0) return;
		switch (playing) {
			case BAND_SPREAD -> poseBandSpread(model, t);
		}
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
			if (t < 0 || playing != Emote.BAND_SPREAD) return;
			float spread = spread(t);
			fan(matrices, queue, getParentModel().rightArm, -1, spread, light);
			fan(matrices, queue, getParentModel().leftArm, 1, spread, light);
		}

		private void fan(PoseStack matrices, SubmitNodeCollector queue, ModelPart arm, int side, float spread, int light) {
			matrices.pushPose();
			arm.translateAndRotate(matrices);
			queue.submitCustomGeometry(matrices, cutout(BILL), (pose, consumer) -> drawFan(pose, consumer, side, spread, light));
			matrices.popPose();
		}
	}

	/**
	 * A fan of bills pinched in the fist. Arm space: the arm runs down +y to the fist at about 0.6, so
	 * with the arm held out in front, -z points up and the fan opens across x, facing whoever's ahead.
	 */
	private static void drawFan(PoseStack.Pose pose, VertexConsumer c, int side, float spread, int light) {
		float fistX = side * 0.0625f, fistY = 0.6f, fistZ = -0.02f;
		for (int i = 0; i < BILLS; i++) {
			float angle = side * (-spread / 2 + spread * i / (BILLS - 1));
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

	private static void billVertex(VertexConsumer c, PoseStack.Pose pose, Vector3f p, float u, float v, int light, Vector3f n) {
		c.addVertex(pose, p.x, p.y, p.z).setColor(0xFFFFFFFF).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, n.x, n.y, n.z);
	}
}
