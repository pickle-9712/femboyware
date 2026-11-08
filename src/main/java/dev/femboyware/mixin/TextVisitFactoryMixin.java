package dev.femboyware.mixin;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.utility.NameProtectModule;
import net.minecraft.util.StringDecomposer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(StringDecomposer.class)
public class TextVisitFactoryMixin {
	/** Name Protect: swap our name out of every formatted string before it's drawn. */
	@ModifyArg(method = "iterateFormatted(Ljava/lang/String;ILnet/minecraft/network/chat/Style;Lnet/minecraft/util/FormattedCharSink;)Z",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/util/StringDecomposer;iterateFormatted(Ljava/lang/String;ILnet/minecraft/network/chat/Style;Lnet/minecraft/network/chat/Style;Lnet/minecraft/util/FormattedCharSink;)Z", ordinal = 0),
		index = 0)
	private static String femboyware$nameProtect(String text) {
		if (FemboyWare.getModules() == null) return text;
		return FemboyWare.getModules().get(NameProtectModule.class).replaceName(text);
	}
}
