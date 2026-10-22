package dev.femboyware.mixin;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.render.WorldRendererModule;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** World Renderer: server time updates are replaced by the chosen time of day, which then stays still. */
@Mixin(ClientWorld.class)
public class ClientWorldMixin {
	@ModifyVariable(method = "setTime", at = @At("HEAD"), argsOnly = true, ordinal = 1)
	private long femboyware$timeOfDay(long timeOfDay) {
		if (FemboyWare.getModules() == null) return timeOfDay;
		long override = FemboyWare.getModules().get(WorldRendererModule.class).getTimeOverride();
		return override >= 0 ? override : timeOfDay;
	}

	@ModifyVariable(method = "setTime", at = @At("HEAD"), argsOnly = true)
	private boolean femboyware$freezeTime(boolean shouldTickTimeOfDay) {
		if (FemboyWare.getModules() == null) return shouldTickTimeOfDay;
		return shouldTickTimeOfDay && FemboyWare.getModules().get(WorldRendererModule.class).getTimeOverride() < 0;
	}
}
