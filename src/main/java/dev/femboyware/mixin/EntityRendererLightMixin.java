package dev.femboyware.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.larp.MansionModule;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The mansion's lamps light whoever's in it or out on its grounds, so nobody's a silhouette at night. */
@Mixin(EntityRenderer.class)
public class EntityRendererLightMixin {
	@ModifyReturnValue(method = "getBlockLightLevel", at = @At("RETURN"))
	private int femboyware$mansionLamps(int light, Entity entity, BlockPos pos) {
		if (FemboyWare.getModules() == null || light >= 14) return light;
		return Math.max(light, FemboyWare.getModules().get(MansionModule.class).lampLight(entity.position()));
	}

	/** The FBI raid's agents and stand-in are never frustum culled: they pop in and out otherwise as the film cuts. */
	@ModifyReturnValue(method = "affectedByCulling", at = @At("RETURN"))
	private boolean femboyware$raidAlwaysDrawn(boolean culled, Entity entity) {
		return culled && !(FemboyWare.getModules() != null && FemboyWare.getModules().get(dev.femboyware.module.larp.FbiRaidModule.class).isActor(entity));
	}
}
