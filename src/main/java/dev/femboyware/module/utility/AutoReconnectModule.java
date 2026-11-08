package dev.femboyware.module.utility;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

/**
 * Reconnects to the last server after a disconnect. The countdown and buttons live in
 * DisconnectedScreenMixin, since no world exists to tick modules there.
 * Ported from Meteor Client's AutoReconnect (https://github.com/MeteorDevelopment/meteor-client), GPL-3.0.
 */
public class AutoReconnectModule extends Module {
	private final NumberSetting delay = add(new NumberSetting("Delay", "Seconds to wait before reconnecting", 3.5, 0, 60, 0.5));
	private final BoolSetting hideButtons = add(new BoolSetting("Hide Buttons", "Don't add the reconnect buttons to the disconnect screen", false));

	private ServerAddress lastAddress;
	private ServerData lastInfo;

	public AutoReconnectModule() {
		super("Auto Reconnect", "Reconnects when you get disconnected", Category.MISC);
	}

	/** Called from ConnectScreenMixin whenever we start joining a server. */
	public void onConnect(ServerAddress address, ServerData info) {
		lastAddress = address;
		lastInfo = info;
	}

	public boolean hasLastServer() {
		return lastAddress != null;
	}

	public boolean showButtons() {
		return !hideButtons.get();
	}

	public int delayTicks() {
		return (int) (delay.get() * 20);
	}

	public void reconnect() {
		if (lastAddress == null) return;
		ConnectScreen.startConnecting(new TitleScreen(), mc, lastAddress, lastInfo, false, null);
	}
}
