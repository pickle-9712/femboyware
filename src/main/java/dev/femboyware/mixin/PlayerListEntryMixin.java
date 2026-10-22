package dev.femboyware.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.utility.NameProtectModule;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.entity.player.SkinTextures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(PlayerListEntry.class)
public class PlayerListEntryMixin {
	/** Name Protect's Skin Protect: everyone gets the default skin for their UUID. */
	@ModifyReturnValue(method = "getSkinTextures", at = @At("RETURN"))
	private SkinTextures femboyware$skinProtect(SkinTextures textures) {
		if (FemboyWare.getModules() == null || !FemboyWare.getModules().get(NameProtectModule.class).skinProtect()) return textures;
		return DefaultSkinHelper.getSkinTextures(((PlayerListEntry) (Object) this).getProfile());
	}
}
