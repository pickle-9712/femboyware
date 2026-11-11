package dev.femboyware.module.larp;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.femboyware.FemboyWare;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.module.client.FakePlayerModule;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.FxRender;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import com.mojang.authlib.GameProfile;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.StringSetting;
import java.util.UUID;
import net.minecraft.world.entity.Entity;
import java.util.ArrayList;
import net.minecraft.client.player.RemotePlayer;
import dev.femboyware.module.client.FakeWanderer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Paparazzi: a crew of photographers following you round, moving between angles and taking a
 * picture every few seconds, or your fake players doing it, with cameras, flashes, shutter clicks
 * and a faint flash on your screen when one's close.
 */
public class PaparazziModule extends Module {
	private record Flash(Vec3 pos, long at, float size) {}

	private static final long FLASH_LIFE = 220_000_000L;

	public enum Source { OWN_CREW, FAKE_PLAYERS }

	private final EnumSetting<Source> source = add(new EnumSetting<>("Paparazzi", "Own Crew spawns photographers of their own who follow you round; Fake Players turns your fake players into them", Source.OWN_CREW));
	private final NumberSetting crewSize = add(new NumberSetting("Crew Size", "How many photographers", 5, 1, 12, 1)).visibleWhen(() -> source.is(Source.OWN_CREW));
	private final StringSetting crewNames = add(new StringSetting("Crew Names", "Their names, separated by commas; each wears that account's real skin. Empty picks real accounts from Name Protect's Fake Names", "")).visibleWhen(() -> source.is(Source.OWN_CREW));
	private final NumberSetting crewRadius = add(new NumberSetting("Crew Distance", "How far round you they stand, in blocks", 5, 2.5, 12, 0.5)).visibleWhen(() -> source.is(Source.OWN_CREW));
	private final NumberSetting shotEvery = add(new NumberSetting("Shot Every", "About how many seconds each photographer waits between pictures", 3.5, 1, 20, 0.5));
	private final NumberSetting moveEvery = add(new NumberSetting("Move Every", "About how many seconds before a photographer walks somewhere new for another angle", 7, 2, 30, 0.5)).visibleWhen(() -> source.is(Source.OWN_CREW));
	private final BoolSetting holdCameras = add(new BoolSetting("Hold Cameras", "They hold a camera up to their face, aimed at you, and the flashes come out of it", true));
	private final BoolSetting sound = add(new BoolSetting("Sound", "Shutter clicks", true));
	private final BoolSetting screenFlash = add(new BoolSetting("Screen Flash", "A faint flash over your screen when a camera goes off close by", true));

	private final List<Flash> flashes = new ArrayList<>();
	private final Random random = new Random();
	private long lastScreenFlash;

	/** Each photographer's own timing, and for the crew where round you they're heading and their legs. */
	private static final class Shooter {
		final FakeWanderer walker = new FakeWanderer();
		double angle, distance;
		int nextMove, nextShot, burst;
		/** Rather shoots a parked car than you, when there's one about. */
		boolean carFan;
	}

	private final Map<Integer, Shooter> shooters = new HashMap<>();

	public PaparazziModule() {
		super("Paparazzi", "Photographers round you: cameras up, flashes and shutter clicks", Category.LARP);
		LivingEntityFeatureRendererRegistrationCallback.EVENT.register((type, renderer, helper, context) -> {
			if (renderer instanceof AvatarRenderer<?> player) helper.register(new CameraFeature(asContext(player)));
		});
	}

	@SuppressWarnings("unchecked")
	private static RenderLayerParent<AvatarRenderState, PlayerModel> asContext(AvatarRenderer<?> renderer) {
		return (RenderLayerParent<AvatarRenderState, PlayerModel>) (Object) renderer;
	}

	/** Whether this player is a fake player holding a camera. */
	private boolean holdsCamera(AvatarRenderState state) {
		if (!isEnabled() || !holdCameras.get()) return false;
		if (source.is(Source.OWN_CREW)) {
			for (RemotePlayer member : crew) {
				if (member.getId() == state.id) return true;
			}
			return false;
		}
		return FemboyWare.getModules().get(FakePlayerModule.class).isFake(state.id);
	}

	/** Read by PlayerModelMixin: fake players hold the camera up to their eye with both hands. */
	public void pose(PlayerModel model, AvatarRenderState state) {
		if (!holdsCamera(state)) return;
		model.rightArm.xRot = model.head.xRot - 1.55f;
		model.rightArm.yRot = model.head.yRot - 0.35f;
		model.rightArm.zRot = 0;
		model.leftArm.xRot = model.head.xRot - 1.45f;
		model.leftArm.yRot = model.head.yRot + 0.55f;
		model.leftArm.zRot = 0;
	}

	private static final String[] CREW_NAMES = {"TMZ_Mike", "FlashFrank", "ShutterBug", "LensKing", "PressPass", "SnapSam", "ZoomZoe", "CandidCarl",
		"PhotoPete", "Paparazzo", "FocusFred", "ClickClick"};
	private static final int FIRST_CREW_ID = -20000;

	/** Photographers of our own, following you round and moving between angles. */
	private final List<RemotePlayer> crew = new ArrayList<>();
	private String crewFor = "";

	/** Whoever's taking the pictures right now. */
	private List<? extends Player> photographers() {
		if (source.is(Source.OWN_CREW)) return crew;
		return FemboyWare.getModules().get(FakePlayerModule.class).fakeEntities();
	}

	/** Spawns the crew, or again round you if their number, distance or world changed. */
	private void syncCrew() {
		if (!source.is(Source.OWN_CREW)) {
			despawnCrew();
			return;
		}
		String wanted = crewSize.getInt() + "/" + crewRadius.get() + "/" + crewNames.get() + "/" + System.identityHashCode(mc.level);
		boolean gone = false;
		for (RemotePlayer member : crew) gone |= member.isRemoved() || member.level() != mc.level;
		if (wanted.equals(crewFor) && !gone) return;
		despawnCrew();
		crewFor = wanted;
		Vec3 center = mc.player.position();
		int count = crewSize.getInt();
		double offset = random.nextDouble() * Math.PI * 2;
		List<String> names = crewNameList(count);
		for (int i = 0; i < count; i++) {
			double angle = offset + Math.PI * 2 * i / count;
			Shooter shooter = new Shooter();
			shooter.angle = angle;
			shooter.carFan = i % 3 == 1;
			shooter.distance = crewRadius.get();
			shooter.nextMove = mc.player.tickCount + 40 + random.nextInt(Math.max(1, (int) (moveEvery.get() * 20)));
			shooter.nextShot = mc.player.tickCount + 10 + random.nextInt(Math.max(1, (int) (shotEvery.get() * 20)));
			shooters.put(FIRST_CREW_ID - i, shooter);
			Vec3 at = center.add(Math.cos(angle) * crewRadius.get(), 0, Math.sin(angle) * crewRadius.get());
			String name = names.get(i % names.size());
			RemotePlayer member = new RemotePlayer(mc.level, new GameProfile(UUID.nameUUIDFromBytes(("femboyware paparazzi " + name).getBytes()), name));
			member.setId(FIRST_CREW_ID - i);
			member.snapTo(at.x, at.y, at.z, 0, 0);
			mc.level.addEntity(member);
			crew.add(member);
		}
	}

	/** The names you typed, or real accounts' names from Name Protect's list, or our own made-up ones if that's empty. */
	private List<String> crewNameList(int count) {
		List<String> names = new ArrayList<>();
		for (String part : crewNames.get().split(",")) {
			if (!part.isBlank()) names.add(part.trim());
		}
		if (names.isEmpty()) {
			if (pickedNames.size() < count) {
				pickedNames.clear();
				List<String> pool = FemboyWare.getModules().get(dev.femboyware.module.utility.NameProtectModule.class).unusedFakeNames();
				java.util.Collections.shuffle(pool, random);
				pickedNames.addAll(pool.subList(0, Math.min(count, pool.size())));
			}
			names.addAll(pickedNames);
		}
		if (names.isEmpty()) names.addAll(List.of(CREW_NAMES));
		return names;
	}

	/** Names picked for the crew from Name Protect, kept so they don't change each time it respawns. */
	private final List<String> pickedNames = new ArrayList<>();

	/** Called from AbstractClientPlayerEntityMixin: a photographer wears the real skin of the account it's named after. */
	public net.minecraft.world.entity.player.PlayerSkin skinFor(net.minecraft.client.player.AbstractClientPlayer player, net.minecraft.world.entity.player.PlayerSkin textures) {
		if (!isEnabled() || !crew.contains(player)) return textures;
		net.minecraft.world.entity.player.PlayerSkin account = dev.femboyware.util.AccountSkins.get(player.getGameProfile().name());
		return account != null ? account : textures;
	}

	private void despawnCrew() {
		for (RemotePlayer member : crew) {
			if (mc.level != null && member.level() == mc.level) mc.level.removeEntity(member.getId(), Entity.RemovalReason.DISCARDED);
		}
		for (RemotePlayer member : crew) shooters.remove(member.getId());
		crew.clear();
		crewFor = "";
	}

	@Override
	public void onTick() {
		syncCrew();
		int now = mc.player.tickCount;
		boolean ownCrew = source.is(Source.OWN_CREW);
		for (Player photographer : photographers()) {
			Shooter shooter = shooters.computeIfAbsent(photographer.getId(), id -> {
				Shooter fresh = new Shooter();
				fresh.nextShot = now + 10 + random.nextInt(Math.max(1, (int) (shotEvery.get() * 20)));
				return fresh;
			});
			boolean settled = true;
			if (ownCrew && photographer instanceof RemotePlayer member) {
				// Every so often, off to a new angle a little way round you
				if (now >= shooter.nextMove) {
					shooter.angle += (random.nextBoolean() ? 1 : -1) * (0.45 + random.nextDouble() * 0.8);
					shooter.distance = crewRadius.get() * (0.75 + random.nextDouble() * 0.5);
					shooter.nextMove = now + (int) (moveEvery.get() * 20 * (0.6 + random.nextDouble() * 0.8));
				}
				// Your subject: you, or for the car fans a parked car nearby (a Jesko first) when there is one
				Vec3 car = shooter.carFan ? FemboyWare.getModules().get(CarsModule.class).nearestCar(member.position(), 40) : null;
				Vec3 subject = car != null ? car : mc.player.position();
				double reach = car != null ? 3.5 : shooter.distance;
				Vec3 spot = subject.add(Math.cos(shooter.angle) * reach, 0, Math.sin(shooter.angle) * reach);
				Vec3 to = subject.subtract(member.position());
				float facing = (float) Math.toDegrees(Math.atan2(-to.x, to.z));
				// The walker moves and turns them like a real player, so their head and body never fight it
				shooter.walker.tickFormation(member, mc.level, spot, facing);
				settled = member.position().distanceToSqr(spot) < 0.5 * 0.5;
			}
			// Pictures only once they've stopped, every few seconds, now and then a quick burst
			if (settled && now >= shooter.nextShot) {
				flash(photographer, System.nanoTime());
				if (shooter.burst > 0) {
					shooter.burst--;
					shooter.nextShot = now + 3 + random.nextInt(3);
				} else {
					shooter.burst = random.nextFloat() < 0.3f ? 1 + random.nextInt(2) : 0;
					shooter.nextShot = now + (int) (shotEvery.get() * 20 * (0.6 + random.nextDouble() * 0.8));
				}
			}
		}
	}

	private static final Identifier CAMERA = Identifier.fromNamespaceAndPath("femboyware", "textures/larp/camera.png");

	/** A camera in a fake player's right hand: black body, flash on top, a lens pointing where they look. */
	private class CameraFeature extends RenderLayer<AvatarRenderState, PlayerModel> {
		CameraFeature(RenderLayerParent<AvatarRenderState, PlayerModel> context) {
			super(context);
		}

		@Override
		public void submit(PoseStack matrices, SubmitNodeCollector queue, int light, AvatarRenderState state, float headYaw, float headPitch) {
			if (!holdsCamera(state)) return;
			matrices.pushPose();
			getParentModel().rightArm.translateAndRotate(matrices);
			queue.submitCustomGeometry(matrices, cutout(), (pose, c) -> drawCamera(pose, c, light));
			matrices.popPose();
		}
	}

	private static net.minecraft.client.renderer.rendertype.RenderType cutout() {
		//? if >=26.1 {
		/*return net.minecraft.client.renderer.rendertype.RenderTypes.entityCutout(CAMERA);
		*///?} else {
		return net.minecraft.client.renderer.rendertype.RenderTypes.entityCutoutNoCull(CAMERA);
		//?}
	}

	/**
	 * Right arm space: the arm runs down +y to the hand, held out in front so +y is forward and -z
	 * up. The body sits across the hand toward the face (+x), the lens sticks out forward.
	 */
	private static void drawCamera(PoseStack.Pose pose, VertexConsumer c, int light) {
		float[] body = {0, 0, 0.5f, 0.5f}, lens = {0.5f, 0, 1, 0.5f}, metal = {0, 0.5f, 0.5f, 1}, glass = {0.5f, 0.5f, 1, 1};
		cuboid(pose, c, light, -0.1f, 0.56f, -0.22f, 0.26f, 0.72f, -0.02f, body);
		cuboid(pose, c, light, 0.0f, 0.72f, -0.18f, 0.14f, 0.82f, -0.05f, lens);
		cuboid(pose, c, light, 0.02f, 0.821f, -0.165f, 0.12f, 0.823f, -0.065f, glass);
		cuboid(pose, c, light, -0.08f, 0.58f, -0.27f, 0.02f, 0.66f, -0.22f, metal);
	}

	private static void cuboid(PoseStack.Pose pose, VertexConsumer c, int light, float x1, float y1, float z1, float x2, float y2, float z2, float[] uv) {
		float[][][] faces = {
			{{x1, y1, z1}, {x2, y1, z1}, {x2, y2, z1}, {x1, y2, z1}},
			{{x2, y1, z2}, {x1, y1, z2}, {x1, y2, z2}, {x2, y2, z2}},
			{{x1, y1, z2}, {x1, y1, z1}, {x1, y2, z1}, {x1, y2, z2}},
			{{x2, y1, z1}, {x2, y1, z2}, {x2, y2, z2}, {x2, y2, z1}},
			{{x1, y1, z2}, {x2, y1, z2}, {x2, y1, z1}, {x1, y1, z1}},
			{{x1, y2, z1}, {x2, y2, z1}, {x2, y2, z2}, {x1, y2, z2}}};
		float[][] normals = {{0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}};
		float[][] corners = {{uv[0], uv[1]}, {uv[2], uv[1]}, {uv[2], uv[3]}, {uv[0], uv[3]}};
		for (int f = 0; f < 6; f++) {
			for (int k = 0; k < 4; k++) {
				c.addVertex(pose, faces[f][k][0], faces[f][k][1], faces[f][k][2]).setColor(0xFFFFFFFF).setUv(corners[k][0], corners[k][1])
					.setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, normals[f][0], normals[f][1], normals[f][2]);
			}
		}
	}

	@Override
	protected void onDisable() {
		despawnCrew();
		flashes.clear();
		shooters.clear();
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		long now = System.nanoTime();
		flashes.removeIf(f -> now - f.at > FLASH_LIFE);
		if (flashes.isEmpty()) return;
		Vec3 cam = event.camera().pos;
		VertexConsumer consumer = FxRender.textured(event, FxRender.GLOW, true, false);
		for (Flash f : flashes) {
			float age = (now - f.at) / (float) FLASH_LIFE;
			// A hard pop, then a quick fade
			float strength = age < 0.15f ? 1 : 1 - (age - 0.15f) / 0.85f;
			float x = (float) (f.pos.x - cam.x), y = (float) (f.pos.y - cam.y), z = (float) (f.pos.z - cam.z);
			FxRender.billboard(event, consumer, x, y, z, f.size * 2.4f, 0, FxRender.fade(0xFFDDEEFF, strength * 0.55f));
			FxRender.billboard(event, consumer, x, y, z, f.size, 0, FxRender.fade(0xFFFFFFFF, strength));
			// A star of light across it
			FxRender.billboard(event, consumer, x, y, z, f.size * 0.35f, 0, FxRender.fade(0xFFFFFFFF, strength));
		}
	}

	/** A photographer takes a picture of you. */
	private void flash(Player fake, long now) {
		Vec3 look = fake.getViewVector(1);
		// Out of the camera's lens when they're holding one, a bit in front of their face otherwise
		Vec3 where = fake.getEyePosition().add(look.scale(holdCameras.get() ? 0.75 : 0.4)).add(0, holdCameras.get() ? 0.08 : 0, 0);
		flashes.add(new Flash(where, now, 0.18f + random.nextFloat() * 0.1f));
		double distance = where.distanceTo(mc.player.getEyePosition());
		if (sound.get()) {
			float volume = (float) Math.max(0.12, 0.6 - distance * 0.03);
			LarpSounds.play(LarpSounds.SHUTTER, 0.95f + random.nextFloat() * 0.1f, volume * 1.4f);
		}
		if (distance < 8) lastScreenFlash = System.currentTimeMillis();
	}

	@Override
	public void onRender2D(GuiGraphics context, float tickDelta) {
		if (!screenFlash.get()) return;
		long age = System.currentTimeMillis() - lastScreenFlash;
		if (age > 160) return;
		int alpha = (int) (60 * (1 - age / 160f));
		context.fill(0, 0, context.guiWidth(), context.guiHeight(), alpha << 24 | 0xFFFFFF);
	}
}
