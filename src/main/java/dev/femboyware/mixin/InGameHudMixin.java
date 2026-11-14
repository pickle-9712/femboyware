package dev.femboyware.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.client.FakePlayerModule;
import dev.femboyware.module.hud.PotionEffectsModule;
import dev.femboyware.module.render.CrosshairModule;
import dev.femboyware.module.utility.NoRenderModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;
import net.minecraft.client.DeltaTracker;
//? if >=26.1 {
/*import net.minecraft.client.gui.Hud;
*///?} else {
import net.minecraft.client.gui.Gui;
//?}
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;

/** No Render toggles for HUD overlays. */
//? if >=26.1 {
/*@Mixin(Hud.class)
*///?} else {
@Mixin(Gui.class)
//?}
public class InGameHudMixin {
	//? if >=26.1 {
	/*private static final String HOTBAR = "extractHotbarAndDecorations", NAME = "extractSelectedItemName";
	private static final String TAB = "extractTabList", CROSSHAIR = "extractCrosshair", EFFECTS = "extractEffects", TITLE = "extractTitle", TEXTURE_OVERLAY = "extractTextureOverlay", VIGNETTE = "extractVignette", PORTAL = "extractPortalOverlay", CONFUSION = "extractConfusionOverlay", BOSS = "extractBossOverlay",
		SCOREBOARD = "extractScoreboardSidebar(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V";
	*///?} else {
	private static final String HOTBAR = "renderHotbarAndDecorations", NAME = "renderSelectedItemName";
	private static final String TAB = "renderTabList", CROSSHAIR = "renderCrosshair", EFFECTS = "renderEffects", TITLE = "renderTitle", TEXTURE_OVERLAY = "renderTextureOverlay", VIGNETTE = "renderVignette", PORTAL = "renderPortalOverlay", CONFUSION = "renderConfusionOverlay", BOSS = "renderBossOverlay",
		SCOREBOARD = "renderScoreboardSidebar(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V";
	//?}

	/** Singleplayer hides the tab list when you're alone, and fake players count as company. */
	@ModifyExpressionValue(method = TAB, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientPacketListener;getListedOnlinePlayers()Ljava/util/Collection;"))
	private Collection<PlayerInfo> femboyware$fakePlayers(Collection<PlayerInfo> entries) {
		return FemboyWare.getModules().get(FakePlayerModule.class).withTabEntries(entries);
	}

	/** Crosshair: our own in place of Minecraft's. */
	@Inject(method = CROSSHAIR, at = @At("HEAD"), cancellable = true)
	private void femboyware$crosshair(GuiGraphics context, DeltaTracker tickCounter, CallbackInfo ci) {
		if (FemboyWare.getModules().get(dev.femboyware.module.larp.FbiRaidModule.class).hidesHud()) {
			ci.cancel();
			return;
		}
		if (!FemboyWare.isEnabled(CrosshairModule.class)) return;
		ci.cancel();
		FemboyWare.getModules().get(CrosshairModule.class).render(context);
	}

	/** The raid's a film from the first shot to the end: no hotbar, hearts or item names over it. */
	@Inject(method = {HOTBAR, NAME, EFFECTS, SCOREBOARD}, at = @At("HEAD"), cancellable = true)
	private void femboyware$raidFilm(CallbackInfo ci) {
		if (FemboyWare.getModules() != null && FemboyWare.getModules().get(dev.femboyware.module.larp.FbiRaidModule.class).hidesHud()) ci.cancel();
	}

	/** Potion Effects: its own list in place of the vanilla icons in the corner. */
	@Inject(method = EFFECTS, at = @At("HEAD"), cancellable = true)
	private void femboyware$hideEffects(GuiGraphics context, DeltaTracker tickCounter, CallbackInfo ci) {
		if (FemboyWare.getModules().get(PotionEffectsModule.class).hidesVanilla()) ci.cancel();
	}

	@Inject(method = TITLE, at = @At("HEAD"), cancellable = true)
	private void femboyware$noTitles(GuiGraphics context, DeltaTracker tickCounter, CallbackInfo ci) {
		if (FemboyWare.getModules().get(NoRenderModule.class).titles()) ci.cancel();
	}

	@Inject(method = TEXTURE_OVERLAY, at = @At("HEAD"), cancellable = true)
	private void femboyware$noPumpkin(GuiGraphics context, Identifier texture, float opacity, CallbackInfo ci) {
		if (noRender().pumpkin()) ci.cancel();
	}

	@Inject(method = VIGNETTE, at = @At("HEAD"), cancellable = true)
	private void femboyware$noVignette(GuiGraphics context, Entity entity, CallbackInfo ci) {
		if (noRender().vignette()) ci.cancel();
	}

	@Inject(method = {PORTAL, CONFUSION}, at = @At("HEAD"), cancellable = true)
	private void femboyware$noPortal(GuiGraphics context, float strength, CallbackInfo ci) {
		if (noRender().portal()) ci.cancel();
	}

	@Inject(method = BOSS, at = @At("HEAD"), cancellable = true)
	private void femboyware$noBossBar(GuiGraphics context, DeltaTracker tickCounter, CallbackInfo ci) {
		if (noRender().bossBar()) ci.cancel();
	}

	@Inject(method = SCOREBOARD, at = @At("HEAD"), cancellable = true)
	private void femboyware$noScoreboard(GuiGraphics context, DeltaTracker tickCounter, CallbackInfo ci) {
		if (noRender().scoreboard()) ci.cancel();
	}

	private static NoRenderModule noRender() {
		return FemboyWare.getModules().get(NoRenderModule.class);
	}

	//? if >=26.1 {
	/*private static final String ENTRY_NAME = "Lnet/minecraft/client/gui/Hud$1DisplayEntry;name:Lnet/minecraft/network/chat/Component;";
	private static final String ENTRY_SCORE = "Lnet/minecraft/client/gui/Hud$1DisplayEntry;score:Lnet/minecraft/network/chat/Component;";
	*///?} else {
	private static final String ENTRY_NAME = "Lnet/minecraft/client/gui/Gui$1DisplayEntry;name:Lnet/minecraft/network/chat/Component;";
	private static final String ENTRY_SCORE = "Lnet/minecraft/client/gui/Gui$1DisplayEntry;score:Lnet/minecraft/network/chat/Component;";
	//?}

	/**
	 * Scoreboard Edit: the sidebar title, lines and scores are rewritten as they're read for sizing and
	 * drawing. The lines and scores are read straight off the entry's fields (not its accessors) in every
	 * version, so those hooks are on the field reads.
	 */
	@ModifyExpressionValue(method = "displayScoreboardSidebar", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/scores/Objective;getDisplayName()Lnet/minecraft/network/chat/Component;"), require = 0)
	private net.minecraft.network.chat.Component femboyware$sidebarTitle(net.minecraft.network.chat.Component title) {
		return FemboyWare.getModules() == null ? title : FemboyWare.getModules().get(dev.femboyware.module.render.ScoreboardEditModule.class).title(title);
	}

	@ModifyExpressionValue(method = "displayScoreboardSidebar", at = @At(value = "FIELD", target = ENTRY_NAME, opcode = org.objectweb.asm.Opcodes.GETFIELD), require = 0)
	private net.minecraft.network.chat.Component femboyware$sidebarLine(net.minecraft.network.chat.Component line) {
		return FemboyWare.getModules() == null ? line : FemboyWare.getModules().get(dev.femboyware.module.render.ScoreboardEditModule.class).line(line);
	}

	@ModifyExpressionValue(method = "displayScoreboardSidebar", at = @At(value = "FIELD", target = ENTRY_SCORE, opcode = org.objectweb.asm.Opcodes.GETFIELD), require = 0)
	private net.minecraft.network.chat.Component femboyware$sidebarScore(net.minecraft.network.chat.Component score) {
		return FemboyWare.getModules() == null ? score : FemboyWare.getModules().get(dev.femboyware.module.render.ScoreboardEditModule.class).line(score);
	}
}
