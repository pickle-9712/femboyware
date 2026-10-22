package dev.femboyware.mixin;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.utility.NameProtectModule;
import net.minecraft.text.TextVisitFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(TextVisitFactory.class)
public class TextVisitFactoryMixin {
	/** Name Protect: swap our name out of every formatted string before it's drawn. */
	@ModifyArg(method = "visitFormatted(Ljava/lang/String;ILnet/minecraft/text/Style;Lnet/minecraft/text/CharacterVisitor;)Z",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/text/TextVisitFactory;visitFormatted(Ljava/lang/String;ILnet/minecraft/text/Style;Lnet/minecraft/text/Style;Lnet/minecraft/text/CharacterVisitor;)Z", ordinal = 0),
		index = 0)
	private static String femboyware$nameProtect(String text) {
		if (FemboyWare.getModules() == null) return text;
		return FemboyWare.getModules().get(NameProtectModule.class).replaceName(text);
	}
}
