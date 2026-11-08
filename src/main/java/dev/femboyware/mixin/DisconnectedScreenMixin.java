package dev.femboyware.mixin;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.utility.AutoReconnectModule;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Auto Reconnect's countdown, plus Reconnect and Toggle buttons on the disconnect screen. */
@Mixin(DisconnectedScreen.class)
public abstract class DisconnectedScreenMixin extends Screen {
	private static final String LAYOUT = "Lnet/minecraft/client/gui/layouts/LinearLayout;arrangeElements()V";

	@Shadow
	@Final
	private LinearLayout layout;

	@Unique
	private Button femboyware$reconnectButton;
	@Unique
	private int femboyware$ticksLeft = femboyware$module().delayTicks();

	protected DisconnectedScreenMixin(Component title) {
		super(title);
	}

	@Inject(method = "init", at = @At(value = "INVOKE", target = LAYOUT))
	private void femboyware$addButtons(CallbackInfo ci) {
		AutoReconnectModule module = femboyware$module();
		if (!module.hasLastServer() || !module.showButtons()) return;
		femboyware$reconnectButton = layout.addChild(Button.builder(Component.literal(femboyware$label()), button -> module.reconnect()).build());
		layout.addChild(Button.builder(Component.literal("Toggle Auto Reconnect"), button -> {
			module.toggle();
			femboyware$ticksLeft = module.delayTicks();
			femboyware$reconnectButton.setMessage(Component.literal(femboyware$label()));
		}).build());
	}

	@Override
	public void tick() {
		AutoReconnectModule module = femboyware$module();
		if (!module.isEnabled() || !module.hasLastServer()) return;
		if (femboyware$ticksLeft <= 0) {
			module.reconnect();
			return;
		}
		femboyware$ticksLeft--;
		if (femboyware$reconnectButton != null) femboyware$reconnectButton.setMessage(Component.literal(femboyware$label()));
	}

	@Unique
	private String femboyware$label() {
		if (!femboyware$module().isEnabled()) return "Reconnect";
		return String.format("Reconnect (%.1f)", femboyware$ticksLeft / 20f);
	}

	@Unique
	private static AutoReconnectModule femboyware$module() {
		return FemboyWare.getModules().get(AutoReconnectModule.class);
	}
}
