package dev.femboyware.mixin;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.render.SwingAnimationsModule;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Swing Animations: items stay fully raised instead of bobbing down when switched. */
//? if >=26.3 {
/*@Mixin(net.minecraft.client.player.FirstPersonHandsAndItems.class)
*///?} else {
@Mixin(net.minecraft.client.renderer.ItemInHandRenderer.class)
//?}
public class FirstPersonHandsMixin {
	@Shadow
	private float mainHandHeight;
	@Shadow
	private float oMainHandHeight;
	@Shadow
	private float offHandHeight;
	@Shadow
	private float oOffHandHeight;
	@Shadow
	private ItemStack mainHandItem;
	@Shadow
	private ItemStack offHandItem;

	@Inject(method = "tick", at = @At("TAIL"))
	private void femboyware$noEquip(CallbackInfo ci) {
		if (FemboyWare.getModules() == null || !FemboyWare.getModules().get(SwingAnimationsModule.class).noEquip()) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		mainHandItem = mc.player.getMainHandItem();
		offHandItem = mc.player.getOffhandItem();
		mainHandHeight = 1f;
		oMainHandHeight = 1f;
		offHandHeight = 1f;
		oOffHandHeight = 1f;
	}
}
