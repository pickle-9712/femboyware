package dev.femboyware.module.donut;

import dev.femboyware.FemboyWare;
import dev.femboyware.util.KeyUtil;
import dev.femboyware.util.Rotation;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The melee, rotation and friend calls Tpahere Kill Bot makes, on femboyware's own systems.
 * Logic ported from the Anubis client's MeleeAttacks (GPL-3.0, by 4ldenz, https://github.com/4ldenz/Anubis-client).
 */
final class DonutCombat {
	private static final int PRIORITY = 60;
	private static int attackedTick = -1;

	private DonutCombat() {
	}

	static boolean isEnemy(LocalPlayer self, Entity entity) {
		if (!(entity instanceof LivingEntity living) || entity == self || !living.isAlive() || entity.isSpectator()) return false;
		if (FemboyWare.getFriends().isFriend(entity)) return false;
		return !(entity instanceof Player player && (!self.canHarmPlayer(player) || self.connection.getPlayerInfo(player.getUUID()) == null));
	}

	static float charge(Player player) {
		return player.getAttackStrengthScale(0.5F);
	}

	static int ticksToFull(Player player) {
		return Math.max(0, Mth.ceil((1.0F - charge(player)) * player.getCurrentItemAttackStrengthDelay()));
	}

	static boolean lineIsClear(LocalPlayer player, Vec3 from, Vec3 to) {
		BlockHitResult block = player.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		return block.getType() == HitResult.Type.MISS || block.getLocation().distanceToSqr(from) >= from.distanceToSqr(to) - 0.001;
	}

	/** One hit a tick at most, like vanilla. */
	static boolean attack(Minecraft mc, EntityHitResult hit) {
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null || mc.gameMode == null || player.isSpectator()) return false;
		if (attackedTick == player.tickCount || player.isUsingItem()) return false;
		attackedTick = player.tickCount;
		mc.gameMode.attack(player, hit.getEntity());
		player.swing(InteractionHand.MAIN_HAND);
		return true;
	}

	static void request(Rotation aim) {
		FemboyWare.getRotations().request(aim, PRIORITY, 180.0F, false);
	}

	static Rotation sent() {
		return FemboyWare.getRotations().getServerRotation();
	}

	static Rotation of(Entity entity) {
		return new Rotation(entity.getYRot(), entity.getXRot());
	}

	static boolean eating() {
		var autoEat = FemboyWare.getModules().get(dev.femboyware.module.utility.AutoEatModule.class);
		return autoEat != null && autoEat.isEnabled() && autoEat.isEating();
	}

	static boolean physicallyDown(KeyMapping key) {
		return KeyUtil.isPhysicallyPressed(key);
	}

	static boolean isFriend(String name) {
		return FemboyWare.getFriends().isFriend(name);
	}
}
