package dev.femboyware.module.render;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.util.AssetInfo;
import net.minecraft.util.Identifier;

/**
 * A femboyware cape: pink, with a glowing heart. Minecraft's own cape renderer draws it, so it
 * waves and swings like a real one, and turns into matching wings if you wear an elytra. Only you
 * see it. Needs Cape turned on in Skin Customization.
 */
public class CapeModule extends Module {
	private static final AssetInfo.TextureAsset CAPE = new AssetInfo.TextureAssetInfo(
		Identifier.of("femboyware", "cape"), Identifier.of("femboyware", "textures/cape.png"));

	private final BoolSetting friends = add(new BoolSetting("Friends", "Give your friends the cape too", false));
	private final BoolSetting replace = add(new BoolSetting("Replace Capes", "Show it even over a real Minecraft or OptiFine cape you have", true));

	public CapeModule() {
		super("Cape", "A femboyware cape only you can see", Category.COSMETIC);
		setEnabled(true);
	}

	/** Called with the skin of the player called {@code name}: gives it the femboyware cape if it should have one. */
	public SkinTextures apply(SkinTextures textures, String name) {
		if (!isEnabled() || mc.player == null) return textures;
		boolean you = name.equalsIgnoreCase(mc.getSession().getUsername()) || name.equalsIgnoreCase(mc.player.getGameProfile().name());
		if (!you && !(friends.get() && FemboyWare.getFriends().isFriend(name))) return textures;
		if (textures.cape() != null && !replace.get()) return textures;
		return new SkinTextures(textures.body(), CAPE, textures.elytra(), textures.model(), textures.secure());
	}
}
