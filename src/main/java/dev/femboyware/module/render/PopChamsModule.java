package dev.femboyware.module.render;

import dev.femboyware.event.PacketEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.NumberSetting;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;

/**
 * Leaves a glowing ghost of a player where they popped a totem, drifting upwards until it
 * disappears. The ghost is a client-side copy of the player; its outline colour comes from the
 * entity mixin.
 */
public class PopChamsModule extends Module {
	private static final class Ghost {
		final RemotePlayer entity;
		int age;

		Ghost(RemotePlayer entity) {
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
		if (!(event.getPacket() instanceof ClientboundEntityEventPacket packet) || packet.getEventId() != EntityEvent.PROTECTED_FROM_DEATH) return;
		mc.execute(() -> {
			if (!isEnabled() || mc.level == null) return;
			if (packet.getEntity(mc.level) instanceof Player player && (player != mc.player || self.get())) spawn(player);
		});
	}

	@Override
	public void onTick() {
		for (Iterator<Ghost> iterator = ghosts.iterator(); iterator.hasNext(); ) {
			Ghost ghost = iterator.next();
			if (++ghost.age > duration.getInt() || ghost.entity.level() != mc.level) {
				remove(ghost);
				iterator.remove();
				continue;
			}
			ghost.entity.setPos(ghost.entity.getX(), ghost.entity.getY() + rise.get(), ghost.entity.getZ());
		}
	}

	private void spawn(Player player) {
		RemotePlayer ghost = new RemotePlayer(mc.level, player.getGameProfile());
		ghost.setId(nextId--);
		ghost.copyPosition(player);
		ghost.setYHeadRot(player.getYHeadRot());
		ghost.setYBodyRot(player.getVisualRotationYInDegrees());
		ghost.setPose(player.getPose());
		ghost.setGlowingTag(true);
		ghost.noPhysics = true;
		if (armor.get()) {
			for (EquipmentSlot slot : EquipmentSlot.values()) {
				if (slot.isArmor()) ghost.setItemSlot(slot, player.getItemBySlot(slot).copy());
			}
		}
		mc.level.addEntity(ghost);
		ghosts.add(new Ghost(ghost));
	}

	private void remove(Ghost ghost) {
		if (mc.level != null && ghost.entity.level() == mc.level) {
			mc.level.removeEntity(ghost.entity.getId(), Entity.RemovalReason.DISCARDED);
		}
	}
}
