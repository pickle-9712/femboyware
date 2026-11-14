package dev.femboyware.mixin;

import com.mojang.blaze3d.resource.CrossFrameResourcePool;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
	@Accessor("resourcePool")
	CrossFrameResourcePool femboyware$getPool();

	@Accessor("fogRenderer")
	net.minecraft.client.renderer.fog.FogRenderer femboyware$getFog();
}
