package dev.femboyware.mixin;

import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.util.memory.ObjectPool;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
	@Accessor("pool")
	ObjectPool femboyware$getPool();
}
