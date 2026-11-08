package dev.femboyware.module.render;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.NumberSetting;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.level.GameType;

/**
 * A bigger tab list with your own name highlighted, ping in milliseconds and game modes.
 * The tab list itself is changed in PlayerListHudMixin.
 * Ported from Meteor Client's BetterTab (https://github.com/MeteorDevelopment/meteor-client), GPL-3.0.
 */
public class BetterTabModule extends Module {
	/** Extra column width for the ping number in place of the bars. */
	public static final int LATENCY_WIDTH = 30;

	private final NumberSetting tabSize = add(new NumberSetting("Tab Size", "Most players shown, vanilla is 80", 100, 1, 1000, 1));
	private final NumberSetting tabHeight = add(new NumberSetting("Column Height", "Players per column, vanilla is 20", 20, 1, 100, 1));
	private final BoolSetting self = add(new BoolSetting("Self", "Highlight yourself", true));
	private final ColorSetting selfColor = add(new ColorSetting("Self Color", "Colour of your own name", 0xFFFF77C8)).visibleWhen(self::get);
	private final BoolSetting accurateLatency = add(new BoolSetting("Accurate Latency", "Show ping in milliseconds instead of bars", true));
	private final BoolSetting gameMode = add(new BoolSetting("Game Mode", "Show each player's game mode", false));

	public BetterTabModule() {
		super("Better Tab", "Improves the tab list", Category.COSMETIC);
	}

	public long tabSize(long vanilla) {
		return isEnabled() ? tabSize.getInt() : vanilla;
	}

	public int tabHeight(int vanilla) {
		return isEnabled() ? tabHeight.getInt() : vanilla;
	}

	public boolean accurateLatency() {
		return isEnabled() && accurateLatency.get();
	}

	/** Vanilla's tab name for {@code entry} with our highlight and game mode added. */
	public Component playerName(PlayerInfo entry, Component name) {
		if (!isEnabled()) return name;
		if (self.get() && mc.player != null && entry.getProfile().id().equals(mc.player.getGameProfile().id())) {
			// Strip colour codes so our colour wins
			String plain = ChatFormatting.stripFormatting(name.getString());
			name = Component.literal(plain).setStyle(name.getStyle().withColor(TextColor.fromRgb(selfColor.get() & 0xFFFFFF)));
		}
		if (gameMode.get()) {
			GameType mode = entry.getGameMode();
			String tag = mode == null ? "?" : switch (mode) {
				case SPECTATOR -> "Sp";
				case SURVIVAL -> "S";
				case CREATIVE -> "C";
				case ADVENTURE -> "A";
			};
			MutableComponent text = Component.empty().append(name);
			text.append(" [" + tag + "]");
			name = text;
		}
		return name;
	}

	/** Draws the ping as a coloured number where vanilla draws its bars. */
	public void drawLatency(GuiGraphics context, int width, int x, int y, PlayerInfo entry) {
		int latency = entry.getLatency();
		String text = latency + "ms";
		int color = latency < 150 ? 0xFF77FF99 : latency < 300 ? 0xFFFFD966 : 0xFFFF6666;
		context.drawString(mc.font, text, x + width - mc.font.width(text) - 1, y, color);
	}
}
