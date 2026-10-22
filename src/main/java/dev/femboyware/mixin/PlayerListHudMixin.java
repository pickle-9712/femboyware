package dev.femboyware.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.render.BetterTabModule;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Better Tab: more players, taller columns, custom names and ping in milliseconds. */
@Mixin(PlayerListHud.class)
public class PlayerListHudMixin {
	@ModifyConstant(method = "collectPlayerEntries", constant = @Constant(longValue = 80L))
	private long femboyware$tabSize(long vanilla) {
		return femboyware$module() == null ? vanilla : femboyware$module().tabSize(vanilla);
	}

	@ModifyConstant(method = "render", constant = @Constant(intValue = 20))
	private int femboyware$tabHeight(int vanilla) {
		return femboyware$module() == null ? vanilla : femboyware$module().tabHeight(vanilla);
	}

	/** Widens each column so the ping number fits. */
	@ModifyArg(method = "render", at = @At(value = "INVOKE", target = "Ljava/lang/Math;min(II)I"), index = 0)
	private int femboyware$latencyWidth(int width) {
		return femboyware$module() != null && femboyware$module().accurateLatency() ? width + BetterTabModule.LATENCY_WIDTH : width;
	}

	@ModifyReturnValue(method = "getPlayerName", at = @At("RETURN"))
	private Text femboyware$playerName(Text name, PlayerListEntry entry) {
		return femboyware$module() == null ? name : femboyware$module().playerName(entry, name);
	}

	@Inject(method = "renderLatencyIcon", at = @At("HEAD"), cancellable = true)
	private void femboyware$latency(DrawContext context, int width, int x, int y, PlayerListEntry entry, CallbackInfo ci) {
		if (femboyware$module() == null || !femboyware$module().accurateLatency()) return;
		femboyware$module().drawLatency(context, width, x, y, entry);
		ci.cancel();
	}

	@Unique
	private static BetterTabModule femboyware$module() {
		return FemboyWare.getModules() == null ? null : FemboyWare.getModules().get(BetterTabModule.class);
	}
}
