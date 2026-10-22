package dev.femboyware.mixin;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.utility.NoRenderModule;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.entity.Entity;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No Render toggles for HUD overlays. */
@Mixin(InGameHud.class)
public class InGameHudMixin {
	@Inject(method = "renderOverlay", at = @At("HEAD"), cancellable = true)
	private void femboyware$noPumpkin(DrawContext context, Identifier texture, float opacity, CallbackInfo ci) {
		if (noRender().pumpkin()) ci.cancel();
	}

	@Inject(method = "renderVignetteOverlay", at = @At("HEAD"), cancellable = true)
	private void femboyware$noVignette(DrawContext context, Entity entity, CallbackInfo ci) {
		if (noRender().vignette()) ci.cancel();
	}

	@Inject(method = {"renderPortalOverlay", "renderNauseaOverlay"}, at = @At("HEAD"), cancellable = true)
	private void femboyware$noPortal(DrawContext context, float strength, CallbackInfo ci) {
		if (noRender().portal()) ci.cancel();
	}

	@Inject(method = "renderBossBarHud", at = @At("HEAD"), cancellable = true)
	private void femboyware$noBossBar(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
		if (noRender().bossBar()) ci.cancel();
	}

	@Inject(method = "renderScoreboardSidebar(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V", at = @At("HEAD"), cancellable = true)
	private void femboyware$noScoreboard(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
		if (noRender().scoreboard()) ci.cancel();
	}

	private static NoRenderModule noRender() {
		return FemboyWare.getModules().get(NoRenderModule.class);
	}
}
