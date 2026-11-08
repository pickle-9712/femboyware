package dev.femboyware.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.utility.NoRenderModule;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** No Render: entities you've chosen to hide, like dropped items, are never drawn. */
@Mixin(EntityRenderDispatcher.class)
public class EntityRenderManagerMixin {
	@ModifyReturnValue(method = "shouldRender", at = @At("RETURN"))
	private boolean femboyware$noRenderEntities(boolean render, Entity entity) {
		return render && !(FemboyWare.getModules() != null && FemboyWare.getModules().get(NoRenderModule.class).hidesEntity(entity));
	}
}
