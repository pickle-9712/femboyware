package dev.femboyware.module.movement;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Strider;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

/**
 * Walk on water and lava.
 * Ported from Meteor Client's Jesus (https://github.com/MeteorDevelopment/meteor-client), GPL-3.0.
 * Meteor's NCP bypass and powder snow walking aren't included.
 */
public class JesusModule extends Module {
	/** Upward speed while surfacing out of a liquid. */
	private static final double ASCEND_SPEED = 0.11;

	public enum Mode { SOLID, IGNORE }

	private final EnumSetting<Mode> waterMode = add(new EnumSetting<>("Water", "Solid walks on water, Ignore swims as normal", Mode.SOLID));
	private final BoolSetting dipIfBurning = add(new BoolSetting("Dip If Burning", "Fall into water to put yourself out", true)).visibleWhen(() -> waterMode.is(Mode.SOLID));
	private final BoolSetting dipOnSneakWater = add(new BoolSetting("Dip On Sneak Water", "Sneak to go into water", true)).visibleWhen(() -> waterMode.is(Mode.SOLID));
	private final BoolSetting dipOnFallWater = add(new BoolSetting("Dip On Fall Water", "Fall into water from high up to cancel fall damage", true)).visibleWhen(() -> waterMode.is(Mode.SOLID));
	private final NumberSetting dipFallHeightWater = add(new NumberSetting("Fall Height Water", "Fall distance to dip into water from", 4, 1, 20, 1)).visibleWhen(() -> waterMode.is(Mode.SOLID) && dipOnFallWater.get());
	private final EnumSetting<Mode> lavaMode = add(new EnumSetting<>("Lava", "Solid walks on lava, Ignore falls in", Mode.SOLID));
	private final BoolSetting dipIfFireResistant = add(new BoolSetting("Dip If Fire Resistant", "Let yourself into lava with enough Fire Resistance", false)).visibleWhen(() -> lavaMode.is(Mode.SOLID));
	private final BoolSetting dipOnSneakLava = add(new BoolSetting("Dip On Sneak Lava", "Sneak to go into lava, only when it's safe", true)).visibleWhen(() -> lavaMode.is(Mode.SOLID));
	private final BoolSetting dipOnFallLava = add(new BoolSetting("Dip On Fall Lava", "Fall into lava from high up, only when it's safe", true)).visibleWhen(() -> lavaMode.is(Mode.SOLID));
	private final NumberSetting dipFallHeightLava = add(new NumberSetting("Fall Height Lava", "Fall distance to dip into lava from", 4, 1, 20, 1)).visibleWhen(() -> lavaMode.is(Mode.SOLID) && dipOnFallLava.get());

	private int ascending = 10;

	public JesusModule() {
		super("Jesus", "Walk on water and lava", Category.RAGE);
	}

	@Override
	public void onTick() {
		if (mc.player.isSwimming()) return;
		if (mc.player.isInWater() && !waterSolid()) return;
		if (mc.player.isInLava() && !lavaSolid()) return;

		Entity moving = mc.player.isPassenger() ? mc.player.getVehicle() : mc.player;
		if (moving.isInWater() || moving.isInLava()) {
			// Float back up to the surface
			setVerticalSpeed(moving, ASCEND_SPEED);
			ascending = 0;
			return;
		}

		BlockState below = mc.level.getBlockState(moving.blockPosition().below());
		boolean liquidBelow = below.is(Blocks.WATER) || below.is(Blocks.LAVA) || below.getOptionalValue(BlockStateProperties.WATERLOGGED).orElse(false);
		// One more push to clear the surface, then settle on it
		if (ascending == 0) setVerticalSpeed(moving, ASCEND_SPEED);
		else if (ascending == 1 && liquidBelow) setVerticalSpeed(moving, 0);
		ascending++;
	}

	/**
	 * Called from AbstractBlockStateMixin: gives liquid blocks below the player a full collision
	 * shape so they can be stood on.
	 */
	public boolean isSolid(BlockState state, BlockPos pos) {
		if (!isEnabled() || mc.player == null || mc.player.isSwimming()) return false;
		FluidState fluid = state.getFluidState();
		if (fluid.isEmpty()) return false;
		if (fluid.is(FluidTags.WATER)) return !mc.player.isInWater() && waterSolid() && pos.getY() <= mc.player.getY() - 1;
		if (fluid.is(FluidTags.LAVA)) return !mc.player.isInLava() && lavaSolid() && (lavaDangerous() || pos.getY() <= mc.player.getY() - 1);
		return false;
	}

	private boolean waterSolid() {
		if (mc.player.isSpectator() || mc.player.getAbilities().flying) return false;
		if (mc.player.getVehicle() instanceof AbstractBoat) return false;
		if (dipIfBurning.get() && mc.player.isOnFire()) return false;
		if (dipOnSneakWater.get() && mc.options.keyShift.isDown()) return false;
		if (dipOnFallWater.get() && mc.player.fallDistance > dipFallHeightWater.get()) return false;
		return waterMode.is(Mode.SOLID);
	}

	private boolean lavaSolid() {
		if (mc.player.isSpectator() || mc.player.getAbilities().flying) return false;
		if (mc.player.getVehicle() instanceof Strider) return false;
		if (lavaDangerous() && lavaMode.is(Mode.SOLID)) return true;
		if (dipOnSneakLava.get() && mc.options.keyShift.isDown()) return false;
		if (dipOnFallLava.get() && mc.player.fallDistance > dipFallHeightLava.get()) return false;
		return lavaMode.is(Mode.SOLID);
	}

	/** Lava is dangerous unless Dip If Fire Resistant is on and Fire Resistance has 15+ seconds left. */
	private boolean lavaDangerous() {
		if (!dipIfFireResistant.get()) return true;
		MobEffectInstance resistance = mc.player.getEffect(MobEffects.FIRE_RESISTANCE);
		return resistance == null || resistance.getDuration() <= 15 * 20;
	}

	private static void setVerticalSpeed(Entity entity, double y) {
		Vec3 velocity = entity.getDeltaMovement();
		entity.setDeltaMovement(velocity.x, y, velocity.z);
	}
}
