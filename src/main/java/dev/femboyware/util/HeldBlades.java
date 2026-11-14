package dev.femboyware.util;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Where blades are actually drawn: while a player's held sword, axe or mace is submitted (first or
 * third person), the pose it's drawn with is turned into world points for its hilt and tip and kept
 * for a moment, so effects can follow the rendered blade exactly. Fed by the hand and item mixins.
 */
public final class HeldBlades {
	/** One frame's blade: when, and the hilt and tip in the world. */
	public record Sample(long nanos, Vec3 hilt, Vec3 tip, boolean midSwing, boolean firstPerson) {
	}

	/** How long samples are kept. */
	private static final long KEEP = 1_500_000_000L;

	/** Recent samples by player id and arm (id * 2 + 0 right, + 1 left). */
	private static final Map<Long, ArrayDeque<Sample>> trails = new HashMap<>();

	private static boolean active, firstPerson, left;
	private static long key;

	/** The first person hands are drawn already turned with your view (camera-relative world space), in every version. */
	private static final boolean WORLD_ORIENTED_HANDS = true;

	private HeldBlades() {
	}

	/** Whether this item counts as a blade: swords, axes, maces, tridents and spears. */
	public static boolean blade(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return false;
		return stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES) || stack.is(Items.MACE) || stack.is(Items.TRIDENT);
	}

	/** The held item about to be drawn, for models that replace it (see SwordModelsModule). */
	public record Hand(int playerId, ItemStack stack, boolean firstPerson, boolean left) {
	}

	private static Hand hand;

	/** The held item being drawn, once: null if what's being drawn isn't one (or it's been taken). */
	public static Hand takeHand() {
		Hand h = hand;
		hand = null;
		return h;
	}

	/** Puts minecraft:item/handheld's display transform on {@code matrices}, then moves to item space (0 to 1). */
	public static void handheld(PoseStack matrices, boolean inFirstPerson, boolean leftHand) {
		float tx, ty, tz, ry, rz, s;
		if (inFirstPerson) {
			tx = 1.13f; ty = 3.2f; tz = 1.13f; ry = -90; rz = 25; s = 0.68f;
		} else {
			tx = 0; ty = 4f; tz = 0.5f; ry = -90; rz = 55; s = 0.85f;
		}
		if (leftHand) {
			tx = -tx;
			ry = -ry;
			rz = -rz;
		}
		matrices.translate(tx / 16, ty / 16, tz / 16);
		rotate(matrices, new Quaternionf().rotationXYZ(0, (float) Math.toRadians(ry), (float) Math.toRadians(rz)));
		matrices.scale(s, s, s);
		matrices.translate(-0.5f, -0.5f, -0.5f);
	}

	/** Turns {@code matrices} (PoseStack's method for it was renamed in 26.3). */
	public static void rotate(PoseStack matrices, Quaternionf turn) {
		//? if >=26.3 {
		/*matrices.rotate(turn);
		*///?} else {
		matrices.mulPose(turn);
		//?}
	}

	/** From the hand renderers: an arm's held item is about to be drawn. */
	public static void begin(int playerId, HumanoidArm arm, ItemStack stack, boolean inFirstPerson) {
		hand = stack == null || stack.isEmpty() ? null : new Hand(playerId, stack, inFirstPerson, arm == HumanoidArm.LEFT);
		active = blade(stack);
		if (!active) return;
		firstPerson = inFirstPerson;
		left = arm == HumanoidArm.LEFT;
		key = playerId * 2L + (left ? 1 : 0);
	}

	public static void end() {
		active = false;
		hand = null;
	}

	/**
	 * From ItemStackRenderState.submit: the pose the item's drawn with, before its model's display
	 * transform. The vanilla handheld transform is put on top, then the hilt and tip of a sword's
	 * sprite go through it into the world.
	 */
	public static void capture(PoseStack matrices) {
		if (!active) return;
		active = false;
		Matrix4f m = new Matrix4f(matrices.last().pose());
		// minecraft:item/handheld's display transforms (first and third person), mirrored for the left hand
		float tx, ty, tz, rx, ry, rz, s;
		if (firstPerson) {
			tx = 1.13f; ty = 3.2f; tz = 1.13f; rx = 0; ry = -90; rz = 25; s = 0.68f;
		} else {
			tx = 0; ty = 4f; tz = 0.5f; rx = 0; ry = -90; rz = 55; s = 0.85f;
		}
		if (left) {
			tx = -tx;
			ry = -ry;
			rz = -rz;
		}
		m.translate(tx / 16, ty / 16, tz / 16);
		m.rotate(new Quaternionf().rotationXYZ((float) Math.toRadians(rx), (float) Math.toRadians(ry), (float) Math.toRadians(rz)));
		m.scale(s);
		m.translate(-0.5f, -0.5f, -0.5f);
		// A sword sprite runs from its hilt at the bottom left to its tip at the top right
		Vector3f hilt = m.transformPosition(new Vector3f(4.5f / 16, 4.5f / 16, 0.5f));
		Vector3f tip = m.transformPosition(new Vector3f(15.5f / 16, 15.5f / 16, 0.5f));
		Minecraft mc = Minecraft.getInstance();
		var camera = Mc.camera();
		Vec3 cam = camera.position();
		Vec3 h, t;
		if (firstPerson) {
			// The hands are drawn in view space at their own field of view: put the points where they
			// show on screen at the world's field of view, then turn them out into the world
			double world = Math.toRadians(mc.options.fov().get()), hands = Math.toRadians(70);
			float k = (float) (Math.tan(hands / 2) / Math.tan(world / 2));
			// Your view's turn (first person is from your eyes): view space -Z is where you look
			float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
			float yaw = mc.player == null ? 0 : mc.player.getViewYRot(partial), pitch = mc.player == null ? 0 : mc.player.getViewXRot(partial);
			Quaternionf turn = new Quaternionf().rotationYXZ((float) Math.PI - (float) Math.toRadians(yaw), -(float) Math.toRadians(pitch), 0);
			if (WORLD_ORIENTED_HANDS) {
				// Already turned with the view: back into view space for the field of view fix
				Quaternionf back = new Quaternionf(turn).conjugate();
				back.transform(hilt);
				back.transform(tip);
			}
			Vector3f hv = turn.transform(new Vector3f(hilt.x * k, hilt.y * k, hilt.z));
			Vector3f tv = turn.transform(new Vector3f(tip.x * k, tip.y * k, tip.z));
			h = cam.add(hv.x, hv.y, hv.z);
			t = cam.add(tv.x, tv.y, tv.z);
		} else {
			h = cam.add(hilt.x, hilt.y, hilt.z);
			t = cam.add(tip.x, tip.y, tip.z);
		}
		long now = System.nanoTime();
		ArrayDeque<Sample> trail = trails.computeIfAbsent(key, k2 -> new ArrayDeque<>());
		// One per frame: a second draw the same frame (shadows, mirrors) replaces it
		if (!trail.isEmpty() && now - trail.peekLast().nanos() < 2_000_000L) trail.pollLast();
		// Whether they're mid-swing, so effects can trail swings rather than every turn of the head
		var entity = mc.level == null ? null : mc.level.getEntity(playerOf(key));
		boolean midSwing = entity instanceof net.minecraft.world.entity.LivingEntity living && living.getAttackAnim(mc.getDeltaTracker().getGameTimeDeltaPartialTick(true)) > 0;
		trail.addLast(new Sample(now, h, t, midSwing, firstPerson));
	}

	/** Every blade's recent samples, oldest first; stale ones dropped. */
	public static Map<Long, ArrayDeque<Sample>> trails() {
		long now = System.nanoTime();
		for (Iterator<ArrayDeque<Sample>> it = trails.values().iterator(); it.hasNext(); ) {
			ArrayDeque<Sample> trail = it.next();
			while (!trail.isEmpty() && now - trail.peekFirst().nanos() > KEEP) trail.pollFirst();
			if (trail.isEmpty()) it.remove();
		}
		return trails;
	}

	/** The player a trail belongs to. */
	public static int playerOf(long key) {
		return (int) (key >> 1);
	}
}
