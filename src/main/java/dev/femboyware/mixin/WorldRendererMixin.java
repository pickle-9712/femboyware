package dev.femboyware.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.render.BlockOutlineModule;
import dev.femboyware.util.ProjectionUtil;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class WorldRendererMixin {
	/** While you fly, the sections you and the captain are in count as visible, so you're both drawn over unloaded ground. */
	@Inject(method = "isSectionCompiledAndVisible", at = @At("HEAD"), cancellable = true)
	private void femboyware$keepFlyersVisible(net.minecraft.core.BlockPos pos, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
		if (dev.femboyware.module.larp.FarGround.keepsVisible(pos)) cir.setReturnValue(true);
	}

	//? if >=26.1 {
	/*private static final String BLOCK_OUTLINE = "submitBlockOutline";
	*///?} else {
	private static final String BLOCK_OUTLINE = "renderBlockOutline";
	//?}

	//? if >=26.1 {
	/*@Inject(method = "render", at = @At("HEAD"))
	private void femboyware$captureMatrices(GraphicsResourceAllocator allocator, DeltaTracker tickCounter, boolean renderBlockOutline,
											net.minecraft.client.renderer.state.level.CameraRenderState camera, org.joml.Matrix4fc modelViewMatrix,
											GpuBufferSlice fogBuffer, Vector4f fogColor, boolean renderSky, CallbackInfo ci) {
		ProjectionUtil.update(camera.viewRotationMatrix, camera.projectionMatrix, camera.pos);
	}
	*///?} else {
	@Inject(method = "renderLevel", at = @At("HEAD"))
	private void femboyware$captureMatrices(GraphicsResourceAllocator allocator, DeltaTracker tickCounter, boolean renderBlockOutline,
											Camera camera, Matrix4f positionMatrix, Matrix4f basicProjectionMatrix, Matrix4f projectionMatrix,
											GpuBufferSlice fogBuffer, Vector4f fogColor, boolean renderSky, CallbackInfo ci) {
		ProjectionUtil.update(positionMatrix, basicProjectionMatrix, camera.position());
	}
	//?}

	/** Block Outline draws its own outline, so the vanilla one is skipped. */
	@Inject(method = BLOCK_OUTLINE, at = @At("HEAD"), cancellable = true)
	private void femboyware$blockOutline(CallbackInfo ci) {
		if (FemboyWare.isEnabled(BlockOutlineModule.class)) ci.cancel();
	}
}
