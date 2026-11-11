package dev.femboyware.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** For Client Sync: gliding and swimming are shared flags, which only the entity itself can set. */
@Mixin(Entity.class)
public interface EntitySharedFlagInvoker {
	@Invoker("setSharedFlag")
	void femboyware$setSharedFlag(int flag, boolean on);
}
