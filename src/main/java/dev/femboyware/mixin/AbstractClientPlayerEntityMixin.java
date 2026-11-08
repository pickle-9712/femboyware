package dev.femboyware.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.client.FakePlayerModule;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(AbstractClientPlayer.class)
public class AbstractClientPlayerEntityMixin {
	/** Fake players aren't in the tab list, so PlayerListEntryMixin never sees them. */
	@ModifyReturnValue(method = "getSkin", at = @At("RETURN"))
	private PlayerSkin femboyware$fakePlayerSkin(PlayerSkin textures) {
		if (FemboyWare.getModules() == null) return textures;
		return FemboyWare.getModules().get(FakePlayerModule.class).skinFor((AbstractClientPlayer) (Object) this, textures);
	}
}
