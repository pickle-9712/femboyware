package dev.femboyware.module.donut;

import dev.femboyware.setting.StringSetting;
import dev.femboyware.event.PacketEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ChoiceSetting;
import dev.femboyware.setting.NumberSetting;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
/** Ported from the Anubis client (GPL-3.0, by 4ldenz, https://github.com/4ldenz/Anubis-client). */
public final class AhSniperModule extends Module {
	private static final Logger LOGGER = LoggerFactory.getLogger("femboyware");
	private static final String EXACT = "Exactly these";
	private static final String AT_LEAST = "At least these";
	private final StringSetting item = add(new StringSetting("Item", "Item", SnipeTarget.Preset.SHARD_AXE.value));
	private final StringSetting maxPrice = add(new StringSetting("Buy Below (each)", "e.g. 50m", ""));
	private final StringSetting enchants = add(new StringSetting("Enchantments", "e.g. protection 2, unbreaking 3, mending", ""));
	private final ChoiceSetting enchantMatch = add(new ChoiceSetting("Enchant Match", "Enchant Match", "Exactly these", () -> List.of("Exactly these", "At least these")));
	private final NumberSetting minHours = add(new NumberSetting("Min Time Left (h)", "Min Time Left (h)", 0.0F, 0.0F, 48.0F, 0.5F));
	private final NumberSetting refresh = add(new NumberSetting("Refresh Every (ms)", "Refresh Every (ms)", 300.0F, 100.0F, 2000.0F, 50.0F));
	private final NumberSetting settle = add(new NumberSetting("Wait After Page Change (ms)", "Wait After Page Change (ms)", 200.0F, 50.0F, 1000.0F, 25.0F));
	private final StringSetting budget = add(new StringSetting("Max Total Spend", "e.g. 500m, empty = no limit", ""));
	private final BoolSetting requireConfirm = add(new BoolSetting("Only Buy With Confirm", "Only Buy With Confirm", false));
	private final NumberSetting maxBuys = add(new NumberSetting("Stop After (buys, 0 = never)", "Stop After (buys, 0 = never)", 0.0F, 0.0F, 100.0F, 1.0F));
	private final BoolSetting autoSell = add(new BoolSetting("Auto Sell", "Auto Sell", false));
	private final StringSetting sellPrice = add(new StringSetting("Sell Price (each)", "e.g. 60m", ""));
	private final MacroWorker worker = new MacroWorker("femboyware AH Sniper");
	private volatile SniperBot bot;

	public AhSniperModule() {
		super("AH Sniper", "Refreshes the AH and instantly buys your item below your price, with optional auto selling.", Category.MISC);
		this.enchantMatch.visibleWhen(() -> !this.enchants.get().isBlank());
		this.sellPrice.visibleWhen(this.autoSell::get);
	}

	@Override
	public boolean persistsEnabled() {
		return false;
	}

	@Override
	protected void onEnable() {
		if (mc.player != null && mc.getConnection() != null && mc.getUser() != null) {
			long max = ServerText.parseMoney(this.maxPrice.get());
			if (max <= 0L) {
				this.say(ModuleChat.body().warn("Set Buy Below to a price like 50m."));
				mc.execute(() -> this.setEnabled(false));
			} else {
				SnipeTarget target = SnipeTarget.parse(
					this.item.get(), this.enchants.get(), this.enchantMatch.get().equals("Exactly these"), max, Math.round(this.minHours.get().floatValue() * 60.0F)
				);
				if (target == null) {
					this.say(ModuleChat.body().warn("Pick an item to snipe first."));
					mc.execute(() -> this.setEnabled(false));
				} else {
					long spendLimit = 0L;
					if (!this.budget.get().isBlank()) {
						spendLimit = ServerText.parseMoney(this.budget.get());
						if (spendLimit <= 0L) {
							this.say(ModuleChat.body().warn("Set Max Total Spend to a price like 500m, or leave it empty."));
							mc.execute(() -> this.setEnabled(false));
							return;
						}
					}

					long sell = 0L;
					if (this.autoSell.get()) {
						sell = ServerText.parseMoney(this.sellPrice.get());
						if (sell <= 0L) {
							this.say(ModuleChat.body().warn("Set Sell Price to a price like 60m, or turn Auto Sell off."));
							mc.execute(() -> this.setEnabled(false));
							return;
						}

						if (sell <= max) {
							this.say(ModuleChat.body().warn("Sell Price must be higher than Buy Below").text(", or the sniper would buy your own listing back."));
							mc.execute(() -> this.setEnabled(false));
							return;
						}
					}

					SniperBot created = new SniperBot(
						this,
						this::stopFromBot,
						this.worker,
						target,
						this.refresh.get().intValue(),
						this.settle.get().intValue(),
						spendLimit,
						this.requireConfirm.get(),
						this.maxBuys.get().intValue(),
						sell,
						mc.getUser().getName()
					);
					this.bot = created;
					this.worker.start(created::run, failure -> {
						LOGGER.error("AH Sniper stopped", failure);
						this.say(ModuleChat.body().danger("Stopped").text(": " + failure));
						this.stopFromBot(created);
					});
				}
			}
		} else {
			this.say(ModuleChat.body().warn("Join a server first."));
			mc.execute(() -> this.setEnabled(false));
		}
	}

	void stopFromBot(SniperBot from) {
		Minecraft.getInstance().execute(() -> {
			if (this.bot == from && this.isEnabled()) {
				this.setEnabled(false);
			}
		});
	}

	@Override
	protected void onDisable() {
		SniperBot stopped = this.bot;
		this.bot = null;
		this.worker.stop();
		LocalPlayer player = mc.player;
		if (stopped != null && player != null) {
			if (player.containerMenu != player.inventoryMenu) {
				player.closeContainer();
			} else {
				Dialogs.close(mc);
			}

			this.say(ModuleChat.body().text(stopped.summary()));
		}
	}

	@Override
	public void onPacketReceive(PacketEvent event) {
		SniperBot current = this.bot;
		if (current != null && event.getPacket() instanceof ClientboundSystemChatPacket chat && chat.content() != null) {
			current.onChat(chat.content().getString());
		}
	}

	private void say(ModuleChat.Body body) {
		ModuleChat.send(this, body);
	}
}
