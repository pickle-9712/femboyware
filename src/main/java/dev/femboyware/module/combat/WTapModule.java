package dev.femboyware.module.combat;

import dev.femboyware.mixin.InputAccessor;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;

/**
 * After a charged sprint hit, lets go of W (or taps S) for a moment, so you sprint again before the
 * next hit and that one deals full sprint knockback too.
 */
public class WTapModule extends Module {
	public enum Tap {
		W_TAP,
		S_TAP
	}

	private final EnumSetting<Tap> mode = add(new EnumSetting<>("Mode", "Let go of W, or also tap S for a harder reset", Tap.W_TAP));
	private final NumberSetting ticks = add(new NumberSetting("Ticks", "How long to let go for", 2, 1, 5, 1));
	private final NumberSetting minCharge = add(new NumberSetting("Min Charge", "Only after hits at least this charged", 0.9, 0, 1, 0.05));

	private float chargeAtHit;
	/** Taken before the hit, since a sprint hit stops your sprint. */
	private boolean sprintingAtHit;
	private int left;

	public WTapModule() {
		super("W Tap", "Briefly release forward after a charged sprint attack", Category.LEGIT);
	}

	@Override
	public void onPreAttack(Entity target) {
		chargeAtHit = mc.player.getAttackStrengthScale(0.5f);
		sprintingAtHit = mc.player.isSprinting();
	}

	@Override
	public void onPostAttack(Entity target) {
		if (target instanceof LivingEntity && sprintingAtHit && chargeAtHit >= minCharge.getFloat()) left = ticks.getInt();
	}

	@Override
	public void onInput() {
		if (left <= 0) return;
		left--;
		Input keys = mc.player.input.keyPresses;
		boolean back = mode.is(Tap.S_TAP) && keys.forward();
		mc.player.input.keyPresses = new Input(false, back || keys.backward(), keys.left(), keys.right(), keys.jump(), keys.shift(), false);
		int forwardAxis = back || keys.backward() ? -1 : 0;
		int strafeAxis = (keys.left() ? 1 : 0) - (keys.right() ? 1 : 0);
		Vec2 movement = new Vec2(strafeAxis, forwardAxis);
		((InputAccessor) mc.player.input).femboyware$setMovementVector(movement.lengthSquared() > 0 ? movement.normalized() : Vec2.ZERO);
		mc.player.setSprinting(false);
	}
}
