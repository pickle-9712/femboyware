package dev.femboyware.util;

import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.DyedItemColor;

/**
 * Team Check for the PvP modules: whether a player is on your team. Same scoreboard team (what
 * minigames like Bedwars use), the same name colour, or the same dyed leather helmet.
 */
public final class Teams {
	private static final Minecraft mc = Minecraft.getInstance();
	private static final TextColor WHITE = TextColor.fromLegacyFormat(ChatFormatting.WHITE);

	private Teams() {
	}

	public static boolean isTeammate(Entity entity) {
		if (!(entity instanceof Player player) || mc.player == null || player == mc.player) return false;
		if (mc.player.getTeam() != null && mc.player.isAlliedTo(player)) return true;
		TextColor mine = nameColor(mc.player.getDisplayName()), theirs = nameColor(player.getDisplayName());
		if (mine != null && mine.equals(theirs)) return true;
		Integer myHelmet = helmetColor(mc.player), theirHelmet = helmetColor(player);
		return myHelmet != null && myHelmet.equals(theirHelmet);
	}

	/** The first real colour in a name, white and uncoloured not counting as a team. */
	private static TextColor nameColor(Component name) {
		if (name == null) return null;
		return name.visit((Style style, String text) -> {
			TextColor color = style.getColor();
			if (text.isBlank()) return Optional.<TextColor>empty();
			if (color != null && !color.equals(WHITE)) return Optional.of(color);
			// Legacy § codes written into the text itself
			int i = text.indexOf('§');
			if (i >= 0 && i + 1 < text.length()) {
				ChatFormatting format = ChatFormatting.getByCode(text.charAt(i + 1));
				TextColor legacy = format == null || format == ChatFormatting.WHITE ? null : TextColor.fromLegacyFormat(format);
				if (legacy != null) return Optional.of(legacy);
			}
			return Optional.<TextColor>empty();
		}, Style.EMPTY).orElse(null);
	}

	private static Integer helmetColor(Player player) {
		ItemStack helmet = player.getItemBySlot(EquipmentSlot.HEAD);
		if (!helmet.is(Items.LEATHER_HELMET)) return null;
		DyedItemColor dyed = helmet.get(DataComponents.DYED_COLOR);
		return dyed == null ? null : dyed.rgb();
	}
}
