package dev.femboyware.module.render;

import dev.femboyware.event.PacketEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import it.unimi.dsi.fastutil.ints.IntList;
import java.util.List;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.component.FireworkExplosion;

/** Celebrates kills and totem pops with a client-side firework burst over the player. */
public class ThrowPetardModule extends Module {
	private final BoolSetting kills = add(new BoolSetting("Kills", "Burst when a player dies", true));
	private final BoolSetting pops = add(new BoolSetting("Pops", "Burst when a player pops a totem", false));
	private final NumberSetting range = add(new NumberSetting("Range", "Only for players this close", 32, 4, 128, 1));
	private final EnumSetting<FireworkExplosion.Shape> shape = add(new EnumSetting<>("Shape", "Firework shape", FireworkExplosion.Shape.LARGE_BALL));
	private final ColorSetting color = add(new ColorSetting("Color", "Main colour", 0xFFFF77C8));
	private final ColorSetting fade = add(new ColorSetting("Fade", "Colour it fades to", 0xFF77C8FF));
	private final BoolSetting trail = add(new BoolSetting("Trail", "Sparks leave a trail", true));
	private final BoolSetting twinkle = add(new BoolSetting("Twinkle", "Sparks crackle", true));

	public ThrowPetardModule() {
		super("Throw Petard", "Fireworks over players you kill", Category.COSMETIC);
	}

	@Override
	public void onPacketReceive(PacketEvent event) {
		if (!(event.getPacket() instanceof ClientboundEntityEventPacket packet)) return;
		byte status = packet.getEventId();
		boolean wanted = status == EntityEvent.DEATH && kills.get()
			|| status == EntityEvent.PROTECTED_FROM_DEATH && pops.get();
		if (!wanted) return;
		mc.execute(() -> {
			if (!isEnabled() || mc.level == null) return;
			Entity entity = packet.getEntity(mc.level);
			if (entity instanceof Player player && player != mc.player && mc.player.distanceTo(player) <= range.get()) burst(player);
		});
	}

	private void burst(Player player) {
		FireworkExplosion explosion = new FireworkExplosion(shape.get(),
			IntList.of(color.get() & 0xFFFFFF), IntList.of(fade.get() & 0xFFFFFF), trail.get(), twinkle.get());
		mc.level.createFireworks(player.getX(), player.getY() + player.getBbHeight() + 1.5, player.getZ(), 0, 0, 0, List.of(explosion));
	}
}
