package dev.femboyware.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.gui.components.ChatComponent;

@Mixin(ChatComponent.class)
public interface ChatHudAccessor {
	/** Every chat line kept, newest first. */
	@Accessor("allMessages")
	List<GuiMessage> femboyware$getMessages();

	/** Rebuilds the lines on screen from the kept messages. */
	@Invoker("refreshTrimmedMessages")
	void femboyware$refresh();
}
