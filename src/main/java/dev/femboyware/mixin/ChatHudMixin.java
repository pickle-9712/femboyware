package dev.femboyware.mixin;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.utility.ChatPlusModule;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Chat Plus: timestamps, anti spam and mentions on each new message, and a longer history. */
@Mixin(ChatComponent.class)
public class ChatHudMixin {
	//? if >=26.1 {
	/*@ModifyVariable(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/GuiMessageTag;)V",
		at = @At("HEAD"), argsOnly = true)
	*///?} else {
	@ModifyVariable(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V",
		at = @At("HEAD"), argsOnly = true)
	//?}
	private Component femboyware$chatPlus(Component message) {
		if (FemboyWare.getModules() == null) return message;
		return FemboyWare.getModules().get(ChatPlusModule.class).process(message, (ChatHudAccessor) this);
	}

	@ModifyConstant(method = {"addMessageToQueue(Lnet/minecraft/client/GuiMessage;)V", "addMessageToDisplayQueue"}, constant = @Constant(intValue = 100), require = 0)
	private int femboyware$longHistory(int limit) {
		return FemboyWare.getModules() == null ? limit : FemboyWare.getModules().get(ChatPlusModule.class).historyLimit();
	}
}
