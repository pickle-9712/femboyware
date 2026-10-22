package dev.femboyware.mixin;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.utility.AutoReconnectModule;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.DirectionalLayoutWidget;
import net.minecraft.text.Text;
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
	@Shadow
	@Final
	private DirectionalLayoutWidget grid;

	@Unique
	private ButtonWidget femboyware$reconnectButton;
	@Unique
	private int femboyware$ticksLeft = femboyware$module().delayTicks();

	protected DisconnectedScreenMixin(Text title) {
		super(title);
	}

	@Inject(method = "init", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/widget/DirectionalLayoutWidget;refreshPositions()V"))
	private void femboyware$addButtons(CallbackInfo ci) {
		AutoReconnectModule module = femboyware$module();
		if (!module.hasLastServer() || !module.showButtons()) return;
		femboyware$reconnectButton = grid.add(ButtonWidget.builder(Text.literal(femboyware$label()), button -> module.reconnect()).build());
		grid.add(ButtonWidget.builder(Text.literal("Toggle Auto Reconnect"), button -> {
			module.toggle();
			femboyware$ticksLeft = module.delayTicks();
			femboyware$reconnectButton.setMessage(Text.literal(femboyware$label()));
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
		if (femboyware$reconnectButton != null) femboyware$reconnectButton.setMessage(Text.literal(femboyware$label()));
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
