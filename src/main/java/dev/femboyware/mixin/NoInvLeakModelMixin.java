package dev.femboyware.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.femboyware.module.client.NoInvLeakModule;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** No Inv Leak: your model in the inventory screen is drawn without its armour and items. Ported from Anubis (GPL-3.0, 4ldenz). */
@Mixin(InventoryScreen.class)
public abstract class NoInvLeakModelMixin {
	//? if >=26.1 {
	/*private static final String FOLLOWS = "extractEntityInInventoryFollowsMouse";
	*///?} else {
	private static final String FOLLOWS = "renderEntityInInventoryFollowsMouse";
	//?}

	@WrapOperation(method = FOLLOWS, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/inventory/InventoryScreen;extractRenderState(Lnet/minecraft/world/entity/LivingEntity;)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;"), require = 0)
	private static EntityRenderState femboyware$bareModel(LivingEntity entity, Operation<EntityRenderState> original) {
		if (!NoInvLeakModule.hidesModelOf(entity)) return original.call(entity);
		NoInvLeakModule.bareModel(true);
		try {
			return original.call(entity);
		} finally {
			NoInvLeakModule.bareModel(false);
		}
	}
}
