package dev.femboyware.module.render;

import dev.femboyware.FemboyWare;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Your model's proportions, on your screen: a chibi with a big head, a bobblehead that wobbles as
 * you walk, a tiny head, bulked up or skinny. Your skin stretches to fit. Or Tung Tung Tung Sahur,
 * log, bat and all, in place of the whole player.
 */
public class PlayerModelModule extends Module {
	/** Whether the shared model was last left with its parts hidden for Tung Sahur. */
	private boolean replaced;

	public enum Shape { CHIBI, BOBBLEHEAD, TINY_HEAD, BULKY, SKINNY, LONG_LEGS, TUNG_SAHUR }

	private final EnumSetting<Shape> shape = add(new EnumSetting<>("Shape", "Your proportions", Shape.CHIBI));
	private final NumberSetting amount = add(new NumberSetting("Amount", "How far to take it", 1, 0.2, 2, 0.05));
	private final BoolSetting others = add(new BoolSetting("Others Too", "Everyone gets the shape (on your screen)", false));

	public PlayerModelModule() {
		super("Player Model", "Chibi, bobblehead, Tung Tung Tung Sahur and other shapes", Category.COSMETIC);
	}

	@Override
	public String getInfo() {
		return shape.getDisplayName();
	}

	/** From PlayerModelMixin after the pose: rescales the parts (the model is shared, so every pose sets them all). */
	public void pose(PlayerModel model, AvatarRenderState state) {
		reset(model.head);
		reset(model.body);
		reset(model.rightArm);
		reset(model.leftArm);
		reset(model.rightLeg);
		reset(model.leftLeg);
		if (replaced) {
			// Last pose was Tung Sahur on the shared model: show the body again (head and Korblox leg are set each pose already)
			model.body.visible = model.rightArm.visible = model.leftArm.visible = model.leftLeg.visible = true;
			if (!FemboyWare.getModules().get(CosmeticsModule.class).replacesRightLeg(state)) model.rightLeg.visible = true;
			replaced = false;
		}
		if (!applies(state.id)) return;
		if (shape.is(Shape.TUNG_SAHUR)) {
			// He's drawn in onRender3D instead
			for (ModelPart part : new ModelPart[]{model.head, model.body, model.rightArm, model.leftArm, model.rightLeg, model.leftLeg}) part.visible = false;
			replaced = true;
			return;
		}
		float k = amount.getFloat();
		switch (shape.get()) {
			case CHIBI -> scale(model.head, 1 + 0.55f * k);
			case BOBBLEHEAD -> {
				scale(model.head, 1 + 0.8f * k);
				// Wobbles on its spring as you walk
				float wobble = (float) Math.sin(state.ageInTicks * 0.6f) * 0.12f * Math.min(1, state.walkAnimationSpeed * 2 + 0.2f) * k;
				model.head.zRot += wobble;
				model.head.xRot += (float) Math.cos(state.ageInTicks * 0.45f) * 0.06f * k;
			}
			case TINY_HEAD -> scale(model.head, 1 - 0.45f * Math.min(1.5f, k));
			case BULKY -> {
				float w = 1 + 0.28f * k;
				model.body.xScale = w;
				model.body.zScale = w;
				for (ModelPart arm : new ModelPart[]{model.rightArm, model.leftArm}) {
					arm.xScale = w;
					arm.zScale = w;
				}
				model.rightArm.x -= 1.2f * k;
				model.leftArm.x += 1.2f * k;
			}
			case SKINNY -> {
				float w = 1 - 0.3f * Math.min(1.5f, k);
				for (ModelPart part : new ModelPart[]{model.body, model.rightArm, model.leftArm, model.rightLeg, model.leftLeg}) {
					part.xScale = w;
					part.zScale = w;
				}
			}
			case TUNG_SAHUR -> {
			}
			case LONG_LEGS -> {
				// Longer legs lift the rest of the body with them
				float l = 1 + 0.35f * k;
				model.rightLeg.yScale = l;
				model.leftLeg.yScale = l;
				float lift = 12 * (l - 1);
				for (ModelPart part : new ModelPart[]{model.head, model.body, model.rightArm, model.leftArm}) part.y -= lift;
				model.rightLeg.y -= lift;
				model.leftLeg.y -= lift;
			}
		}
	}

	private boolean applies(int entityId) {
		return isEnabled() && mc.player != null && (others.get() || entityId == mc.player.getId());
	}

	/** Whether this player is drawn as Tung Tung Tung Sahur, so their armour is hidden. */
	public boolean replaces(AvatarRenderState state) {
		return shape.is(Shape.TUNG_SAHUR) && applies(state.id);
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		if (!shape.is(Shape.TUNG_SAHUR)) return;
		float tickDelta = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		Vec3 camera = event.camera().pos;
		for (Player player : mc.level.players()) {
			if (!applies(player.getId()) || player.isSpectator() || player.isInvisible()) continue;
			if (player == mc.player && mc.options.getCameraType().isFirstPerson()) continue;
			Vec3 feet = player.getPosition(tickDelta);
			// The model faces +Z at yaw 0, Minecraft's body yaw turns the other way round
			float yaw = (float) Math.toRadians(-Mth.rotLerp(tickDelta, player.yBodyRotO, player.yBodyRot));
			// Swing the bat when they hit, sway it while they walk
			float swing = player.getAttackAnim(tickDelta);
			float walk = player.walkAnimation.speed(tickDelta);
			float arm = -15 + (float) Math.sin(player.walkAnimation.position(tickDelta) * 0.6662f) * 25 * Math.min(1, walk);
			if (swing > 0) arm = swing < 0.35f ? Mth.lerp(swing / 0.35f, arm, 150f) : Mth.lerp((swing - 0.35f) / 0.65f, 150f, -60f);
			// He's 2.5 blocks tall; shrink him to the player's height
			float size = player.getBbHeight() / 2.5f * 1.1f;
			TungSahurModule.drawSahur(event, feet.subtract(camera), yaw, 0, arm, size);
		}
	}

	private static void scale(ModelPart head, float s) {
		head.xScale = s;
		head.yScale = s;
		head.zScale = s;
	}

	private static void reset(ModelPart part) {
		part.xScale = 1;
		part.yScale = 1;
		part.zScale = 1;
	}
}
