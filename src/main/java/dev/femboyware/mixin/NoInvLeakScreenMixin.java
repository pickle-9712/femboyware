package dev.femboyware.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.femboyware.module.client.NoInvLeakModule;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No Inv Leak: your items in inventory screens, the item on your cursor, and their tooltips. Ported from Anubis (GPL-3.0, 4ldenz). */
@Mixin(AbstractContainerScreen.class)
public abstract class NoInvLeakScreenMixin {
	//? if >=26.1 {
	/*private static final String SLOT = "extractSlot", FLOATING = "extractFloatingItem", TOOLTIP = "extractTooltip";
	*///?} else {
	private static final String SLOT = "renderSlot", FLOATING = "renderFloatingItem", TOOLTIP = "renderTooltip";
	//?}

	@Shadow
	protected Slot hoveredSlot;

	@WrapOperation(method = SLOT, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/Slot;getItem()Lnet/minecraft/world/item/ItemStack;"), require = 0)
	private ItemStack femboyware$slot(Slot slot, Operation<ItemStack> original) {
		return NoInvLeakModule.container(original.call(slot), slot);
	}

	@WrapOperation(method = SLOT, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;copyWithCount(I)Lnet/minecraft/world/item/ItemStack;"), require = 0)
	private ItemStack femboyware$dragPreview(ItemStack stack, int count, Operation<ItemStack> original) {
		return NoInvLeakModule.dragged(original.call(stack, count));
	}

	@ModifyVariable(method = FLOATING, at = @At("HEAD"), argsOnly = true, require = 0)
	private ItemStack femboyware$carried(ItemStack stack) {
		return NoInvLeakModule.carried(stack);
	}

	@Inject(method = TOOLTIP, at = @At("HEAD"), cancellable = true, require = 0)
	private void femboyware$tooltip(CallbackInfo ci) {
		if (NoInvLeakModule.hidesTooltips() && NoInvLeakModule.ownSlot(hoveredSlot)) ci.cancel();
	}
}
