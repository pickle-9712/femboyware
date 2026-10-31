package dev.femboyware.mixin;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.utility.ChatPlusModule;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Chat Plus: timestamps, anti spam and mentions on each new message, and a longer history. */
@Mixin(ChatHud.class)
public class ChatHudMixin {
	@ModifyVariable(method = "addMessage(Lnet/minecraft/text/Text;Lnet/minecraft/network/message/MessageSignatureData;Lnet/minecraft/client/gui/hud/MessageIndicator;)V",
		at = @At("HEAD"), argsOnly = true)
	private Text femboyware$chatPlus(Text message) {
		if (FemboyWare.getModules() == null) return message;
		return FemboyWare.getModules().get(ChatPlusModule.class).process(message, (ChatHudAccessor) this);
	}

	@ModifyConstant(method = {"addMessage(Lnet/minecraft/client/gui/hud/ChatHudLine;)V", "addVisibleMessage"}, constant = @Constant(intValue = 100), require = 0)
	private int femboyware$longHistory(int limit) {
		return FemboyWare.getModules() == null ? limit : FemboyWare.getModules().get(ChatPlusModule.class).historyLimit();
	}
}
