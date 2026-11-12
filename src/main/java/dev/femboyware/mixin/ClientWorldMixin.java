package dev.femboyware.mixin;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.render.WorldRendererModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

//? if >=26.3 {
/*import dev.femboyware.module.render.TimeChangerModule;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// World Renderer and Time Changer: every clock the client reads shows the chosen time instead.
@Mixin(net.minecraft.client.ClientClockManager.ClientClockInstance.class)
public class ClientWorldMixin {
	@Inject(method = "totalTicks", at = @At("HEAD"), cancellable = true)
	private void femboyware$timeOfDay(CallbackInfoReturnable<Long> cir) {
		if (FemboyWare.getModules() == null) return;
		long override = FemboyWare.getModules().get(WorldRendererModule.class).getTimeOverride();
		if (override < 0) override = FemboyWare.getModules().get(TimeChangerModule.class).getTimeOverride();
		if (override >= 0) cir.setReturnValue(override);
	}
}
*///?} else if >=26.1 {
/*import dev.femboyware.module.render.TimeChangerModule;
import net.minecraft.client.ClientClockManager;
import net.minecraft.core.Holder;
import net.minecraft.world.clock.WorldClock;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// World Renderer and Time Changer: every clock the client reads shows the chosen time instead.
@Mixin(ClientClockManager.class)
public class ClientWorldMixin {
	@Inject(method = "getTotalTicks", at = @At("HEAD"), cancellable = true)
	private void femboyware$timeOfDay(Holder<WorldClock> clock, CallbackInfoReturnable<Long> cir) {
		if (FemboyWare.getModules() == null) return;
		long override = FemboyWare.getModules().get(WorldRendererModule.class).getTimeOverride();
		if (override < 0) override = FemboyWare.getModules().get(TimeChangerModule.class).getTimeOverride();
		if (override >= 0) cir.setReturnValue(override);
	}
}
*///?} else {
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** World Renderer: server time updates are replaced by the chosen time of day, which then stays still. */
@Mixin(ClientLevel.class)
public class ClientWorldMixin {
	@ModifyVariable(method = "setTimeFromServer", at = @At("HEAD"), argsOnly = true, ordinal = 1)
	private long femboyware$timeOfDay(long timeOfDay) {
		if (FemboyWare.getModules() == null) return timeOfDay;
		long override = FemboyWare.getModules().get(WorldRendererModule.class).getTimeOverride();
		return override >= 0 ? override : timeOfDay;
	}

	@ModifyVariable(method = "setTimeFromServer", at = @At("HEAD"), argsOnly = true)
	private boolean femboyware$freezeTime(boolean shouldTickTimeOfDay) {
		if (FemboyWare.getModules() == null) return shouldTickTimeOfDay;
		return shouldTickTimeOfDay && FemboyWare.getModules().get(WorldRendererModule.class).getTimeOverride() < 0;
	}
}
//?}
