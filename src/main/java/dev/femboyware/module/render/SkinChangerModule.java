package dev.femboyware.module.render;

import dev.femboyware.FemboyWare;
import dev.femboyware.manager.CommandManager;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.StringSetting;
import dev.femboyware.util.AccountSkins;
import dev.femboyware.util.ChatUtil;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

/**
 * Wear anyone's skin, on your screen: type a player's name and you look like them, in third person
 * and on your first person arm, keeping your own cape. Their arm shape too, unless you pick one.
 */
public class SkinChangerModule extends Module {
	public enum Arms { THEIRS, WIDE, SLIM }

	private final StringSetting skin = add(new StringSetting("Skin", "Whose skin to wear (a player's name)", "Technoblade"));
	private final EnumSetting<Arms> arms = add(new EnumSetting<>("Arms", "Their arm shape, or wide or slim arms", Arms.THEIRS));

	public SkinChangerModule() {
		super("Skin Changer", "Wear any player's skin (only on your screen): " + CommandManager.PREFIX + "skin <name>", Category.COSMETIC);
	}

	@Override
	public String getInfo() {
		return skin.get();
	}

	/** From AbstractClientPlayerEntityMixin: your skin swapped for the one you picked, once it's loaded. */
	public PlayerSkin skinFor(AbstractClientPlayer player, PlayerSkin textures) {
		if (!isEnabled() || player != mc.player || skin.get().isBlank()) return textures;
		PlayerSkin theirs = AccountSkins.get(skin.get().trim());
		if (theirs == null) return textures;
		PlayerModelType model = switch (arms.get()) {
			case THEIRS -> theirs.model();
			case WIDE -> PlayerModelType.WIDE;
			case SLIM -> PlayerModelType.SLIM;
		};
		return new PlayerSkin(theirs.body(), textures.cape(), textures.elytra(), model, theirs.secure());
	}

	/** Handles {@code .skin <name> [wide|slim]} and {@code .skin off}. */
	public void command(String[] args) {
		if (args.length < 2) {
			ChatUtil.info("Wearing " + skin.get() + (isEnabled() ? "" : " (off)") + ". Usage: " + CommandManager.PREFIX + "skin <name> [wide|slim] | off");
			return;
		}
		if (args[1].equalsIgnoreCase("off")) {
			setEnabled(false);
			ChatUtil.info("Back to your own skin");
			return;
		}
		skin.set(args[1]);
		arms.set(args.length > 2 && args[2].equalsIgnoreCase("slim") ? Arms.SLIM : args.length > 2 && args[2].equalsIgnoreCase("wide") ? Arms.WIDE : Arms.THEIRS);
		if (!isEnabled()) setEnabled(true);
		FemboyWare.getConfig().save();
		ChatUtil.info("Wearing " + args[1] + "'s skin (only you see it)");
	}
}
