package dev.femboyware.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AbstractContainerScreen.class)
public interface HandledScreenAccessor {
	@Accessor("leftPos")
	int femboyware$getX();

	@Accessor("topPos")
	int femboyware$getY();
}
