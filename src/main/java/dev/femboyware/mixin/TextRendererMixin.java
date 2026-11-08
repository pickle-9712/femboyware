package dev.femboyware.mixin;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.render.CleanFontModule;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.FontDescription;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Font.class)
public class TextRendererMixin {
	/** Clean Font: every glyph lookup for the default font goes to Poppins instead. Widths use the same lookup. */
	@ModifyVariable(method = "getGlyphSource", at = @At("HEAD"), argsOnly = true)
	private FontDescription femboyware$cleanFont(FontDescription source) {
		if (FemboyWare.getModules() == null) return source;
		return FemboyWare.getModules().get(CleanFontModule.class).replace(source);
	}

	/** Clean Font: no drop shadows. They suit pixel fonts but blur and double a smooth one. */
	@ModifyVariable(method = {
		"prepareText(Ljava/lang/String;FFIZI)Lnet/minecraft/client/gui/Font$PreparedText;",
		"prepareText(Lnet/minecraft/util/FormattedCharSequence;FFIZZI)Lnet/minecraft/client/gui/Font$PreparedText;"
	}, at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private boolean femboyware$noShadow(boolean shadow) {
		if (!shadow || FemboyWare.getModules() == null) return shadow;
		return !FemboyWare.getModules().get(CleanFontModule.class).isEnabled();
	}
}
