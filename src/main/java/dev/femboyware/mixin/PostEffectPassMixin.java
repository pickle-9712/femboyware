package dev.femboyware.mixin;

import com.mojang.blaze3d.buffers.GpuBuffer;
import net.minecraft.client.renderer.PostPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(PostPass.class)
public class PostEffectPassMixin {
	/** Pink Sky changes its tint every frame, so post effect uniforms need to be writable. */
	@ModifyArg(method = "<init>", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/GpuDevice;createBuffer(Ljava/util/function/Supplier;ILjava/nio/ByteBuffer;)Lcom/mojang/blaze3d/buffers/GpuBuffer;"), index = 1)
	private int femboyware$writableUniforms(int usage) {
		return usage | GpuBuffer.USAGE_COPY_DST;
	}
}
