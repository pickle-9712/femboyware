package dev.femboyware.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.femboyware.util.TickRate;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Timer and Tps Sync: scales how many milliseconds a client tick lasts. */
@Mixin(RenderTickCounter.Dynamic.class)
public class RenderTickCounterMixin {
	@ModifyExpressionValue(method = "beginRenderTick(J)I", at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/floats/FloatUnaryOperator;apply(F)F"))
	private float femboyware$timer(float millisPerTick) {
		return millisPerTick / TickRate.getTimerMultiplier();
	}
}
