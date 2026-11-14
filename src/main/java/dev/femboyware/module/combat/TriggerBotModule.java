package dev.femboyware.module.combat;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.Mc;
import dev.femboyware.util.TargetUtil;
import java.util.Random;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;

/** Hits whatever is under your crosshair once your weapon has charged, after a short human delay. */
public class TriggerBotModule extends Module {
	private final BoolSetting players = add(new BoolSetting("Players", "Hit players", true));
	private final BoolSetting mobs = add(new BoolSetting("Mobs", "Hit mobs and animals too", false));
	private final NumberSetting charge = add(new NumberSetting("Charge", "How charged your weapon must be, 1 is full", 0.92, 0.1, 1, 0.01));
	private final NumberSetting minDelay = add(new NumberSetting("Min Delay", "Fewest ticks to wait once something is under the crosshair", 0, 0, 10, 1));
	private final NumberSetting maxDelay = add(new NumberSetting("Max Delay", "Most ticks to wait once something is under the crosshair", 2, 0, 10, 1));
	private final BoolSetting critsOnly = add(new BoolSetting("Crits Only", "While you're in the air, only hit on the way down", true));
	private final BoolSetting notWhileUsing = add(new BoolSetting("Not While Using", "Don't hit while eating, drinking or blocking", true));

	private final Random random = new Random();
	private Entity seen;
	private int wait;

	public TriggerBotModule() {
		super("Trigger Bot", "Attacks the entity you aim at", Category.LEGIT);
	}

	@Override
	protected void onDisable() {
		seen = null;
	}

	@Override
	public void onTick() {
		if (Mc.screen() != null || (notWhileUsing.get() && mc.player.isUsingItem())) return;
		Entity target = mc.hitResult instanceof EntityHitResult hit ? hit.getEntity() : null;
		if (!valid(target)) {
			seen = null;
			return;
		}
		if (target != seen) {
			seen = target;
			int lo = minDelay.getInt(), hi = Math.max(lo, maxDelay.getInt());
			wait = lo + random.nextInt(hi - lo + 1);
		}
		if (wait > 0) {
			wait--;
			return;
		}
		if (mc.player.getAttackStrengthScale(0.5f) < charge.getFloat()) return;
		// Mid-jump on the way up a hit wouldn't crit; wait for the fall
		if (critsOnly.get() && !mc.player.onGround() && !mc.player.isInWater() && !mc.player.onClimbable() && mc.player.getDeltaMovement().y > -0.08) return;
		mc.gameMode.attack(mc.player, target);
		mc.player.swing(InteractionHand.MAIN_HAND);
	}

	private boolean valid(Entity entity) {
		if (!(entity instanceof LivingEntity living) || !living.isAlive() || entity == mc.player) return false;
		if (entity instanceof Player player) return players.get() && TargetUtil.isValid(player);
		return mobs.get();
	}
}
