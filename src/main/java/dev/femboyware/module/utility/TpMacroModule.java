package dev.femboyware.module.utility;

import dev.femboyware.event.PacketEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.setting.StringSetting;
import dev.femboyware.util.ChatUtil;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;

/**
 * Sends /tpa or /tpahere to one player on a timer, waiting out DonutSMP's "you need to wait
 * another N seconds" limit. Ported from the Anubis client's Tp Macro (GPL-3.0, by 4ldenz,
 * https://github.com/4ldenz/Anubis-client).
 */
public class TpMacroModule extends Module {
	private static final Pattern RATE_LIMIT = Pattern.compile("you need to wait another ([\\d.]+) seconds? to execute a command");
	private static final Pattern VALID_NAME = Pattern.compile("\\.?[A-Za-z0-9_]{1,16}");

	public enum Command {
		TPA, TPAHERE
	}

	private final EnumSetting<Command> command = add(new EnumSetting<>("Command", "/tpa (you go to them) or /tpahere (they come to you)", Command.TPA));
	private final StringSetting target = add(new StringSetting("Player", "Who to send it to", ""));
	private final NumberSetting interval = add(new NumberSetting("Interval", "Seconds between requests", 1, 0.25, 60, 0.25));

	private volatile long holdUntil;
	private long nextAt;
	private int sent;

	public TpMacroModule() {
		super("Tp Macro", "Sends /tpa or /tpahere to one player on a timer", Category.MISC);
	}

	private String line() {
		return command.get().name().toLowerCase(Locale.ROOT) + " " + target.get().trim();
	}

	@Override
	protected void onEnable() {
		if (!VALID_NAME.matcher(target.get().trim()).matches()) {
			ChatUtil.info("Tp Macro: set a valid Player name first");
			setEnabled(false);
			return;
		}
		holdUntil = 0;
		nextAt = 0;
		sent = 0;
		ChatUtil.info("Tp Macro: sending /" + line() + " every " + interval.get() + "s");
	}

	@Override
	protected void onDisable() {
		if (sent > 0) ChatUtil.info("Tp Macro: sent " + sent + (sent == 1 ? " request" : " requests"));
	}

	@Override
	public void onPacketReceive(PacketEvent event) {
		if (!(event.getPacket() instanceof ClientboundSystemChatPacket chat) || chat.content() == null) return;
		Matcher wait = RATE_LIMIT.matcher(chat.content().getString().toLowerCase(Locale.ROOT));
		if (!wait.find()) return;
		try {
			holdUntil = System.currentTimeMillis() + (long) Math.ceil(Math.min(30, Double.parseDouble(wait.group(1))) * 1000) + 80;
		} catch (NumberFormatException ignored) {
		}
	}

	@Override
	public void onTick() {
		if (mc.player == null || mc.getConnection() == null || mc.player.isDeadOrDying()) return;
		long now = System.currentTimeMillis();
		if (now < nextAt || now < holdUntil) return;
		mc.getConnection().sendCommand(line());
		sent++;
		nextAt = now + (long) (interval.get() * 1000);
	}
}
