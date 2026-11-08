package dev.femboyware.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.client.FakePlayerModule;
import dev.femboyware.module.render.CapeModule;
import dev.femboyware.module.render.FurryModule;
import dev.femboyware.module.utility.NameProtectModule;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerInfo.class)
public class PlayerListEntryMixin {
	/** A fake player's row has a made up UUID, so this stops the game asking Mojang for its skin. */
	@Inject(method = "getSkin", at = @At("HEAD"), cancellable = true)
	private void femboyware$fakePlayerSkin(CallbackInfoReturnable<PlayerSkin> cir) {
		if (FemboyWare.getModules() == null) return;
		PlayerSkin fake = FemboyWare.getModules().get(FakePlayerModule.class).tabSkin(((PlayerInfo) (Object) this).getProfile());
		if (fake != null) cir.setReturnValue(fake);
	}

	/** Name Protect: an account's real skin for fake names, or the default skin for Skin Protect. */
	@ModifyReturnValue(method = "getSkin", at = @At("RETURN"))
	private PlayerSkin femboyware$skinProtect(PlayerSkin textures) {
		if (FemboyWare.getModules() == null) return textures;
		PlayerInfo entry = (PlayerInfo) (Object) this;
		if (FemboyWare.getModules().get(FakePlayerModule.class).tabSkin(entry.getProfile()) != null) return textures;
		textures = FemboyWare.getModules().get(NameProtectModule.class).skinFor(entry.getProfile(), textures);
		// Cape: the femboyware cape on you, and on friends if you like
		textures = FemboyWare.getModules().get(CapeModule.class).apply(textures, entry.getProfile().name());
		// Furry: your fursona skin
		return FemboyWare.getModules().get(FurryModule.class).apply(textures, entry.getProfile().name());
	}
}
