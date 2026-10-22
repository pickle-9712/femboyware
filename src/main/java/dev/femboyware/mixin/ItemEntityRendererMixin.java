package dev.femboyware.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.render.ItemPhysicsModule;
import net.minecraft.client.render.entity.ItemEntityRenderer;
import net.minecraft.client.render.entity.state.ItemEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Quaternionfc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** Item Physics: swap the bob and spin of dropped items for a resting pose. */
@Mixin(ItemEntityRenderer.class)
public class ItemEntityRendererMixin {
	private static final String RENDER = "render(Lnet/minecraft/client/render/entity/state/ItemEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/render/state/CameraRenderState;)V";

	@WrapOperation(method = RENDER, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/util/math/MatrixStack;translate(FFF)V"))
	private void femboyware$restingHeight(MatrixStack matrices, float x, float y, float z, Operation<Void> original, @Local(argsOnly = true) ItemEntityRenderState state) {
		ItemPhysicsModule module = femboyware$module();
		if (module == null) original.call(matrices, x, y, z);
		else original.call(matrices, 0f, module.restingHeight(state.itemRenderState.getModelBoundingBox()), 0f);
	}

	@WrapOperation(method = RENDER, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/util/math/MatrixStack;multiply(Lorg/joml/Quaternionfc;)V"))
	private void femboyware$restingRotation(MatrixStack matrices, Quaternionfc rotation, Operation<Void> original, @Local(argsOnly = true) ItemEntityRenderState state) {
		ItemPhysicsModule module = femboyware$module();
		if (module == null) original.call(matrices, rotation);
		else original.call(matrices, module.rotation(state.itemRenderState.getModelBoundingBox(), state.uniqueOffset));
	}

	@Unique
	private static ItemPhysicsModule femboyware$module() {
		if (FemboyWare.getModules() == null) return null;
		ItemPhysicsModule module = FemboyWare.getModules().get(ItemPhysicsModule.class);
		return module.isEnabled() ? module : null;
	}
}
