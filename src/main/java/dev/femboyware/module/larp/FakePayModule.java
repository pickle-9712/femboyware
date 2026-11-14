package dev.femboyware.module.larp;

import dev.femboyware.manager.CommandManager;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.module.utility.FakeEconomy;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.util.ChatUtil;
import dev.femboyware.util.Mc;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.ChatFormatting;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

/**
 * Payments coming in, on your screen only: "<name> paid you $X." in the server's style, landing on
 * your fake balance. Incoming only; it doesn't make "you paid" messages.
 */
public class FakePayModule extends Module {
	private final BoolSetting toBalance = add(new BoolSetting("Add To Balance", "Payments go on your fake balance (see /fakebal)", true));
	private final BoolSetting ding = add(new BoolSetting("Sound", "The payment ding", true));

	public FakePayModule() {
		super("Fake Pay", "Fake payments coming in: " + CommandManager.PREFIX + "fakepay <player|random> <amount>", Category.LARP);
	}

	/** Handles .fakepay <player|random> <amount> [times]. */
	public void command(String[] args) {
		String usage = "Usage: " + CommandManager.PREFIX + "fakepay <player|random> <amount> [times]  (someone paying you, on your screen only)";
		if (args.length < 3) {
			ChatUtil.info(usage);
			return;
		}
		double amount;
		int times = 1;
		try {
			amount = parse(args[2]);
			if (args.length > 3) times = Math.max(1, Math.min(20, Integer.parseInt(args[3])));
		} catch (RuntimeException e) {
			ChatUtil.info(usage);
			return;
		}
		if (!isEnabled()) setEnabled(true);
		for (int i = 0; i < times; i++) {
			String from = args[1].equalsIgnoreCase("random") ? randomName() : args[1];
			receive(from, amount);
		}
	}

	private void receive(String from, double amount) {
		Mc.addChat(Component.literal(from).withStyle(ChatFormatting.AQUA)
			.append(Component.literal(" paid you ").withStyle(ChatFormatting.WHITE))
			.append(Component.literal(FakeEconomy.money(amount)).withStyle(ChatFormatting.GREEN))
			.append(Component.literal(".").withStyle(ChatFormatting.WHITE)));
		if (toBalance.get()) FakeEconomy.add(amount);
		if (ding.get()) mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.5f, 0.6f));
	}

	/** Someone on the server who isn't you, from the tab list. */
	private String randomName() {
		List<String> names = new ArrayList<>();
		if (mc.getConnection() != null) {
			for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
				String name = info.getProfile().name();
				if (mc.player == null || !name.equals(mc.player.getGameProfile().name())) names.add(name);
			}
		}
		if (names.isEmpty()) return "Steve";
		return names.get(ThreadLocalRandom.current().nextInt(names.size()));
	}

	/** 150, 1.5k, 5m, 2b. */
	private static double parse(String text) {
		String clean = text.replace(",", "").replace("$", "").trim().toLowerCase(Locale.ROOT);
		double scale = switch (clean.isEmpty() ? ' ' : clean.charAt(clean.length() - 1)) {
			case 'k' -> 1e3;
			case 'm' -> 1e6;
			case 'b' -> 1e9;
			case 't' -> 1e12;
			default -> 1;
		};
		if (scale != 1) clean = clean.substring(0, clean.length() - 1);
		return Double.parseDouble(clean) * scale;
	}
}
