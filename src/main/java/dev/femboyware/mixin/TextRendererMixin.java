package dev.femboyware.mixin;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.render.CleanFontModule;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.StyleSpriteSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(TextRenderer.class)
public class TextRendererMixin {
	/** Clean Font: every glyph lookup for the default font goes to Poppins instead. Widths use the same lookup. */
	@ModifyVariable(method = "getGlyphs", at = @At("HEAD"), argsOnly = true)
	private StyleSpriteSource femboyware$cleanFont(StyleSpriteSource source) {
		if (FemboyWare.getModules() == null) return source;
		return FemboyWare.getModules().get(CleanFontModule.class).replace(source);
	}

	/** Clean Font: no drop shadows. They suit pixel fonts but blur and double a smooth one. */
	@ModifyVariable(method = {
		"prepare(Ljava/lang/String;FFIZI)Lnet/minecraft/client/font/TextRenderer$GlyphDrawable;",
		"prepare(Lnet/minecraft/text/OrderedText;FFIZZI)Lnet/minecraft/client/font/TextRenderer$GlyphDrawable;"
	}, at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private boolean femboyware$noShadow(boolean shadow) {
		if (!shadow || FemboyWare.getModules() == null) return shadow;
		return !FemboyWare.getModules().get(CleanFontModule.class).isEnabled();
	}
}
