package dev.femboyware.module.render;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;

/**
 * Your model's proportions, on your screen: a chibi with a big head, a bobblehead that wobbles as
 * you walk, a tiny head, bulked up or skinny. Your skin stretches to fit.
 */
public class PlayerModelModule extends Module {
	public enum Shape { CHIBI, BOBBLEHEAD, TINY_HEAD, BULKY, SKINNY, LONG_LEGS }

	private final EnumSetting<Shape> shape = add(new EnumSetting<>("Shape", "Your proportions", Shape.CHIBI));
	private final NumberSetting amount = add(new NumberSetting("Amount", "How far to take it", 1, 0.2, 2, 0.05));
	private final BoolSetting others = add(new BoolSetting("Others Too", "Everyone gets the shape (on your screen)", false));

	public PlayerModelModule() {
		super("Player Model", "Chibi, bobblehead, bulky and other proportions", Category.COSMETIC);
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
		if (!isEnabled() || mc.player == null || !others.get() && state.id != mc.player.getId()) return;
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
