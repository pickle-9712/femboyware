package dev.femboyware.module.render;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.util.Mth;

/**
 * How you walk, on your screen: a rich man's swagger, hands-behind-the-back boss stroll, catwalk,
 * stiff robot, zombie arms, or a sprinting ninja lean. Emotes still take over while you're doing one.
 */
public class WalkStyleModule extends Module {
	public enum Style { SWAGGER, BOSS, CATWALK, ROBOT, ZOMBIE, NINJA }

	private final EnumSetting<Style> style = add(new EnumSetting<>("Style", "How you walk", Style.SWAGGER));
	private final NumberSetting amount = add(new NumberSetting("Amount", "How strongly", 1, 0.2, 2, 0.05));
	private final BoolSetting others = add(new BoolSetting("Others Too", "Everyone walks like this (on your screen)", false));

	public WalkStyleModule() {
		super("Walk Style", "Walk with a swagger, a boss stroll, a catwalk and more", Category.COSMETIC);
	}

	@Override
	public String getInfo() {
		return style.getDisplayName();
	}

	/** From PlayerModelMixin, after the vanilla pose and before emotes. */
	public void pose(PlayerModel model, AvatarRenderState state) {
		if (!isEnabled() || mc.player == null) return;
		if (!others.get() && state.id != mc.player.getId()) return;
		if (state.isPassenger || state.isFallFlying || state.isVisuallySwimming) return;
		float pos = state.walkAnimationPos, speed = Math.min(1, state.walkAnimationSpeed);
		float k = amount.getFloat();
		float phase = pos * 0.6662f;
		float sin = Mth.sin(phase), cos = Mth.cos(phase);
		// Mid-swing, the swinging arm keeps its attack
		var entity = mc.level == null ? null : mc.level.getEntity(state.id);
		boolean attacking = entity instanceof net.minecraft.world.entity.LivingEntity living && living.getAttackAnim(0) > 0;
		switch (style.get()) {
			case SWAGGER -> {
				// Big loose arm swings out to the side, shoulders rolling, head up
				model.body.yRot += sin * 0.18f * speed * k;
				if (!attacking) model.rightArm.xRot = cos * 1.1f * speed * k;
				model.leftArm.xRot = -cos * 1.1f * speed * k;
				model.rightArm.zRot += (0.12f + Math.abs(cos) * 0.18f) * speed * k;
				model.leftArm.zRot -= (0.12f + Math.abs(cos) * 0.18f) * speed * k;
				model.head.xRot -= 0.12f * k;
				model.head.yRot += sin * 0.12f * speed * k;
			}
			case BOSS -> {
				// Hands clasped behind the back, an unhurried stroll, chin up
				if (!attacking) {
					model.rightArm.xRot = 0.55f;
					model.rightArm.zRot = 0.35f;
					model.rightArm.yRot = -0.4f;
				}
				model.leftArm.xRot = 0.55f;
				model.leftArm.zRot = -0.35f;
				model.leftArm.yRot = 0.4f;
				model.rightLeg.xRot *= 0.7f;
				model.leftLeg.xRot *= 0.7f;
				model.head.xRot -= 0.18f * k;
				model.body.yRot += sin * 0.06f * speed * k;
			}
			case CATWALK -> {
				// Legs crossing in front, hips swaying, small arm swings
				model.body.yRot += sin * 0.22f * speed * k;
				model.rightLeg.yRot = -0.25f * speed * k;
				model.leftLeg.yRot = 0.25f * speed * k;
				model.rightLeg.zRot = 0.08f * speed * k;
				model.leftLeg.zRot = -0.08f * speed * k;
				if (!attacking) model.rightArm.xRot *= 0.5f;
				model.leftArm.xRot *= 0.5f;
				model.rightArm.zRot += 0.15f * k;
				model.leftArm.zRot -= 0.15f * k;
			}
			case ROBOT -> {
				// Limbs step between fixed angles, head turning in jerks
				float step = Math.round(cos * 3) / 3f;
				if (!attacking) model.rightArm.xRot = step * 0.9f * speed * k;
				model.leftArm.xRot = -step * 0.9f * speed * k;
				model.rightLeg.xRot = -step * 0.8f * speed * k;
				model.leftLeg.xRot = step * 0.8f * speed * k;
				model.rightArm.zRot = 0;
				model.leftArm.zRot = 0;
				model.head.yRot = Math.round(model.head.yRot * 4) / 4f;
				model.head.xRot = Math.round(model.head.xRot * 4) / 4f;
			}
			case ZOMBIE -> {
				if (!attacking) model.rightArm.xRot = -1.45f + sin * 0.06f;
				model.leftArm.xRot = -1.45f - sin * 0.06f;
				model.rightArm.yRot = -0.1f;
				model.leftArm.yRot = 0.1f;
				model.head.xRot += 0.15f;
			}
			case NINJA -> {
				// Arms swept straight back, leaning into it
				if (!attacking) {
					model.rightArm.xRot = 1.25f * Math.max(0.3f, speed) * k;
					model.rightArm.zRot = 0.1f;
				}
				model.leftArm.xRot = 1.25f * Math.max(0.3f, speed) * k;
				model.leftArm.zRot = -0.1f;
				model.head.xRot -= 0.25f * speed * k;
				model.rightLeg.xRot *= 1.2f;
				model.leftLeg.xRot *= 1.2f;
			}
		}
	}
}
