package dev.femboyware.module.larp;

import dev.femboyware.FemboyWare;
import dev.femboyware.manager.CommandManager;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.StringSetting;
import dev.femboyware.util.AccountSkins;
import dev.femboyware.util.ChatUtil;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.PlayerSkin;

/**
 * Be someone else, on your screen: their skin, their cape and their name everywhere yours would
 * show (chat, tab, your name tag, the scoreboard). Only you see it.
 */
public class LarpAsModule extends Module {
	private final StringSetting player = add(new StringSetting("Player", "Who to be (a player's name)", "Technoblade"));
	private final BoolSetting skin = add(new BoolSetting("Skin", "Wear their skin", true));
	private final BoolSetting cape = add(new BoolSetting("Cape", "Wear their cape, if they have one", true));
	private final BoolSetting name = add(new BoolSetting("Name", "Their name wherever yours shows", true));

	public LarpAsModule() {
		super("Larp As", "Copy someone's skin, cape and name (only on your screen): " + CommandManager.PREFIX + "larpas <name>", Category.LARP);
	}

	@Override
	public String getInfo() {
		return player.get();
	}

	private String who() {
		return player.get().trim();
	}

	/** From AbstractClientPlayerEntityMixin: their skin and cape on you. */
	public PlayerSkin skinFor(AbstractClientPlayer self, PlayerSkin textures) {
		if (!isEnabled() || self != mc.player || who().isEmpty() || !(skin.get() || cape.get())) return textures;
		PlayerSkin theirs = AccountSkins.get(who());
		if (theirs == null) return textures;
		return new PlayerSkin(skin.get() ? theirs.body() : textures.body(), cape.get() ? theirs.cape() : textures.cape(),
			cape.get() ? theirs.elytra() : textures.elytra(), skin.get() ? theirs.model() : textures.model(), textures.secure());
	}

	/** From TextVisitFactoryMixin, before Name Protect: your name swapped for theirs, as a whole word. */
	public String replace(String text) {
		if (!isEnabled() || !name.get() || text == null || mc.getUser() == null || who().isEmpty()) return text;
		String you = mc.getUser().getName();
		if (you.isEmpty() || !text.contains(you)) return text;
		return text.replaceAll("(?<![A-Za-z0-9_])" + java.util.regex.Pattern.quote(you) + "(?![A-Za-z0-9_])", java.util.regex.Matcher.quoteReplacement(who()));
	}

	/** Handles {@code .larpas <name>} and {@code .larpas off}. */
	public void command(String[] args) {
		if (args.length < 2) {
			ChatUtil.info((isEnabled() ? "Larping as " + who() : "Larp As is off") + ". Usage: " + CommandManager.PREFIX + "larpas <name> | off");
			return;
		}
		if (args[1].equalsIgnoreCase("off")) {
			setEnabled(false);
			ChatUtil.info("Back to being you");
			return;
		}
		player.set(args[1]);
		if (!isEnabled()) setEnabled(true);
		FemboyWare.getConfig().save();
		ChatUtil.info("You're " + args[1] + " now: their skin, cape and name (only you see it)");
	}
}
