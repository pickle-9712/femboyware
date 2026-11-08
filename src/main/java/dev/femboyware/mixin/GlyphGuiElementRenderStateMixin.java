package dev.femboyware.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.textures.FilterMode;
import dev.femboyware.module.hud.LyricFont;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.gui.render.state.GlyphRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Lyric fonts are drawn with smooth filtering, as nearest-pixel sampling leaves scaled and tilted text jagged. */
@Mixin(GlyphRenderState.class)
public class GlyphGuiElementRenderStateMixin {
	@Shadow
	@Final
	private TextRenderable renderable;

	@ModifyExpressionValue(method = "textureSetup", at = @At(value = "FIELD", target = "Lcom/mojang/blaze3d/textures/FilterMode;NEAREST:Lcom/mojang/blaze3d/textures/FilterMode;"))
	private FilterMode femboyware$smoothLyrics(FilterMode filter) {
		return LyricFont.isSmooth(renderable.textureView()) ? FilterMode.LINEAR : filter;
	}
}
