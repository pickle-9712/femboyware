package dev.femboyware.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import dev.femboyware.module.hud.LyricFont;
import net.minecraft.client.gui.font.FontTexture;
import net.minecraft.client.gui.font.GlyphStitcher;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Notes which glyph textures hold lyric fonts, for {@link GlyphGuiElementRenderStateMixin}. */
@Mixin(GlyphStitcher.class)
public class GlyphBakerMixin {
	@Shadow
	@Final
	private Identifier texturePrefix;

	@ModifyExpressionValue(method = "stitch", at = @At(value = "NEW", target = "Lnet/minecraft/client/gui/font/FontTexture;"))
	private FontTexture femboyware$noteLyricAtlas(FontTexture atlas) {
		LyricFont.noteAtlas(texturePrefix, atlas.getTextureView());
		// Text drawn in the world samples with the texture's own setting
		if (LyricFont.isSmooth(atlas.getTextureView())) {
			((AbstractTextureAccessor) (Object) atlas).femboyware$setSampler(RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR));
		}
		return atlas;
	}
}
