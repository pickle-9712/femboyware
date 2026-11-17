package dev.femboyware.module.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.femboyware.event.PacketEvent;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.RenderUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * When someone dies, Tung Tung Tung Sahur appears next to them and bonks them on the head with a
 * bat before they fall over. Their death animation waits for the last bonk, then plays out in the
 * time left before the server removes the body. Client-side only.
 */
public class TungSahurModule extends Module {
	/** How long one full swing takes: wind up, bonk, settle. */
	private static final long SWING_TIME = 560;
	/** After the last bonk the body gets this long to fall over before it's removed. */
	private static final long FALL_TIME = 600;
	/** Where in each swing the bat lands, from 0 to 1. */
	private static final float IMPACT = 0.55f;

	// Brown wood, as on the Roblox model
	private static final int WOOD = 0xFF9A6234;
	private static final int WOOD_LIGHT = 0xFFB27A44;
	private static final int GRAIN = 0xFF7A4A26;
	private static final int END_GRAIN = 0xFFE2CC98;
	private static final int END_RING = 0xFFBFA272;
	private static final int SOCKET = 0xFF4A2C20;
	private static final int EYE_WHITE = 0xFFD9D3CC;
	private static final int BROW = 0xFF3A2218;
	private static final int PUPIL = 0xFF050404;
	private static final int GLINT = 0xFFF4F4F4;
	private static final int MOUTH = 0xFF3A1A10;
	private static final int BAT = 0xFF8E5530;
	private static final int BAT_DARK = 0xFF5E3820;

	/** He's 2.5 tall: legs up to the log's bottom, the log, the face high on it. */
	private static final float LOG_BOTTOM = 0.9f;
	static final float LOG_TOP = 2.5f;
	static final float RADIUS = 0.275f;
	private static final float EYE_Y = 2.08f;
	private static final float EYE_X = 0.13f;
	private static final float MOUTH_Y = 1.77f;

	private static final float SHOULDER = 1.5f;
	private static final float SHOULDER_X = 0.285f;
	/** How far the bat leans forward and out from his fist, in degrees. */
	private static final float BAT_TILT = 22;
	private static final float BAT_OUT = 26;
	/** Direction the light comes from, in the model's own space: above, front and a little right. */
	private static final Vec3 LIGHT = new Vec3(0.35, 0.75, 0.55).normalize();

	/** One sahur visit: when the victim died and how many bonks have landed so far. */
	private static final class Visit {
		private final long start;
		private int bonks;
		/** The server has removed the body; it goes once sahur is done. */
		private volatile boolean removed;
		/** The body's pose the moment it died, held until it's gone. */
		private Pose pose;

		private Visit(long start) {
			this.start = start;
		}
	}

	/** A frozen copy of the parts of a body's render state that would otherwise keep moving. */
	private record Pose(double x, double y, double z, float age, float bodyYaw, float headYaw, float pitch,
						float limbProgress, float limbAmplitude, float leaningPitch) {
		private static Pose capture(LivingEntityRenderState state) {
			float leaning = state instanceof HumanoidRenderState biped ? biped.swimAmount : 0;
			return new Pose(state.x, state.y, state.z, state.ageInTicks, state.bodyRot, state.yRot, state.xRot,
				state.walkAnimationPos, state.walkAnimationSpeed, leaning);
		}

		private void apply(LivingEntityRenderState state) {
			state.x = x;
			state.y = y;
			state.z = z;
			state.ageInTicks = age;
			state.bodyRot = bodyYaw;
			state.yRot = headYaw;
			state.xRot = pitch;
			state.walkAnimationPos = limbProgress;
			state.walkAnimationSpeed = limbAmplitude;
			if (state instanceof HumanoidRenderState biped) biped.swimAmount = leaningPitch;
		}

		private Vec3 position() {
			return new Vec3(x, y, z);
		}
	}

	private final NumberSetting hits = add(new NumberSetting("Hits", "Bonks before they fall over", 3, 1, 5, 1));
	private final BoolSetting playersOnly = add(new BoolSetting("Players Only", "Only visit players, not mobs", true));
	private final BoolSetting sounds = add(new BoolSetting("Sounds", "A wooden TUNG on every bonk", true));
	private final BoolSetting words = add(new BoolSetting("Words", "Show TUNG and SAHUR! above their head", true));
	private final NumberSetting scale = add(new NumberSetting("Scale", "How big Tung Tung Tung Sahur is", 1, 0.5, 2, 0.05));
	private final NumberSetting range = add(new NumberSetting("Range", "Only visit deaths within this many blocks", 64, 8, 256, 8));

	private final Map<Integer, Visit> visits = new ConcurrentHashMap<>();
	/**
	 * The server removes bodies a second after death, too soon for a proper bonking, so their
	 * removal (and the death poof) is held back here until sahur is done with them.
	 */
	private final Queue<Integer> removeNow = new ConcurrentLinkedQueue<>();

	public TungSahurModule() {
		super("Tung Sahur", "Tung Tung Tung Sahur bonks whoever just died", Category.COSMETIC);
	}

	@Override
	public String getInfo() {
		return Integer.toString(visits.size());
	}

	@Override
	protected void onDisable() {
		visits.clear();
		removeHeldBodies(true);
	}

	/**
	 * Sends sahur to {@code entity}, who just died, even with this module off: Kill Animations uses
	 * this for its Tung Sahur style, so only your own kills get a visit.
	 */
	public void visit(LivingEntity entity) {
		visits.putIfAbsent(entity.getId(), new Visit(System.currentTimeMillis()));
	}

	/** On, or still finishing a visit someone asked for. */
	public boolean running() {
		return isEnabled() || !visits.isEmpty();
	}

	/** Whether sahur still wants {@code entity}'s body around; the Fake Player checks this before despawning. */
	public boolean holdsBody(Entity entity) {
		if (!running() || entity == null) return false;
		Visit visit = visits.get(entity.getId());
		return visit != null && System.currentTimeMillis() - visit.start < bodyLifetime();
	}

	@Override
	public void onPacketReceive(PacketEvent event) {
		if (event.getPacket() instanceof ClientboundRemoveEntitiesPacket packet) {
			boolean held = false;
			for (int id : packet.getEntityIds()) held |= visits.containsKey(id);
			if (!held) return;
			// Hold the whole packet back, then let through the bodies sahur isn't bonking
			event.cancel();
			for (int id : packet.getEntityIds()) {
				Visit visit = visits.get(id);
				if (visit == null) removeNow.add(id);
				else visit.removed = true;
			}
		} else if (event.getPacket() instanceof ClientboundEntityEventPacket packet && packet.getEventId() == EntityEvent.POOF
			&& mc.level != null) {
			Entity entity = packet.getEntity(mc.level);
			if (entity != null && visits.containsKey(entity.getId())) event.cancel();
		}
	}

	/** Removes bodies the server already removed once sahur is done with them, or all of them with {@code all}. */
	private void removeHeldBodies(boolean all) {
		Integer id;
		while ((id = removeNow.poll()) != null) removeBody(id, false);
		long now = System.currentTimeMillis();
		for (Map.Entry<Integer, Visit> entry : visits.entrySet()) {
			Visit visit = entry.getValue();
			if (!visit.removed || (!all && now - visit.start < bodyLifetime())) continue;
			removeBody(entry.getKey(), true);
			visits.remove(entry.getKey());
		}
	}

	private void removeBody(int id, boolean poof) {
		if (mc.level == null) return;
		if (poof && mc.level.getEntity(id) instanceof LivingEntity living) living.makePoofParticles();
		mc.level.removeEntity(id, Entity.RemovalReason.KILLED);
	}

	@Override
	public void onTick() {
		long now = System.currentTimeMillis();
		removeHeldBodies(false);
		visits.entrySet().removeIf(entry -> now - entry.getValue().start > bodyLifetime() + 2000);
		// Every death nearby when on; only the ones asked for (Kill Animations) when not
		if (isEnabled()) {
			for (Entity entity : mc.level.entitiesForRendering()) {
				if (entity instanceof LivingEntity living && wantsVisit(living)) visits.putIfAbsent(entity.getId(), new Visit(now));
			}
		}
		// One sound per bonk as each one lands
		for (Map.Entry<Integer, Visit> entry : visits.entrySet()) {
			Visit visit = entry.getValue();
			Entity victim = mc.level.getEntity(entry.getKey());
			if (victim == null) continue;
			while (visit.bonks < hits.getInt() && now - visit.start >= bonkTime(visit.bonks)) {
				visit.bonks++;
				if (sounds.get()) {
					mc.level.playLocalSound(victim.getX(), victim.getY() + victim.getBbHeight(), victim.getZ(), SoundEvents.ZOMBIE_ATTACK_WOODEN_DOOR,
						SoundSource.PLAYERS, 0.8f, 1.3f + visit.bonks * 0.1f, false);
				}
			}
		}
	}

	/** When bonk {@code index} (from 0) lands, in milliseconds after the death. */
	private long bonkTime(int index) {
		return (long) ((index + IMPACT) * swingTime());
	}

	private float swingTime() {
		return SWING_TIME;
	}

	private long bonkingTime() {
		return SWING_TIME * hits.getInt();
	}

	private long bodyLifetime() {
		return bonkingTime() + FALL_TIME;
	}

	private boolean wantsVisit(LivingEntity entity) {
		if (entity == mc.player || !entity.isDeadOrDying() || mc.player == null) return false;
		if (playersOnly.get() && !(entity instanceof Player)) return false;
		return mc.player.distanceTo(entity) <= range.get();
	}

	/**
	 * Called from LivingEntityRendererMixin for every dead body. The body freezes in the pose it
	 * died in and stands there while sahur bonks it, flashing red on each hit; after the last bonk
	 * the normal death fall plays out from that pose.
	 */
	public void adjustRenderState(LivingEntity entity, LivingEntityRenderState state) {
		if (!running()) return;
		Visit visit = visits.get(entity.getId());
		if (visit == null) {
			// Seen dying before the tick did; start now so there's no frame of the vanilla fall
			if (!isEnabled() || !wantsVisit(entity)) return;
			visit = visits.computeIfAbsent(entity.getId(), id -> new Visit(System.currentTimeMillis()));
		}
		if (visit.pose == null) visit.pose = Pose.capture(state);
		visit.pose.apply(state);

		long elapsed = System.currentTimeMillis() - visit.start;
		if (elapsed < bonkingTime()) {
			state.deathTime = 0;
			state.hasRedOverlay = flashing(elapsed);
		} else {
			// Vanilla's fall starts at 1 (it takes sqrt(deathTime - 1)); anything between 0 and 1 draws nothing
			state.deathTime = Math.min(20f, 1 + (elapsed - bonkingTime()) * 19f / FALL_TIME);
			state.hasRedOverlay = true;
		}
	}

	/** Just after a bonk lands, so the body flashes red. */
	private boolean flashing(long elapsed) {
		for (int i = 0; i < hits.getInt(); i++) {
			long since = elapsed - bonkTime(i);
			if (since >= 0 && since < 150) return true;
		}
		return false;
	}

	public int visitCount() {
		return visits.size();
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		long now = System.currentTimeMillis();
		float tickDelta = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		Vec3 camera = event.camera().pos;
		for (Map.Entry<Integer, Visit> entry : visits.entrySet()) {
			Entity victim = mc.level.getEntity(entry.getKey());
			if (victim == null) continue;
			long elapsed = now - entry.getValue().start;
			if (elapsed > bodyLifetime()) continue;
			Pose pose = entry.getValue().pose;
			Vec3 victimPos = pose != null ? pose.position() : victim.getPosition(tickDelta);

			// Stand beside them and a little behind, so he faces the camera three-quarters on while bonking
			Vec3 toCamera = camera.subtract(victimPos).multiply(1, 0, 1);
			if (toCamera.lengthSqr() < 1e-4) toCamera = new Vec3(0, 0, 1);
			toCamera = toCamera.normalize();
			Vec3 side = new Vec3(-toCamera.z, 0, toCamera.x);
			float size = scale.getFloat();
			Vec3 sahur = victimPos.add(side.scale(1.55 * size)).subtract(toCamera.scale(0.45 * size));
			// Half towards the victim, half towards the camera, so his face shows while he swings
			Vec3 facing = victimPos.subtract(sahur).multiply(1, 0, 1).normalize().add(toCamera);
			float yaw = (float) Math.atan2(facing.x, facing.z);

			// Swing in the upright plane through his shoulder and their head, so the bat comes down on it
			double cos = Math.cos(yaw), sin = Math.sin(yaw);
			Vec3 offset = victimPos.subtract(sahur).scale(1 / size);
			double localX = offset.x * cos - offset.z * sin, localZ = offset.x * sin + offset.z * cos;
			float swingYaw = (float) Math.atan2(localX - SHOULDER_X, localZ);
			double reach = Math.hypot(localX - SHOULDER_X, localZ);
			double headY = victim.getBbHeight() / size - SHOULDER;
			float impact = (float) -(90 + Math.toDegrees(Math.atan2(headY, reach)));

			float pop = Mth.clamp(elapsed / 120f, 0, 1);
			drawSahur(event, sahur.subtract(camera), yaw, swingYaw, armAngle(elapsed, impact + BAT_TILT), size * (pop < 1 ? pop * (1.15f - 0.15f * pop) : 1));

			if (words.get()) drawWords(event, entry.getValue(), elapsed, victimPos.add(0, victim.getBbHeight() + 0.4, 0));
		}
	}

	/**
	 * An overhead chop from {@code rest}: the bat goes up over his head, comes down hard in front and
	 * settles back. {@code t} runs from 0 to 1 through the swing. Degrees about the shoulder.
	 */
	public static float chop(float t, float rest) {
		if (t <= 0 || t >= 1) return rest;
		if (t < 0.35f) return Mth.lerp(ease(t / 0.35f), rest, -200f);
		if (t < 0.6f) {
			float k = (t - 0.35f) / 0.25f;
			return Mth.lerp(k * k, -200f, -70f + BAT_TILT);
		}
		return Mth.lerp(ease((t - 0.6f) / 0.4f), -70f + BAT_TILT, rest);
	}

	/** Where his feet go so his face sits at {@code face}, turned by {@code yaw}, at this size. */
	public static Vec3 feetForFace(Vec3 face, float yaw, float size) {
		return face.subtract(Math.sin(yaw) * RADIUS * size, (EYE_Y - 0.08) * size, Math.cos(yaw) * RADIUS * size);
	}

	/** The bat arm's angle: wind up behind, swing down onto their head, settle. Degrees about the shoulder. */
	private float armAngle(long elapsed, float impact) {
		if (elapsed >= bonkingTime()) return -15;
		float swing = swingTime();
		float phase = (elapsed % (long) swing) / swing;
		// Slow wind-up over his head, a fast accelerating strike, then a little bounce back off the head
		if (phase < 0.4f) return Mth.lerp(ease(phase / 0.4f), -15f, -200f);
		if (phase < IMPACT) {
			float t = (phase - 0.4f) / (IMPACT - 0.4f);
			return Mth.lerp(t * t, -200f, impact);
		}
		float t = (phase - IMPACT) / (1 - IMPACT);
		float recoil = (float) Math.sin(t * Math.PI) * 25;
		return Mth.lerp(ease(t), impact, -15f) - recoil;
	}

	private static float ease(float t) {
		return t * t * (3 - 2 * t);
	}

	private void drawWords(Render3DEvent event, Visit visit, long elapsed, Vec3 head) {
		for (int i = 0; i < visit.bonks; i++) {
			long since = elapsed - bonkTime(i);
			if (since < 0 || since > 350) continue;
			boolean last = i == hits.getInt() - 1;
			String word = last ? "SAHUR!" : "TUNG";
			int alpha = (int) (255 * (1 - since / 350f));
			int color = (Math.max(alpha, 4) << 24) | (last ? 0xFF77C8 : 0xFFE0B0);
			RenderUtil.drawText(event, word, head.add(0, since / 700.0, 0), color, last ? 0.75f : 0.55f);
		}
	}

	// ---------------------------------------------------------------- model

	/**
	 * Tung Tung Tung Sahur as in the meme: a tall round golden log with a flat sawn top, a human
	 * face high up on the front (big round eyes under dark lids, thin raised brows, a long nose and
	 * a sly smile), short thin arms hanging close to his sides, long thin legs, big bare feet with
	 * toes, and a bat held low in his right hand. Smooth surfaces lit from one side.
	 */
	public static void drawSahur(Render3DEvent event, Vec3 at, float yaw, float swingYaw, float armAngle, float size) {
		if (body == null) buildMeshes();
		PoseStack matrices = event.matrices();
		VertexConsumer quads = event.consumers().getBuffer(RenderTypes.debugQuads());
		matrices.pushPose();
		matrices.translate(at.x, at.y, at.z);
		matrices.mulPose(Axis.YP.rotation(yaw));
		matrices.scale(size, size, size);
		body.draw(quads, matrices.last());

		// Right arm and bat, swung from the shoulder
		matrices.pushPose();
		matrices.translate(SHOULDER_X, SHOULDER, 0);
		matrices.mulPose(Axis.YP.rotation(swingYaw));
		matrices.mulPose(Axis.XP.rotationDegrees(armAngle));
		arm.draw(quads, matrices.last());
		// The bat leans forward and out from the fist, its barrel down by his feet
		matrices.translate(GRIP.x, GRIP.y, GRIP.z);
		matrices.mulPose(Axis.XP.rotationDegrees(-BAT_TILT));
		matrices.mulPose(Axis.ZP.rotationDegrees(BAT_OUT));
		bat.draw(quads, matrices.last());
		matrices.popPose();

		matrices.popPose();
	}

	private static final Vec3 GRIP = new Vec3(0.035, -0.37, 0.0);

	/**
	 * His shape never changes, only how he's posed, so the ~20k vertices (all that trig and
	 * lighting) are worked out once into these and just replayed each frame. Building them every
	 * frame cost about 3 ms per sahur, which piled up with a pet and a few bodies being bonked.
	 */
	private static Mesh body, arm, bat;

	private record Mesh(float[] positions, int[] colors) {
		void draw(VertexConsumer quads, PoseStack.Pose pose) {
			for (int i = 0, p = 0; i < colors.length; i++, p += 3) quads.addVertex(pose, positions[p], positions[p + 1], positions[p + 2]).setColor(colors[i]);
		}
	}

	/** Where {@link #vertex} writes while a mesh is being built; null when drawing straight out. */
	private static it.unimi.dsi.fastutil.floats.FloatArrayList recordPositions;
	private static it.unimi.dsi.fastutil.ints.IntArrayList recordColors;

	private static void buildMeshes() {
		body = record(matrices -> {
			PoseStack.Pose entry = matrices.last();
			drawLog(null, entry);
			drawFace(null, matrices);
			drawLegs(null, entry);
			// Left arm, hanging close to his side
			Vec3 leftHand = new Vec3(-SHOULDER_X - 0.035, SHOULDER - 0.37, 0.04);
			tube(null, entry, new Vec3[]{new Vec3(-SHOULDER_X + 0.02, SHOULDER, 0), new Vec3(-SHOULDER_X - 0.03, SHOULDER - 0.18, 0.01), leftHand},
				new float[]{0.042f, 0.037f, 0.033f}, WOOD, 10);
			hand(null, entry, leftHand, -1);
		});
		arm = record(matrices -> {
			PoseStack.Pose entry = matrices.last();
			tube(null, entry, new Vec3[]{new Vec3(-0.02, 0, 0), new Vec3(0.03, -0.18, 0.0), GRIP}, new float[]{0.042f, 0.037f, 0.033f}, WOOD, 10);
			hand(null, entry, GRIP, 1);
		});
		bat = record(matrices -> {
			PoseStack.Pose entry = matrices.last();
			ellipsoid(null, entry, 0, 0.09, 0, 0.034, 0.016, 0.034, BAT_DARK, 10, 5);
			tube(null, entry, new Vec3[]{new Vec3(0, 0.08, 0), new Vec3(0, -0.2, 0), new Vec3(0, -0.5, 0), new Vec3(0, -0.8, 0), new Vec3(0, -1.04, 0)},
				new float[]{0.024f, 0.028f, 0.045f, 0.066f, 0.07f}, BAT, 14);
		});
	}

	private static Mesh record(java.util.function.Consumer<PoseStack> build) {
		recordPositions = new it.unimi.dsi.fastutil.floats.FloatArrayList();
		recordColors = new it.unimi.dsi.fastutil.ints.IntArrayList();
		try {
			build.accept(new PoseStack());
			return new Mesh(recordPositions.toFloatArray(), recordColors.toIntArray());
		} finally {
			recordPositions = null;
			recordColors = null;
		}
	}

	/** The log: straight round sides, a slightly rounded bottom edge and a flat sawn top with a soft rim. */
	private static void drawLog(VertexConsumer quads, PoseStack.Pose entry) {
		List<float[]> profile = new ArrayList<>();
		// Bottom, closed off in the middle, rounding up into the side
		profile.add(new float[]{LOG_BOTTOM, 0});
		for (int i = 0; i <= 6; i++) {
			double angle = Math.PI / 2 * i / 6;
			profile.add(new float[]{(float) (LOG_BOTTOM + 0.05 * (1 - Math.cos(angle))), (float) (RADIUS - 0.05 + 0.05 * Math.sin(angle))});
		}
		// Up the sides, a touch narrower at the bottom
		for (int i = 1; i <= 30; i++) {
			float y = LOG_BOTTOM + 0.05f + (LOG_TOP - 0.06f - LOG_BOTTOM - 0.05f) * i / 30;
			float t = (y - LOG_BOTTOM) / (LOG_TOP - LOG_BOTTOM);
			profile.add(new float[]{y, RADIUS * (0.95f + 0.05f * Math.min(1, t * 2.5f))});
		}
		// The sawn edge, rounded off, then flat across the top
		for (int i = 1; i <= 6; i++) {
			double angle = Math.PI / 2 * i / 6;
			profile.add(new float[]{(float) (LOG_TOP - 0.06 + 0.06 * Math.sin(angle)), (float) (RADIUS - 0.06 + 0.06 * Math.cos(angle))});
		}
		for (int i = 1; i <= 8; i++) profile.add(new float[]{LOG_TOP, (RADIUS - 0.06f) * (1 - i / 8f)});

		int segments = 48;
		for (int ring = 0; ring < profile.size() - 1; ring++) {
			for (int segment = 0; segment < segments; segment++) {
				double a0 = 2 * Math.PI * segment / segments;
				double a1 = 2 * Math.PI * (segment + 1) / segments;
				logVertex(quads, entry, profile, ring, a0);
				logVertex(quads, entry, profile, ring, a1);
				logVertex(quads, entry, profile, ring + 1, a1);
				logVertex(quads, entry, profile, ring + 1, a0);
			}
		}
	}

	/** One point on the log, coloured by its grain, or the pale end grain on top. */
	private static void logVertex(VertexConsumer quads, PoseStack.Pose entry, List<float[]> profile, int ring, double angle) {
		float y = profile.get(ring)[0];
		float r = profile.get(ring)[1];
		float[] before = profile.get(Math.max(0, ring - 1));
		float[] after = profile.get(Math.min(profile.size() - 1, ring + 1));
		double dy = after[0] - before[0];
		double dr = after[1] - before[1];
		double length = Math.hypot(dy, dr);
		double radial = length == 0 ? 0 : dy / length;
		double up = length == 0 ? 1 : -dr / length;
		double cos = Math.cos(angle), sin = Math.sin(angle);
		Vec3 normal = new Vec3(cos * radial, up, sin * radial);

		int color;
		if (y >= LOG_TOP - 0.001f && r < RADIUS - 0.05f) {
			// Growth rings on the sawn top
			double rings = 0.5 + 0.5 * Math.sin(r * 90);
			color = mix(END_GRAIN, END_RING, (float) (rings * rings * 0.4));
		} else {
			// Long soft streaks running up the log
			double grain = 0.5 + 0.5 * Math.sin(angle * 9 + Math.sin(y * 4) * 0.6 + Math.sin(angle * 2) * 0.8);
			color = mix(WOOD, GRAIN, (float) (Math.pow(grain, 4) * 0.55));
			// The face and belly catch the light
			double front = Math.max(0, sin);
			color = mix(color, WOOD_LIGHT, (float) (front * front * 0.3));
		}
		vertex(quads, entry, (float) (cos * r), y, (float) (sin * r), lit(color, normal));
	}

	/**
	 * The face, kept simple and friendly: two big round eyes popping out of the log, each with a
	 * big black pupil and a glint, thin arched brows, a smooth nose with a round tip, and one
	 * smile line with a soft lower lip. Each eye faces straight out from the log's curve so nothing
	 * sinks into the wood.
	 */
	private static void drawFace(VertexConsumer quads, PoseStack matrices) {
		for (int side = -1; side <= 1; side += 2) {
			double cx = side * EYE_X;
			matrices.pushPose();
			matrices.translate(cx, EYE_Y, surfaceZ(cx));
			matrices.mulPose(Axis.YP.rotation((float) Math.atan2(cx, surfaceZ(cx))));
			PoseStack.Pose eye = matrices.last();
			// A thin lid round the eye so it sits in the wood, then the eyeball popping out
			ring(quads, eye, 0.096, 0.09, 0.012, 0.011f, WOOD_LIGHT);
			ellipsoid(quads, eye, 0, 0, -0.01, 0.094, 0.088, 0.075, EYE_WHITE, 24, 16);
			// Big black pupil looking a little to the side, and a glint
			double look = side * -0.01;
			ellipsoid(quads, eye, look, -0.004, 0.06, 0.048, 0.048, 0.014, PUPIL, 20, 10);
			ellipsoid(quads, eye, look - 0.016, 0.016, 0.072, 0.013, 0.013, 0.004, GLINT, 8, 6);
			// Thin arched brow above
			Vec3[] brow = new Vec3[9];
			float[] browRadius = new float[9];
			for (int i = 0; i < 9; i++) {
				double t = i / 8.0 * 2 - 1;
				brow[i] = new Vec3(t * 0.1, 0.15 + 0.025 * (1 - t * t), 0.006 - t * t * 0.025);
				browRadius[i] = (float) (0.012 - Math.abs(t) * 0.004);
			}
			tube(quads, eye, brow, browRadius, BROW, 8);
			matrices.popPose();
		}
		PoseStack.Pose entry = matrices.last();

		// A smooth nose sloping out from between the eyes to a round tip
		tube(quads, entry, new Vec3[]{new Vec3(0, EYE_Y + 0.02, RADIUS - 0.004), new Vec3(0, EYE_Y - 0.08, RADIUS + 0.014),
			new Vec3(0, EYE_Y - 0.15, RADIUS + 0.03)}, new float[]{0.026f, 0.032f, 0.038f}, WOOD_LIGHT, 12);
		ellipsoid(quads, entry, 0, EYE_Y - 0.17, RADIUS + 0.036, 0.052, 0.044, 0.046, WOOD_LIGHT, 18, 12);

		// One smile line curving up at the ends, with a soft lower lip under the middle
		float width = 0.13f;
		Vec3[] line = new Vec3[15];
		float[] lineRadius = new float[15];
		for (int i = 0; i < 15; i++) {
			double x = -width + 2 * width * i / 14;
			line[i] = new Vec3(x, MOUTH_Y + 2.4 * x * x, surfaceZ(x) + 0.008);
			lineRadius[i] = 0.011f;
		}
		tube(quads, entry, line, lineRadius, MOUTH, 8);
		Vec3[] lip = new Vec3[9];
		float[] lipRadius = new float[9];
		for (int i = 0; i < 9; i++) {
			double t = i / 8.0 * 2 - 1;
			double x = t * 0.075;
			lip[i] = new Vec3(x, MOUTH_Y + 2.4 * x * x - 0.024, surfaceZ(x) + 0.004);
			lipRadius[i] = (float) (0.006 + 0.01 * (1 - t * t));
		}
		tube(quads, entry, lip, lipRadius, WOOD_LIGHT, 8);
	}

	/** A round ring in the xy plane at depth {@code z}, of radius rx by ry, as a tube {@code thickness} thick. */
	private static void ring(VertexConsumer quads, PoseStack.Pose entry, double rx, double ry, double z, float thickness, int color) {
		Vec3[] path = new Vec3[33];
		float[] radii = new float[33];
		for (int i = 0; i < 33; i++) {
			double angle = 2 * Math.PI * i / 32;
			path[i] = new Vec3(Math.cos(angle) * rx, Math.sin(angle) * ry, z);
			radii[i] = thickness;
		}
		tube(quads, entry, path, radii, color, 8);
	}

	/** Long thin legs with knobbly knees, on big bare feet with five toes each. */
	private static void drawLegs(VertexConsumer quads, PoseStack.Pose entry) {
		for (int side = -1; side <= 1; side += 2) {
			double x = side * 0.11;
			tube(quads, entry, new Vec3[]{new Vec3(x, LOG_BOTTOM + 0.05, 0), new Vec3(x + side * 0.004, 0.5, 0.005), new Vec3(x + side * 0.008, 0.1, 0)},
				new float[]{0.062f, 0.054f, 0.046f}, WOOD, 12);
			ellipsoid(quads, entry, x + side * 0.004, 0.5, 0.012, 0.06, 0.064, 0.056, WOOD, 12, 8);
			// The big bare foot, turned out a little: round heel, a long sole widening to the toes
			double turn = side * 0.3;
			double fx = Math.sin(turn), fz = Math.cos(turn);
			Vec3 heel = new Vec3(x + side * 0.012, 0.05, -0.04);
			ellipsoid(quads, entry, heel.x, heel.y, heel.z, 0.055, 0.05, 0.06, WOOD, 12, 8);
			Vec3 middle = heel.add(fx * 0.12, 0.004, fz * 0.12);
			Vec3 ball = heel.add(fx * 0.24, -0.008, fz * 0.24);
			tube(quads, entry, new Vec3[]{heel, middle, ball}, new float[]{0.052f, 0.056f, 0.06f}, WOOD, 12);
			// Flatten the sole onto the ground
			ellipsoid(quads, entry, middle.x, 0.02, middle.z, 0.07, 0.02, 0.15, WOOD, 12, 6);
			// Five toes across the front, the big one on the inside
			for (int toe = 0; toe < 5; toe++) {
				double across = (toe - 2) * 0.027 * -side;
				double length = toe == 0 ? 0.075 : 0.06 - toe * 0.006;
				float r = toe == 0 ? 0.024f : 0.018f - toe * 0.0015f;
				Vec3 base = ball.add(fz * across, 0, -fx * across);
				double reach = 0.045 + length / 2 - Math.abs(toe - 1.5) * 0.008;
				ellipsoid(quads, entry, base.x + fx * reach, r, base.z + fz * reach, r + 0.003, r, length / 2 + r, WOOD_LIGHT, 10, 6);
			}
		}
	}

	/** A small fist with the thumb on the inside. */
	private static void hand(VertexConsumer quads, PoseStack.Pose entry, Vec3 at, int side) {
		ellipsoid(quads, entry, at.x, at.y - 0.03, at.z, 0.036, 0.05, 0.034, WOOD, 10, 8);
		ellipsoid(quads, entry, at.x - side * 0.022, at.y - 0.02, at.z + 0.02, 0.014, 0.026, 0.014, WOOD_LIGHT, 8, 6);
	}

	/** How far forward the log's round surface is at {@code x} across. */
	private static double surfaceZ(double x) {
		return Math.sqrt(Math.max(0, RADIUS * RADIUS - x * x));
	}

	// ---------------------------------------------------------------- mesh building

	/** A smooth ellipsoid with lighting worked out per vertex from its true curved normal. */
	private static void ellipsoid(VertexConsumer quads, PoseStack.Pose entry, double cx, double cy, double cz,
								  double rx, double ry, double rz, int color, int slices, int stacks) {
		for (int stack = 0; stack < stacks; stack++) {
			double p0 = -Math.PI / 2 + Math.PI * stack / stacks;
			double p1 = -Math.PI / 2 + Math.PI * (stack + 1) / stacks;
			for (int slice = 0; slice < slices; slice++) {
				double t0 = 2 * Math.PI * slice / slices;
				double t1 = 2 * Math.PI * (slice + 1) / slices;
				ellipsoidVertex(quads, entry, cx, cy, cz, rx, ry, rz, p0, t0, color);
				ellipsoidVertex(quads, entry, cx, cy, cz, rx, ry, rz, p0, t1, color);
				ellipsoidVertex(quads, entry, cx, cy, cz, rx, ry, rz, p1, t1, color);
				ellipsoidVertex(quads, entry, cx, cy, cz, rx, ry, rz, p1, t0, color);
			}
		}
	}

	private static void ellipsoidVertex(VertexConsumer quads, PoseStack.Pose entry, double cx, double cy, double cz,
										double rx, double ry, double rz, double phi, double theta, int color) {
		double ux = Math.cos(phi) * Math.cos(theta), uy = Math.sin(phi), uz = Math.cos(phi) * Math.sin(theta);
		Vec3 normal = new Vec3(ux / rx, uy / ry, uz / rz).normalize();
		vertex(quads, entry, (float) (cx + rx * ux), (float) (cy + ry * uy), (float) (cz + rz * uz), lit(color, normal));
	}

	/** A smooth tube through {@code path}, with the given radius at each point and rounded ends. */
	private static void tube(VertexConsumer quads, PoseStack.Pose entry, Vec3[] path, float[] radii, int color, int sides) {
		int n = path.length;
		Vec3[][] rings = new Vec3[n][sides];
		Vec3[][] normals = new Vec3[n][sides];
		for (int i = 0; i < n; i++) {
			Vec3 tangent = path[Math.min(n - 1, i + 1)].subtract(path[Math.max(0, i - 1)]).normalize();
			Vec3 reference = Math.abs(tangent.z) > 0.9 ? new Vec3(1, 0, 0) : new Vec3(0, 0, 1);
			Vec3 u = tangent.cross(reference).normalize();
			Vec3 v = tangent.cross(u).normalize();
			for (int side = 0; side < sides; side++) {
				double angle = 2 * Math.PI * side / sides;
				Vec3 out = u.scale(Math.cos(angle)).add(v.scale(Math.sin(angle)));
				normals[i][side] = out;
				rings[i][side] = path[i].add(out.scale(radii[i]));
			}
		}
		for (int i = 0; i < n - 1; i++) {
			for (int side = 0; side < sides; side++) {
				int next = (side + 1) % sides;
				vertex(quads, entry, rings[i][side], lit(color, normals[i][side]));
				vertex(quads, entry, rings[i][next], lit(color, normals[i][next]));
				vertex(quads, entry, rings[i + 1][next], lit(color, normals[i + 1][next]));
				vertex(quads, entry, rings[i + 1][side], lit(color, normals[i + 1][side]));
			}
		}
		// Round both ends off
		for (int end : new int[]{0, n - 1}) {
			ellipsoid(quads, entry, path[end].x, path[end].y, path[end].z, radii[end], radii[end], radii[end], color, sides, 6);
		}
	}

	private static void vertex(VertexConsumer quads, PoseStack.Pose entry, Vec3 at, int color) {
		vertex(quads, entry, (float) at.x, (float) at.y, (float) at.z, color);
	}

	private static void vertex(VertexConsumer quads, PoseStack.Pose entry, float x, float y, float z, int color) {
		if (recordPositions != null) {
			// Into the mesh, in the model's own space (the eyes are posed inside it)
			org.joml.Vector3f at = entry.pose().transformPosition(x, y, z, new org.joml.Vector3f());
			recordPositions.add(at.x);
			recordPositions.add(at.y);
			recordPositions.add(at.z);
			recordColors.add(color);
			return;
		}
		quads.addVertex(entry, x, y, z).setColor(color);
	}

	/** {@code color} shaded by how much the surface faces the light, never fully dark on the far side. */
	private static int lit(int color, Vec3 normal) {
		double facing = normal.dot(LIGHT);
		float light = (float) (0.5 + 0.55 * Math.max(0, facing));
		return shade(color, light);
	}

	private static int mix(int a, int b, float t) {
		int r = (int) Mth.lerpInt(t, (a >> 16) & 0xFF, (b >> 16) & 0xFF);
		int g = (int) Mth.lerpInt(t, (a >> 8) & 0xFF, (b >> 8) & 0xFF);
		int bl = (int) Mth.lerpInt(t, a & 0xFF, b & 0xFF);
		return 0xFF000000 | (r << 16) | (g << 8) | bl;
	}

	private static int shade(int color, float factor) {
		int r = Math.min(255, (int) (((color >> 16) & 0xFF) * factor));
		int g = Math.min(255, (int) (((color >> 8) & 0xFF) * factor));
		int b = Math.min(255, (int) ((color & 0xFF) * factor));
		return (color & 0xFF000000) | (r << 16) | (g << 8) | b;
	}
}
