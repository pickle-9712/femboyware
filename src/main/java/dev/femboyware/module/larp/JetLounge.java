package dev.femboyware.module.larp;

import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.larp.ShapeBuilder.Station;
import dev.femboyware.util.FxRender;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Champagne on the jet: a flute poured at the galley or brought to your seat, sipped with
 * right-click (four sips a glass), put down with your drop key. Drawn in your hand in first person,
 * and in your hand on your model and on other people's screens through Client Sync, who see you
 * raise it to drink. Only on your side, like the rest of the larp.
 */
public final class JetLounge {
	private static final Minecraft mc = Minecraft.getInstance();
	private static final SoundEvent DRINK = SoundEvent.createVariableRangeEvent(Identifier.withDefaultNamespace("entity.generic.drink"));
	private static final SoundEvent CLINK = SoundEvent.createVariableRangeEvent(Identifier.withDefaultNamespace("block.amethyst_block.chime"));
	/** Sips in a glass, and how long one takes in ticks: up to your lips, a drink, back down. */
	private static final int SIPS = 4, SIP_TICKS = 30;
	/** The galley's counter, in the jet's own space (across from the door). */
	private static final float GALLEY_X = 0.75f, GALLEY_Z = 9.7f;

	private static boolean holding;
	private static int sipsLeft;
	/** When the sip under way started (player tick), or -1. */
	private static int sipStart = -1;
	private static int lastTick = Integer.MIN_VALUE;

	private JetLounge() {
	}

	public static boolean holding() {
		return holding;
	}

	/** For Client Sync: how full your glass is, 0 to 1, or -1 with no glass. */
	public static float shareLevel() {
		return holding ? sipsLeft / (float) SIPS : -1;
	}

	/** For Client Sync: whether you're sipping right now. */
	public static boolean shareSipping() {
		return holding && sipStart >= 0;
	}

	/** Whether a spot in a jet's cabin (its own space) is at the galley counter. */
	public static boolean nearGalley(float x, float z) {
		float dx = x - GALLEY_X, dz = z - GALLEY_Z;
		return dx * dx + dz * dz < 1.1f * 1.1f;
	}

	/** A fresh glass: poured yourself at the galley, or brought to your seat by the flight attendant. */
	public static void serve(boolean toSeat) {
		holding = true;
		sipsLeft = SIPS;
		sipStart = -1;
		LarpSounds.play(LarpSounds.CHAMPAGNE_POP, 1.1f, 0.7f);
		LarpSounds.play(CLINK, 1.4f, 0.5f);
		if (toSeat) dev.femboyware.util.Mc.addChat(net.minecraft.network.chat.Component.literal("<Flight Attendant> Your Dom Pérignon. Enjoy."));
		dev.femboyware.util.Mc.actionBar("Right-click to sip, drop key to put it down");
	}

	public static void putDown() {
		if (!holding) return;
		holding = false;
		sipStart = -1;
		LarpSounds.play(CLINK, 1.1f, 0.4f);
	}

	/**
	 * Every tick, at its start so the game never sees these clicks: right-click sips, the drop key
	 * puts the glass down (instead of throwing what's in your hand), and the sip plays out.
	 */
	public static void tick() {
		if (mc.player == null || !holding) return;
		int now = mc.player.tickCount;
		if (now == lastTick) return;
		lastTick = now;
		if (dev.femboyware.util.Mc.screen() == null) {
			boolean drop = false;
			while (mc.options.keyDrop.consumeClick()) drop = true;
			if (drop) {
				putDown();
				return;
			}
			boolean use = false;
			while (mc.options.keyUse.consumeClick()) use = true;
			if (use && sipStart < 0) sipStart = now;
		}
		if (sipStart < 0) return;
		int t = now - sipStart;
		if (t == 11) LarpSounds.play(DRINK, 1.1f, 0.6f);
		if (t == 20) sipsLeft--;
		if (t >= SIP_TICKS) {
			sipStart = -1;
			if (sipsLeft <= 0) {
				holding = false;
				dev.femboyware.util.Mc.actionBar("Glass finished. Another at the galley, or right-click in your seat");
			}
		}
	}

	/** How far the glass is raised to your lips, 0 to 1, {@code t} ticks into a sip. */
	public static float raise(float t) {
		if (t < 0 || t > SIP_TICKS) return 0;
		if (t < 10) return smooth(t / 10);
		if (t < 22) return 1;
		return 1 - smooth((t - 22) / 8);
	}

	private static float smooth(float t) {
		t = Mth.clamp(t, 0, 1);
		return t * t * (3 - 2 * t);
	}

	private static float yourRaise(float partial) {
		return sipStart < 0 || mc.player == null ? 0 : raise(mc.player.tickCount - sipStart + partial);
	}

	// ---------------------------------------------------------------- the arm

	/** Called from PlayerModelMixin, last: whoever's holding a glass holds it, and lifts it to drink. */
	public static void pose(net.minecraft.client.model.player.PlayerModel model, net.minecraft.client.renderer.entity.state.AvatarRenderState state) {
		float raise;
		if (holding && mc.player != null && state.id == mc.player.getId()) {
			raise = yourRaise(mc.getDeltaTracker().getGameTimeDeltaPartialTick(true));
		} else {
			float[] theirs = dev.femboyware.FemboyWare.getModules().get(dev.femboyware.module.client.ClientSyncModule.class).fluteOf(state.id);
			if (theirs == null) return;
			raise = theirs[1];
		}
		model.rightArm.xRot = Mth.lerp(raise, -0.9f, -1.95f);
		model.rightArm.yRot = -0.35f * raise;
		model.rightArm.zRot = 0;
		model.head.xRot = Mth.lerp(raise, model.head.xRot, -0.25f);
	}

	// ---------------------------------------------------------------- the glass

	/** Called each frame: your glass, in your hand in first person or on your model, and everyone else's. */
	public static void draw(Render3DEvent event) {
		if (mc.player == null) return;
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		if (holding) {
			float raise = yourRaise(partial), level = sipsLeft / (float) SIPS;
			if (mc.options.getCameraType().isFirstPerson()) {
				// Down and to the right of your view, coming up to your lips to drink
				Quaternionf view = event.camera().orientation;
				Vector3f at = new Vector3f(Mth.lerp(raise, 0.26f, 0.03f), Mth.lerp(raise, -0.34f, -0.17f), Mth.lerp(raise, -0.58f, -0.3f)).rotate(view);
				Quaternionf turn = new Quaternionf(view).rotateX(raise * 1.15f);
				drawFlute(event, turn, at.x, at.y, at.z, level);
			} else drawInHand(event, mc.player, partial, raise, level);
		}
		for (Object[] f : dev.femboyware.FemboyWare.getModules().get(dev.femboyware.module.client.ClientSyncModule.class).flutes()) {
			drawInHand(event, (Player) f[0], partial, (float) f[2], (float) f[1]);
		}
	}

	/** A glass in someone's right hand, from where the arm's pointing (see {@link #pose}). */
	private static void drawInHand(Render3DEvent event, Player who, float partial, float raise, float level) {
		Vec3 cam = event.camera().pos, feet = who.getPosition(partial);
		if (cam.distanceToSqr(feet) > 48 * 48) return;
		float bodyYaw = Mth.rotLerp(partial, who.yBodyRotO, who.yBodyRot);
		Quaternionf body = new Quaternionf().rotateY((float) Math.toRadians(-bodyYaw));
		// The right hand: out from the shoulder along the arm, raised and swung in toward the mouth to sip
		float arm = Mth.lerp(raise, -0.9f, -1.95f);
		Vector3f hand = new Vector3f(Mth.lerp(raise, -0.31f, -0.14f), 1.29f - 0.62f * Mth.cos(arm), 0.62f * -Mth.sin(arm)).rotate(body);
		Quaternionf turn = new Quaternionf(body).rotateX(-raise * 1.1f);
		drawFlute(event, turn, (float) (feet.x - cam.x) + hand.x, (float) (feet.y - cam.y) + hand.y - 0.04f, (float) (feet.z - cam.z) + hand.z, level);
	}

	/**
	 * A champagne flute, upright in {@code turn}'s space with its foot at the given camera-relative
	 * spot: a round foot, a thin stem, a tall tapering bowl, and the champagne in it to {@code level}.
	 */
	private static void drawFlute(Render3DEvent event, Quaternionf turn, float ox, float oy, float oz, float level) {
		// Built along z, then stood up: z becomes up
		Quaternionf upright = new Quaternionf(turn).rotateX((float) -Math.PI / 2);
		ShapeBuilder glass = new ShapeBuilder(null, event.matrices().last(), upright, ox, oy, oz).lit(0.9f);
		int clear = 0x60E4EEF4, rim = 0x90F4F8FA;
		glass.loft(new Station[]{new Station(0, 0.034f, 0), new Station(0.006f, 0.034f, 0), new Station(0.01f, 0.008f, 0)}, 16, clear);
		glass.loft(new Station[]{new Station(0.01f, 0.006f, 0), new Station(0.095f, 0.006f, 0)}, 8, clear);
		Station[] bowl = {new Station(0.095f, 0.006f, 0), new Station(0.11f, 0.017f, 0), new Station(0.17f, 0.024f, 0), new Station(0.245f, 0.028f, 0)};
		glass.loft(bowl, 18, clear);
		glass.loft(new Station[]{new Station(0.243f, 0.0285f, 0), new Station(0.247f, 0.0285f, 0)}, 18, rim);
		if (level > 0) {
			// The champagne: pale gold, filling the bowl from the bottom to how much is left
			float top = 0.1f + 0.13f * level;
			int gold = 0xC8F1D27A;
			ShapeBuilder wine = new ShapeBuilder(null, event.matrices().last(), upright, ox, oy, oz).lit(0.95f);
			wine.loft(new Station[]{new Station(0.1f, 0.009f, 0), new Station(0.11f, 0.0145f, 0), new Station(Math.min(top, 0.17f), Math.min(0.021f, 0.0145f + (top - 0.11f) * 0.11f), 0),
				new Station(top, 0.0145f + (Math.min(top, 0.245f) - 0.11f) * 0.085f, 0)}, 14, gold);
			// Its surface, a disc across the top
			float r = 0.0145f + (Math.min(top, 0.245f) - 0.11f) * 0.085f;
			wine.loft(new Station[]{new Station(top, r, 0), new Station(top + 0.0005f, 0, 0)}, 14, 0xE0F7E3A4);
			wine.flush(FxRender.quads(event, false, false));
		}
		glass.flush(FxRender.quads(event, false, false));
	}
}
