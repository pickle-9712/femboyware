package dev.femboyware.module.movement;

import dev.femboyware.util.Mc;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import net.minecraft.core.component.DataComponents;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.FireworkRocketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Fireworks;

/**
 * Boosts your elytra with a client-side firework, so using a rocket while flying doesn't use it up.
 * Ported from Meteor Client's ElytraBoost (https://github.com/MeteorDevelopment/meteor-client), GPL-3.0.
 * Meteor's separate boost keybind isn't included; right click a rocket instead.
 */
public class ElytraBoostModule extends Module {
	private final BoolSetting dontConsume = add(new BoolSetting("Dont Consume", "Using a rocket while flying boosts without spending it", true));
	private final NumberSetting fireworkLevel = add(new NumberSetting("Firework Level", "Flight duration of the fake rocket", 0, 0, 10, 1));
	private final BoolSetting playSound = add(new BoolSetting("Play Sound", "Play the launch sound", true));

	private final Set<FireworkRocketEntity> fireworks = Collections.newSetFromMap(new IdentityHashMap<>());

	public ElytraBoostModule() {
		super("Elytra Boost", "Boosts your elytra without using rockets", Category.RAGE);
	}

	@Override
	protected void onDisable() {
		fireworks.clear();
	}

	@Override
	public void onTick() {
		fireworks.removeIf(Entity::isRemoved);
	}

	/** Called from the interaction manager mixin. Returns true when the rocket use was replaced by a boost. */
	public boolean onUseItem(InteractionHand hand) {
		if (!isEnabled() || !dontConsume.get()) return false;
		if (!(mc.player.getItemInHand(hand).getItem() instanceof FireworkRocketItem)) return false;
		if (!mc.player.isFallFlying() || Mc.screen() != null) return false;
		boost();
		return true;
	}

	private void boost() {
		ItemStack stack = Items.FIREWORK_ROCKET.getDefaultInstance();
		stack.set(DataComponents.FIREWORKS, new Fireworks(fireworkLevel.getInt(), List.of()));
		FireworkRocketEntity firework = new FireworkRocketEntity(mc.level, stack, mc.player);
		fireworks.add(firework);
		if (playSound.get()) {
			mc.level.playLocalSound(mc.player.getX(), mc.player.getY(), mc.player.getZ(), SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.AMBIENT, 3f, 1f, false);
		}
		mc.level.addEntity(firework);
	}

	public boolean isOurFirework(FireworkRocketEntity firework) {
		return isEnabled() && fireworks.contains(firework);
	}
}
