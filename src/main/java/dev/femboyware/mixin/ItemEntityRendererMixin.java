package dev.femboyware.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.render.ItemPhysicsModule;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import org.joml.Quaternionfc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** Item Physics: swap the bob and spin of dropped items for a resting pose. */
@Mixin(ItemEntityRenderer.class)
public class ItemEntityRendererMixin {
	private static final String RENDER = "submit(Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/CameraRenderState;)V";

	@WrapOperation(method = RENDER, at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V"))
	private void femboyware$restingHeight(PoseStack matrices, float x, float y, float z, Operation<Void> original, @Local(argsOnly = true) ItemEntityRenderState state) {
		ItemPhysicsModule module = femboyware$module();
		if (module == null) original.call(matrices, x, y, z);
		else original.call(matrices, 0f, module.restingHeight(state.item.getModelBoundingBox()), 0f);
	}

	@WrapOperation(method = RENDER, at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;mulPose(Lorg/joml/Quaternionfc;)V"))
	private void femboyware$restingRotation(PoseStack matrices, Quaternionfc rotation, Operation<Void> original, @Local(argsOnly = true) ItemEntityRenderState state) {
		ItemPhysicsModule module = femboyware$module();
		if (module == null) original.call(matrices, rotation);
		else original.call(matrices, module.rotation(state.item.getModelBoundingBox(), state.bobOffset));
	}

	@Unique
	private static ItemPhysicsModule femboyware$module() {
		if (FemboyWare.getModules() == null) return null;
		ItemPhysicsModule module = FemboyWare.getModules().get(ItemPhysicsModule.class);
		return module.isEnabled() ? module : null;
	}
}
