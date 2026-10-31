package dev.femboyware.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import dev.femboyware.module.hud.LyricFont;
import net.minecraft.client.font.GlyphAtlasTexture;
import net.minecraft.client.font.GlyphBaker;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Notes which glyph textures hold lyric fonts, for {@link GlyphGuiElementRenderStateMixin}. */
@Mixin(GlyphBaker.class)
public class GlyphBakerMixin {
	@Shadow
	@Final
	private Identifier fontId;

	@ModifyExpressionValue(method = "bake", at = @At(value = "NEW", target = "Lnet/minecraft/client/font/GlyphAtlasTexture;"))
	private GlyphAtlasTexture femboyware$noteLyricAtlas(GlyphAtlasTexture atlas) {
		LyricFont.noteAtlas(fontId, atlas.getGlTextureView());
		// Text drawn in the world samples with the texture's own setting
		if (LyricFont.isSmooth(atlas.getGlTextureView())) {
			((AbstractTextureAccessor) (Object) atlas).femboyware$setSampler(RenderSystem.getSamplerCache().getRepeated(FilterMode.LINEAR));
		}
		return atlas;
	}
}
