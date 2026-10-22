package dev.femboyware.module.render;

import dev.femboyware.event.PacketEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.component.type.FireworkExplosionComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityStatuses;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;

import java.util.List;

/** Celebrates kills and totem pops with a client-side firework burst over the player. */
public class ThrowPetardModule extends Module {
	private final BoolSetting kills = add(new BoolSetting("Kills", "Burst when a player dies", true));
	private final BoolSetting pops = add(new BoolSetting("Pops", "Burst when a player pops a totem", false));
	private final NumberSetting range = add(new NumberSetting("Range", "Only for players this close", 32, 4, 128, 1));
	private final EnumSetting<FireworkExplosionComponent.Type> shape = add(new EnumSetting<>("Shape", "Firework shape", FireworkExplosionComponent.Type.LARGE_BALL));
	private final ColorSetting color = add(new ColorSetting("Color", "Main colour", 0xFFFF77C8));
	private final ColorSetting fade = add(new ColorSetting("Fade", "Colour it fades to", 0xFF77C8FF));
	private final BoolSetting trail = add(new BoolSetting("Trail", "Sparks leave a trail", true));
	private final BoolSetting twinkle = add(new BoolSetting("Twinkle", "Sparks crackle", true));

	public ThrowPetardModule() {
		super("Throw Petard", "Fireworks over players you kill", Category.COSMETIC);
	}

	@Override
	public void onPacketReceive(PacketEvent event) {
		if (!(event.getPacket() instanceof EntityStatusS2CPacket packet)) return;
		byte status = packet.getStatus();
		boolean wanted = status == EntityStatuses.PLAY_DEATH_SOUND_OR_ADD_PROJECTILE_HIT_PARTICLES && kills.get()
			|| status == EntityStatuses.USE_TOTEM_OF_UNDYING && pops.get();
		if (!wanted) return;
		mc.execute(() -> {
			if (!isEnabled() || mc.world == null) return;
			Entity entity = packet.getEntity(mc.world);
			if (entity instanceof PlayerEntity player && player != mc.player && mc.player.distanceTo(player) <= range.get()) burst(player);
		});
	}

	private void burst(PlayerEntity player) {
		FireworkExplosionComponent explosion = new FireworkExplosionComponent(shape.get(),
			IntList.of(color.get() & 0xFFFFFF), IntList.of(fade.get() & 0xFFFFFF), trail.get(), twinkle.get());
		mc.world.addFireworkParticle(player.getX(), player.getY() + player.getHeight() + 1.5, player.getZ(), 0, 0, 0, List.of(explosion));
	}
}
