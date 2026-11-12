package dev.femboyware.module.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.femboyware.FemboyWare;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.manager.CommandManager;
import dev.femboyware.setting.StringSetting;
import dev.femboyware.util.ChatUtil;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.module.client.FakePlayerModule;
import dev.femboyware.module.effects.PlayerFxModule.ShowOn;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.FxRender;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback;
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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Roblox larp cosmetics: the R6 Korblox leg, headless, and fiery, frozen or poisoned horns. The leg
 * and horns are the real Roblox meshes, converted to Minecraft model space (see models/*.bin).
 */
public class CosmeticsModule extends Module {
	public enum Horns {
		OFF(null, 0, 0, 0),
		FIERY("horns_fiery", 0xFFFFF070, 0xFFFF5A10, 0xFFFFD060),
		FROZEN("horns_frozen", 0xFFC8FAFF, 0xFF2AA8E8, 0xFFB8F4FF),
		POISONED("horns_poisoned", 0xFFF0FF60, 0xFF60F020, 0xFFC8FF90);

		final Identifier texture;
		/** The fire's colour where it starts, the colour it fades to as it rises, and its sparks. */
		final int core, outer, spark;

		Horns(String texture, int core, int outer, int spark) {
			this.texture = texture == null ? null : tex(texture);
			this.core = core;
			this.outer = outer;
			this.spark = spark;
		}
	}

	private static final Identifier KORBLOX = tex("korblox");
	/** The exported leg fills the whole Minecraft leg; Roblox's is 0.86 studs of a 2-stud leg, so 0.43 of that. */
	private static final float KORBLOX_SCALE = 0.43f;
	private static final float DEFAULT_HORN_SIZE = 0.28f;
	private static Mesh korbloxMesh, hornsMesh;
	private static final int MAX_FIRES = 64;
	private final List<FireSpot> fires = new ArrayList<>();

	private final BoolSetting korblox = add(new BoolSetting("Korblox", "Swap your right leg for the Korblox leg", true));
	private final BoolSetting headless = add(new BoolSetting("Headless", "No head", true));
	private final EnumSetting<Horns> horns = add(new EnumSetting<>("Horns", "Fiery, frozen or poisoned horns", Horns.FIERY));
	private final NumberSetting hornSize = add(new NumberSetting("Horn Size", "How big the horns are", DEFAULT_HORN_SIZE, 0.15, 0.5, 0.01)).visibleWhen(() -> !horns.is(Horns.OFF));
	private final BoolSetting fire = add(new BoolSetting("Fire", "A big fire burning between the horns", true)).visibleWhen(() -> !horns.is(Horns.OFF));
	private final EnumSetting<ShowOn> showOn = add(new EnumSetting<>("Show On", "You, you and your friends, or every player", ShowOn.YOU));
	private final EnumSetting<Watch> watch = add(new EnumSetting<>("Watch", "A watch on your left wrist", Watch.ICED));
	private final BoolSetting onFakes = add(new BoolSetting("Fake Players", "Fake players wear your cosmetics too, each with its own random horns", true));
	/** Who you gave the cosmetics to with .cos give, as name:horns pairs. Only saved, never shown. */
	private final StringSetting givenTo = add(new StringSetting("Given To", "", "")).visibleWhen(() -> false);
	private String givenParsedFrom;
	private Map<String, String> givenParsed = Map.of();

	public CosmeticsModule() {
		super("Cosmetics", "Korblox leg, headless and Roblox horns", Category.LARP);
		LivingEntityFeatureRendererRegistrationCallback.EVENT.register((type, renderer, helper, context) -> {
			if (renderer instanceof AvatarRenderer<?> player) helper.register(new Feature(asContext(player)));
		});
	}

	private static Identifier tex(String name) {
		return Identifier.fromNamespaceAndPath("femboyware", "textures/cosmetics/" + name + ".png");
	}

	@SuppressWarnings("unchecked")
	private static RenderLayerParent<AvatarRenderState, PlayerModel> asContext(AvatarRenderer<?> renderer) {
		return (RenderLayerParent<AvatarRenderState, PlayerModel>) (Object) renderer;
	}

	@Override
	public String getInfo() {
		return horns.is(Horns.OFF) ? null : horns.getDisplayName();
	}

	// ---------------------------------------------------------------- Client Sync

	/** What someone's wearing: the leg, the head, their horns and fire, and their watch. */
	public record Worn(boolean korblox, boolean headless, Horns horns, float hornSize, boolean fire, Watch watch) {}

	/** For Client Sync: what you're wearing, or null with this off. */
	public Worn shareWorn() {
		if (!isEnabled()) return null;
		return new Worn(korblox.get(), headless.get(), horns.get(), hornSize.get().floatValue(), fire.get(), watch.get());
	}

	/** For Client Sync: the horns one of your fake players wears (each has its own), or null if it wears none of yours. */
	public Horns fakeHorns(Entity fake) {
		if (!isEnabled() || !onFakes.get()) return null;
		return horns.get() == Horns.OFF ? Horns.OFF : randomHorns(fake);
	}

	/** Whether your fake players wear your cosmetics, so other people's copies of them do too. */
	public boolean sharesWithFakes() {
		return onFakes.get();
	}

	/** What another femboyware user (or one of their fake players) is wearing, from Client Sync; null if not shared. */
	private Worn peer(AvatarRenderState state) {
		return mc.player == null || state.id == mc.player.getId() ? null
			: FemboyWare.getModules().get(dev.femboyware.module.client.ClientSyncModule.class).cosmeticsOf(state.id);
	}

	private boolean wears(AvatarRenderState state) {
		if (mc.player != null && !state.isInvisible && peer(state) != null) return true;
		if (!isEnabled() || mc.player == null || mc.level == null || state.isInvisible) return false;
		if (state.id == mc.player.getId()) return true;
		if (onFakes.get() && FemboyWare.getModules().get(FakePlayerModule.class).isFake(state.id)) return true;
		Entity entity = mc.level.getEntity(state.id);
		if (!(entity instanceof Player player)) return false;
		if (given().containsKey(nameOf(player))) return true;
		if (showOn.is(ShowOn.YOU)) return false;
		return showOn.is(ShowOn.EVERYONE) || FemboyWare.getFriends().isFriend(player);
	}

	/**
	 * Whose horns: yours; a player you gave them to gets the kind you picked for them; a fake player
	 * gets its own pick of fiery, frozen or poisoned, kept while it's around (from its UUID). With your
	 * horns off, only players you gave a kind to have any.
	 */
	private Horns hornsFor(AvatarRenderState state) {
		Horns yours = horns.get();
		if (mc.player == null || state.id == mc.player.getId()) return yours;
		Entity entity = mc.level.getEntity(state.id);
		if (entity instanceof Player player) {
			String pick = given().get(nameOf(player));
			if (pick != null && !pick.equals(GIVE_SAME)) {
				if (pick.equals(GIVE_RANDOM)) return yours == Horns.OFF ? Horns.OFF : randomHorns(entity);
				return Horns.valueOf(pick);
			}
		}
		if (yours == Horns.OFF || entity == null) return yours;
		if (!onFakes.get() || !FemboyWare.getModules().get(FakePlayerModule.class).isFake(state.id)) return yours;
		return randomHorns(entity);
	}

	private static Horns randomHorns(Entity entity) {
		Horns[] styles = {Horns.FIERY, Horns.FROZEN, Horns.POISONED};
		return styles[Math.floorMod(entity.getUUID().hashCode(), styles.length)];
	}

	// ---------------------------------------------------------------- giving cosmetics to players

	private static final String GIVE_SAME = "SAME", GIVE_RANDOM = "RANDOM";

	private static String nameOf(Player player) {
		return player.getGameProfile().name().toLowerCase(Locale.ROOT);
	}

	/** Players you gave the cosmetics to, by lower case name, with their horns: a Horns name, SAME as yours, or RANDOM. */
	private Map<String, String> given() {
		String raw = givenTo.get();
		if (raw.equals(givenParsedFrom)) return givenParsed;
		Map<String, String> parsed = new LinkedHashMap<>();
		for (String entry : raw.split(",")) {
			String[] parts = entry.trim().split(":");
			if (parts[0].isEmpty()) continue;
			parsed.put(parts[0].toLowerCase(Locale.ROOT), parts.length > 1 ? parts[1] : GIVE_SAME);
		}
		givenParsedFrom = raw;
		givenParsed = parsed;
		return parsed;
	}

	private void saveGiven(Map<String, String> map) {
		List<String> entries = new ArrayList<>();
		map.forEach((name, pick) -> entries.add(name + ":" + pick));
		givenTo.set(String.join(",", entries));
		FemboyWare.getConfig().save();
	}

	/** Handles {@code .cos <give|take|list|clear>}. */
	public void command(String[] args) {
		String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "list";
		Map<String, String> map = new LinkedHashMap<>(given());
		switch (action) {
			case "give", "add" -> {
				if (args.length < 3) {
					ChatUtil.info("Usage: " + CommandManager.PREFIX + "cos give <name> [fiery|frozen|poisoned|random|none|same]");
					return;
				}
				String pick = args.length > 3 ? hornsPick(args[3]) : GIVE_SAME;
				if (pick == null) {
					ChatUtil.info("Horns can be fiery, frozen, poisoned, random, none or same (as yours)");
					return;
				}
				map.put(args[2].toLowerCase(Locale.ROOT), pick);
				saveGiven(map);
				if (!isEnabled()) setEnabled(true);
				ChatUtil.info("Gave " + args[2] + " your cosmetics" + (pick.equals(GIVE_SAME) ? "" : " with " + pick.toLowerCase(Locale.ROOT) + " horns") + ". Only you can see them");
			}
			case "take", "remove", "del" -> {
				if (args.length < 3) {
					ChatUtil.info("Usage: " + CommandManager.PREFIX + "cos take <name>");
					return;
				}
				boolean had = map.remove(args[2].toLowerCase(Locale.ROOT)) != null;
				saveGiven(map);
				ChatUtil.info(had ? "Took the cosmetics off " + args[2] : args[2] + " didn't have any");
			}
			case "clear" -> {
				saveGiven(new LinkedHashMap<>());
				ChatUtil.info("Took the cosmetics off everyone you gave them to");
			}
			case "list" -> {
				if (map.isEmpty()) {
					ChatUtil.info("You haven't given anyone cosmetics. Use " + CommandManager.PREFIX + "cos give <name> [horns]");
					return;
				}
				List<String> rows = new ArrayList<>();
				map.forEach((name, pick) -> rows.add(name + " (" + (pick.equals(GIVE_SAME) ? "same horns" : pick.toLowerCase(Locale.ROOT) + " horns") + ")"));
				ChatUtil.info("Wearing your cosmetics: " + String.join(", ", rows));
			}
			default -> ChatUtil.info("Usage: " + CommandManager.PREFIX + "cos <give|take|list|clear> [name] [horns]");
		}
	}

	/** The saved form of a horns choice typed in a command, or null if it isn't one. */
	private static String hornsPick(String typed) {
		return switch (typed.toLowerCase(Locale.ROOT)) {
			case "fiery", "fire", "flaming" -> Horns.FIERY.name();
			case "frozen", "ice", "frost" -> Horns.FROZEN.name();
			case "poisoned", "poison", "toxic" -> Horns.POISONED.name();
			case "none", "off", "no" -> Horns.OFF.name();
			case "random", "rand" -> GIVE_RANDOM;
			case "same", "mine" -> GIVE_SAME;
			default -> null;
		};
	}

	/** Read by PlayerModelMixin each time the model is posed. */
	public boolean hidesHead(AvatarRenderState state) {
		Worn peer = peer(state);
		if (peer != null) return peer.headless() && !state.isInvisible;
		return headless.get() && wears(state);
	}

	public boolean replacesRightLeg(AvatarRenderState state) {
		Worn peer = peer(state);
		if (peer != null) return peer.korblox() && !state.isInvisible && korblox() != null;
		return korblox.get() && wears(state) && korblox() != null;
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
			if (!wears(state)) return;
			// Theirs, if it's someone else with femboyware; otherwise yours
			Worn peer = peer(state);
			boolean wearsLeg = peer != null ? peer.korblox() : korblox.get();
			Watch wornWatch = peer != null ? peer.watch() : watch.get();
			Mesh leg = korblox();
			if (wearsLeg && leg != null) {
				matrices.pushPose();
				getParentModel().rightLeg.translateAndRotate(matrices);
				// At its true Roblox size: a short thin peg hanging from the hip, a third as long as a leg
				matrices.scale(KORBLOX_SCALE, KORBLOX_SCALE, KORBLOX_SCALE);
				queue.submitCustomGeometry(matrices, cutout(KORBLOX), (pose, consumer) -> leg.draw(pose, consumer, light));
				matrices.popPose();
			}
			if (wornWatch != Watch.OFF) submitWatch(matrices, queue, getParentModel().leftArm, wornWatch, light, state.ageInTicks);
			Horns style = peer != null ? peer.horns() : hornsFor(state);
			Mesh hornMesh = horns();
			if (style == Horns.OFF || hornMesh == null) return;
			float size = peer != null ? Mth.clamp(peer.hornSize(), 0.15f, 0.5f) : hornSize.get().floatValue();
			boolean burning = peer != null ? peer.fire() : fire.get();
			matrices.pushPose();
			getParentModel().head.translateAndRotate(matrices);
			// The hat attachment sits on top of the head; the mesh is in studs from it
			matrices.translate(0, -0.5f, 0);
			matrices.scale(size, size, size);
			queue.submitCustomGeometry(matrices, cutout(style.texture), (pose, consumer) -> hornMesh.draw(pose, consumer, light));
			if (burning && fires.size() < MAX_FIRES) {
				// Between the horn roots, relative to the camera. The fire itself is drawn at the end of the
				// frame (onRender3D), after block entities, so a chest or sign behind it can't paint over it
				Vector3f base = matrices.last().pose().transformPosition(new Vector3f(0, FIRE_BASE, 0));
				fires.add(new FireSpot(base, new Vec3(state.x, state.y, state.z), state.ageInTicks, size, style));
			}
			matrices.popPose();
		}
	}

	/** A fire to draw this frame: where it starts relative to the camera, and whose it is. */
	private record FireSpot(Vector3f base, Vec3 owner, float age, float studs, Horns style) {
	}

	/** Late, once everything solid's drawn, so nothing drawn after it (cars, the jet) can paint over the fire. */
	@Override
	public void onRender3DLate(Render3DEvent event) {
		if (fires.isEmpty()) return;
		Vec3 cam = event.camera().pos;
		VertexConsumer consumer = null;
		for (FireSpot spot : fires) {
			// Players drawn somewhere else, like the inventory preview, land far from their owner: skip those
			Vec3 at = cam.add(spot.base.x, spot.base.y, spot.base.z);
			if (at.distanceToSqr(spot.owner) > 16) continue;
			if (consumer == null) consumer = FxRender.textured(event, FIRE_PUFF, false, false);
			drawFire(event, consumer, spot);
		}
		fires.clear();
	}

	@Override
	protected void onDisable() {
		fires.clear();
	}

	// ---------------------------------------------------------------- watch

	public enum Watch {
		OFF, GOLD, ICED, ROSE_GOLD, BLACK;

		Identifier texture() {
			String name = switch (this) {
				case GOLD -> "gold";
				case ROSE_GOLD -> "rose";
				case BLACK -> "black";
				default -> "iced";
			};
			return Identifier.fromNamespaceAndPath("femboyware", "textures/larp/watch_" + name + ".png");
		}
	}

	// ---------------------------------------------------------------- the 3D skin in menus

	/** What the menu skin had visible before we hid parts of it, to put back after. */
	private boolean skinHeadWas = true, skinLegWas = true;

	/**
	 * Shrinks and lowers a menu's 3D skin so the horns fit in its box with the feet, since the box
	 * only has room for a plain player. Model space: y down, head top at -0.5, feet at 1.5.
	 */
	public void fitSkin(PoseStack matrices) {
		if (!isEnabled() || horns.is(Horns.OFF)) return;
		// The horns reach this far above the head's top; the body's middle sits where it did
		float size = hornSize.get().floatValue();
		float top = -0.5f - 0.6f * size / 0.28f, bottom = 1.5f;
		// The fire reaches higher still; its faint tip can go past the edge
		if (fire.get()) top = Math.min(top, -0.5f + (FIRE_BASE - FIRE_HEIGHT * 0.8f) * size);
		float fit = 2f / (bottom - top), middle = (top + bottom) / 2;
		matrices.translate(0, 0.5f - middle * fit, 0);
		matrices.scale(fit, fit, fit);
	}

	/**
	 * Called from GuiSkinRendererMixin before a menu's 3D skin is drawn (the main menu, the phone's
	 * Roblox profile): hides the head for Headless and the right leg for Korblox.
	 */
	public void prepareSkin(PlayerModel model) {
		skinHeadWas = model.head.visible;
		skinLegWas = model.rightLeg.visible;
		if (!isEnabled()) return;
		if (headless.get()) model.head.visible = false;
		if (korblox.get()) model.rightLeg.visible = false;
	}

	//? if <26.1 {
	/** Called right after: draws the Korblox leg, horns and watch onto it, then puts the model back. */
	public void decorateSkin(PlayerModel model, PoseStack matrices, net.minecraft.client.renderer.MultiBufferSource buffers, int light) {
		model.head.visible = skinHeadWas;
		model.rightLeg.visible = skinLegWas;
		if (!isEnabled()) return;
		Mesh leg = korblox();
		if (korblox.get() && leg.triangles() > 0) {
			matrices.pushPose();
			model.rightLeg.translateAndRotate(matrices);
			matrices.scale(KORBLOX_SCALE, KORBLOX_SCALE, KORBLOX_SCALE);
			leg.draw(matrices.last(), buffers.getBuffer(cutout(KORBLOX)), light);
			matrices.popPose();
		}
		Horns style = horns.get();
		Mesh hornMesh = horns();
		if (style != Horns.OFF && hornMesh.triangles() > 0) {
			float size = hornSize.get().floatValue();
			matrices.pushPose();
			model.head.translateAndRotate(matrices);
			matrices.translate(0, -0.5f, 0);
			matrices.scale(size, size, size);
			hornMesh.draw(matrices.last(), buffers.getBuffer(cutout(style.texture)), light);
			matrices.popPose();
		}
		if (!watch.is(Watch.OFF)) {
			matrices.pushPose();
			model.leftArm.translateAndRotate(matrices);
			drawWatch(matrices.last(), buffers.getBuffer(cutout(watch.get().texture())), light);
			matrices.popPose();
		}
		// The fire last, since it's see-through: in the horns' own space, rising up the screen
		if (style != Horns.OFF && fire.get()) {
			float size = hornSize.get().floatValue();
			matrices.pushPose();
			model.head.translateAndRotate(matrices);
			matrices.translate(0, -0.5f, 0);
			matrices.scale(size, size, size);
			drawSkinFire(matrices.last(), buffers.getBuffer(FxRender.spriteLayer(FIRE_PUFF)), style, System.nanoTime() / 5e7f);
			matrices.popPose();
		}
	}

	/**
	 * The same fire as in the world, for menus: there's no camera to face there, so each puff is
	 * three crossed quads round the upright. Horn space, in studs: y down, so the fire goes to -y.
	 */
	private static void drawSkinFire(PoseStack.Pose pose, VertexConsumer c, Horns style, float age) {
		float height = FIRE_HEIGHT;
		for (int i = 0; i < OUTER_PUFFS + CORE_PUFFS; i++) {
			boolean core = i >= OUTER_PUFFS;
			int seed = core ? i + 100 - OUTER_PUFFS : i;
			float life = (age * ((core ? 0.026f : 0.022f) + 0.012f * random(seed, 1)) + random(seed, 2)) % 1f;
			if (core) life *= 0.8f;
			float reach = fireWidth(life) * (core ? 0.3f : 0.55f) * (float) Math.sqrt(random(seed, 4));
			float size = (core ? 0.4f + 0.15f * random(seed, 5) : 0.55f + 0.22f * random(seed, 5)) * (1 - life * (core ? 0.45f : 0.35f));
			int color = core ? FxRender.fade(style.core, fade(life) * 0.75f) : FxRender.fade(FxRender.mix(style.outer, style.core, 0.15f * random(seed, 6)), fade(life) * 0.55f);
			float around = random(seed, 3) * Mth.TWO_PI + age * 0.02f;
			float x = Mth.cos(around) * reach, z = Mth.sin(around) * reach, y = FIRE_BASE - life * height;
			float turn = random(seed, 9) * Mth.PI;
			for (int k = 0; k < 3; k++) {
				float angle = turn + k * Mth.PI / 3, dx = Mth.cos(angle) * size, dz = Mth.sin(angle) * size;
				skinFireVertex(c, pose, x - dx, y + size, z - dz, 0, 1, color);
				skinFireVertex(c, pose, x + dx, y + size, z + dz, 1, 1, color);
				skinFireVertex(c, pose, x + dx, y - size, z + dz, 1, 0, color);
				skinFireVertex(c, pose, x - dx, y - size, z - dz, 0, 0, color);
			}
		}
	}

	private static void skinFireVertex(VertexConsumer c, PoseStack.Pose pose, float x, float y, float z, float u, float v, int color) {
		c.addVertex(pose, x, y, z).setColor(color).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(0xF000F0).setNormal(pose, 0, -1, 0);
	}
	//?}

	/** Your horns, or OFF when Cosmetics is off. Read by the phone's Roblox app. */
	public Horns hornStyle() {
		return isEnabled() ? horns.get() : Horns.OFF;
	}

	public boolean korbloxOn() {
		return isEnabled() && korblox.get();
	}

	public boolean headlessOn() {
		return isEnabled() && headless.get();
	}

	/** Whether you (or whoever this is) are already wearing a watch from here. */
	public boolean showsWatch(AvatarRenderState state) {
		Worn peer = peer(state);
		if (peer != null) return peer.watch() != Watch.OFF && !state.isInvisible;
		return !watch.is(Watch.OFF) && wears(state);
	}

	/**
	 * A watch on the left wrist: a band round the arm and a dial on the outside of the wrist, both
	 * from the dial texture (its corner is the case colour). Iced ones glint. Left arm space.
	 */
	public static void submitWatch(PoseStack matrices, SubmitNodeCollector queue, ModelPart leftArm, Watch style, int light, float age) {
		if (style == Watch.OFF) return;
		matrices.pushPose();
		leftArm.translateAndRotate(matrices);
		queue.submitCustomGeometry(matrices, cutout(style.texture()), (pose, c) -> drawWatch(pose, c, light));
		if (style == Watch.ICED) queue.submitCustomGeometry(matrices, RenderTypes.eyes(FxRender.GLOW), (pose, c) -> drawGlints(pose, c, age));
		matrices.popPose();
	}

	private static final float WATCH_FACE_X = 0.226f;

	private static void drawWatch(PoseStack.Pose pose, VertexConsumer c, int light) {
		float[] caseUv = {0.01f, 0.01f, 0.06f, 0.06f};
		// The band
		cuboid(c, pose, light, -0.078f, 0.48f, -0.14f, 0.203f, 0.55f, 0.14f, caseUv);
		// The case, standing off the outside of the wrist
		cuboid(c, pose, light, 0.2f, 0.452f, -0.068f, WATCH_FACE_X, 0.578f, 0.068f, caseUv);
		// The dial, facing out
		Vec3 n = new Vec3(1, 0, 0);
		float x = WATCH_FACE_X + 0.001f;
		vertex(c, pose, x, 0.455f, 0.065f, 0, 0, light, n);
		vertex(c, pose, x, 0.455f, -0.065f, 1, 0, light, n);
		vertex(c, pose, x, 0.575f, -0.065f, 1, 1, light, n);
		vertex(c, pose, x, 0.575f, 0.065f, 0, 1, light, n);
	}

	/** Little stars twinkling on an iced-out dial. */
	private static void drawGlints(PoseStack.Pose pose, VertexConsumer c, float age) {
		float[][] spots = {{0.47f, 0.05f}, {0.56f, -0.05f}, {0.50f, -0.02f}, {0.53f, 0.04f}, {0.46f, -0.055f}};
		for (int i = 0; i < spots.length; i++) {
			float twinkle = (float) Math.max(0, Math.sin(age * 0.35f + i * 2.1f));
			twinkle = twinkle * twinkle * twinkle;
			if (twinkle < 0.05f) continue;
			float size = 0.012f + 0.03f * twinkle, x = WATCH_FACE_X + 0.004f, y = spots[i][0], z = spots[i][1];
			int color = FxRender.fade(0xFFFFFFFF, twinkle);
			glint(c, pose, x, y - size, z, x, y + size, z, size * 0.18f, color);
			glint(c, pose, x, y, z - size, x, y, z + size, size * 0.18f, color);
		}
	}

	/** A thin bright line from a to b on the dial's plane, as a quad. */
	private static void glint(VertexConsumer c, PoseStack.Pose pose, float ax, float ay, float az, float bx, float by, float bz, float half, int color) {
		boolean vertical = ay != by;
		float oy = vertical ? 0 : half, oz = vertical ? half : 0;
		glow(c, pose, ax, ay - oy, az - oz, color);
		glow(c, pose, bx, by - oy, bz - oz, color);
		glow(c, pose, bx, by + oy, bz + oz, color);
		glow(c, pose, ax, ay + oy, az + oz, color);
	}

	private static void glow(VertexConsumer c, PoseStack.Pose pose, float x, float y, float z, int color) {
		c.addVertex(pose, x, y, z).setColor(color).setUv(0.5f, 0.5f).setOverlay(OverlayTexture.NO_OVERLAY).setLight(0xF000F0).setNormal(pose, 1, 0, 0);
	}

	/** A box with every face showing the same piece of texture. */
	private static void cuboid(VertexConsumer c, PoseStack.Pose pose, int light, float x1, float y1, float z1, float x2, float y2, float z2, float[] uv) {
		float[][][] faces = {
			{{x1, y1, z1}, {x2, y1, z1}, {x2, y2, z1}, {x1, y2, z1}},
			{{x2, y1, z2}, {x1, y1, z2}, {x1, y2, z2}, {x2, y2, z2}},
			{{x1, y1, z2}, {x1, y1, z1}, {x1, y2, z1}, {x1, y2, z2}},
			{{x2, y1, z1}, {x2, y1, z2}, {x2, y2, z2}, {x2, y2, z1}},
			{{x1, y1, z2}, {x2, y1, z2}, {x2, y1, z1}, {x1, y1, z1}},
			{{x1, y2, z1}, {x2, y2, z1}, {x2, y2, z2}, {x1, y2, z2}}};
		Vec3[] normals = {new Vec3(0, 0, -1), new Vec3(0, 0, 1), new Vec3(-1, 0, 0), new Vec3(1, 0, 0), new Vec3(0, -1, 0), new Vec3(0, 1, 0)};
		float[][] corners = {{uv[0], uv[1]}, {uv[2], uv[1]}, {uv[2], uv[3]}, {uv[0], uv[3]}};
		for (int f = 0; f < faces.length; f++) {
			for (int k = 0; k < 4; k++) vertex(c, pose, faces[f][k][0], faces[f][k][1], faces[f][k][2], corners[k][0], corners[k][1], light, normals[f]);
		}
	}

	private static void vertex(VertexConsumer c, PoseStack.Pose pose, float x, float y, float z, float u, float v, int light, Vec3 n) {
		c.addVertex(pose, x, y, z).setColor(0xFFFFFFFF).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, (float) n.x, (float) n.y, (float) n.z);
	}

	// ---------------------------------------------------------------- meshes

	private static Mesh korblox() {
		if (korbloxMesh == null) korbloxMesh = Mesh.load("korblox");
		return korbloxMesh;
	}

	private static Mesh horns() {
		if (hornsMesh == null) hornsMesh = Mesh.load("horns");
		return hornsMesh;
	}

	/** A triangle mesh: per vertex x y z u v nx ny nz. Drawn as quads with the last corner repeated. */
	private record Mesh(float[] data, int triangles) {
		private static final int STRIDE = 8;
		private static final Mesh MISSING = new Mesh(new float[0], 0);

		static Mesh load(String name) {
			try (InputStream in = CosmeticsModule.class.getResourceAsStream("/assets/femboyware/models/" + name + ".bin")) {
				if (in == null) throw new IOException("missing");
				ByteBuffer buffer = ByteBuffer.wrap(in.readAllBytes()).order(ByteOrder.LITTLE_ENDIAN);
				int triangles = buffer.getInt();
				float[] data = new float[triangles * 3 * STRIDE];
				for (int i = 0; i < data.length; i++) data[i] = buffer.getFloat();
				return new Mesh(data, triangles);
			} catch (IOException | RuntimeException e) {
				FemboyWare.LOGGER.error("Couldn't load cosmetic model {}", name, e);
				return MISSING;
			}
		}

		void draw(PoseStack.Pose pose, VertexConsumer c, int light) {
			for (int t = 0; t < triangles; t++) {
				int base = t * 3 * STRIDE;
				for (int corner = 0; corner < 4; corner++) {
					int o = base + Math.min(corner, 2) * STRIDE;
					c.addVertex(pose, data[o], data[o + 1], data[o + 2]).setColor(0xFFFFFFFF).setUv(data[o + 3], data[o + 4])
						.setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, data[o + 5], data[o + 6], data[o + 7]);
				}
			}
		}
	}

	// ---------------------------------------------------------------- fire

	private static final Identifier FIRE_PUFF = Identifier.fromNamespaceAndPath("femboyware", "textures/fx/fire_puff.png");
	/** Where the fire starts in the horn mesh's space: between the roots, just under the hat attachment. */
	private static final float FIRE_BASE = 0.35f;
	/** How tall the fire rises, in studs: well past the horn tips, like the Roblox horns. */
	private static final float FIRE_HEIGHT = 4.6f;
	private static final int OUTER_PUFFS = 60, CORE_PUFFS = 34, SPARKS = 12;

	/**
	 * A tall column of fire like the Roblox horns': grainy puffs that start between the horn roots,
	 * swell out into a wide belly a third of the way up and taper off well above the tips. The outer
	 * puffs are the fire's main colour, round a hotter core of the light colour. Sizes are in studs,
	 * times the horn size. Camera-relative world space, y up; every puff faces the camera.
	 */
	private static void drawFire(Render3DEvent event, VertexConsumer c, FireSpot spot) {
		float age = spot.age, studs = spot.studs, height = FIRE_HEIGHT * studs;
		Horns style = spot.style;
		for (int i = 0; i < OUTER_PUFFS; i++) {
			float life = (age * (0.022f + 0.012f * random(i, 1)) + random(i, 2)) % 1f;
			float reach = fireWidth(life) * 0.55f * (float) Math.sqrt(random(i, 4));
			float size = (0.55f + 0.22f * random(i, 5)) * (1 - life * 0.35f) * studs;
			int color = FxRender.fade(FxRender.mix(style.outer, style.core, 0.15f * random(i, 6)), fade(life) * 0.55f);
			puff(event, c, spot.base, age, i, life, height, reach * studs, size, color);
		}
		for (int i = 0; i < CORE_PUFFS; i++) {
			int seed = i + 100;
			float life = (age * (0.026f + 0.012f * random(seed, 1)) + random(seed, 2)) % 1f;
			float reach = fireWidth(life) * 0.3f * (float) Math.sqrt(random(seed, 4));
			float size = (0.4f + 0.15f * random(seed, 5)) * (1 - life * 0.45f) * studs;
			int color = FxRender.fade(style.core, fade(life) * 0.75f);
			puff(event, c, spot.base, age, seed, life * 0.8f, height, reach * studs, size, color);
		}
		for (int i = 0; i < SPARKS; i++) {
			float life = (age * 0.02f + random(i, 7)) % 1f;
			float around = random(i, 8) * Mth.TWO_PI, out = 0.5f * studs * life;
			float size = 0.07f * studs * (1 - life);
			FxRender.billboard(event, c, spot.base.x + Mth.cos(around) * out, spot.base.y + life * height * 1.3f, spot.base.z + Mth.sin(around) * out,
				size, 0, FxRender.fade(style.spark, 1 - life));
		}
	}

	/** One rising puff, {@code life} of the way up, drifting {@code reach} out from the middle, slowly turning. */
	private static void puff(Render3DEvent event, VertexConsumer c, Vector3f base, float age, int seed, float life, float height, float reach, float size, int color) {
		float around = random(seed, 3) * Mth.TWO_PI + age * 0.02f;
		float wobble = 0.08f * height * life * Mth.sin(age * 0.18f + seed);
		float x = base.x + Mth.cos(around) * reach + wobble * 0.3f;
		float z = base.z + Mth.sin(around) * reach;
		float roll = random(seed, 9) * Mth.TWO_PI + age * 0.03f * (random(seed, 10) - 0.5f);
		FxRender.billboard(event, c, x, base.y + life * height, z, size, roll, color);
	}

	/** How wide the column is, in studs, {@code life} of the way up: a teardrop, widest a third of the way. */
	private static float fireWidth(float life) {
		return life < 0.3f ? 0.9f + life / 0.3f * 0.7f : 1.6f - (life - 0.3f) / 0.7f * 1.0f;
	}

	/** Puffs fade in quickly at the bottom and out slowly toward the top. */
	private static float fade(float life) {
		return Math.min(1, life / 0.08f) * (1 - life * life);
	}

	/** A steady pseudo-random number in 0..1 for flame {@code i}, so each flame keeps its own path. */
	private static float random(int i, int salt) {
		float v = Mth.sin(i * 12.9898f + salt * 78.233f) * 43758.547f;
		return v - (float) Math.floor(v);
	}

}
