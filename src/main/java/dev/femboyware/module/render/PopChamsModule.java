package dev.femboyware.module.render;

import dev.femboyware.event.PacketEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.NumberSetting;
import net.minecraft.client.network.OtherClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityStatuses;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Leaves a glowing ghost of a player where they popped a totem, drifting upwards until it
 * disappears. The ghost is a client-side copy of the player; its outline colour comes from the
 * entity mixin.
 */
public class PopChamsModule extends Module {
	private static final class Ghost {
		final OtherClientPlayerEntity entity;
		int age;

		Ghost(OtherClientPlayerEntity entity) {
			this.entity = entity;
		}
	}

	private final NumberSetting duration = add(new NumberSetting("Duration", "Ticks the ghost stays", 30, 5, 100, 1));
	private final NumberSetting rise = add(new NumberSetting("Rise", "Blocks the ghost floats up per tick", 0.05, 0, 0.3, 0.01));
	private final BoolSetting armor = add(new BoolSetting("Armor", "Copy the player's armor onto the ghost", false));
	private final BoolSetting self = add(new BoolSetting("Self", "Also for your own pops", false));
	private final ColorSetting color = add(new ColorSetting("Color", "Ghost outline colour", 0xFFFF77C8));

	private final List<Ghost> ghosts = new ArrayList<>();
	private int nextId = -300000;

	public PopChamsModule() {
		super("Pop Chams", "Shows a ghost where players pop totems", Category.COSMETIC);
	}

	public boolean isGhost(Entity entity) {
		for (Ghost ghost : ghosts) {
			if (ghost.entity == entity) return true;
		}
		return false;
	}

	public int getColor() {
		return color.get() & 0xFFFFFF;
	}

	@Override
	protected void onDisable() {
		for (Ghost ghost : ghosts) remove(ghost);
		ghosts.clear();
	}

	@Override
	public void onPacketReceive(PacketEvent event) {
		if (!(event.getPacket() instanceof EntityStatusS2CPacket packet) || packet.getStatus() != EntityStatuses.USE_TOTEM_OF_UNDYING) return;
		mc.execute(() -> {
			if (!isEnabled() || mc.world == null) return;
			if (packet.getEntity(mc.world) instanceof PlayerEntity player && (player != mc.player || self.get())) spawn(player);
		});
	}

	@Override
	public void onTick() {
		for (Iterator<Ghost> iterator = ghosts.iterator(); iterator.hasNext(); ) {
			Ghost ghost = iterator.next();
			if (++ghost.age > duration.getInt() || ghost.entity.getEntityWorld() != mc.world) {
				remove(ghost);
				iterator.remove();
				continue;
			}
			ghost.entity.setPosition(ghost.entity.getX(), ghost.entity.getY() + rise.get(), ghost.entity.getZ());
		}
	}

	private void spawn(PlayerEntity player) {
		OtherClientPlayerEntity ghost = new OtherClientPlayerEntity(mc.world, player.getGameProfile());
		ghost.setId(nextId--);
		ghost.copyPositionAndRotation(player);
		ghost.setHeadYaw(player.getHeadYaw());
		ghost.setBodyYaw(player.getBodyYaw());
		ghost.setPose(player.getPose());
		ghost.setGlowing(true);
		ghost.noClip = true;
		if (armor.get()) {
			for (EquipmentSlot slot : EquipmentSlot.values()) {
				if (slot.isArmorSlot()) ghost.equipStack(slot, player.getEquippedStack(slot).copy());
			}
		}
		mc.world.addEntity(ghost);
		ghosts.add(new Ghost(ghost));
	}

	private void remove(Ghost ghost) {
		if (mc.world != null && ghost.entity.getEntityWorld() == mc.world) {
			mc.world.removeEntity(ghost.entity.getId(), Entity.RemovalReason.DISCARDED);
		}
	}
}
