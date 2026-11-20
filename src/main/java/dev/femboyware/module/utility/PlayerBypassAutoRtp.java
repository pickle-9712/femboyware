package dev.femboyware.module.utility;

import dev.femboyware.util.playerbypass.*;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.setting.StringSetting;

import com.mojang.authlib.GameProfile;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
/** Ported from the Anubis client (GPL-3.0, by 4ldenz, https://github.com/4ldenz/Anubis-client). */
final class PlayerBypassAutoRtp {
	private static final double TELEPORT_DISTANCE_SQ = 256.0;
	private static final long REPLY_WINDOW_MS = 5000L;
	private static final long STOP_REPLY_WINDOW_MS = 2500L;
	private static final long RESUME_GRACE_MS = 1000L;
	private static final long RESPAWN_GRACE_MS = 3000L;
	private static final long OFFLINE_LIMIT_MS = 30000L;
	private static final long MENU_CLICK_DELAY_MS = 300L;
	private static final long MENU_FILL_MS = 2500L;
	private static final long MENU_CLAIM_MS = 4000L;
	private static final int MAX_MENU_CLICKS = 3;
	private static final int MENU_NAMES_SHOWN = 10;
	private static final long LONG_COOLDOWN_MS = 60000L;
	private static final long BAR_REFRESH_MS = 2000L;
	private static final long NO_CHUNK = Long.MIN_VALUE;
	private static final long NEVER = Long.MIN_VALUE;
	private final PlayerBypassModule module;
	private final BoolSetting enabled;
	private final StringSetting command;
	private final NumberSetting scanTime;
	private final NumberSetting delay;
	private final NumberSetting timeout;
	private final NumberSetting maxRtps;
	private final BoolSetting stopOnSuspect;
	private final StringSetting menuItem;
	private final BoolSetting sound;
	private final BoolSetting showCoordinates;
	private final AutoRtpLoop loop = new AutoRtpLoop();
	private final LongOpenHashSet knownSuspects = new LongOpenHashSet();
	private static final net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
	/** Whether the module should pass ticks and packets on (Anubis registered listeners for this). */
	private boolean listening;
	private Connection link;
	private ClientLevel level;
	private Vec3 anchor;
	private int startChunkX;
	private int startChunkZ;
	private boolean wasDead;
	private long offlineSince;
	private long lastRevision;
	private boolean lastTryLanded;
	private long commandSentAt = Long.MIN_VALUE;
	private boolean sendingCommand;
	private volatile long userActionAt = Long.MIN_VALUE;
	private int menuId = -1;
	private int ignoredMenuId = -1;
	private long menuSeenAt;
	private boolean menuClicked;
	private int menuClicks;
	private long lastMenuClickAt;
	private long noticedCooldownUntil;
	private String lastBar = "";
	private long lastBarAt;

	PlayerBypassAutoRtp(
		PlayerBypassModule module,
		BoolSetting enabled,
		StringSetting command,
		NumberSetting scanTime,
		NumberSetting delay,
		NumberSetting timeout,
		NumberSetting maxRtps,
		BoolSetting stopOnSuspect,
		StringSetting menuItem,
		BoolSetting sound,
		BoolSetting showCoordinates
	) {
		this.module = module;
		this.enabled = enabled;
		this.command = command;
		this.scanTime = scanTime;
		this.delay = delay;
		this.timeout = timeout;
		this.maxRtps = maxRtps;
		this.stopOnSuspect = stopOnSuspect;
		this.menuItem = menuItem;
		this.sound = sound;
		this.showCoordinates = showCoordinates;
	}

	void start() {
		if (!this.loop.running()) {
			LocalPlayer player = mc.player;
			ClientPacketListener connection = mc.getConnection();
			if (player != null && mc.level != null && connection != null && connection.getConnection().isConnected()) {
				long now = System.currentTimeMillis();
				AutoRtpLoop.Config config = this.config();
				this.loop.start(now, config);
				this.link = connection.getConnection();
				this.level = mc.level;
				this.anchor = player.position();
				this.startChunkX = player.blockPosition().getX() >> 4;
				this.startChunkZ = player.blockPosition().getZ() >> 4;
				this.wasDead = player.isDeadOrDying();
				this.offlineSince = 0L;
				this.lastRevision = PlayerBypassLightTracker.visibilityRevision();
				this.lastTryLanded = false;
				this.noticedCooldownUntil = 0L;
				this.lastBar = "";
				this.resetMenu();
				this.rememberSuspects();
				this.listening = true;

				ModuleChat.Body body = ModuleChat.body()
					.good("Auto RTP started")
					.text(": ")
					.value("/" + this.command())
					.text(", ")
					.value(seconds(config.scanMs()))
					.text(" scan per spot, at least ")
					.value(seconds(config.delayMs()))
					.text(" between RTPs");
				if (config.maxRtps() > 0) {
					body.text(", max ").value(config.maxRtps());
				}

				long cooldown = this.loop.cooldownLeft(now);
				if (cooldown > 0L) {
					this.noticedCooldownUntil = now + cooldown;
					body.text(", server cooldown ").value(seconds(cooldown)).text(" left");
					if (cooldown >= 60000L) {
						body.text(" (turn Player Bypass off and on to clear it)");
					}
				}

				this.say(body.text("."));
			} else {
				this.say(ModuleChat.body().warn("Join a server first").text(", Auto RTP is off."));
				this.enabled.set(false);
			}
		}
	}

	void stopByUser() {
		if (!this.loop.running()) {
			this.unlisten();
		} else {
			this.stop(ModuleChat.body().text("Auto RTP stopped after ").value(this.loop.landings()).text(rtpWord(this.loop.landings())).text("."));
		}
	}

	void forgetCooldown() {
		this.loop.forgetCooldown();
	}

	private void stop(ModuleChat.Body why) {
		boolean was = this.loop.running() || this.listening;
		this.loop.stop();
		this.unlisten();
		this.resetMenu();
		this.link = null;
		this.level = null;
		this.anchor = null;
		if (was && why != null) {
			this.say(why);
		}
	}

	private void stopWithRestartHint(ModuleChat.Body why) {
		this.stop(why.text(" Turn ").value("Auto RTP").text(" off and on to start again."));
	}

	private void unlisten() {
		this.listening = false;
	}

	void onTick() {
		if (this.listening) this.tick();
	}

	void onReceive(Packet<?> packet) {
		if (this.listening) this.onPacket(packet);
	}

	void onSend(Packet<?> packet) {
		if (this.listening) this.onPacketSent(packet);
	}

	boolean baseSpotted(int blockX, int blockZ, boolean disconnecting) {
		if (this.loop.running() && !this.atStartSpot(blockX >> 4, blockZ >> 4)) {
			this.found("Base", blockX, blockZ, disconnecting);
			return true;
		} else {
			return false;
		}
	}

	private void found(String what, int blockX, int blockZ, boolean disconnecting) {
		LocalPlayer player = Minecraft.getInstance().player;
		boolean teleporting = this.loop.phase() == AutoRtpLoop.Phase.TELEPORTING;
		boolean moved = player != null && this.anchor != null && (player.level() != this.level || player.position().distanceToSqr(this.anchor) > 256.0);
		boolean pending = this.loop.awaitingTeleport() && player != null && !moved;
		int rtps = this.loop.landings() + (teleporting && moved ? 1 : 0);
		boolean showPos = this.showCoordinates.get();
		ModuleChat.Body body = ModuleChat.body().good(what + " found");
		if (showPos) {
			body.text(" at ").value(blockX + ", " + blockZ);
		}

		this.stopWithRestartHint(body.text(" after ").value(rtps).text(rtpWord(rtps) + ". Auto RTP stopped."));
		if (pending && !disconnecting) {
			this.say(
				ModuleChat.body()
					.warn("Your /" + this.command() + " is still pending")
					.text(": move a little to cancel it (most servers cancel a teleport when you move),")
					.text(" or the server may still teleport you away.")
			);
		}

		ModuleChat.toast(
			new ItemStack(what.equals("Base") ? Items.PLAYER_HEAD : Items.SPYGLASS),
			what + " Found - Auto RTP",
			Component.literal(showPos ? "at " + blockX + ", " + blockZ : "after " + rtps + rtpWord(rtps)),
			this.sound.get()
		);
		if (this.sound.get()) {
			Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.PLAYER_LEVELUP, 1.0F));
		}
	}

	private void tick() {
		if (this.loop.running()) {
			LocalPlayer player = mc.player;
			ClientPacketListener connection = mc.getConnection();
			long now = System.currentTimeMillis();
			if (this.link != null && this.link.isConnected() && (connection == null || connection.getConnection() == this.link)) {
				if (player != null && mc.level != null && connection != null && mc.gameMode != null) {
					this.offlineSince = 0L;
					AutoRtpLoop.Config config = this.config();
					long revision = PlayerBypassLightTracker.visibilityRevision();
					if (revision != this.lastRevision) {
						this.lastRevision = revision;
						this.loop.dataArrived(now);
					}

					if (player.isDeadOrDying()) {
						if (!this.wasDead) {
							this.wasDead = true;
							this.loop.interrupt(now, config);
							this.resetMenu();
						}

						this.loop.holdUntil(now + 3000L);
						this.bar(now, this.label(config) + " - paused, you died", now - this.lastBarAt >= 2000L);
					} else {
						if (this.wasDead) {
							this.wasDead = false;
							this.level = mc.level;
							this.anchor = player.position();
							this.loop.holdUntil(now + 3000L);
						}

						boolean levelChanged = mc.level != this.level;
						if (levelChanged) {
							this.level = mc.level;
							this.knownSuspects.clear();
						}

						if (levelChanged || this.anchor != null && player.position().distanceToSqr(this.anchor) > 256.0) {
							switch (this.loop.phase()) {
								case TELEPORTING:
									this.land(player, now, config);
									break;
								case SCANNING:
									this.anchor = player.position();
									this.loop.relanded(now, config);
									break;
								default:
									if (this.loop.landedLate(now, config)) {
										this.anchor = player.position();
										this.closeMenu(player);
										this.resetMenu();
									} else if (levelChanged) {
										this.stopWithRestartHint(ModuleChat.body().warn("World changed").text(", Auto RTP stopped."));
										return;
									}
							}
						}

						if (this.stopOnSuspect.get() && !levelChanged) {
							long suspect = this.newSuspectNear(mc, player);
							if (suspect != Long.MIN_VALUE) {
								this.found("Suspect", (ChunkPos.getX(suspect) << 4) + 8, (ChunkPos.getZ(suspect) << 4) + 8, false);
								return;
							}
						}

						if (!this.handleMenu(mc, player, now, config)) {
							String pause = dev.femboyware.util.Mc.screen() != null && !this.ourMenuOpen(mc, player) ? "a menu is open" : null;
							if (pause != null) {
								this.loop.holdUntil(now + 1000L);
							}

							switch (this.loop.tick(now, config)) {
								case SEND:
									this.send(player, connection, now);
									break;
								case TIMED_OUT:
									this.lastTryLanded = false;
									this.closeMenu(player);
									break;
								case SCAN_CLEAR:
									this.lastTryLanded = true;
									break;
								case LIMIT:
									this.stopWithRestartHint(
										ModuleChat.body().text("Max RTPs reached (").value(config.maxRtps()).text("), no base found. Auto RTP stopped.")
									);
									return;
								case GAVE_UP:
									this.closeMenu(player);
									this.stopWithRestartHint(
										ModuleChat.body()
											.warn("No teleport after 5 tries")
											.text(", check ")
											.value("RTP Command")
											.text(" and ")
											.value("Menu Item")
											.text(". Auto RTP stopped.")
									);
									return;
							}

							this.progress(now, pause, config);
						}
					}
				} else {
					if (this.offlineSince == 0L) {
						this.offlineSince = now;
					}

					if (now - this.offlineSince > 30000L) {
						this.stopWithRestartHint(ModuleChat.body().warn("No world for 30s").text(", Auto RTP stopped."));
					}
				}
			} else {
				this.stopWithRestartHint(ModuleChat.body().warn("Disconnected").text(", Auto RTP stopped."));
			}
		}
	}

	private void send(LocalPlayer player, ClientPacketListener connection, long now) {
		this.anchor = player.position();
		this.resetMenu();
		this.rememberSuspects();
		this.commandSentAt = now;
		this.sendingCommand = true;

		try {
			connection.sendCommand(this.command());
		} finally {
			this.sendingCommand = false;
		}
	}

	private void land(LocalPlayer player, long now, AutoRtpLoop.Config config) {
		this.anchor = player.position();
		this.closeMenu(player);
		this.resetMenu();
		this.loop.landed(now, config);
	}

	private void onPacket(Packet<?> packet) {
		if (this.loop.running()) {
			if (packet instanceof ClientboundDamageEventPacket damage) {
				this.onDamage(damage);
			} else if (packet instanceof ClientboundSystemChatPacket chat) {
				this.onServerText(chat.content().getString(), !chat.overlay());
			} else if (packet instanceof ClientboundSetTitleTextPacket title) {
				this.onServerText(title.text().getString(), false);
			} else if (packet instanceof ClientboundSetSubtitleTextPacket subtitle) {
				this.onServerText(subtitle.text().getString(), false);
			}
		}
	}

	private void onPacketSent(Packet<?> packet) {
		if (!this.sendingCommand) {
			if (packet instanceof ServerboundUseItemOnPacket
				|| packet instanceof ServerboundChatCommandPacket
				|| packet instanceof ServerboundChatCommandSignedPacket
				|| packet instanceof ServerboundInteractPacket interact && isInteraction(interact)) {
				this.userActionAt = System.currentTimeMillis();
			}
		}
	}

	private static boolean isInteraction(ServerboundInteractPacket packet) {
		//? if >=26.1 {
		/*return true; // attacks have their own packet on 26.x
		*///?} else {
		final boolean[] interaction = new boolean[]{false};
		packet.dispatch(new ServerboundInteractPacket.Handler() {
			public void onInteraction(InteractionHand hand) {
				interaction[0] = true;
			}

			public void onInteraction(InteractionHand hand, Vec3 location) {
				interaction[0] = true;
			}

			public void onAttack() {
			}
		});
		return interaction[0];
		//?}
	}

	private void onServerText(String raw, boolean chatLine) {
		long now = System.currentTimeMillis();
		boolean teleporting = this.loop.phase() == AutoRtpLoop.Phase.TELEPORTING;
		if (teleporting || this.loop.sentWithin(now, 5000L)) {
			if (!chatLine || !RtpReply.looksLikePlayerChat(raw, otherPlayerNames())) {
				RtpReply reply = RtpReply.parse(raw);
				boolean fresh = this.loop.sentWithin(now, 2500L);
				switch (reply.kind()) {
					case COOLDOWN:
						this.loop.cooldown(now, reply.millis());
						this.noticeCooldown(now, reply.millis());
						break;
					case WARMUP:
						this.loop.warmup(now, reply.millis(), this.config());
						if (reply.nextUseMs() >= 0L) {
							this.loop.nextUseIn(now, reply.nextUseMs());
							this.noticeCooldown(now, reply.nextUseMs());
						}
						break;
					case CANCELLED:
						this.loop.cancelled(now);
						break;
					case UNKNOWN_COMMAND:
						if (fresh) {
							this.stopWithRestartHint(
								ModuleChat.body()
									.warn("The server does not know ")
									.value("/" + this.command())
									.text(", check ")
									.value("RTP Command")
									.text(". Auto RTP stopped.")
							);
						}
						break;
					case NO_PERMISSION:
						if (fresh) {
							this.stopWithRestartHint(ModuleChat.body().warn("No permission for ").value("/" + this.command()).text(". Auto RTP stopped."));
						}
						break;
					case COMBAT:
						if (teleporting && fresh) {
							this.stopWithRestartHint(ModuleChat.body().danger("In combat").text(", Auto RTP stopped."));
						}
				}
			}
		}
	}

	private void noticeCooldown(long now, long ms) {
		long until = now + ms;
		if (ms >= 60000L && until > this.noticedCooldownUntil + 5000L) {
			this.noticedCooldownUntil = until;
			this.say(ModuleChat.body().text("Server cooldown ").value(seconds(ms)).text(", Auto RTP waits."));
		}
	}

	private static List<String> otherPlayerNames() {
		ClientPacketListener connection = mc.getConnection();
		if (connection == null) {
			return List.of();
		}

		LocalPlayer self = mc.player;
		UUID selfId = self == null ? null : self.getUUID();
		String selfName = self == null ? null : self.getGameProfile().name();
		List<String> names = new ArrayList<>();

		for (PlayerInfo info : connection.getOnlinePlayers()) {
			GameProfile profile = info.getProfile();
			if (profile != null && profile.name() != null && !Objects.equals(profile.id(), selfId) && !profile.name().equalsIgnoreCase(selfName)) {
				names.add(profile.name());
			}
		}

		return names;
	}

	private void onDamage(ClientboundDamageEventPacket packet) {
		LocalPlayer player = mc.player;
		if (player != null && mc.level != null && packet.entityId() == player.getId()) {
			Entity cause = packet.sourceCauseId() >= 0 ? mc.level.getEntity(packet.sourceCauseId()) : null;
			if (cause != player) {
				boolean byPlayer = cause instanceof Player
					|| packet.sourceType().is(DamageTypes.PLAYER_ATTACK)
					|| packet.sourceType().is(DamageTypes.PLAYER_EXPLOSION);
				if (byPlayer) {
					ModuleChat.Body body = ModuleChat.body().danger("Hit by ");
					if (cause instanceof Player attacker) {
						body.name(attacker.getGameProfile().name());
					} else {
						body.text("a player");
					}

					this.stopWithRestartHint(body.text(", Auto RTP stopped (combat)."));
				}
			}
		}
	}

	private boolean handleMenu(Minecraft mc, LocalPlayer player, long now, AutoRtpLoop.Config config) {
		if (this.loop.phase() == AutoRtpLoop.Phase.TELEPORTING && containerOpen(mc, player)) {
			AbstractContainerMenu menu = player.containerMenu;
			if (menu.containerId != this.menuId) {
				if (menu.containerId == this.ignoredMenuId) {
					return false;
				}

				if (!this.menuMayBeOurs(now)) {
					this.ignoredMenuId = menu.containerId;
					return false;
				}

				if (this.menuClicks >= 3) {
					this.stopWithRestartHint(ModuleChat.body().warn("The RTP menu keeps opening").text(", Auto RTP stopped."));
					return true;
				}

				this.menuId = menu.containerId;
				this.menuSeenAt = now;
				this.menuClicked = false;
				this.loop.extend(now, config);
			}

			if (!this.menuClicked && now - this.menuSeenAt >= 300L) {
				List<String> names = new ArrayList<>();
				String wanted = this.menuItem.get().trim();
				int slot = findSlot(menu, wanted, names);
				boolean loading = now - this.menuSeenAt < 2500L;
				if (wanted.isEmpty()) {
					if (names.isEmpty() && loading) {
						return false;
					}

					this.stopWithRestartHint(
						ModuleChat.body()
							.warn("The RTP command opened a menu")
							.text(", set ")
							.value("Menu Item")
							.text(" to the item to click")
							.text(nameList(names))
							.text(". Auto RTP stopped.")
					);
					return true;
				} else {
					if (slot >= 0) {
						mc.gameMode.handleInventoryMouseClick(menu.containerId, slot, 0, ClickType.PICKUP, player);
						this.menuClicked = true;
						this.menuClicks++;
						this.lastMenuClickAt = now;
						this.loop.extend(now, config);
						return false;
					}

					if (loading) {
						return false;
					}

					this.stopWithRestartHint(ModuleChat.body().warn("No \"" + wanted + "\" in the RTP menu").text(nameList(names)).text(". Auto RTP stopped."));
					return true;
				}
			} else {
				return false;
			}
		} else {
			return false;
		}
	}

	private boolean menuMayBeOurs(long now) {
		if (this.commandSentAt != Long.MIN_VALUE && this.userActionAt < this.commandSentAt) {
			return now - this.commandSentAt <= 4000L ? true : this.menuClicks > 0 && now - this.lastMenuClickAt <= 4000L;
		} else {
			return false;
		}
	}

	private static int findSlot(AbstractContainerMenu menu, String wanted, List<String> names) {
		List<Slot> slots = new ArrayList<>();
		List<String> slotNames = new ArrayList<>();
		List<String> slotIds = new ArrayList<>();

		for (Slot slot : menu.slots) {
			if (slot.container instanceof Inventory) {
				break;
			}

			ItemStack stack = slot.getItem();
			if (!stack.isEmpty()) {
				String name = RtpReply.normalize(stack.getHoverName().getString());
				slots.add(slot);
				slotNames.add(name);
				slotIds.add(BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath());
				if (!name.isEmpty() && !names.contains(name)) {
					names.add(name);
				}
			}
		}

		for (String part : wanted.split(",")) {
			String text = RtpReply.normalize(part);
			if (!text.isEmpty()) {
				String id = text.replace(' ', '_');

				for (int i = 0; i < slots.size(); i++) {
					if (slotNames.get(i).contains(text) || slotIds.get(i).equals(id)) {
						return slots.get(i).index;
					}
				}
			}
		}

		return -1;
	}

	private static String nameList(List<String> names) {
		if (names.isEmpty()) {
			return "";
		}

		List<String> shown = names.size() > 10 ? names.subList(0, 10) : names;
		return " (items: " + String.join(", ", shown) + (names.size() > shown.size() ? ", ..." : "") + ")";
	}

	private static boolean containerOpen(Minecraft mc, LocalPlayer player) {
		return player.containerMenu != player.inventoryMenu && dev.femboyware.util.Mc.screen() instanceof AbstractContainerScreen;
	}

	private boolean ourMenuOpen(Minecraft mc, LocalPlayer player) {
		return this.menuId >= 0 && containerOpen(mc, player) && player.containerMenu.containerId == this.menuId;
	}

	private void closeMenu(LocalPlayer player) {
		if (this.menuId >= 0 && player.containerMenu != player.inventoryMenu && player.containerMenu.containerId == this.menuId) {
			player.closeContainer();
		}
	}

	private void resetMenu() {
		this.menuId = -1;
		this.ignoredMenuId = -1;
		this.menuClicked = false;
		this.menuClicks = 0;
	}

	private void rememberSuspects() {
		this.knownSuspects.clear();
		LongIterator var1 = PlayerBypassLightTracker.snapshots().keySet().iterator();

		while (var1.hasNext()) {
			long key = (Long)var1.next();
			if (PlayerBypassLightTracker.isSuspect(key)) {
				this.knownSuspects.add(key);
			}
		}
	}

	private long newSuspectNear(Minecraft mc, LocalPlayer player) {
		int radius = mc.options.getEffectiveRenderDistance() + 1;
		int centerX = player.blockPosition().getX() >> 4;
		int centerZ = player.blockPosition().getZ() >> 4;
		ObjectIterator var6 = PlayerBypassLightTracker.snapshots().long2ObjectEntrySet().iterator();

		while (var6.hasNext()) {
			Entry<ChunkLightSnapshot> entry = (Entry<ChunkLightSnapshot>)var6.next();
			long key = entry.getLongKey();
			if (!this.knownSuspects.contains(key)
				&& PlayerBypassLightTracker.isSuspect(key)
				&& PlayerBypassLightTracker.isFlagged(key, (ChunkLightSnapshot)entry.getValue())) {
				int chunkX = ChunkPos.getX(key);
				int chunkZ = ChunkPos.getZ(key);
				if (Math.abs(chunkX - centerX) <= radius && Math.abs(chunkZ - centerZ) <= radius && !this.atStartSpot(chunkX, chunkZ)) {
					return key;
				}
			}
		}

		return Long.MIN_VALUE;
	}

	private boolean atStartSpot(int chunkX, int chunkZ) {
		if (this.loop.landings() > 0) {
			return false;
		}

		int radius = Minecraft.getInstance().options.getEffectiveRenderDistance() + 1;
		return Math.abs(chunkX - this.startChunkX) <= radius && Math.abs(chunkZ - this.startChunkZ) <= radius;
	}

	private void progress(long now, String pause, AutoRtpLoop.Config config) {
		String label = this.label(config);
		String text;
		if (pause != null) {
			text = label + " - paused, " + pause;
		} else {
			long next = this.loop.nextSendIn(now);

			text = switch (this.loop.phase()) {
				case TELEPORTING -> label + " - waiting for the teleport";
				case SCANNING -> label + " - scanning, " + seconds(this.loop.scanLeft(now)) + " left";
				case WAITING -> this.loop.cooldownLeft(now) > 0L
					? label + " - server cooldown, next in " + seconds(next)
					: (
						this.loop.landings() == 0 && this.loop.sent() == 0
							? label + " - starting" + (next >= 1000L ? " in " + seconds(next) : "")
							: (
								!this.lastTryLanded
									? label + " - no teleport, retry in " + seconds(next)
									: (this.loop.settling(now) ? label + " - no base yet, chunks still loading" : label + " - no base, next in " + seconds(next))
							)
					);
				default -> "";
			};
		}

		boolean refresh = this.loop.phase() != AutoRtpLoop.Phase.TELEPORTING && now - this.lastBarAt >= 2000L;
		this.bar(now, text, refresh);
	}

	private String label(AutoRtpLoop.Config config) {
		return "Auto RTP #" + this.loop.sent() + (config.maxRtps() > 0 ? "/" + config.maxRtps() : "");
	}

	private void bar(long now, String text, boolean refresh) {
		if (!text.isEmpty() && (!text.equals(this.lastBar) || refresh)) {
			this.lastBar = text;
			this.lastBarAt = now;
			ModuleChat.actionBarAlways(this.module, ModuleChat.body().text(text));
		}
	}

	private AutoRtpLoop.Config config() {
		return new AutoRtpLoop.Config(millis(this.scanTime), millis(this.delay), millis(this.timeout), this.maxRtps.getInt());
	}

	private String command() {
		String text = this.command.get().trim();

		while (text.startsWith("/")) {
			text = text.substring(1).trim();
		}

		return text.isEmpty() ? "rtp" : text;
	}

	private void say(ModuleChat.Body body) {
		ModuleChat.send(this.module, body);
	}

	private static long millis(NumberSetting setting) {
		return Math.round(setting.get() * 1000.0);
	}

	private static String seconds(long ms) {
		long s = (ms + 999L) / 1000L;
		return s >= 120L ? String.format(Locale.ROOT, "%dm %ds", s / 60L, s % 60L) : s + "s";
	}

	private static String rtpWord(int count) {
		return count == 1 ? " RTP" : " RTPs";
	}
}
