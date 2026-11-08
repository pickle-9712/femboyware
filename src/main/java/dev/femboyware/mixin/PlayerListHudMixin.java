package dev.femboyware.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.client.FakePlayerModule;
import dev.femboyware.module.render.BetterTabModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

/** Better Tab: more players, taller columns, custom names and ping in milliseconds. */
@Mixin(PlayerTabOverlay.class)
public class PlayerListHudMixin {
	//? if >=26.1 {
	/*private static final String RENDER = "extractRenderState", PING = "extractPingIcon";
	*///?} else {
	private static final String RENDER = "render", PING = "renderPingIcon";
	//?}

	@ModifyConstant(method = "getPlayerInfos", constant = @Constant(longValue = 80L))
	private long femboyware$tabSize(long vanilla) {
		return femboyware$module() == null ? vanilla : femboyware$module().tabSize(vanilla);
	}

	/** Fake players get rows too, added before the game sorts the list. */
	@ModifyExpressionValue(method = "getPlayerInfos", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientPacketListener;getListedOnlinePlayers()Ljava/util/Collection;"))
	private Collection<PlayerInfo> femboyware$fakePlayers(Collection<PlayerInfo> entries) {
		return FemboyWare.getModules() == null ? entries : FemboyWare.getModules().get(FakePlayerModule.class).withTabEntries(entries);
	}

	@ModifyConstant(method = RENDER, constant = @Constant(intValue = 20))
	private int femboyware$tabHeight(int vanilla) {
		return femboyware$module() == null ? vanilla : femboyware$module().tabHeight(vanilla);
	}

	/** Widens each column so the ping number fits. */
	@ModifyArg(method = RENDER, at = @At(value = "INVOKE", target = "Ljava/lang/Math;min(II)I"), index = 0)
	private int femboyware$latencyWidth(int width) {
		return femboyware$module() != null && femboyware$module().accurateLatency() ? width + BetterTabModule.LATENCY_WIDTH : width;
	}

	@ModifyReturnValue(method = "getNameForDisplay", at = @At("RETURN"))
	private Component femboyware$playerName(Component name, PlayerInfo entry) {
		return femboyware$module() == null ? name : femboyware$module().playerName(entry, name);
	}

	@Inject(method = PING, at = @At("HEAD"), cancellable = true)
	private void femboyware$latency(GuiGraphics context, int width, int x, int y, PlayerInfo entry, CallbackInfo ci) {
		if (femboyware$module() == null || !femboyware$module().accurateLatency()) return;
		femboyware$module().drawLatency(context, width, x, y, entry);
		ci.cancel();
	}

	@Unique
	private static BetterTabModule femboyware$module() {
		return FemboyWare.getModules() == null ? null : FemboyWare.getModules().get(BetterTabModule.class);
	}
}
