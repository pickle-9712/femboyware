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
		AbstractClientPlayer self = (AbstractClientPlayer) (Object) this;
		textures = FemboyWare.getModules().get(dev.femboyware.module.larp.CoinbaseAgentModule.class).skinFor(self, textures);
		textures = FemboyWare.getModules().get(dev.femboyware.module.larp.PaparazziModule.class).skinFor(self, textures);
		textures = FemboyWare.getModules().get(dev.femboyware.module.larp.FbiRaidModule.class).skinFor(self, textures);
		textures = FemboyWare.getModules().get(dev.femboyware.module.larp.CarsModule.class).skinFor(self, textures);
		textures = FemboyWare.getModules().get(dev.femboyware.module.larp.JetModule.class).skinFor(self, textures);
		textures = FemboyWare.getModules().get(dev.femboyware.module.larp.HelicopterModule.class).skinFor(self, textures);
		textures = FemboyWare.getModules().get(dev.femboyware.module.client.ClientSyncModule.class).skinFor(self, textures);
		textures = FemboyWare.getModules().get(dev.femboyware.module.render.SkinChangerModule.class).skinFor(self, textures);
		textures = FemboyWare.getModules().get(dev.femboyware.module.larp.LarpAsModule.class).skinFor(self, textures);
		textures = dev.femboyware.module.effects.PlayerTrailModule.skinFor(self, textures);
		return FemboyWare.getModules().get(FakePlayerModule.class).skinFor(self, textures);
	}
}
