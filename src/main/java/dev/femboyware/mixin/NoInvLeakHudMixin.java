package dev.femboyware.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.femboyware.module.client.NoInvLeakModule;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No Inv Leak: your hotbar items, the held-item name and the selection box. Ported from Anubis (GPL-3.0, 4ldenz). */
//? if >=26.1 {
/*@Mixin(net.minecraft.client.gui.Hud.class)
*///?} else {
@Mixin(net.minecraft.client.gui.Gui.class)
//?}
public abstract class NoInvLeakHudMixin {
	//? if >=26.1 {
	/*private static final String SLOT = "extractSlot", NAME = "extractSelectedItemName", HOTBAR = "extractItemHotbar";
	private static final String BLIT = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V";
	*///?} else {
	private static final String SLOT = "renderSlot", NAME = "renderSelectedItemName", HOTBAR = "renderItemHotbar";
	private static final String BLIT = "Lnet/minecraft/client/gui/GuiGraphics;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V";
	//?}

	@Unique
	private static final Identifier femboyware$SELECTION = Identifier.withDefaultNamespace("hud/hotbar_selection");

	@ModifyVariable(method = SLOT, at = @At("HEAD"), argsOnly = true, require = 0)
	private ItemStack femboyware$hotbar(ItemStack stack, @com.llamalad7.mixinextras.sugar.Local(argsOnly = true, ordinal = 2) int seed) {
		return NoInvLeakModule.hotbar(stack, seed);
	}

	@Inject(method = NAME, at = @At("HEAD"), cancellable = true, require = 0)
	private void femboyware$itemName(CallbackInfo ci) {
		if (NoInvLeakModule.hidesItemName()) ci.cancel();
	}

	@WrapWithCondition(method = HOTBAR, at = @At(value = "INVOKE", target = BLIT), require = 0)
	//? if >=26.1 {
	/*private boolean femboyware$selection(net.minecraft.client.gui.GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier sprite, int x, int y, int width, int height) {
	*///?} else {
	private boolean femboyware$selection(net.minecraft.client.gui.GuiGraphics graphics, RenderPipeline pipeline, Identifier sprite, int x, int y, int width, int height) {
	//?}
		return !(NoInvLeakModule.hidesSelectedSlot() && femboyware$SELECTION.equals(sprite));
	}
}
