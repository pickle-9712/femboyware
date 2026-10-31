package dev.femboyware.mixin;

import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.ChatHudLine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

@Mixin(ChatHud.class)
public interface ChatHudAccessor {
	/** Every chat line kept, newest first. */
	@Accessor("messages")
	List<ChatHudLine> femboyware$getMessages();

	/** Rebuilds the lines on screen from the kept messages. */
	@Invoker("refresh")
	void femboyware$refresh();
}
