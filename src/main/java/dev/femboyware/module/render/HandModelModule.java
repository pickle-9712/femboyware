package dev.femboyware.module.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import java.util.function.Consumer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * Shows your own arm holding the item in first person: swords, tools, items and blocks, with your
 * whole arm reaching up from the shoulder. Ported from the Anubis client's 3D Hand (GPL-3.0, by
 * 4ldenz, https://github.com/4ldenz/Anubis-client). 1.21.11 only: 26.x rewrote first-person rendering.
 */
public class HandModelModule extends Module {
	public enum ItemsMode { ALL_ITEMS, WEAPONS_AND_TOOLS }

	static final Vector3f FOREARM = new Vector3f(0.75f, -0.45f, 0.5f).normalize();
	static final float ELBOW = 0.6f;
	private static final Vector3f ARM_UP = new Vector3f(0, 1, 0);
	private static final float HANDLE_INSET = 0.1875f, BLOCK_BELOW = 0.17f;
	private static final Vector3f BLOCK_SHIFT = new Vector3f(0.09f, 0, 0.06f);

	private final EnumSetting<ItemsMode> items = add(new EnumSetting<>("Items", "Which held items get an arm", ItemsMode.ALL_ITEMS));
	private final BoolSetting mainHand = add(new BoolSetting("Main Hand", "Arm on your main hand", true));
	private final BoolSetting offHand = add(new BoolSetting("Off Hand", "Arm on your off hand", true));

	public HandModelModule() {
		super("3D Hand", "Your arm holds the item in first person (1.21.11)", Category.COSMETIC);
	}

	public static HandModelModule active() {
		if (FemboyWare.getModules() == null) return null;
		HandModelModule module = FemboyWare.getModules().get(HandModelModule.class);
		return module != null && module.isEnabled() ? module : null;
	}

	static boolean covers(ItemStack stack, boolean main) {
		HandModelModule module = active();
		if (module == null || !(main ? module.mainHand.get() : module.offHand.get())) return false;
		return module.items.get() == ItemsMode.ALL_ITEMS || weaponOrTool(stack);
	}

	private static boolean weaponOrTool(ItemStack stack) {
		return stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES) || stack.is(ItemTags.PICKAXES) || stack.is(ItemTags.SHOVELS) || stack.is(ItemTags.HOES)
			|| stack.is(ItemTags.SPEARS) || stack.is(Items.MACE) || stack.is(Items.FISHING_ROD) || stack.is(Items.SHEARS) || stack.is(Items.FLINT_AND_STEEL)
			|| stack.is(Items.BRUSH) || stack.is(Items.CARROT_ON_A_STICK) || stack.is(Items.WARPED_FUNGUS_ON_A_STICK);
	}

	private static boolean slim() {
		//? if <26.1 {
		return mc.player != null && mc.player.getSkin().model() == net.minecraft.world.entity.player.PlayerModelType.SLIM;
		//?} else {
		/*return false;
		*///?}
	}

	//? if <26.1 {
	/** Stretches the empty-hand arm up to the shoulder. */
	public static void shoulder(PoseStack pose, boolean right) {
		boolean slim = slim();
		float pivotX = right ? -0.3125f : 0.3125f, pivotY = 0.125f, tilt = right ? 0.1f : -0.1f;
		float middle = (right ? -1f : 1f) * (slim ? 0.5f : 1f) / 16f, top = -0.125f;
		pose.translate(pivotX, pivotY, 0);
		pose.mulPose(Axis.ZP.rotation(tilt));
		pose.translate(middle, top, 0);
		pose.scale(-1, -3, 1);
		pose.translate(-middle, -top, 0);
		pose.mulPose(Axis.ZP.rotation(-tilt));
		pose.translate(-pivotX, -pivotY, 0);
	}

	private static final net.minecraft.client.renderer.item.ItemStackRenderState MODEL = new net.minecraft.client.renderer.item.ItemStackRenderState();

	/** Draws your arm gripping the item about to be drawn. */
	public static void renderArm(PoseStack pose, net.minecraft.world.entity.LivingEntity holder, ItemStack stack, net.minecraft.world.item.ItemDisplayContext context,
								 net.minecraft.client.renderer.SubmitNodeCollector collector, int light, boolean main) {
		if (!covers(stack, main)) return;
		var player = mc.player;
		if (player == null || player.isInvisible()) return;
		var renderer = mc.getEntityRenderDispatcher().getPlayerRenderer(player);
		var skin = player.getSkin();
		var texture = skin.body().texturePath();
		boolean rightSleeve = player.isModelPartShown(net.minecraft.world.entity.player.PlayerModelPart.RIGHT_SLEEVE);
		boolean leftSleeve = player.isModelPartShown(net.minecraft.world.entity.player.PlayerModelPart.LEFT_SLEEVE);
		boolean slim = skin.model() == net.minecraft.world.entity.player.PlayerModelType.SLIM;
		Vector3f grip = grip(stack, context, holder);
		if (grip == null) return;
		Vector3f elbow = new Vector3f(grip).fma(ELBOW, FOREARM);
		boolean left = context.leftHand();
		pose.pushPose();
		if (left) pose.scale(-1, 1, 1);
		place(pose, grip, elbow, slim, posed -> {
			if (left) {
				posed.scale(-1, 1, 1);
				renderer.renderLeftHand(posed, collector, light, texture, leftSleeve);
			} else renderer.renderRightHand(posed, collector, light, texture, rightSleeve);
		});
		pose.popPose();
	}

	private static Vector3f grip(ItemStack stack, net.minecraft.world.item.ItemDisplayContext context, net.minecraft.world.entity.LivingEntity holder) {
		if (stack.isEmpty() || stack.is(Items.BOW) || stack.is(Items.CROSSBOW) || stack.is(Items.SHIELD) || stack.is(Items.TRIDENT) || stack.is(Items.SPYGLASS)) return null;
		mc.getItemModelResolver().updateForTopItem(MODEL, stack, context, holder.level(), holder, holder.getId() + context.ordinal());
		var state = (dev.femboyware.mixin.HandModelStateAccessor) MODEL;
		if (state.femboyware$layerCount() <= 0) return null;
		var layer = (dev.femboyware.mixin.HandModelLayerAccessor) state.femboyware$layers()[0];
		var transform = layer.femboyware$transform();
		PoseStack.Pose display = new PoseStack.Pose();
		if (transform != null) transform.apply(context.leftHand(), display);
		org.joml.Matrix4f m = display.pose();
		if (!MODEL.usesBlockLight()) {
			float[] handle = handle(layer.femboyware$extents() != null ? layer.femboyware$extents().get() : null);
			Vector3f grip = m.transformPosition(handle[0] - 0.5f, handle[1] - 0.5f, 0, new Vector3f());
			if (context.leftHand()) grip.x = -grip.x;
			return grip;
		}
		if (!(stack.getItem() instanceof net.minecraft.world.item.BlockItem)) return null;
		Vector3f center = m.transformPosition(0, 0, 0, new Vector3f());
		if (context.leftHand()) center.x = -center.x;
		float scale = transform != null ? transform.scale().y() : 1;
		return new Vector3f(center).add(BLOCK_SHIFT).sub(0, 0.5f * scale + BLOCK_BELOW, 0);
	}

	/** Where the hand grips a flat item: its lowest-left corner pixel, nudged in. */
	private static float[] handle(Vector3fc[] extents) {
		float lowest = Float.MAX_VALUE;
		if (extents != null) for (Vector3fc p : extents) if (!squareCorner(p)) lowest = Math.min(lowest, p.x() + p.y());
		if (lowest == Float.MAX_VALUE) return new float[] {HANDLE_INSET, HANDLE_INSET};
		float x = 0, y = 0;
		int tied = 0;
		for (Vector3fc p : extents)
			if (!squareCorner(p) && !(p.x() + p.y() > lowest + 1.0E-4f)) {
				x += p.x();
				y += p.y();
				tied++;
			}
		return new float[] {x / tied + HANDLE_INSET, y / tied + HANDLE_INSET};
	}

	private static boolean squareCorner(Vector3fc p) {
		return (p.x() < 1.0E-4f || p.x() > 0.9999f) && (p.y() < 1.0E-4f || p.y() > 0.9999f);
	}

	private static void place(PoseStack pose, Vector3f hand, Vector3f elbow, boolean slim, Consumer<PoseStack> draw) {
		Vector3f along = new Vector3f(hand).sub(elbow).normalize();
		Vector3f front = new Vector3f(ARM_UP).fma(-ARM_UP.dot(along), along).normalize();
		Vector3f side = new Vector3f(along).cross(front);
		pose.pushPose();
		pose.translate(hand.x, hand.y, hand.z);
		pose.mulPose(new Quaternionf().setFromNormalized(new Matrix3f(side, along, front)));
		drawPart(pose, slim, draw);
		pose.translate(0, -0.625f, 0);
		pose.scale(-1, -3, 1);
		pose.translate(0, 0.625f, 0);
		drawPart(pose, slim, draw);
		pose.popPose();
	}

	private static void drawPart(PoseStack pose, boolean slim, Consumer<PoseStack> draw) {
		pose.pushPose();
		pose.translate(slim ? 0.03125f : 0.0625f, -0.5f, 0);
		pose.mulPose(Axis.ZP.rotation(-0.1f));
		pose.translate(0.3125f, -0.125f, 0);
		draw.accept(pose);
		pose.popPose();
	}
	//?} else {
	/*public static void shoulder(PoseStack pose, boolean right) {
	}
	*///?}
}
