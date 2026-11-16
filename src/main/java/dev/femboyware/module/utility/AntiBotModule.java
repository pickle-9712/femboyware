package dev.femboyware.module.utility;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.module.client.FakePlayerModule;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import java.util.regex.Pattern;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.EquipmentSlot;

/**
 * Tells server NPCs and anticheat bots apart from real players, so every combat module (anything
 * that picks targets through TargetUtil) leaves them alone: shop keepers, lobby NPCs, and the fake
 * players anticheats spawn round you to catch auras.
 */
public class AntiBotModule extends Module {
	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

	private final BoolSetting tabList = add(new BoolSetting("Tab List", "Not in the tab list: NPCs and most anticheat bots never are", true));
	private final BoolSetting npcIds = add(new BoolSetting("NPC IDs", "A version 2 UUID, which plugins like Citizens give NPCs and real accounts never have", true));
	private final BoolSetting badNames = add(new BoolSetting("Bad Names", "A name no real account can have: colours, spaces, symbols or over 16 letters", true));
	private final BoolSetting duplicates = add(new BoolSetting("Duplicates", "Two players with the same name: the one the tab list doesn't know is the copy", true));
	private final BoolSetting noPing = add(new BoolSetting("No Ping", "A ping of exactly 0 in the tab list. Some servers show 0 for everyone, so it's off by default", false));
	private final BoolSetting naked = add(new BoolSetting("Invisible Naked", "Invisible with no armour and nothing in hand, which is how anticheat bots often hide", false));
	private final NumberSetting minAge = add(new NumberSetting("Min Age", "Ticks someone has to be in view before they count; anticheat bots flash in and out", 0, 0, 100, 1));

	public AntiBotModule() {
		super("Anti Bot", "Combat modules ignore NPCs and anticheat bots", Category.MISC);
	}

	/** Whether {@code player} looks like a bot to the checks that are on; false while this module's off. */
	public static boolean isBot(Player player) {
		AntiBotModule module = FemboyWare.getModules().get(AntiBotModule.class);
		return module.isEnabled() && module.check(player);
	}

	private boolean check(Player player) {
		if (player == mc.player || mc.getConnection() == null) return false;
		// Our own test dummies stay fair game
		if (FemboyWare.getModules().get(FakePlayerModule.class).isFake(player.getId())) return false;
		String name = player.getGameProfile().name();
		PlayerInfo info = mc.getConnection().getPlayerInfo(player.getUUID());
		if (tabList.get() && info == null) return true;
		if (npcIds.get() && player.getUUID().version() == 2) return true;
		if (badNames.get() && !NAME.matcher(name).matches()) return true;
		if (duplicates.get() && info == null) {
			for (Player other : mc.level.players()) {
				if (other != player && other.getGameProfile().name().equals(name) && mc.getConnection().getPlayerInfo(other.getUUID()) != null) return true;
			}
		}
		if (noPing.get() && info != null && info.getLatency() == 0) return true;
		if (naked.get() && player.isInvisible() && player.getMainHandItem().isEmpty() && wearsNothing(player)) return true;
		return player.tickCount < minAge.getInt();
	}

	private static boolean wearsNothing(Player player) {
		for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
			ItemStack stack = player.getItemBySlot(slot);
			if (!stack.isEmpty()) return false;
		}
		return true;
	}
}
