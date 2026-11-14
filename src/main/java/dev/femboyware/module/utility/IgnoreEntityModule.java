package dev.femboyware.module.utility;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;

/**
 * Your crosshair looks straight through the kinds of entity you pick, so you can mine, place and
 * open things behind item frames, armour stands, paintings and the like. The crosshair change is in
 * ClientPlayerEntityMixin, next to No Mining Trace.
 */
public class IgnoreEntityModule extends Module {
	private final BoolSetting frames = add(new BoolSetting("Item Frames", "Look through item frames", true));
	private final BoolSetting stands = add(new BoolSetting("Armor Stands", "Look through armour stands", true));
	private final BoolSetting paintings = add(new BoolSetting("Paintings", "Look through paintings", true));
	private final BoolSetting minecarts = add(new BoolSetting("Minecarts", "Look through minecarts", false));
	private final BoolSetting dropped = add(new BoolSetting("Items", "Look through dropped items and XP", true));
	private final BoolSetting players = add(new BoolSetting("Players", "Look through players (you can't hit them then)", false));
	private final BoolSetting friends = add(new BoolSetting("Friends", "Look through your friends so you don't hit them by accident", true));

	public IgnoreEntityModule() {
		super("Ignore Entity", "Look through item frames, armour stands and more", Category.PLAYER);
	}

	/** Whether the crosshair should look past {@code entity}. */
	public boolean ignores(Entity entity) {
		if (!isEnabled()) return false;
		if (entity instanceof Player player) {
			return players.get() || friends.get() && dev.femboyware.FemboyWare.getFriends().isFriend(player.getGameProfile().name());
		}
		return frames.get() && entity instanceof ItemFrame
			|| stands.get() && entity instanceof ArmorStand
			|| paintings.get() && net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath().equals("painting")
			|| minecarts.get() && net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath().contains("minecart")
			|| dropped.get() && (entity instanceof ItemEntity || entity instanceof ExperienceOrb);
	}
}
