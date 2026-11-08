package dev.femboyware.module.render;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerSkin;

/**
 * A femboyware cape: pink, with a glowing heart. Minecraft's own cape renderer draws it, so it
 * waves and swings like a real one, and turns into matching wings if you wear an elytra. Only you
 * see it. Needs Cape turned on in Skin Customization.
 */
public class CapeModule extends Module {
	private static final ClientAsset.Texture CAPE = new ClientAsset.ResourceTexture(
		Identifier.fromNamespaceAndPath("femboyware", "cape"), Identifier.fromNamespaceAndPath("femboyware", "textures/cape.png"));

	private final BoolSetting friends = add(new BoolSetting("Friends", "Give your friends the cape too", false));
	private final BoolSetting replace = add(new BoolSetting("Replace Capes", "Show it even over a real Minecraft or OptiFine cape you have", true));

	public CapeModule() {
		super("Cape", "A femboyware cape only you can see", Category.COSMETIC);
		setEnabled(true);
	}

	/** Called with the skin of the player called {@code name}: gives it the femboyware cape if it should have one. */
	public PlayerSkin apply(PlayerSkin textures, String name) {
		if (!isEnabled() || mc.player == null) return textures;
		boolean you = name.equalsIgnoreCase(mc.getUser().getName()) || name.equalsIgnoreCase(mc.player.getGameProfile().name());
		if (!you && !(friends.get() && FemboyWare.getFriends().isFriend(name))) return textures;
		if (textures.cape() != null && !replace.get()) return textures;
		return new PlayerSkin(textures.body(), CAPE, textures.elytra(), textures.model(), textures.secure());
	}
}
