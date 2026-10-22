package dev.femboyware.module.movement;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.passive.StriderEntity;
import net.minecraft.entity.vehicle.AbstractBoatEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

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
		if (mc.player.isTouchingWater() && !waterSolid()) return;
		if (mc.player.isInLava() && !lavaSolid()) return;

		Entity moving = mc.player.hasVehicle() ? mc.player.getVehicle() : mc.player;
		if (moving.isTouchingWater() || moving.isInLava()) {
			// Float back up to the surface
			setVerticalSpeed(moving, ASCEND_SPEED);
			ascending = 0;
			return;
		}

		BlockState below = mc.world.getBlockState(moving.getBlockPos().down());
		boolean liquidBelow = below.isOf(Blocks.WATER) || below.isOf(Blocks.LAVA) || below.getOrEmpty(Properties.WATERLOGGED).orElse(false);
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
		if (fluid.isIn(FluidTags.WATER)) return !mc.player.isTouchingWater() && waterSolid() && pos.getY() <= mc.player.getY() - 1;
		if (fluid.isIn(FluidTags.LAVA)) return !mc.player.isInLava() && lavaSolid() && (lavaDangerous() || pos.getY() <= mc.player.getY() - 1);
		return false;
	}

	private boolean waterSolid() {
		if (mc.player.isSpectator() || mc.player.getAbilities().flying) return false;
		if (mc.player.getVehicle() instanceof AbstractBoatEntity) return false;
		if (dipIfBurning.get() && mc.player.isOnFire()) return false;
		if (dipOnSneakWater.get() && mc.options.sneakKey.isPressed()) return false;
		if (dipOnFallWater.get() && mc.player.fallDistance > dipFallHeightWater.get()) return false;
		return waterMode.is(Mode.SOLID);
	}

	private boolean lavaSolid() {
		if (mc.player.isSpectator() || mc.player.getAbilities().flying) return false;
		if (mc.player.getVehicle() instanceof StriderEntity) return false;
		if (lavaDangerous() && lavaMode.is(Mode.SOLID)) return true;
		if (dipOnSneakLava.get() && mc.options.sneakKey.isPressed()) return false;
		if (dipOnFallLava.get() && mc.player.fallDistance > dipFallHeightLava.get()) return false;
		return lavaMode.is(Mode.SOLID);
	}

	/** Lava is dangerous unless Dip If Fire Resistant is on and Fire Resistance has 15+ seconds left. */
	private boolean lavaDangerous() {
		if (!dipIfFireResistant.get()) return true;
		StatusEffectInstance resistance = mc.player.getStatusEffect(StatusEffects.FIRE_RESISTANCE);
		return resistance == null || resistance.getDuration() <= 15 * 20;
	}

	private static void setVerticalSpeed(Entity entity, double y) {
		Vec3d velocity = entity.getVelocity();
		entity.setVelocity(velocity.x, y, velocity.z);
	}
}
