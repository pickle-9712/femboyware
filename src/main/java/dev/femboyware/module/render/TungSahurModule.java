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

	private static final int WOOD = 0xFFA0602E;
	private static final int WOOD_LIGHT = 0xFFBE7E48;
	private static final int WOOD_DARK = 0xFF6A3A18;
	private static final int GRAIN = 0xFF7E451E;
	private static final int SOCKET = 0xFF45240E;
	private static final int EYE_WHITE = 0xFFF4EBDD;
	private static final int IRIS = 0xFF5A3418;
	private static final int PUPIL = 0xFF140A05;
	private static final int MOUTH = 0xFF3A1A0A;
	private static final int BAT = 0xFFD2A066;
	private static final int BAT_HANDLE = 0xFFB07A42;
	/** The log's radius and where its straight part ends; the domed top adds a little more. */
	private static final float RADIUS = 0.33f;
	private static final float BODY_TOP = 2.3f;
	private static final float DOME = 0.25f;
	/** Shoulder height the bat arm swings from. */
	private static final float SHOULDER = 1.72f;
	private static final float SHOULDER_X = 0.32f;
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
			drawSahur(event, sahur.subtract(camera), yaw, swingYaw, armAngle(elapsed, impact), size * (pop < 1 ? pop * (1.15f - 0.15f * pop) : 1));

			if (words.get()) drawWords(event, entry.getValue(), elapsed, victimPos.add(0, victim.getBbHeight() + 0.4, 0));
		}
	}

	/** The bat arm's angle: wind up behind, swing down onto their head, settle. Degrees about the shoulder. */
	private float armAngle(long elapsed, float impact) {
		if (elapsed >= bonkingTime()) return -15;
		float swing = swingTime();
		float phase = (elapsed % (long) swing) / swing;
		// Slow wind-up over his head, a fast accelerating strike, then a little bounce back off the head
		if (phase < 0.4f) return Mth.lerp(ease(phase / 0.4f), -15f, 160f);
		if (phase < IMPACT) {
			float t = (phase - 0.4f) / (IMPACT - 0.4f);
			return Mth.lerp(t * t, 160f, impact);
		}
		float t = (phase - IMPACT) / (1 - IMPACT);
		float recoil = (float) Math.sin(t * Math.PI) * 25;
		return Mth.lerp(ease(t), impact, -15f) + recoil;
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
	 * Tung Tung Tung Sahur, all smooth curved surfaces: a tall round log with a domed top, huge
	 * staring eyes in dark sockets under heavy brows, a long nose, a wide grin, long thin arms and
	 * legs, and a tapered bat in his right hand. Lit from one direction for a solid 3D look.
	 */
	private void drawSahur(Render3DEvent event, Vec3 at, float yaw, float swingYaw, float armAngle, float size) {
		PoseStack matrices = event.matrices();
		VertexConsumer quads = event.consumers().getBuffer(RenderTypes.debugQuads());
		matrices.pushPose();
		matrices.translate(at.x, at.y, at.z);
		matrices.mulPose(Axis.YP.rotation(yaw));
		matrices.scale(size, size, size);
		PoseStack.Pose entry = matrices.last();

		drawBody(quads, entry);
		drawFace(quads, entry);
		drawLegs(quads, entry);
		// Left arm hangs loosely down his side
		tube(quads, entry, new Vec3[]{new Vec3(-0.32, SHOULDER, 0), new Vec3(-0.4, 1.42, 0.02), new Vec3(-0.42, 1.1, 0.04), new Vec3(-0.41, 0.87, 0.05)},
			new float[]{0.055f, 0.048f, 0.045f, 0.042f}, WOOD, 12);
		ellipsoid(quads, entry, -0.32, SHOULDER, 0, 0.062, 0.062, 0.062, WOOD, 12, 8);
		ellipsoid(quads, entry, -0.41, 0.8, 0.05, 0.05, 0.078, 0.046, WOOD_DARK, 12, 8);

		// Right arm and bat, swung from the shoulder
		matrices.pushPose();
		matrices.translate(SHOULDER_X, SHOULDER, 0);
		matrices.mulPose(Axis.YP.rotation(swingYaw));
		matrices.mulPose(Axis.XP.rotationDegrees(armAngle));
		PoseStack.Pose arm = matrices.last();
		ellipsoid(quads, arm, 0, 0, 0, 0.062, 0.062, 0.062, WOOD, 12, 8);
		tube(quads, arm, new Vec3[]{new Vec3(0, 0, 0), new Vec3(0.03, -0.38, 0.01), new Vec3(0.04, -0.72, 0.02)},
			new float[]{0.055f, 0.048f, 0.044f}, WOOD, 12);
		ellipsoid(quads, arm, 0.04, -0.79, 0.02, 0.056, 0.076, 0.05, WOOD_DARK, 12, 8);
		// The bat: a knob, a thin handle, then a barrel swelling to a rounded end
		ellipsoid(quads, arm, 0.04, -0.71, 0.02, 0.046, 0.022, 0.046, BAT_HANDLE, 12, 6);
		tube(quads, arm, new Vec3[]{new Vec3(0.04, -0.72, 0.02), new Vec3(0.04, -1.0, 0.02), new Vec3(0.04, -1.3, 0.02),
			new Vec3(0.04, -1.6, 0.02), new Vec3(0.04, -1.82, 0.02)}, new float[]{0.026f, 0.03f, 0.045f, 0.06f, 0.062f}, BAT, 14);
		ellipsoid(quads, arm, 0.04, -1.82, 0.02, 0.062, 0.035, 0.062, BAT, 14, 6);
		matrices.popPose();

		matrices.popPose();
	}

	/**
	 * The log, round all over: a rounded bottom, a slight barrel belly, straight where the face is,
	 * and a full dome on top, turned on a lathe.
	 */
	private static void drawBody(VertexConsumer quads, PoseStack.Pose entry) {
		List<float[]> profile = new ArrayList<>();
		// The rounded bottom, closed off in the middle
		float bottom = 0.4f, bottomRise = 0.3f;
		for (int i = 0; i <= 10; i++) {
			double angle = Math.PI / 2 * i / 10;
			profile.add(new float[]{(float) (bottom + bottomRise * (1 - Math.cos(angle))), (float) (RADIUS * Math.sin(angle))});
		}
		// Up the sides, swelling a little through the belly and back in before the face
		float sideStart = bottom + bottomRise;
		for (int i = 1; i <= 28; i++) {
			float y = sideStart + (BODY_TOP - sideStart) * i / 28;
			double t = Mth.clamp((y - sideStart) / (1.6 - sideStart), 0, 1);
			double belly = Math.pow(Math.sin(Math.PI * t), 2) * 0.075;
			profile.add(new float[]{y, (float) (RADIUS * (1 + belly))});
		}
		// The domed top
		for (int i = 1; i <= 12; i++) {
			double angle = Math.PI / 2 * i / 12;
			profile.add(new float[]{(float) (BODY_TOP + DOME * Math.sin(angle)), (float) (RADIUS * Math.cos(angle))});
		}
		int segments = 48;
		for (int ring = 0; ring < profile.size() - 1; ring++) {
			float[] lower = profile.get(ring);
			float[] upper = profile.get(ring + 1);
			for (int segment = 0; segment < segments; segment++) {
				double a0 = 2 * Math.PI * segment / segments;
				double a1 = 2 * Math.PI * (segment + 1) / segments;
				latheVertex(quads, entry, profile, ring, a0);
				latheVertex(quads, entry, profile, ring, a1);
				latheVertex(quads, entry, profile, ring + 1, a1);
				latheVertex(quads, entry, profile, ring + 1, a0);
			}
		}
	}

	/** One point on the log, coloured by the grain, the lighter face and the end grain on top. */
	private static void latheVertex(VertexConsumer quads, PoseStack.Pose entry, List<float[]> profile, int ring, double angle) {
		float y = profile.get(ring)[0];
		float r = profile.get(ring)[1];
		// The surface's slope from its neighbours along the profile gives the normal
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
		if (y > BODY_TOP + 0.01f) {
			// End grain: rings round the middle of the dome
			double rings = 0.5 + 0.5 * Math.sin(r * 70);
			color = mix(WOOD_LIGHT, GRAIN, (float) (rings * rings * 0.35));
		} else {
			double grain = 0.5 + 0.5 * Math.sin(angle * 11 + Math.sin(y * 7) * 0.8 + Math.sin(angle * 3) * 0.6);
			color = mix(WOOD, GRAIN, (float) (Math.pow(grain, 3) * 0.75));
			// Finer streaks between the bigger ones
			double fine = 0.5 + 0.5 * Math.sin(angle * 37 + Math.sin(y * 13) * 0.5);
			color = mix(color, WOOD_DARK, (float) (Math.pow(fine, 6) * 0.3));
			// The face area catches more light, as in the meme
			double front = Math.max(0, sin);
			double band = Mth.clamp((y - 1.45) / 0.25, 0, 1) * Mth.clamp((2.3 - y) / 0.15, 0, 1);
			color = mix(color, WOOD_LIGHT, (float) (front * front * band * 0.45));
		}
		vertex(quads, entry, (float) (cos * r), y, (float) (sin * r), lit(color, normal));
	}

	/** Eyes, brows, nose, cheeks and grin, set into the front of the log. */
	private static void drawFace(VertexConsumer quads, PoseStack.Pose entry) {
		for (int side = -1; side <= 1; side += 2) {
			double cx = side * 0.152;
			double surface = surfaceZ(cx);
			// Socket, then the big eyeball bulging out of it, iris, pupil and a glint
			ellipsoid(quads, entry, cx, 1.99, surface - 0.035, 0.152, 0.168, 0.06, SOCKET, 20, 12);
			ellipsoid(quads, entry, cx, 1.99, surface - 0.01, 0.128, 0.14, 0.072, EYE_WHITE, 20, 12);
			double front = surface + 0.062;
			ellipsoid(quads, entry, cx - side * 0.008, 1.985, front - 0.006, 0.052, 0.056, 0.016, IRIS, 16, 8);
			ellipsoid(quads, entry, cx - side * 0.008, 1.985, front + 0.002, 0.03, 0.033, 0.012, PUPIL, 14, 8);
			ellipsoid(quads, entry, cx - side * 0.008 - 0.026, 2.018, front + 0.012, 0.014, 0.014, 0.008, EYE_WHITE, 8, 6);
			// Heavy brow, curving over the eye and dipping towards the nose
			Vec3[] brow = new Vec3[7];
			float[] browRadius = new float[7];
			for (int i = 0; i < 7; i++) {
				double t = i / 6.0 * 2 - 1;
				double x = cx + t * 0.16;
				double inner = side * t < 0 ? (-side * t) * 0.035 : 0;
				brow[i] = new Vec3(x, 2.185 + 0.035 * (1 - t * t) - inner, surfaceZ(x) + 0.02);
				browRadius[i] = 0.03f - (float) Math.abs(t) * 0.008f;
			}
			tube(quads, entry, brow, browRadius, WOOD_DARK, 10);
			// Round cheeks beside the grin
			ellipsoid(quads, entry, side * 0.215, 1.66, surfaceZ(0.2) - 0.012, 0.085, 0.072, 0.05, WOOD_LIGHT, 14, 10);
			// Nostril
			ellipsoid(quads, entry, side * 0.032, 1.674, RADIUS + 0.082, 0.02, 0.011, 0.013, SOCKET, 8, 6);
		}
		// Long nose ending in a rounded bulb
		ellipsoid(quads, entry, 0, 1.83, RADIUS + 0.025, 0.055, 0.13, 0.07, WOOD, 16, 12);
		ellipsoid(quads, entry, 0, 1.71, RADIUS + 0.065, 0.077, 0.058, 0.062, WOOD_LIGHT, 16, 12);

		// The grin: a dark curve following the log round, curling up at both ends, with a lit lower lip
		Vec3[] smile = new Vec3[13];
		float[] smileRadius = new float[13];
		for (int i = 0; i < 13; i++) {
			double x = -0.245 + 0.49 * i / 12;
			smile[i] = new Vec3(x, 1.55 + 1.5 * x * x, surfaceZ(x) + 0.006);
			smileRadius[i] = 0.017f;
		}
		tube(quads, entry, smile, smileRadius, MOUTH, 8);
		Vec3[] lip = new Vec3[9];
		float[] lipRadius = new float[9];
		for (int i = 0; i < 9; i++) {
			double x = -0.15 + 0.3 * i / 8;
			lip[i] = new Vec3(x, 1.515 + 1.2 * x * x, surfaceZ(x) + 0.004);
			lipRadius[i] = 0.015f;
		}
		tube(quads, entry, lip, lipRadius, WOOD_LIGHT, 8);
	}

	private static void drawLegs(VertexConsumer quads, PoseStack.Pose entry) {
		for (int side = -1; side <= 1; side += 2) {
			double x = side * 0.11;
			tube(quads, entry, new Vec3[]{new Vec3(x, 0.62, 0), new Vec3(x + side * 0.005, 0.33, 0.01), new Vec3(x, 0.07, 0.02)},
				new float[]{0.06f, 0.05f, 0.045f}, WOOD, 12);
			ellipsoid(quads, entry, x, 0.045, 0.06, 0.065, 0.045, 0.11, WOOD_DARK, 12, 8);
		}
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
				tubeVertex(quads, entry, rings[i][side], normals[i][side], color);
				tubeVertex(quads, entry, rings[i][next], normals[i][next], color);
				tubeVertex(quads, entry, rings[i + 1][next], normals[i + 1][next], color);
				tubeVertex(quads, entry, rings[i + 1][side], normals[i + 1][side], color);
			}
		}
		// Round both ends off
		for (int end : new int[]{0, n - 1}) {
			ellipsoid(quads, entry, path[end].x, path[end].y, path[end].z, radii[end], radii[end], radii[end], color, sides, 6);
		}
	}

	private static void tubeVertex(VertexConsumer quads, PoseStack.Pose entry, Vec3 position, Vec3 normal, int color) {
		vertex(quads, entry, (float) position.x, (float) position.y, (float) position.z, lit(color, normal));
	}

	private static void vertex(VertexConsumer quads, PoseStack.Pose entry, float x, float y, float z, int color) {
		quads.addVertex(entry, x, y, z).setColor(color);
	}

	/** {@code color} shaded by how much the surface faces the light, never fully black on the dark side. */
	private static int lit(int color, Vec3 normal) {
		double facing = normal.dot(LIGHT);
		float light = (float) (0.42 + 0.58 * Math.max(0, facing));
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
