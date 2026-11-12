package dev.femboyware.module.utility;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.femboyware.FemboyWare;
import dev.femboyware.event.PacketEvent;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.manager.CommandManager;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.StringSetting;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.component.ItemContainerContents;
import dev.femboyware.util.ChatUtil;
import dev.femboyware.util.Mc;
import dev.femboyware.util.RenderUtil;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.common.ServerboundClientInformationPacket;
import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetDataPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerRotationPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetCursorItemPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ServerboundChatAckPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.network.protocol.game.ServerboundChatSessionUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket;
import net.minecraft.network.protocol.game.ServerboundConfigurationAcknowledgedPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.FireworkRocketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import io.netty.buffer.Unpooled;
import dev.femboyware.module.client.FakePlayerModule;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.level.block.Block;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A private sandbox. While on, nothing you do reaches the server: it sees you standing still where
 * you turned this on, while here you move, fly, place and break blocks, give yourself items, and fill
 * chests, all client-side. Anything the server says about you meanwhile (teleports, inventory, health)
 * is held back. Turning it off puts back your spot, inventory, game mode and every block you touched,
 * then plays the held back updates, so you're exactly as the server has you.
 */
public class ServerLarpModule extends Module {
	private static final int FAKE_CONTAINER_ID = 121;
	private static final int FIRST_ROCKET_ID = -2_000_000;
	private static final Gson GSON = new Gson();
	private static final Path SAVE_FILE = FabricLoader.getInstance().getGameDir().resolve("femboyware").resolve("larp_save.json");

	private final BoolSetting allowChat = add(new BoolSetting("Allow Chat", "Chat and commands still reach the server; everything else is held back", true));
	private final BoolSetting render = add(new BoolSetting("Render", "Show where the server sees you standing", true));
	private final ColorSetting color = add(new ColorSetting("Color", "Colour of your server-side spot", 0x40FF77C8)).visibleWhen(render::get);
	private final BoolSetting resumePosition = add(new BoolSetting("Resume Position", "Turning it back on puts you where you were last larping here, instead of where you're standing", true));
	private final BoolSetting worthAlways = add(new BoolSetting("Worth Always", "Show item worth in tooltips even when not larping. Set prices with " + CommandManager.PREFIX + "larp worth", false));
	private final BoolSetting autoResume = add(new BoolSetting("Auto Resume", "Re-enable and restore your larp when joining back after a relog or restart", true));
	private final BoolSetting allowTeleports = add(new BoolSetting("Allow Teleports", "Other femboyware users larping here can teleport you in your larp with /tp <your name> <where> (only on your screen, like the rest of the larp)", true));
	/** Prices per item, as item_id=price pairs. */
	private final StringSetting prices = add(new StringSetting("Worth Prices", "", "")).visibleWhen(() -> false);
	private Map<String, Double> priceMap = new HashMap<>();
	private String pricesParsedFrom;

	/** Set from the network thread too, so packets know whether to be held. */
	private volatile boolean larping;
	private volatile int selfId = Integer.MIN_VALUE;
	private final List<Packet<?>> held = Collections.synchronizedList(new ArrayList<>());

	private ClientLevel startLevel;
	private Vec3 startPos;
	private float startYaw, startPitch;
	private GameType startMode;
	/** Your health when larp started, put back at the end; the larp's own health meanwhile is only on your screen. */
	private float startHealth;
	/** The highest you've been since you last stood on something, for fall damage; NaN on the ground. */
	private double fallTop = Double.NaN;
	private int regenTicks, hurtCooldown;
	private final List<ItemStack> startInventory = new ArrayList<>();
	private int startSlot;
	private ItemStack startCursor = ItemStack.EMPTY;
	private AABB serverBox;

	/** Chests, barrels and shulkers opened while larping, by position; their contents vanish when it ends. */
	private final Map<BlockPos, SimpleContainer> containers = new HashMap<>();
	private SimpleContainer enderChest;
	private final Set<FireworkRocketEntity> rockets = Collections.newSetFromMap(new IdentityHashMap<>());
	/** Items dropped while larping: they only exist here, so picking up, burning and despawning are done here too. */
	private final Set<ItemEntity> drops = Collections.newSetFromMap(new IdentityHashMap<>());
	private static final int DESPAWN_TICKS = 6000;
	/** End crystals placed while larping; they only exist here, and blow up here. */
	private final Set<EndCrystal> crystals = Collections.newSetFromMap(new IdentityHashMap<>());
	private int nextRocketId = FIRST_ROCKET_ID;
	/** Levers, buttons, plates and redstone, worked out here while larping. */
	private final dev.femboyware.module.larp.LarpRedstone redstone = new dev.femboyware.module.larp.LarpRedstone();
	/** Arrows shot while larping (by Force Dispenser), which only exist here and go when it ends or after a minute. */
	private final Map<Entity, Integer> localShots = new IdentityHashMap<>();
	/** Everything from the last larp, kept when it's turned off so turning it back on carries on; .larp reset forgets it. */
	/**
	 * Items are kept as data, not live stacks: each join builds fresh registries, and a stack still pointing
	 * at an old session's enchantments can't be hashed, which breaks inventory clicks.
	 */
	private record Saved(String world, Vec3 pos, float yaw, float pitch, GameType mode, List<Tag> inventory, int slot,
						 Map<BlockPos, BlockState> blocks, Map<BlockPos, List<Tag>> containers, List<Tag> ender,
						 List<Drop> drops, List<Vec3> crystals) {
	}

	private record Drop(Vec3 pos, Tag stack) {
	}

	private Tag pack(ItemStack stack) {
		return ItemStack.OPTIONAL_CODEC.encodeStart(mc.level.registryAccess().createSerializationContext(NbtOps.INSTANCE), stack)
			.result().orElseGet(CompoundTag::new);
	}

	private ItemStack unpack(Tag tag) {
		return ItemStack.OPTIONAL_CODEC.parse(mc.level.registryAccess().createSerializationContext(NbtOps.INSTANCE), tag)
			.result().orElse(ItemStack.EMPTY);
	}

	private List<Tag> packAll(Container container) {
		List<Tag> list = new ArrayList<>();
		for (int i = 0; i < container.getContainerSize(); i++) list.add(pack(container.getItem(i)));
		return list;
	}

	private SimpleContainer unpackAll(List<Tag> list) {
		SimpleContainer container = new SimpleContainer(list.size());
		for (int i = 0; i < list.size(); i++) container.setItem(i, unpack(list.get(i)));
		return container;
	}

	private Saved saved;
	/** Set by .larp reset, so turning off doesn't save what's being thrown away. */
	private boolean discardOnDisable;

	/** The container open right now, to play its closing when the screen goes. */
	private BlockPos openPos;
	private net.minecraft.world.inventory.AbstractContainerMenu openMenu;

	public ServerLarpModule() {
		super("Server Larp", "Freezes you server-side while you play client-side with any items, blocks and chests; off puts everything back", Category.LARP);
		ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
			if (!isEnabled() && !worthAlways.get()) return;
			double worth = worth(stack);
			if (worth > 0) lines.add(worthLine(worth));
		});
		loadFromDisk();
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(() -> {
			if (!isEnabled() && autoResume.get() && saved != null && client.level != null) {
				try {
					if (saved.world().equals(worldKey())) setEnabled(true);
				} catch (RuntimeException ignored) {}
			}
		}));
	}

	/** What a stack is worth at your prices: the count times its price, plus whatever's inside if it's a shulker box. */
	private double worth(ItemStack stack) {
		if (stack.isEmpty()) return 0;
		Map<String, Double> table = prices();
		double total = table.getOrDefault(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), 0.0) * stack.getCount();
		ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
		if (contents != null) {
			//? if >=26.1 {
			/*total += contents.nonEmptyItemCopyStream().mapToDouble(this::worth).sum() * stack.getCount();
			*///?} else {
			total += contents.nonEmptyStream().mapToDouble(this::worth).sum() * stack.getCount();
			//?}
		}
		return total;
	}

	/** ~$ 5M: a grey tilde, a green dollar sign, a space and the short amount in white. */
	private static Component worthLine(double worth) {
		return Component.literal("~").withStyle(ChatFormatting.GRAY)
			.append(Component.literal("$").withStyle(ChatFormatting.GREEN))
			.append(Component.literal(" " + shortMoney(worth)).withStyle(ChatFormatting.WHITE));
	}

	/** 950, 1.5K, 5M, 2.3B, 1T: never the long form. */
	static String shortMoney(double amount) {
		String[] units = {"", "K", "M", "B", "T", "Q"};
		int unit = 0;
		while (Math.abs(amount) >= 999.95 && unit < units.length - 1) {
			amount /= 1000;
			unit++;
		}
		String number = unit == 0 && amount == Math.rint(amount) ? String.valueOf((long) amount)
			: String.format(java.util.Locale.ROOT, "%.1f", amount);
		if (number.endsWith(".0")) number = number.substring(0, number.length() - 2);
		return number + units[unit];
	}

	/** Reads 150, 1.5k, 5m, 2b, 1t or 5,000,000. */
	private static double parseMoney(String text) {
		String clean = text.replace(",", "").replace("$", "").trim().toLowerCase(java.util.Locale.ROOT);
		double scale = 1;
		if (!clean.isEmpty()) {
			switch (clean.charAt(clean.length() - 1)) {
				case 'k' -> scale = 1e3;
				case 'm' -> scale = 1e6;
				case 'b' -> scale = 1e9;
				case 't' -> scale = 1e12;
				case 'q' -> scale = 1e15;
				default -> {
				}
			}
			if (scale != 1) clean = clean.substring(0, clean.length() - 1);
		}
		return Double.parseDouble(clean) * scale;
	}

	private Map<String, Double> prices() {
		if (!prices.get().equals(pricesParsedFrom)) {
			pricesParsedFrom = prices.get();
			Map<String, Double> map = new HashMap<>();
			for (String pair : pricesParsedFrom.split(",")) {
				int eq = pair.indexOf('=');
				if (eq <= 0) continue;
				try {
					map.put(pair.substring(0, eq).trim(), Double.parseDouble(pair.substring(eq + 1).trim()));
				} catch (NumberFormatException ignored) {
				}
			}
			priceMap = map;
		}
		return priceMap;
	}

	private void savePrices(Map<String, Double> map) {
		List<String> pairs = new ArrayList<>();
		map.forEach((id, price) -> pairs.add(id + "=" + price));
		prices.set(String.join(",", pairs));
	}

	private void worthCommand(String[] args) {
		String usage = "Usage: " + CommandManager.PREFIX + "larp worth <item|hand> <price>, " + CommandManager.PREFIX + "larp worth remove <item|hand>, " + CommandManager.PREFIX + "larp worth list";
		if (args.length < 3) {
			ChatUtil.info(usage);
			return;
		}
		Map<String, Double> map = new HashMap<>(prices());
		String what = args[2].toLowerCase(java.util.Locale.ROOT);
		if (what.equals("list")) {
			if (map.isEmpty()) ChatUtil.info("No prices set yet");
			map.entrySet().stream().sorted(Map.Entry.comparingByKey())
				.forEach(entry -> Mc.addChat(Component.literal("[femboyware] " + entry.getKey().replace("minecraft:", "") + " ").append(worthLine(entry.getValue()))));
			return;
		}
		if (what.equals("remove") || what.equals("clear")) {
			if (what.equals("clear")) map.clear();
			else if (args.length > 3) map.remove(itemId(args[3]));
			savePrices(map);
			ChatUtil.info(what.equals("clear") ? "Cleared every price" : "Removed that price");
			return;
		}
		if (args.length < 4) {
			ChatUtil.info(usage);
			return;
		}
		String id = itemId(args[2]);
		if (id == null) {
			ChatUtil.info("No item called " + args[2] + (args[2].equalsIgnoreCase("hand") ? " (your hand is empty)" : ""));
			return;
		}
		double price;
		try {
			price = parseMoney(args[3]);
		} catch (NumberFormatException e) {
			ChatUtil.info("Not a price: " + args[3] + " (like 150, 1.5k, 5m or 2b)");
			return;
		}
		map.put(id, price);
		savePrices(map);
		Mc.addChat(Component.literal("[femboyware] " + id.replace("minecraft:", "") + " is now worth ").append(worthLine(price)).append(Component.literal(" each")));
	}

	/** An item id from a name like diamond or minecraft:diamond, or what's in your hand for "hand"; null if there's no such item. */
	private String itemId(String name) {
		if (name.equalsIgnoreCase("hand")) {
			if (mc.player == null || mc.player.getMainHandItem().isEmpty()) return null;
			return BuiltInRegistries.ITEM.getKey(mc.player.getMainHandItem().getItem()).toString();
		}
		Identifier id = Identifier.tryParse(name.toLowerCase(java.util.Locale.ROOT).contains(":") ? name.toLowerCase(java.util.Locale.ROOT) : "minecraft:" + name.toLowerCase(java.util.Locale.ROOT));
		return id != null && BuiltInRegistries.ITEM.containsKey(id) ? id.toString() : null;
	}

	@Override
	public String getInfo() {
		return larping ? String.valueOf(held.size()) : null;
	}

	public boolean isLarping() {
		return larping;
	}

	@Override
	protected void onEnable() {
		if (nullCheck()) {
			setEnabled(false);
			return;
		}
		LocalPlayer player = mc.player;
		if (player.containerMenu != player.inventoryMenu) player.closeContainer();
		startLevel = mc.level;
		startPos = player.position();
		startYaw = player.getYRot();
		startPitch = player.getXRot();
		startMode = mc.gameMode.getPlayerMode();
		startHealth = player.getHealth();
		fallTop = Double.NaN;
		hurtCooldown = 0;
		Inventory inventory = player.getInventory();
		startInventory.clear();
		for (int i = 0; i < inventory.getContainerSize(); i++) startInventory.add(inventory.getItem(i).copy());
		startSlot = inventory.getSelectedSlot();
		startCursor = player.inventoryMenu.getCarried().copy();
		serverBox = player.getBoundingBox();
		containers.clear();
		enderChest = new SimpleContainer(54);
		held.clear();
		selfId = player.getId();
		larping = true;
		boolean resumed = saved != null && saved.world().equals(worldKey());
		if (resumed) resume(saved);
		ChatUtil.info(resumed ? "Larping again, right where you left off (" + CommandManager.PREFIX + "larp reset starts fresh)"
			: "Larping: the server sees you standing still here. Give yourself items with " + CommandManager.PREFIX + "larp give <item> [count]");
	}

	/** This server (or singleplayer world) and dimension, so a save only comes back where it was made. */
	private String worldKey() {
		String where = mc.getCurrentServer() != null ? mc.getCurrentServer().ip
			: mc.getSingleplayerServer() != null ? "singleplayer:" + mc.getSingleplayerServer().getWorldData().getLevelName() : "unknown";
		return where + "|" + mc.level.dimension().identifier();
	}

	/** Puts the last larp back: blocks (as predictions, so turning off still rolls them back), chests, inventory, drops and crystals. */
	private void resume(Saved from) {
		LocalPlayer player = mc.player;
		var handler = ((dev.femboyware.mixin.ClientLevelAccessor) mc.level).femboyware$getPredictionHandler();
		try (var predicting = handler.startPredicting()) {
			from.blocks().forEach((pos, state) -> mc.level.setBlock(pos, state, 3));
		}
		from.containers().forEach((pos, items) -> containers.put(pos, unpackAll(items)));
		List<Tag> ender = from.ender();
		enderChest = new SimpleContainer(Math.max(54, ender.size()));
		for (int i = 0; i < ender.size(); i++) enderChest.setItem(i, unpack(ender.get(i)));
		Inventory inventory = player.getInventory();
		for (int i = 0; i < from.inventory().size() && i < inventory.getContainerSize(); i++) inventory.setItem(i, unpack(from.inventory().get(i)));
		inventory.setSelectedSlot(from.slot());
		if (mc.gameMode.getPlayerMode() != from.mode()) mc.gameMode.setLocalMode(from.mode());
		for (Drop drop : from.drops()) {
			ItemStack stack = unpack(drop.stack());
			if (stack.isEmpty()) continue;
			ItemEntity item = new ItemEntity(mc.level, drop.pos().x, drop.pos().y, drop.pos().z, stack);
			item.setDeltaMovement(Vec3.ZERO);
			item.setId(nextRocketId--);
			drops.add(item);
			mc.level.addEntity(item);
		}
		for (Vec3 at : from.crystals()) {
			EndCrystal crystal = new EndCrystal(mc.level, at.x, at.y, at.z);
			crystal.setShowBottom(false);
			crystal.setId(nextRocketId--);
			crystals.add(crystal);
			mc.level.addEntity(crystal);
		}
		if (resumePosition.get()) {
			player.snapTo(from.pos().x, from.pos().y, from.pos().z, from.yaw(), from.pitch());
			player.setOldPosAndRot();
			player.setDeltaMovement(Vec3.ZERO);
		}
	}

	/** Takes down everything about the larp as it stands, before it's all put back. */
	private Saved capture(LocalPlayer player) {
		Map<BlockPos, BlockState> blocks = new HashMap<>();
		var handler = ((dev.femboyware.mixin.ClientLevelAccessor) mc.level).femboyware$getPredictionHandler();
		for (long key : ((dev.femboyware.mixin.BlockStatePredictionHandlerAccessor) handler).femboyware$getServerVerifiedStates().keySet()) {
			BlockPos pos = BlockPos.of(key);
			blocks.put(pos, mc.level.getBlockState(pos));
		}
		Inventory current = player.getInventory();
		List<Tag> inventory = packAll(current);
		List<Drop> dropped = new ArrayList<>();
		for (ItemEntity drop : drops) {
			if (!drop.isRemoved()) dropped.add(new Drop(drop.position(), pack(drop.getItem())));
		}
		Map<BlockPos, List<Tag>> chests = new HashMap<>();
		containers.forEach((pos, container) -> chests.put(pos, packAll(container)));
		List<Vec3> placed = new ArrayList<>();
		for (EndCrystal crystal : crystals) {
			if (!crystal.isRemoved()) placed.add(crystal.position());
		}
		return new Saved(worldKey(), player.position(), player.getYRot(), player.getXRot(), mc.gameMode.getPlayerMode(), inventory,
			current.getSelectedSlot(), blocks, chests, packAll(enderChest), dropped, placed);
	}

	@Override
	protected void onDisable() {
		if (!larping) return;
		larping = false;
		ClientPacketListener connection = mc.getConnection();
		LocalPlayer player = mc.player;
		if (player == null || mc.level != startLevel || connection == null) {
			// The world went away; nothing left to put back
			reset();
			return;
		}
		if (player.containerMenu != player.inventoryMenu) {
			player.containerMenu = player.inventoryMenu;
			Mc.setScreen(null);
		}
		closeAnimation();
		if (discardOnDisable) saved = null;
		else saved = capture(player);
		discardOnDisable = false;
		saveToDisk();
		for (FireworkRocketEntity rocket : rockets) rocket.discard();
		rockets.clear();
		for (EndCrystal crystal : crystals) crystal.discard();
		crystals.clear();
		for (ItemEntity drop : drops) drop.discard();
		drops.clear();
		for (Entity shot : localShots.keySet()) shot.discard();
		localShots.clear();
		redstone.reset();
		// Every block placed or broken was a prediction the server never answered; this rolls them all back
		mc.level.handleBlockChangedAck(Integer.MAX_VALUE);
		// Then whatever the schematics replaced (those aren’t predictions: millions of them would never fit)
		restoreSchematics();
		if (mc.gameMode.getPlayerMode() != startMode) mc.gameMode.setLocalMode(startMode);
		// The larp's falls and fights never happened
		player.setHealth(startHealth);
		Inventory inventory = player.getInventory();
		for (int i = 0; i < startInventory.size() && i < inventory.getContainerSize(); i++) inventory.setItem(i, startInventory.get(i));
		inventory.setSelectedSlot(startSlot);
		player.inventoryMenu.setCarried(startCursor);
		player.stopFallFlying();
		player.resetFallDistance();
		player.setDeltaMovement(Vec3.ZERO);
		player.snapTo(startPos.x, startPos.y, startPos.z, startYaw, startPitch);
		player.setOldPosAndRot();
		// Then what the server said meanwhile, in order, so we end up exactly as it has us
		List<Packet<?>> replay;
		synchronized (held) {
			replay = new ArrayList<>(held);
			held.clear();
		}
		for (Packet<?> packet : replay) handle(packet, connection);
		reset();
		ChatUtil.info("Back to where you were; the server never saw a thing");
	}

	@SuppressWarnings("unchecked")
	private static void handle(Packet<?> packet, ClientPacketListener connection) {
		((Packet<ClientPacketListener>) (Packet<?>) packet).handle(connection);
	}

	private void reset() {
		held.clear();
		containers.clear();
		enderChest = null;
		rockets.clear();
		crystals.clear();
		drops.clear();
		localShots.clear();
		arrows.clear();
		arrowPunch.clear();
		arrowDamage.clear();
		redstone.reset();
		clearSchematics();
		startInventory.clear();
		startLevel = null;
		serverBox = null;
		selfId = Integer.MIN_VALUE;
	}

	@Override
	public void onTick() {
		if (larping && (mc.level != startLevel || mc.player == null)) setEnabled(false);
		rockets.removeIf(Entity::isRemoved);
		crystals.removeIf(Entity::isRemoved);
		if (larping && mc.player != null) {
			stopGlidingIfNeeded(mc.player);
			tickDrops(mc.player);
			tickHealth(mc.player);
			tickArrows(mc.player);
			redstone.tick();
			tickSchematics();
			localShots.entrySet().removeIf(shot -> {
				if (shot.getKey().isRemoved()) return true;
				if (shot.setValue(shot.getValue() + 1) < 1200) return false;
				shot.getKey().discard();
				return true;
			});
		}
		if (openPos != null && (mc.player == null || mc.player.containerMenu != openMenu)) closeAnimation();
	}

	@Override
	public void onPacketSend(PacketEvent event) {
		if (!larping) return;
		String command = event.getPacket() instanceof ServerboundChatCommandPacket plain ? plain.command()
			: event.getPacket() instanceof ServerboundChatCommandSignedPacket signed ? signed.command() : null;
		if (command != null && localCommand(command)) {
			event.cancel();
			return;
		}
		if (passesThrough(event.getPacket())) return;
		event.cancel();
		// Modules like Auto Crystal break crystals with a bare attack packet; ours blow up here instead
		if (event.getPacket() instanceof ServerboundInteractPacket interact) {
			int[] target = attackTarget(interact);
			if (target != null) mc.execute(() -> {
				Entity entity = mc.level == null ? null : mc.level.getEntity(target[0]);
				if (entity != null) onAttack(entity);
			});
		}
	}

	/** The entity id an attack packet is for, or null if it isn't an attack. */
	private static int[] attackTarget(ServerboundInteractPacket packet) {
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		try {
			ServerboundInteractPacket.STREAM_CODEC.encode(buf, packet);
			int id = buf.readVarInt();
			return buf.readVarInt() == 1 ? new int[]{id} : null;
		} catch (RuntimeException e) {
			return null;
		} finally {
			buf.release();
		}
	}

	/** Called from ClientPlayerInteractionManagerMixin: hitting one of our crystals blows it up here; true if it was one. */
	public boolean onAttack(Entity target) {
		if (!larping || !(target instanceof EndCrystal crystal) || !crystals.remove(crystal) || crystal.isRemoved()) return false;
		Vec3 center = crystal.position();
		crystal.discard();
		mc.level.playLocalSound(center.x, center.y, center.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 4f, 0.7f + java.util.concurrent.ThreadLocalRandom.current().nextFloat() * 0.2f, false);
		mc.level.addParticle(ParticleTypes.EXPLOSION_EMITTER, center.x, center.y, center.z, 1, 0, 0);
		mc.player.swing(InteractionHand.MAIN_HAND);
		FemboyWare.getModules().get(FakePlayerModule.class).onExplosion(center, 6f);
		DamageSource blast = mc.level.damageSources().explosion(null, null);
		for (ItemEntity drop : drops) {
			if (drop.distanceToSqr(center) < 144 && ServerExplosion.getSeenPercent(center, drop) > 0 && drop.getItem().canBeHurtBy(blast)) drop.discard();
		}
		// Crystals caught in the blast go off too
		for (EndCrystal other : new ArrayList<>(crystals)) {
			if (other.distanceToSqr(center) < 36) onAttack(other);
		}
		return true;
	}

	/** What may still reach the server: keep-alives and other housekeeping, chunk flow, and chat if allowed. */
	private boolean passesThrough(Packet<?> packet) {
		// By class: outside the dev environment class and package names are obfuscated, so names can't be checked
		if (packet instanceof ServerboundKeepAlivePacket || packet instanceof ServerboundPongPacket
			|| packet instanceof ServerboundCustomPayloadPacket || packet instanceof ServerboundClientInformationPacket
			|| packet instanceof ServerboundResourcePackPacket || packet instanceof ServerboundCustomClickActionPacket) return true;
		if (packet instanceof ServerboundChunkBatchReceivedPacket || packet instanceof ServerboundClientTickEndPacket
			|| packet instanceof ServerboundConfigurationAcknowledgedPacket || packet instanceof ServerboundPlayerLoadedPacket
			|| packet instanceof ServerboundChatAckPacket || packet instanceof ServerboundChatSessionUpdatePacket) return true;
		return allowChat.get() && (packet instanceof ServerboundChatPacket || packet instanceof ServerboundChatCommandPacket
			|| packet instanceof ServerboundChatCommandSignedPacket || packet instanceof ServerboundCommandSuggestionPacket);
	}

	@Override
	public void onPacketReceive(PacketEvent event) {
		if (!larping) return;
		Packet<?> packet = event.getPacket();
		if (packet instanceof ClientboundDisconnectPacket) return;
		if (packet instanceof ClientboundRespawnPacket || packet instanceof ClientboundStartConfigurationPacket) {
			// Died, changed world or server: put everything back first, then let it happen
			event.cancel();
			held.add(packet);
			mc.execute(() -> setEnabled(false));
			return;
		}
		if (aboutUs(packet)) {
			event.cancel();
			held.add(packet);
		}
	}

	/** Server news about us that would undo the larp: held until it ends. */
	private boolean aboutUs(Packet<?> packet) {
		int self = selfId;
		return packet instanceof ClientboundPlayerPositionPacket
			|| packet instanceof ClientboundPlayerRotationPacket
			|| packet instanceof ClientboundContainerSetContentPacket
			|| packet instanceof ClientboundContainerSetSlotPacket
			|| packet instanceof ClientboundContainerSetDataPacket
			|| packet instanceof ClientboundContainerClosePacket
			|| packet instanceof ClientboundOpenScreenPacket
			|| packet instanceof ClientboundSetCursorItemPacket
			|| packet instanceof ClientboundSetPlayerInventoryPacket
			|| packet instanceof ClientboundSetHeldSlotPacket
			|| packet instanceof ClientboundSetHealthPacket
			|| packet instanceof ClientboundSetExperiencePacket
			|| packet instanceof ClientboundPlayerAbilitiesPacket
			|| packet instanceof ClientboundSetEntityDataPacket data && data.id() == self
			//? if >=26.1 {
			/*|| packet instanceof ClientboundSetEntityMotionPacket motion && motion.id() == self
			*///?} else {
			|| packet instanceof ClientboundSetEntityMotionPacket motion && motion.getId() == self
			//?}
			|| packet instanceof ClientboundTeleportEntityPacket teleport && teleport.id() == self
			|| packet instanceof ClientboundEntityPositionSyncPacket sync && sync.id() == self
			|| packet instanceof ClientboundHurtAnimationPacket hurt && hurt.id() == self
			|| packet instanceof ClientboundDamageEventPacket damage && damage.entityId() == self;
	}

	/** Called from ClientPlayerInteractionManagerMixin: opens chests and the like locally; true if it did. */
	public boolean onUseItemOn(LocalPlayer player, InteractionHand hand, BlockHitResult hit) {
		if (!larping) return false;
		if (player.getItemInHand(hand).is(Items.END_CRYSTAL)) {
			BlockState base = mc.level.getBlockState(hit.getBlockPos());
			if (base.is(Blocks.OBSIDIAN) || base.is(Blocks.BEDROCK)) return placeCrystal(player, hand, hit.getBlockPos());
		}
		if (hand != InteractionHand.MAIN_HAND) return false;
		// Sneaking with something in hand places it, as usual
		if (player.isSecondaryUseActive() && !player.getMainHandItem().isEmpty()) return false;
		// Levers and buttons work here, since the server isn't listening
		if (redstone.use(hit)) {
			player.swing(hand);
			return true;
		}
		BlockPos pos = hit.getBlockPos();
		BlockState state = mc.level.getBlockState(pos);
		Inventory inventory = player.getInventory();
		if (state.getBlock() instanceof ChestBlock) {
			boolean doubled = state.getValue(ChestBlock.TYPE) != ChestType.SINGLE;
			BlockPos key = pos;
			if (doubled) {
				BlockPos other = pos.relative(ChestBlock.getConnectedDirection(state));
				key = other.asLong() < pos.asLong() ? other : pos;
			}
			SimpleContainer container = containers.computeIfAbsent(key.immutable(), k -> new SimpleContainer(doubled ? 54 : 27));
			ChestMenu menu = doubled ? ChestMenu.sixRows(FAKE_CONTAINER_ID, inventory, container) : ChestMenu.threeRows(FAKE_CONTAINER_ID, inventory, container);
			open(menu, new ContainerScreen(menu, inventory, Component.translatable(doubled ? "container.chestDouble" : "container.chest")), pos);
			return true;
		}
		if (state.getBlock() instanceof BarrelBlock) {
			ChestMenu menu = ChestMenu.threeRows(FAKE_CONTAINER_ID, inventory, containers.computeIfAbsent(pos.immutable(), k -> new SimpleContainer(27)));
			open(menu, new ContainerScreen(menu, inventory, Component.translatable("container.barrel")), pos);
			return true;
		}
		if (state.getBlock() instanceof EnderChestBlock) {
			ChestMenu menu = ChestMenu.sixRows(FAKE_CONTAINER_ID, inventory, enderChest);
			open(menu, new ContainerScreen(menu, inventory, Component.translatable("container.enderchest")), pos);
			return true;
		}
		if (state.getBlock() instanceof net.minecraft.world.level.block.DispenserBlock) {
			// Dispensers and droppers too: fill them here, and powering them shoots it out (see Force Dispenser)
			SimpleContainer container = containers.computeIfAbsent(pos.immutable(), k -> new SimpleContainer(9));
			var menu = new net.minecraft.world.inventory.DispenserMenu(FAKE_CONTAINER_ID, inventory, container);
			boolean dropper = state.getBlock() instanceof net.minecraft.world.level.block.DropperBlock;
			open(menu, new net.minecraft.client.gui.screens.inventory.DispenserScreen(menu, inventory,
				Component.translatable(dropper ? "container.dropper" : "container.dispenser")), pos);
			return true;
		}
		if (state.getBlock() instanceof ShulkerBoxBlock) {
			Container container = containers.computeIfAbsent(pos.immutable(), k -> new SimpleContainer(27));
			ShulkerBoxMenu menu = new ShulkerBoxMenu(FAKE_CONTAINER_ID, inventory, container);
			open(menu, new ShulkerBoxScreen(menu, inventory, Component.translatable("container.shulkerBox")), pos);
			return true;
		}
		return false;
	}

	/** An end crystal on obsidian or bedrock, here only, as long as the space above is clear like vanilla wants. */
	private boolean placeCrystal(LocalPlayer player, InteractionHand hand, BlockPos base) {
		BlockPos above = base.above();
		if (!mc.level.isEmptyBlock(above)) return true;
		AABB space = new AABB(above).expandTowards(0, 1, 0);
		if (!mc.level.getEntities(null, space).isEmpty()) return true;
		EndCrystal crystal = new EndCrystal(mc.level, above.getX() + 0.5, above.getY(), above.getZ() + 0.5);
		crystal.setShowBottom(false);
		crystal.setId(nextRocketId--);
		crystals.add(crystal);
		mc.level.addEntity(crystal);
		ItemStack stack = player.getItemInHand(hand);
		if (!player.getAbilities().instabuild) stack.shrink(1);
		player.swing(hand);
		return true;
	}

	private void open(net.minecraft.world.inventory.AbstractContainerMenu menu, net.minecraft.client.gui.screens.Screen screen, BlockPos pos) {
		closeAnimation();
		mc.player.containerMenu = menu;
		mc.player.swing(InteractionHand.MAIN_HAND);
		openPos = pos.immutable();
		openMenu = menu;
		animate(openPos, true);
		Mc.setScreen(screen);
	}

	private void closeAnimation() {
		if (openPos == null) return;
		if (mc.level != null) animate(openPos, false);
		openPos = null;
		openMenu = null;
	}

	/**
	 * What the server would send: chests, ender chests and shulkers get the block event their lids
	 * animate from (how many are looking inside), barrels their open state, and each its own sound.
	 */
	private void animate(BlockPos pos, boolean open) {
		BlockState state = mc.level.getBlockState(pos);
		double x = pos.getX() + 0.5, y = pos.getY() + 0.5, z = pos.getZ() + 0.5;
		float pitch = java.util.concurrent.ThreadLocalRandom.current().nextFloat() * 0.1f + 0.9f;
		if (state.getBlock() instanceof BarrelBlock) {
			if (state.hasProperty(BarrelBlock.OPEN)) mc.level.setBlock(pos, state.setValue(BarrelBlock.OPEN, open), 3);
			mc.level.playLocalSound(x, y, z, open ? SoundEvents.BARREL_OPEN : SoundEvents.BARREL_CLOSE, SoundSource.BLOCKS, 0.5f, pitch, false);
			return;
		}
		int viewers = open ? 1 : 0;
		mc.level.blockEvent(pos, state.getBlock(), 1, viewers);
		if (state.getBlock() instanceof ChestBlock) {
			if (state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
				BlockPos other = pos.relative(ChestBlock.getConnectedDirection(state));
				mc.level.blockEvent(other, mc.level.getBlockState(other).getBlock(), 1, viewers);
				// The sound comes from between the two halves
				x = (pos.getX() + other.getX()) / 2.0 + 0.5;
				z = (pos.getZ() + other.getZ()) / 2.0 + 0.5;
			}
			mc.level.playLocalSound(x, y, z, open ? SoundEvents.CHEST_OPEN : SoundEvents.CHEST_CLOSE, SoundSource.BLOCKS, 0.5f, pitch, false);
		} else if (state.getBlock() instanceof EnderChestBlock) {
			mc.level.playLocalSound(x, y, z, open ? SoundEvents.ENDER_CHEST_OPEN : SoundEvents.ENDER_CHEST_CLOSE, SoundSource.BLOCKS, 0.5f, pitch, false);
		} else if (state.getBlock() instanceof ShulkerBoxBlock) {
			mc.level.playLocalSound(x, y, z, open ? SoundEvents.SHULKER_BOX_OPEN : SoundEvents.SHULKER_BOX_CLOSE, SoundSource.BLOCKS, 0.5f, pitch, false);
		}
	}

	/** Called from ClientPlayerInteractionManagerMixin: rockets while gliding boost locally, since the server isn't listening; true if it did. */
	public boolean onUseItem(InteractionHand hand) {
		if (!larping || mc.player == null) return false;
		ItemStack stack = mc.player.getItemInHand(hand);
		if (!(stack.getItem() instanceof FireworkRocketItem) || !mc.player.isFallFlying()) return false;
		FireworkRocketEntity rocket = new FireworkRocketEntity(mc.level, stack.copyWithCount(1), mc.player);
		rocket.setId(nextRocketId--);
		rockets.add(rocket);
		mc.level.addEntity(rocket);
		mc.level.playLocalSound(mc.player.getX(), mc.player.getY(), mc.player.getZ(), SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.AMBIENT, 3f, 1f, false);
		if (!mc.player.getAbilities().instabuild) stack.shrink(1);
		mc.player.swing(hand);
		return true;
	}

	/**
	 * The server ends a glide when the elytra comes off, you land, or you're in water; while larping it
	 * isn't listening, so this does it instead.
	 */
	private void stopGlidingIfNeeded(LocalPlayer player) {
		if (!player.isFallFlying()) return;
		boolean wearing = false;
		for (EquipmentSlot slot : EquipmentSlot.values()) {
			if (LivingEntity.canGlideUsing(player.getItemBySlot(slot), slot)) wearing = true;
		}
		if (!wearing || player.onGround() || player.isInWater() || player.isPassenger()) player.stopFallFlying();
	}

	/** Called from ClientPlayerEntityMixin and PlayerEntityMixin: drops an item here, as the server would have; false if not larping. */
	public boolean dropLocally(ItemStack stack, boolean randomly) {
		if (!larping || mc.player == null || mc.level == null || stack.isEmpty()) return false;
		LocalPlayer player = mc.player;
		// Thrown as LivingEntity#createItemStackToDrop does
		ItemEntity item = new ItemEntity(mc.level, player.getX(), player.getEyeY() - 0.3, player.getZ(), stack.copy());
		item.setPickUpDelay(40);
		item.setThrower(player);
		java.util.Random random = new java.util.Random();
		if (randomly) {
			float speed = random.nextFloat() * 0.5f, angle = random.nextFloat() * Mth.TWO_PI;
			item.setDeltaMovement(-Mth.sin(angle) * speed, 0.2, Mth.cos(angle) * speed);
		} else {
			float pitchSin = Mth.sin(player.getXRot() * Mth.DEG_TO_RAD), pitchCos = Mth.cos(player.getXRot() * Mth.DEG_TO_RAD);
			float yawSin = Mth.sin(player.getYRot() * Mth.DEG_TO_RAD), yawCos = Mth.cos(player.getYRot() * Mth.DEG_TO_RAD);
			float angle = random.nextFloat() * Mth.TWO_PI, spread = 0.02f * random.nextFloat();
			item.setDeltaMovement(-yawSin * pitchCos * 0.3 + Mth.cos(angle) * spread,
				-pitchSin * 0.3 + 0.1 + (random.nextFloat() - random.nextFloat()) * 0.1,
				yawCos * pitchCos * 0.3 + Mth.sin(angle) * spread);
		}
		item.setId(nextRocketId--);
		drops.add(item);
		mc.level.addEntity(item);
		return true;
	}

	// ---------------------------------------------------------------- schematics

	/**
	 * One schematic in the larp, yours or someone else's through Client Sync: what it is, what each
	 * chunk had before it (pairs of position in the chunk and block state id) to put back, and for
	 * yours, its file and hash so Client Sync can share it.
	 */
	private static final class Build {
		final dev.femboyware.module.larp.LarpSchematics.Schematic schematic;
		final String name;
		/** Your schematic's file and its SHA-256, for sharing; null for someone else's. */
		final java.nio.file.Path file;
		final String sha256;
		/** Someone else's: which of theirs it is, so it goes when they take it away. */
		final String peerKey;
		final java.util.Map<Long, it.unimi.dsi.fastutil.ints.IntArrayList> originals = new java.util.HashMap<>();

		Build(dev.femboyware.module.larp.LarpSchematics.Schematic schematic, String name, java.nio.file.Path file, String sha256, String peerKey) {
			this.schematic = schematic;
			this.name = name;
			this.file = file;
			this.sha256 = sha256;
			this.peerKey = peerKey;
		}
	}

	/** Every schematic in the larp, oldest first. */
	private final List<Build> builds = new ArrayList<>();
	/**
	 * What's still to build, by chunk. Only chunks you can see (loaded, within your render distance)
	 * are built, nearest first and as many as fit in a slice of each tick, so a big build shows up
	 * around you straight away instead of grinding through parts you can't see. The rest waits and
	 * fills in as you get near. The schematics stay packed; blocks are read out a chunk at a time, so
	 * even a whole server spawn fits in memory.
	 */
	private final java.util.Map<Long, List<Build>> placing = new java.util.LinkedHashMap<>();
	/** How long each tick may spend building, in nanoseconds. */
	private static final long BUILD_NANOS_PER_TICK = 25_000_000L;
	private long placedBlocks;
	/** Whether "built what's in view" has been said for this build of yours. */
	private boolean saidInView = true;

	private void tickSchematics() {
		if (mc.level == null || mc.player == null || builds.isEmpty()) return;
		// A built chunk that unloaded lost our blocks (and what was under them): build it again when it's back
		for (Build build : builds) {
			for (var it = build.originals.keySet().iterator(); it.hasNext(); ) {
				long key = it.next();
				if (mc.level.hasChunk(chunkX(key), chunkZ(key))) continue;
				it.remove();
				placing.computeIfAbsent(key, k -> new ArrayList<>()).add(build);
			}
		}
		if (placing.isEmpty()) return;
		int px = mc.player.getBlockX() >> 4, pz = mc.player.getBlockZ() >> 4, range = mc.options.getEffectiveRenderDistance();
		List<Long> inView = new ArrayList<>();
		boolean yoursWaiting = false;
		for (var entry : placing.entrySet()) {
			long key = entry.getKey();
			int cx = chunkX(key), cz = chunkZ(key);
			if (Math.max(Math.abs(cx - px), Math.abs(cz - pz)) <= range && mc.level.hasChunk(cx, cz)) inView.add(key);
			else if (!yoursWaiting) for (Build build : entry.getValue()) yoursWaiting |= build.peerKey == null;
		}
		if (inView.isEmpty()) {
			if (!saidInView && yoursWaiting) {
				saidInView = true;
				ChatUtil.info("Built what's in view (" + String.format(java.util.Locale.ROOT, "%,d", placedBlocks)
					+ " blocks); the rest of the schematic fills in as you get near it");
			}
			return;
		}
		inView.sort(java.util.Comparator.comparingInt(key -> {
			int dx = chunkX(key) - px, dz = chunkZ(key) - pz;
			return dx * dx + dz * dz;
		}));
		long until = System.nanoTime() + BUILD_NANOS_PER_TICK;
		for (long key : inView) {
			if (System.nanoTime() > until) break;
			for (Build build : placing.remove(key)) {
				var before = build.originals.computeIfAbsent(key, k -> new it.unimi.dsi.fastutil.ints.IntArrayList());
				build.schematic.forEachInChunk(chunkX(key), chunkZ(key), (pos, state) -> {
					BlockState was = mc.level.getBlockState(pos);
					if (was == state) return;
					// Remembered to put back when larp ends; no neighbour updates, the schematic's blocks are already right
					before.add(packLocal(pos));
					before.add(net.minecraft.world.level.block.Block.getId(was));
					mc.level.setBlock(pos, state, 2 | 16);
					if (build.peerKey == null) placedBlocks++;
				});
			}
		}
		boolean yoursLeft = false;
		for (List<Build> waiting : placing.values()) for (Build build : waiting) yoursLeft |= build.peerKey == null;
		if (!yoursLeft && !saidInView) {
			saidInView = true;
			ChatUtil.info("Schematic built: " + String.format(java.util.Locale.ROOT, "%,d", placedBlocks) + " blocks. Break and build on it like anything else while larping");
		}
	}

	/** A position within its chunk as one int: y (from -2048) in the high bits, then x and z in the chunk. */
	private static int packLocal(BlockPos pos) {
		return (pos.getY() + 2048) << 8 | (pos.getX() & 15) << 4 | (pos.getZ() & 15);
	}

	/** Puts back what one schematic replaced, newest first, in chunks that are still here, and stops building it. */
	private void restore(Build build) {
		for (List<Build> waiting : placing.values()) waiting.remove(build);
		placing.values().removeIf(List::isEmpty);
		if (mc.level == null) return;
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		for (var entry : build.originals.entrySet()) {
			int cx = chunkX(entry.getKey()), cz = chunkZ(entry.getKey());
			if (!mc.level.hasChunk(cx, cz)) continue;
			var pairs = entry.getValue();
			for (int i = pairs.size() - 2; i >= 0; i -= 2) {
				int packed = pairs.getInt(i);
				at.set((cx << 4) | (packed >> 4 & 15), (packed >>> 8) - 2048, (cz << 4) | (packed & 15));
				mc.level.setBlock(at, net.minecraft.world.level.block.Block.stateById(pairs.getInt(i + 1)), 2 | 16);
			}
		}
		build.originals.clear();
	}

	/** Puts back everything every schematic replaced, newest first. */
	private void restoreSchematics() {
		for (int i = builds.size() - 1; i >= 0; i--) restore(builds.get(i));
		builds.clear();
		placing.clear();
	}

	private void queue(Build build) {
		builds.add(build);
		for (int[] chunk : build.schematic.chunks()) placing.computeIfAbsent(chunkKey(chunk[0], chunk[1]), k -> new ArrayList<>()).add(build);
		if (build.peerKey == null) saidInView = false;
	}

	private static long chunkKey(int cx, int cz) {
		return (long) cx << 32 | (cz & 0xFFFFFFFFL);
	}

	private static int chunkX(long key) {
		return (int) (key >> 32);
	}

	private static int chunkZ(long key) {
		return (int) key;
	}

	/** Stops building schematics and forgets them (and what they replaced: only for when the world's gone). */
	private void clearSchematics() {
		placing.clear();
		builds.clear();
		placedBlocks = 0;
		saidInView = true;
	}

	/** One of your schematics, for Client Sync to share: its file's hash and name, and where it's placed. */
	public record SharedSchematic(String sha256, String name, java.nio.file.Path file, BlockPos origin, int rotation, int mirror) {
	}

	/** Your schematics in the larp, for Client Sync; empty when you're not larping. */
	public List<SharedSchematic> shareSchematics() {
		if (!larping) return List.of();
		List<SharedSchematic> out = new ArrayList<>();
		for (Build build : builds) {
			if (build.peerKey != null || build.sha256 == null) continue;
			var s = build.schematic;
			out.add(new SharedSchematic(build.sha256, build.name, build.file, s.origin(), s.rotation().ordinal(), s.mirror().ordinal()));
		}
		return out;
	}

	/** The keys of the other people's schematics in the larp. */
	public java.util.Set<String> peerSchematics() {
		java.util.Set<String> keys = new java.util.HashSet<>();
		for (Build build : builds) if (build.peerKey != null) keys.add(build.peerKey);
		return keys;
	}

	/** From Client Sync: someone else's schematic, to build like yours (only while you're larping, where blocks are safe to add). */
	public void addPeerSchematic(String key, String name, dev.femboyware.module.larp.LarpSchematics.Schematic schematic) {
		if (!larping || schematic.isEmpty() || peerSchematics().contains(key)) return;
		queue(new Build(schematic, name, null, null, key));
	}

	/** From Client Sync: someone took a schematic away (or left); what it replaced comes back. */
	public void removePeerSchematic(String key) {
		for (java.util.Iterator<Build> it = builds.iterator(); it.hasNext(); ) {
			Build build = it.next();
			if (!key.equals(build.peerKey)) continue;
			restore(build);
			it.remove();
		}
	}

	/**
	 * Reads a schematic off the main thread (a big one is tens of megabytes to unpack), then, back on
	 * the main thread, places it and starts building. {@code centreOn} puts its middle there instead
	 * of its origin.
	 */
	private void load(java.nio.file.Path file, String name, BlockPos origin, net.minecraft.world.level.block.Rotation rotation,
					  net.minecraft.world.level.block.Mirror mirror, BlockPos centreOn) {
		var blocks = dev.femboyware.module.larp.LarpSchematics.blockLookup();
		var level = mc.level;
		Thread reader = new Thread(() -> {
			try {
				var schematic = dev.femboyware.module.larp.LarpSchematics.read(file, origin, rotation, mirror, blocks);
				// Its hash, so Client Sync can share it and others can ask for it
				String sha256 = null;
				try {
					sha256 = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(file)));
				} catch (java.security.NoSuchAlgorithmException ignored) {
				}
				String hash = sha256;
				mc.execute(() -> {
					if (!larping || mc.level != level) return;
					if (schematic.isEmpty()) {
						ChatUtil.info(name + " has nothing in it");
						return;
					}
					if (centreOn != null) schematic.centreOn(centreOn);
					queue(new Build(schematic, name, file, hash, null));
					ChatUtil.info("Building " + name + (centreOn != null ? " centred on you" : "") + " (" + (schematic.maxX - schematic.minX + 1) + "x"
						+ (schematic.maxY - schematic.minY + 1) + "x" + (schematic.maxZ - schematic.minZ + 1) + "), nearest first");
				});
			} catch (IOException | RuntimeException | OutOfMemoryError e) {
				mc.execute(() -> ChatUtil.info("Couldn't read " + name + ": " + e.getMessage()));
			}
		}, "femboyware schematic reader");
		reader.setDaemon(true);
		reader.start();
	}

	/** Handles {@code .larp schem [list|name|clear]}. */
	private void schematicCommand(String[] args) {
		String what = args.length > 2 ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)) : "";
		if (what.equalsIgnoreCase("list")) {
			var files = dev.femboyware.module.larp.LarpSchematics.files();
			if (files.isEmpty()) ChatUtil.info("No schematics in " + dev.femboyware.module.larp.LarpSchematics.folder());
			for (var f : files) ChatUtil.info(f.getFileName().toString().replace(".litematic", ""));
			return;
		}
		if (what.equalsIgnoreCase("stop") || what.equalsIgnoreCase("clear")) {
			// Yours stop; other people's carry on
			for (List<Build> waiting : placing.values()) waiting.removeIf(build -> build.peerKey == null);
			placing.values().removeIf(List::isEmpty);
			ChatUtil.info("Stopped building the schematic (what's built stays until larp ends; " + CommandManager.PREFIX + "larp reset clears it)");
			return;
		}
		if (what.isEmpty()) {
			// Whatever Litematica has placed, where it has it
			var placements = dev.femboyware.module.larp.LarpSchematics.litematicaPlacements();
			if (placements.isEmpty()) {
				ChatUtil.info("Nothing placed in Litematica. Use " + CommandManager.PREFIX + "larp schem <name> to build one centred on you, or "
					+ CommandManager.PREFIX + "larp schem list");
				return;
			}
			ChatUtil.info("Loading " + placements.size() + " Litematica placement" + (placements.size() == 1 ? "" : "s") + "...");
			for (var p : placements) load(p.file(), p.name(), p.origin(), p.rotation(), p.mirror(), null);
			return;
		}
		var file = dev.femboyware.module.larp.LarpSchematics.find(what);
		if (file == null) {
			ChatUtil.info("No schematic called " + what + " in " + dev.femboyware.module.larp.LarpSchematics.folder() + " (" + CommandManager.PREFIX + "larp schem list)");
			return;
		}
		// Centred on you, turned the way you face, like placing it yourself
		float yaw = mc.player.getYRot();
		var rotation = switch (Math.floorMod(Math.round(yaw / 90f), 4)) {
			case 1 -> net.minecraft.world.level.block.Rotation.CLOCKWISE_90;
			case 2 -> net.minecraft.world.level.block.Rotation.CLOCKWISE_180;
			case 3 -> net.minecraft.world.level.block.Rotation.COUNTERCLOCKWISE_90;
			default -> net.minecraft.world.level.block.Rotation.NONE;
		};
		String name = file.getFileName().toString().replace(".litematic", "");
		ChatUtil.info("Loading " + name + "...");
		BlockPos feet = mc.player.blockPosition();
		load(file, name, feet, rotation, net.minecraft.world.level.block.Mirror.NONE, feet);
	}

	/** A block changed (placed, broken, or from the server): the redstone looks again. */
	@Override
	public void onBlockUpdate(BlockPos pos, BlockState state) {
		if (larping) redstone.changed(pos, state);
	}

	/** One item out of a random filled slot of a dispenser you've filled while larping, as vanilla picks; empty if it has none. */
	public ItemStack takeForDispenser(BlockPos pos) {
		SimpleContainer container = containers.get(pos);
		if (container == null) return ItemStack.EMPTY;
		List<Integer> filled = new ArrayList<>();
		for (int i = 0; i < container.getContainerSize(); i++) if (!container.getItem(i).isEmpty()) filled.add(i);
		if (filled.isEmpty()) return ItemStack.EMPTY;
		int slot = filled.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(filled.size()));
		ItemStack one = container.getItem(slot).split(1);
		container.setChanged();
		return one;
	}

	/**
	 * Called from ClientPlayerInteractionManagerMixin once a block's broken: while larping in survival
	 * it drops what it would, close enough. The loot tables only exist on the server, so this follows
	 * the usual rules by hand: nothing without the right tool, the block itself with silk touch, ores'
	 * raw materials, stone as cobblestone, and so on.
	 */
	public void onLarpBroken(BlockPos pos, BlockState state) {
		if (!larping || mc.player == null || mc.level == null || mc.gameMode.getPlayerMode() != GameType.SURVIVAL) return;
		if (state.isAir() || state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) return;
		ItemStack tool = mc.player.getMainHandItem();
		if (state.requiresCorrectToolForDrops() && !tool.isCorrectToolForDrops(state)) return;
		boolean silk = false;
		try {
			var silkTouch = mc.level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
				.get(net.minecraft.world.item.enchantment.Enchantments.SILK_TOUCH);
			silk = silkTouch.isPresent() && net.minecraft.world.item.enchantment.EnchantmentHelper.getItemEnchantmentLevel(silkTouch.get(), tool) > 0;
		} catch (RuntimeException ignored) {
		}
		ItemStack drop = silk ? new ItemStack(state.getBlock().asItem()) : larpDrop(state);
		if (drop.isEmpty()) return;
		java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
		Vec3 at = Vec3.atCenterOf(pos);
		spawnLocalItem(drop, at, new Vec3((r.nextDouble() - 0.5) * 0.1, 0.2, (r.nextDouble() - 0.5) * 0.1));
	}

	/** What a block drops without silk touch, for the ones that don't just drop themselves. */
	private static ItemStack larpDrop(BlockState state) {
		java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
		var b = state.getBlock();
		if (state.is(net.minecraft.tags.BlockTags.LEAVES)) return r.nextInt(20) == 0 ? new ItemStack(Items.STICK) : ItemStack.EMPTY;
		if (b == Blocks.STONE) return new ItemStack(Items.COBBLESTONE);
		if (b == Blocks.DEEPSLATE) return new ItemStack(Items.COBBLED_DEEPSLATE);
		if (b == Blocks.GRASS_BLOCK || b == Blocks.MYCELIUM || b == Blocks.PODZOL || b == Blocks.DIRT_PATH || b == Blocks.FARMLAND) return new ItemStack(Items.DIRT);
		if (b == Blocks.COAL_ORE || b == Blocks.DEEPSLATE_COAL_ORE) return new ItemStack(Items.COAL);
		if (b == Blocks.IRON_ORE || b == Blocks.DEEPSLATE_IRON_ORE) return new ItemStack(Items.RAW_IRON);
		if (b == Blocks.GOLD_ORE || b == Blocks.DEEPSLATE_GOLD_ORE) return new ItemStack(Items.RAW_GOLD);
		if (b == Blocks.COPPER_ORE || b == Blocks.DEEPSLATE_COPPER_ORE) return new ItemStack(Items.RAW_COPPER, 2 + r.nextInt(4));
		if (b == Blocks.DIAMOND_ORE || b == Blocks.DEEPSLATE_DIAMOND_ORE) return new ItemStack(Items.DIAMOND);
		if (b == Blocks.EMERALD_ORE || b == Blocks.DEEPSLATE_EMERALD_ORE) return new ItemStack(Items.EMERALD);
		if (b == Blocks.LAPIS_ORE || b == Blocks.DEEPSLATE_LAPIS_ORE) return new ItemStack(Items.LAPIS_LAZULI, 4 + r.nextInt(6));
		if (b == Blocks.REDSTONE_ORE || b == Blocks.DEEPSLATE_REDSTONE_ORE) return new ItemStack(Items.REDSTONE, 4 + r.nextInt(2));
		if (b == Blocks.NETHER_QUARTZ_ORE) return new ItemStack(Items.QUARTZ);
		if (b == Blocks.NETHER_GOLD_ORE) return new ItemStack(Items.GOLD_NUGGET, 2 + r.nextInt(5));
		if (b == Blocks.GILDED_BLACKSTONE) return r.nextInt(10) == 0 ? new ItemStack(Items.GOLD_NUGGET, 2 + r.nextInt(4)) : new ItemStack(Items.GILDED_BLACKSTONE);
		if (b == Blocks.GLOWSTONE) return new ItemStack(Items.GLOWSTONE_DUST, 2 + r.nextInt(3));
		if (b == Blocks.CLAY) return new ItemStack(Items.CLAY_BALL, 4);
		if (b == Blocks.GRAVEL) return new ItemStack(r.nextInt(10) == 0 ? Items.FLINT : Items.GRAVEL);
		if (b == Blocks.BOOKSHELF) return new ItemStack(Items.BOOK, 3);
		if (b == Blocks.MELON) return new ItemStack(Items.MELON_SLICE, 3 + r.nextInt(5));
		if (b == Blocks.SNOW_BLOCK) return new ItemStack(Items.SNOWBALL, 4);
		if (b == Blocks.SHORT_GRASS || b == Blocks.TALL_GRASS || b == Blocks.FERN || b == Blocks.LARGE_FERN) {
			return r.nextInt(8) == 0 ? new ItemStack(Items.WHEAT_SEEDS) : ItemStack.EMPTY;
		}
		if (state.is(net.minecraft.tags.BlockTags.IMPERMEABLE) || b == Blocks.ICE || b == Blocks.GLASS_PANE || b instanceof net.minecraft.world.level.block.StainedGlassPaneBlock) return ItemStack.EMPTY;
		var item = b.asItem();
		return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
	}

	/** An item that only exists here, landing and picked up like ones you drop while larping. */
	public void spawnLocalItem(ItemStack stack, Vec3 at, Vec3 velocity) {
		if (!larping || mc.level == null || stack.isEmpty()) return;
		ItemEntity item = new ItemEntity(mc.level, at.x, at.y, at.z, stack);
		item.setDeltaMovement(velocity);
		item.setPickUpDelay(10);
		item.setId(nextRocketId--);
		drops.add(item);
		mc.level.addEntity(item);
	}

	/** A firework that only exists here, flying off the way it's pointed. */
	public void spawnLocalRocket(ItemStack stack, Vec3 at, Vec3 direction) {
		if (!larping || mc.level == null) return;
		FireworkRocketEntity rocket = new FireworkRocketEntity(mc.level, at.x, at.y, at.z, stack);
		rocket.shoot(direction.x, direction.y, direction.z, 0.5f, 1f);
		rocket.setId(nextRocketId--);
		rockets.add(rocket);
		mc.level.addEntity(rocket);
		mc.level.playLocalSound(at.x, at.y, at.z, SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.AMBIENT, 3f, 1f, false);
	}

	/** An arrow that only exists here, shot the way it's pointed. */
	public void spawnLocalArrow(ItemStack stack, Vec3 at, Vec3 direction, float power) {
		if (!larping || mc.level == null) return;
		var arrow = new net.minecraft.world.entity.projectile.arrow.Arrow(mc.level, at.x, at.y, at.z, stack, null);
		arrow.shoot(direction.x, direction.y + 0.1, direction.z, power, 6f);
		arrow.setId(nextRocketId--);
		localShots.put(arrow, 0);
		mc.level.addEntity(arrow);
	}

	/** Picking up, burning, cactus, the void and despawning for items dropped while larping. */
	private void tickDrops(LocalPlayer player) {
		for (java.util.Iterator<ItemEntity> iterator = drops.iterator(); iterator.hasNext(); ) {
			ItemEntity drop = iterator.next();
			if (drop.isRemoved()) {
				iterator.remove();
				continue;
			}
			ItemStack stack = drop.getItem();
			if (drop.getAge() >= DESPAWN_TICKS || drop.getY() < mc.level.getMinY() - 64) {
				drop.discard();
				continue;
			}
			DamageSource burn = touching(drop);
			if (burn != null && stack.canBeHurtBy(burn)) {
				mc.level.playLocalSound(drop.getX(), drop.getY(), drop.getZ(), SoundEvents.GENERIC_BURN, SoundSource.NEUTRAL, 0.4f,
					2f + java.util.concurrent.ThreadLocalRandom.current().nextFloat() * 0.4f, false);
				for (int i = 0; i < 4; i++) mc.level.addParticle(ParticleTypes.LARGE_SMOKE, drop.getX(), drop.getY() + 0.2, drop.getZ(), 0, 0.05, 0);
				drop.discard();
				continue;
			}
			if (drop.hasPickUpDelay() || !player.isAlive() || !drop.getBoundingBox().intersects(player.getBoundingBox().inflate(1, 0.5, 1))) continue;
			int before = stack.getCount();
			ItemStack left = stack.copy();
			player.getInventory().add(left);
			if (left.getCount() == before) continue;
			mc.level.playLocalSound(player.getX(), player.getY(), player.getZ(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.2f,
				(java.util.concurrent.ThreadLocalRandom.current().nextFloat() - java.util.concurrent.ThreadLocalRandom.current().nextFloat()) * 1.4f + 2f, false);
			if (left.isEmpty()) drop.discard();
			else drop.setItem(left);
		}
	}

	/** Fire, lava or cactus the item is in, as the damage it would take; null if none. */
	private DamageSource touching(ItemEntity drop) {
		if (drop.isInLava()) return mc.level.damageSources().lava();
		AABB box = drop.getBoundingBox().inflate(0.001);
		for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ), BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
			BlockState state = mc.level.getBlockState(pos);
			if (state.is(net.minecraft.world.level.block.Blocks.FIRE) || state.is(net.minecraft.world.level.block.Blocks.SOUL_FIRE)) return mc.level.damageSources().inFire();
			if (state.is(net.minecraft.world.level.block.Blocks.CACTUS)) return mc.level.damageSources().cactus();
		}
		return null;
	}

	/** Called from ClientPlayerInteractionManagerMixin: middle-clicking a block picks it here, as the server would; false if not larping. */
	public boolean pickBlock(BlockPos pos, boolean withData) {
		if (!larping || mc.player == null) return false;
		pick(mc.level.getBlockState(pos).getCloneItemStack(mc.level, pos, withData && mc.player.hasInfiniteMaterials()));
		return true;
	}

	/** Called from ClientPlayerInteractionManagerMixin: middle-clicking an entity picks its item here; false if not larping. */
	public boolean pickEntity(Entity entity) {
		if (!larping || mc.player == null) return false;
		ItemStack stack = entity.getPickResult();
		if (stack != null) pick(stack);
		return true;
	}

	/** The server's pick: switch to it if you have it, otherwise in creative put one in your hand. */
	private void pick(ItemStack stack) {
		if (stack.isEmpty()) return;
		Inventory inventory = mc.player.getInventory();
		int slot = inventory.findSlotMatchingItem(stack);
		if (slot != -1) {
			if (Inventory.isHotbarSlot(slot)) inventory.setSelectedSlot(slot);
			else inventory.pickSlot(slot);
		} else if (mc.player.hasInfiniteMaterials()) {
			inventory.addAndPickItem(stack);
		}
	}

	/** Called from FireworkRocketEntityMixin: one of our local rockets, which has to remove itself when spent. */
	public boolean isOurRocket(FireworkRocketEntity rocket) {
		return rockets.contains(rocket);
	}

	public void command(String[] args) {
		if (args.length > 1 && args[1].equalsIgnoreCase("worth")) {
			worthCommand(args);
			return;
		}
		if (args.length > 1 && args[1].equalsIgnoreCase("reset") && !larping) {
			resetLarp();
			return;
		}
		if (!larping) {
			ChatUtil.info("Turn on Server Larp first (" + CommandManager.PREFIX + "larp on)");
			if (args.length < 2 || !args[1].equalsIgnoreCase("on")) return;
		}
		String action = args.length > 1 ? args[1].toLowerCase(java.util.Locale.ROOT) : "";
		switch (action) {
			case "on" -> {
				if (!isEnabled()) setEnabled(true);
			}
			case "off" -> setEnabled(false);
			case "give", "i" -> give(args);
			case "schem", "schematic", "schematics", "litematica", "litematic" -> schematicCommand(args);
			case "reset" -> resetLarp();
			case "tp", "teleport" -> teleportCommand(java.util.Arrays.copyOfRange(args, 2, args.length));
			case "paste" -> {
				// Long commands don't fit in chat (it stops at 256 characters), so this runs the one on the clipboard
				String command = mc.keyboardHandler.getClipboard().replaceAll("[\r\n\t]+", " ").trim();
				if (command.startsWith("/")) command = command.substring(1);
				if (command.isEmpty()) ChatUtil.info("Your clipboard is empty: copy a command like /give @s diamond_sword first");
				else if (!localCommand(command)) ChatUtil.info("Only /give, /enchant, /gamemode, /clear and /tp run while larping");
			}
			case "clear" -> {
				mc.player.getInventory().clearContent();
				ChatUtil.info("Emptied your inventory (client-side)");
			}
			case "creative", "c" -> {
				mc.gameMode.setLocalMode(GameType.CREATIVE);
				ChatUtil.info("Creative mode, client-side: pick anything from the creative menu");
			}
			case "survival", "s" -> {
				mc.gameMode.setLocalMode(GameType.SURVIVAL);
				ChatUtil.info("Survival mode, client-side");
			}
			default -> ChatUtil.info("Usage: " + CommandManager.PREFIX + "larp <on|off|give <item> [count]|schem [name|list|stop]|tp <x y z|player> [x y z|player]|paste|creative|survival|clear|reset|worth>");
		}
	}

	// ---------------------------------------------------------------- bows

	/** Arrows you've shot while larping, and the arrow you get back for picking each one up (empty if you don't). */
	private final Map<Entity, ItemStack> arrows = new IdentityHashMap<>();
	/** Each arrow's Punch level. */
	private final Map<Entity, Integer> arrowPunch = new IdentityHashMap<>();
	/** Each arrow's base damage: 2, more with Power. */
	private final Map<Entity, Double> arrowDamage = new IdentityHashMap<>();

	private static int enchantLevel(ItemStack stack, net.minecraft.resources.ResourceKey<net.minecraft.world.item.enchantment.Enchantment> which) {
		for (var entry : stack.getEnchantments().entrySet()) if (entry.getKey().is(which)) return entry.getIntValue();
		return 0;
	}

	/**
	 * From the interaction mixin, as you let go of a drawn bow: shoots the arrow here, as BowItem
	 * would on the server (charge, a crit at full draw, Power, Flame, Punch and Infinity, tipped and
	 * spectral arrows), uses one up and wears the bow.
	 */
	public void onReleaseUsing(net.minecraft.world.entity.player.Player player) {
		if (!larping || player != mc.player || mc.level == null) return;
		ItemStack bow = player.getUseItem();
		if (!(bow.getItem() instanceof net.minecraft.world.item.BowItem)) return;
		float power = net.minecraft.world.item.BowItem.getPowerForTime(player.getTicksUsingItem());
		if (power < 0.1f) return;
		boolean creative = player.getAbilities().instabuild;
		ItemStack ammo = player.getProjectile(bow);
		if (ammo.isEmpty() && !creative) return;
		if (ammo.isEmpty()) ammo = new ItemStack(Items.ARROW);
		boolean infinite = creative || ammo.is(Items.ARROW) && enchantLevel(bow, net.minecraft.world.item.enchantment.Enchantments.INFINITY) > 0;
		ItemStack one = ammo.copyWithCount(1);
		var kind = ammo.getItem() instanceof net.minecraft.world.item.ArrowItem arrowItem ? arrowItem : (net.minecraft.world.item.ArrowItem) Items.ARROW;
		var arrow = kind.createArrow(mc.level, one, player, bow);
		arrow.shootFromRotation(player, player.getXRot(), player.getYRot(), 0, power * 3, 1);
		if (power >= 1) arrow.setCritArrow(true);
		int powerLevel = enchantLevel(bow, net.minecraft.world.item.enchantment.Enchantments.POWER);
		double baseDamage = 2.0 + (powerLevel > 0 ? powerLevel * 0.5 + 0.5 : 0);
		arrow.setBaseDamage(baseDamage);
		if (enchantLevel(bow, net.minecraft.world.item.enchantment.Enchantments.FLAME) > 0) arrow.igniteForSeconds(100);
		arrow.setId(nextRocketId--);
		localShots.put(arrow, 0);
		arrows.put(arrow, infinite ? ItemStack.EMPTY : one);
		arrowPunch.put(arrow, enchantLevel(bow, net.minecraft.world.item.enchantment.Enchantments.PUNCH));
		arrowDamage.put(arrow, baseDamage);
		mc.level.addEntity(arrow);
		mc.level.playLocalSound(player.getX(), player.getY(), player.getZ(), SoundEvents.ARROW_SHOOT, SoundSource.PLAYERS, 1,
			1 / (java.util.concurrent.ThreadLocalRandom.current().nextFloat() * 0.4f + 1.2f) + power * 0.5f, false);
		if (!infinite) ammo.shrink(1);
		if (!creative && bow.isDamageableItem()) {
			bow.setDamageValue(bow.getDamageValue() + 1);
			if (bow.getDamageValue() >= bow.getMaxDamage()) {
				bow.shrink(1);
				mc.level.playLocalSound(player.getX(), player.getY(), player.getZ(), SoundEvents.ITEM_BREAK.value(), SoundSource.PLAYERS, 0.8f, 0.8f, false);
			}
		}
	}

	/**
	 * Your arrows in flight hit fake players in their way (the arrow's own hits only happen on a
	 * server); stuck ones can be walked over and picked up.
	 */
	private void tickArrows(LocalPlayer player) {
		var fakes = dev.femboyware.FemboyWare.getModules().get(dev.femboyware.module.client.FakePlayerModule.class);
		for (var it = arrows.entrySet().iterator(); it.hasNext(); ) {
			var entry = it.next();
			var arrow = (net.minecraft.world.entity.projectile.arrow.AbstractArrow) entry.getKey();
			if (arrow.isRemoved()) {
				arrowPunch.remove(arrow);
				arrowDamage.remove(arrow);
				it.remove();
				continue;
			}
			Vec3 velocity = arrow.getDeltaMovement();
			if (velocity.lengthSqr() > 0.01) {
				Vec3 from = arrow.position(), to = from.add(velocity);
				for (var target : fakes.fakeEntities()) {
					if (target.isDeadOrDying() || target.getBoundingBox().inflate(0.3).clip(from, to).isEmpty()) continue;
					// As AbstractArrow#onHitEntity: speed times base damage, and a random extra for a crit
					int damage = net.minecraft.util.Mth.ceil(net.minecraft.util.Mth.clamp(velocity.length() * arrowDamage.getOrDefault(arrow, 2.0), 0, Integer.MAX_VALUE));
					if (arrow.isCritArrow()) damage = (int) Math.min((long) java.util.concurrent.ThreadLocalRandom.current().nextInt(damage / 2 + 2) + damage, Integer.MAX_VALUE);
					fakes.hitByArrow(target, damage, player, arrow, velocity, arrowPunch.getOrDefault(arrow, 0));
					mc.level.playLocalSound(player.getX(), player.getY(), player.getZ(), SoundEvents.ARROW_HIT_PLAYER, SoundSource.PLAYERS, 0.18f, 0.45f, false);
					arrow.discard();
					break;
				}
				continue;
			}
			// Stuck in something: walk over it to pick it back up
			ItemStack back = entry.getValue();
			if (back.isEmpty() || !player.getBoundingBox().inflate(1, 0.5, 1).intersects(arrow.getBoundingBox())) continue;
			if (!player.getInventory().add(back.copy())) continue;
			mc.level.playLocalSound(player.getX(), player.getY(), player.getZ(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.2f,
				(java.util.concurrent.ThreadLocalRandom.current().nextFloat() - java.util.concurrent.ThreadLocalRandom.current().nextFloat()) * 1.4f + 2, false);
			arrow.discard();
		}
	}

	// ---------------------------------------------------------------- health

	/** Whether you can be hurt in the larp right now (not in creative or spectator). */
	private boolean hurtable(LocalPlayer player) {
		return larping && !player.getAbilities().invulnerable && !player.isSpectator() && !player.isCreative();
	}

	/**
	 * Fall damage and healing while larping. The server never sees you fall, so it never hurts you:
	 * this does it on your screen, as vanilla would. A fall is measured from the highest point since
	 * you last stood on something; water, ladders, gliding, flying and slow falling all break it.
	 */
	private void tickHealth(LocalPlayer player) {
		if (hurtCooldown > 0) hurtCooldown--;
		// Fell into the void: back to where the larp started, whatever mode you're in
		if (player.getY() < mc.level.getMinY() - 64) {
			player.snapTo(startPos.x, startPos.y, startPos.z, startYaw, startPitch);
			player.setOldPosAndRot();
			player.setDeltaMovement(Vec3.ZERO);
			player.resetFallDistance();
			fallTop = Double.NaN;
			ChatUtil.info("You fell out of the world: back where the larp started");
			return;
		}
		if (!hurtable(player)) {
			fallTop = Double.NaN;
			return;
		}
		boolean breaksFall = player.isInWater() || player.isInLava() || player.onClimbable() || player.isFallFlying() || player.getAbilities().flying
			|| player.hasEffect(net.minecraft.world.effect.MobEffects.SLOW_FALLING) || player.hasEffect(net.minecraft.world.effect.MobEffects.LEVITATION)
			|| player.isPassenger() || player.isNoGravity();
		if (breaksFall) fallTop = Double.NaN;
		else if (!player.onGround()) fallTop = Double.isNaN(fallTop) ? player.getY() : Math.max(fallTop, player.getY());
		else {
			if (!Double.isNaN(fallTop)) land(player, fallTop - player.getY());
			fallTop = Double.NaN;
		}
		// Healing slowly with a full enough hunger bar, as natural regeneration does
		if (player.getHealth() < player.getMaxHealth() && player.getFoodData().getFoodLevel() >= 18 && ++regenTicks >= 80) {
			regenTicks = 0;
			player.setHealth(Math.min(player.getMaxHealth(), player.getHealth() + 1));
		}
	}

	/** Protection levels on your armour, and Feather Falling counted three times over for falls. */
	private static int protectionOf(LocalPlayer player, boolean falling) {
		int protection = 0;
		for (net.minecraft.world.entity.EquipmentSlot slot : new net.minecraft.world.entity.EquipmentSlot[]{net.minecraft.world.entity.EquipmentSlot.HEAD,
			net.minecraft.world.entity.EquipmentSlot.CHEST, net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET}) {
			for (var entry : player.getItemBySlot(slot).getEnchantments().entrySet()) {
				if (entry.getKey().is(net.minecraft.world.item.enchantment.Enchantments.PROTECTION)) protection += entry.getIntValue();
				else if (falling && entry.getKey().is(net.minecraft.world.item.enchantment.Enchantments.FEATHER_FALLING)) protection += 3 * entry.getIntValue();
			}
		}
		return Math.min(20, protection);
	}

	/** You landed after falling {@code fell} blocks: the damage vanilla would do. */
	private void land(LocalPlayer player, double fell) {
		double safe = player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.SAFE_FALL_DISTANCE);
		double damage = Math.ceil((fell - safe) * player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.FALL_DAMAGE_MULTIPLIER));
		if (damage <= 0) return;
		// What you land on: slime bounces you (unless you're sneaking), hay and honey soften it, beds a bit
		BlockState on = mc.level.getBlockState(BlockPos.containing(player.getX(), player.getY() - 0.2, player.getZ()));
		if (on.is(net.minecraft.world.level.block.Blocks.SLIME_BLOCK) && !player.isSuppressingBounce()) return;
		if (on.is(net.minecraft.world.level.block.Blocks.POWDER_SNOW) || on.is(net.minecraft.world.level.block.Blocks.COBWEB)) return;
		if (on.is(net.minecraft.world.level.block.Blocks.HAY_BLOCK) || on.is(net.minecraft.world.level.block.Blocks.HONEY_BLOCK)) damage *= 0.2;
		else if (on.is(net.minecraft.tags.BlockTags.BEDS)) damage *= 0.5;
		// Feather Falling and Protection take some off; armour doesn't
		float amount = net.minecraft.world.damagesource.CombatRules.getDamageAfterMagicAbsorb((float) damage, protectionOf(player, true));
		hurt(player, amount, mc.level.damageSources().fall(), null);
	}

	/**
	 * For fake players fighting back: one of them hit you, while you're larping. Your armour and
	 * Protection count, and you get knocked back away from them.
	 */
	public void hitByFake(Entity attacker, float damage) {
		LocalPlayer player = mc.player;
		if (player == null || !hurtable(player) || hurtCooldown > 0) return;
		var source = attacker instanceof net.minecraft.world.entity.player.Player p ? mc.level.damageSources().playerAttack(p) : mc.level.damageSources().generic();
		damage = net.minecraft.world.damagesource.CombatRules.getDamageAfterAbsorb(player, damage, source, player.getArmorValue(),
			(float) player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR_TOUGHNESS));
		damage = net.minecraft.world.damagesource.CombatRules.getDamageAfterMagicAbsorb(damage, protectionOf(player, false));
		hurt(player, damage, source, attacker);
		if (!larping || player.getHealth() <= 0 || player.distanceToSqr(attacker) > 64) return;
		// Knocked back away from them, as LivingEntity#knockback does
		double awayX = attacker.getX() - player.getX(), awayZ = attacker.getZ() - player.getZ(), length = Math.hypot(awayX, awayZ);
		if (length < 1e-5) return;
		Vec3 was = player.getDeltaMovement();
		player.setDeltaMovement(was.x / 2 - awayX / length * 0.4, player.onGround() ? Math.min(0.4, was.y / 2 + 0.4) : was.y, was.z / 2 - awayZ / length * 0.4);
	}

	/** Takes {@code amount} off you in the larp: absorption first, then health. A totem saves you; otherwise dying puts you back at the start, healed. */
	private void hurt(LocalPlayer player, float amount, net.minecraft.world.damagesource.DamageSource source, Entity attacker) {
		if (amount <= 0 || !hurtable(player)) return;
		var resistance = player.getEffect(net.minecraft.world.effect.MobEffects.RESISTANCE);
		if (resistance != null) {
			amount *= Math.max(0, 1 - (resistance.getAmplifier() + 1) * 0.2f);
			if (amount <= 0) return;
		}
		hurtCooldown = 10;
		float absorbed = Math.min(player.getAbsorptionAmount(), amount);
		player.setAbsorptionAmount(player.getAbsorptionAmount() - absorbed);
		float health = player.getHealth() - (amount - absorbed);
		// The red flash, the camera shake and the hurt sound
		player.handleDamageEvent(source);
		if (health > 0) {
			player.setHealth(health);
			return;
		}
		for (net.minecraft.world.InteractionHand hand : net.minecraft.world.InteractionHand.values()) {
			ItemStack held = player.getItemInHand(hand);
			if (!held.is(net.minecraft.world.item.Items.TOTEM_OF_UNDYING)) continue;
			held.shrink(1);
			player.setHealth(1);
			player.removeAllEffects();
			player.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.REGENERATION, 900, 1));
			player.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.ABSORPTION, 100, 1));
			player.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE, 800, 0));
			mc.particleEngine.createTrackingEmitter(player, net.minecraft.core.particles.ParticleTypes.TOTEM_OF_UNDYING, 30);
			mc.level.playLocalSound(player.getX(), player.getY(), player.getZ(), SoundEvents.TOTEM_USE, player.getSoundSource(), 1, 1, false);
			//? if >=26.3 {
			/*mc.player.displayItemActivation(new ItemStack(net.minecraft.world.item.Items.TOTEM_OF_UNDYING));
			*///?} else {
			mc.gameRenderer.displayItemActivation(new ItemStack(net.minecraft.world.item.Items.TOTEM_OF_UNDYING));
			//?}
			return;
		}
		mc.level.playLocalSound(player.getX(), player.getY(), player.getZ(), SoundEvents.PLAYER_DEATH, player.getSoundSource(), 1, 1, false);
		String how = attacker != null ? "were slain by " + attacker.getName().getString() : source.is(net.minecraft.tags.DamageTypeTags.IS_FALL) ? "fell from a high place" : "died";
		ChatUtil.info("You " + how + " (in the larp): back where you started, healed");
		player.setHealth(player.getMaxHealth());
		player.snapTo(startPos.x, startPos.y, startPos.z, startYaw, startPitch);
		player.setOldPosAndRot();
		player.setDeltaMovement(Vec3.ZERO);
		player.resetFallDistance();
		fallTop = Double.NaN;
	}

	/** Forgets the saved larp; if larping now, starts a fresh one from your real spot and inventory. */
	private void resetLarp() {
		saved = null;
		if (larping) {
			discardOnDisable = true;
			setEnabled(false);
			setEnabled(true);
		}
		ChatUtil.info("Larp reset: next time starts fresh from your real spot and inventory");
	}

	/**
	 * Vanilla commands that only make sense for you, done here while larping instead of on a server that
	 * isn't listening. Returns whether it took the command.
	 */
	private boolean localCommand(String command) {
		String[] words = command.trim().split("\\s+");
		if (words.length == 0) return false;
		String name = words[0].toLowerCase(java.util.Locale.ROOT);
		if (name.contains(":")) name = name.substring(name.indexOf(':') + 1);
		switch (name) {
			case "give", "i", "item", "enchant", "gamemode", "gm", "gmc", "gms", "gma", "gmsp", "clear", "ci", "tp", "teleport" -> {
			}
			default -> {
				return false;
			}
		}
		String which = name;
		mc.execute(() -> runLocal(which, words));
		return true;
	}

	/** Skips a target like @s, @p or your own name, which is the only target there is here. */
	private int afterTarget(String[] words, int at) {
		if (at < words.length && (words[at].startsWith("@") || mc.player != null && words[at].equalsIgnoreCase(mc.player.getGameProfile().name()))) return at + 1;
		return at;
	}

	private void runLocal(String name, String[] words) {
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null) return;
		Component you = player.getDisplayName();
		switch (name) {
			case "give", "i", "item" -> {
				int from = name.equals("give") ? afterTarget(words, 1) : 1;
				if (from >= words.length) {
					reply(Component.literal("Usage: /give <item> [count]"));
					return;
				}
				String[] args = new String[words.length - from + 2];
				args[0] = "larp";
				args[1] = "give";
				System.arraycopy(words, from, args, 2, words.length - from);
				give(args, true);
			}
			case "enchant" -> {
				int from = afterTarget(words, 1);
				if (from >= words.length) {
					reply(Component.literal("Usage: /enchant <enchantment> [level]"));
					return;
				}
				String id = words[from].toLowerCase(java.util.Locale.ROOT);
				int level = 1;
				if (from + 1 < words.length) {
					try {
						level = Math.max(1, Integer.parseInt(words[from + 1]));
					} catch (NumberFormatException ignored) {
					}
				}
				Identifier key = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
				var holder = key == null ? java.util.Optional.<net.minecraft.core.Holder.Reference<net.minecraft.world.item.enchantment.Enchantment>>empty()
					: mc.level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
						.get(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ENCHANTMENT, key));
				if (holder.isEmpty()) {
					reply(Component.literal("Unknown enchantment: " + id));
					return;
				}
				ItemStack held = player.getMainHandItem();
				if (held.isEmpty()) {
					reply(Component.translatable("commands.enchant.failed.itemless", you));
					return;
				}
				held.enchant(holder.get(), level);
				reply(Component.translatable("commands.enchant.success.single", net.minecraft.world.item.enchantment.Enchantment.getFullname(holder.get(), level), you));
			}
			case "gamemode", "gm", "gmc", "gms", "gma", "gmsp" -> {
				String wanted = switch (name) {
					case "gmc" -> "creative";
					case "gms" -> "survival";
					case "gma" -> "adventure";
					case "gmsp" -> "spectator";
					default -> words.length > 1 ? words[1].toLowerCase(java.util.Locale.ROOT) : "";
				};
				GameType mode = switch (wanted) {
					case "creative", "c", "1" -> GameType.CREATIVE;
					case "survival", "s", "0" -> GameType.SURVIVAL;
					case "adventure", "a", "2" -> GameType.ADVENTURE;
					case "spectator", "sp", "3" -> GameType.SPECTATOR;
					default -> null;
				};
				if (mode == null) {
					reply(Component.literal("Usage: /gamemode <survival|creative|adventure|spectator>"));
					return;
				}
				mc.gameMode.setLocalMode(mode);
				reply(Component.translatable("commands.gamemode.success.self", mode.getLongDisplayName()));
			}
			case "clear", "ci" -> {
				Inventory inventory = player.getInventory();
				int removed = 0;
				for (int i = 0; i < inventory.getContainerSize(); i++) removed += inventory.getItem(i).getCount();
				inventory.clearContent();
				reply(removed == 0 ? Component.translatable("clear.failed.single", you) : Component.translatable("commands.clear.success.single", removed, you));
			}
			case "tp", "teleport" -> teleportCommand(java.util.Arrays.copyOfRange(words, 1, words.length));
			default -> {
			}
		}
	}

	private static final String TP_USAGE = "Usage: /tp <x y z | player>, or /tp <player | @a> <x y z | player> to move other femboyware users larping here";

	/**
	 * /tp while larping: you to a spot or to someone, or (through Client Sync) other femboyware users
	 * who are larping here, to a spot or to someone. @a is everyone larping, you included. Someone
	 * else's spot while they larp is where they really are, not where the server has them.
	 */
	private void teleportCommand(String[] args) {
		LocalPlayer player = mc.player;
		if (args.length == 0) {
			reply(Component.literal(TP_USAGE));
			return;
		}
		// Who goes: you, unless the first word names someone and there's a destination after it
		boolean coordsOnly = args.length == 3 && isCoordinate(args[0]);
		String who = coordsOnly || args.length == 1 ? "@s" : args[0];
		String[] where = coordsOnly || args.length == 1 ? args : java.util.Arrays.copyOfRange(args, 1, args.length);
		Vec3 to;
		String toName;
		if (where.length == 3) {
			try {
				to = new Vec3(coordinate(where[0], player.getX()), coordinate(where[1], player.getY()), coordinate(where[2], player.getZ()));
			} catch (NumberFormatException e) {
				reply(Component.literal(TP_USAGE));
				return;
			}
			toName = String.format(java.util.Locale.ROOT, "%.1f %.1f %.1f", to.x, to.y, to.z);
		} else if (where.length == 1) {
			to = positionOf(where[0]);
			if (to == null) {
				reply(Component.literal("Can't find " + where[0] + " (they need to be in render distance, or larping with Client Sync)"));
				return;
			}
			toName = where[0];
		} else {
			reply(Component.literal(TP_USAGE));
			return;
		}
		var sync = dev.femboyware.FemboyWare.getModules().get(dev.femboyware.module.client.ClientSyncModule.class);
		boolean self = who.equalsIgnoreCase("@s") || who.equalsIgnoreCase("@p") || who.equalsIgnoreCase(player.getGameProfile().name());
		if (self || who.equalsIgnoreCase("@a")) teleportTo(to);
		if (self) {
			reply(Component.literal("Teleported you to " + toName));
			return;
		}
		if (who.equalsIgnoreCase("@a")) {
			int sent = sync.teleportEveryone(to);
			reply(Component.literal("Teleported you" + (sent > 0 ? " and " + sent + " other" + (sent == 1 ? "" : "s") + " larping here" : "") + " to " + toName));
			return;
		}
		String problem = sync.teleport(who, to);
		reply(Component.literal(problem != null ? problem : "Teleported " + who + " to " + toName + " (in their larp)"));
	}

	private static boolean isCoordinate(String text) {
		try {
			coordinate(text, 0);
			return true;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	/** Where someone is: you, someone larping with Client Sync (where they really are), or a player in sight. */
	private Vec3 positionOf(String name) {
		if (name.equalsIgnoreCase("@s") || name.equalsIgnoreCase("@p") || name.equalsIgnoreCase(mc.player.getGameProfile().name())) return mc.player.position();
		Vec3 larping = dev.femboyware.FemboyWare.getModules().get(dev.femboyware.module.client.ClientSyncModule.class).larpPositionOf(name);
		if (larping != null) return larping;
		for (var other : mc.level.players()) if (other.getGameProfile().name().equalsIgnoreCase(name)) return other.position();
		return null;
	}

	private void teleportTo(Vec3 to) {
		LocalPlayer player = mc.player;
		player.snapTo(to.x, to.y, to.z, player.getYRot(), player.getXRot());
		player.setOldPosAndRot();
		player.setDeltaMovement(Vec3.ZERO);
		player.resetFallDistance();
	}

	/** From Client Sync: someone larping here teleported you, in your larp. */
	public void teleportedBy(String who, Vec3 to) {
		if (!larping || mc.player == null || !allowTeleports.get()) return;
		if (!Double.isFinite(to.x) || !Double.isFinite(to.y) || !Double.isFinite(to.z) || Math.abs(to.x) > 3.0E7 || Math.abs(to.z) > 3.0E7 || Math.abs(to.y) > 4096) return;
		teleportTo(to);
		ChatUtil.info(who + " teleported you to " + String.format(java.util.Locale.ROOT, "%.1f %.1f %.1f", to.x, to.y, to.z)
			+ " (turn off Allow Teleports in Server Larp to stop that)");
	}

	/** A coordinate, with ~ meaning relative to where you are. */
	private static double coordinate(String text, double current) {
		if (text.startsWith("~")) return current + (text.length() > 1 ? Double.parseDouble(text.substring(1)) : 0);
		return Double.parseDouble(text);
	}

	/** Command feedback the way the server shows it. */
	private static void reply(Component text) {
		Mc.addChat(text);
	}

	private void give(String[] args) {
		give(args, false);
	}

	private void give(String[] args, boolean vanillaReply) {
		if (args.length < 3) {
			ChatUtil.info("Usage: " + CommandManager.PREFIX + "larp give <item> [count], like " + CommandManager.PREFIX + "larp give diamond_sword[enchantments={sharpness:5}]");
			return;
		}
		int count = 1;
		int end = args.length;
		try {
			count = Math.max(1, Integer.parseInt(args[args.length - 1]));
			end--;
		} catch (NumberFormatException ignored) {
		}
		String text = String.join(" ", java.util.Arrays.copyOfRange(args, 2, end));
		try {
			CommandBuildContext context = CommandBuildContext.simple(mc.level.registryAccess(), mc.level.enabledFeatures());
			ItemInput input = ItemArgument.item(context).parse(new StringReader(text));
			int max = Math.max(1, create(input, 1).getMaxStackSize());
			int left = count;
			while (left > 0) {
				int size = Math.min(left, max);
				if (!mc.player.getInventory().add(create(input, size))) break;
				left -= size;
			}
			if (vanillaReply) reply(Component.translatable("commands.give.success.single", count - left, create(input, 1).getDisplayName(), mc.player.getDisplayName()));
			else ChatUtil.info("Gave yourself " + (count - left) + " " + text + " (client-side)");
		} catch (CommandSyntaxException e) {
			if (vanillaReply) reply(Component.literal(e.getMessage()).withStyle(ChatFormatting.RED));
			else ChatUtil.info("Not an item: " + e.getMessage());
		}
	}

	private static ItemStack create(ItemInput input, int count) throws CommandSyntaxException {
		//? if >=26.1 {
		/*return input.createItemStack(count);
		*///?} else {
		return input.createItemStack(count, true);
		//?}
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		if (larping && render.get() && serverBox != null) RenderUtil.drawBox(event, serverBox, color.get(), color.withAlpha(255), 1.5f);
	}

	private void saveToDisk() {
		try {
			Files.createDirectories(SAVE_FILE.getParent());
			if (saved == null) {
				Files.deleteIfExists(SAVE_FILE);
				return;
			}
			JsonObject root = new JsonObject();
			root.addProperty("world", saved.world());
			root.addProperty("x", saved.pos().x);
			root.addProperty("y", saved.pos().y);
			root.addProperty("z", saved.pos().z);
			root.addProperty("yaw", saved.yaw());
			root.addProperty("pitch", saved.pitch());
			root.addProperty("mode", switch (saved.mode()) {
				case CREATIVE -> "creative";
				case ADVENTURE -> "adventure";
				case SPECTATOR -> "spectator";
				default -> "survival";
			});
			root.addProperty("slot", saved.slot());
			JsonArray inv = new JsonArray();
			for (Tag tag : saved.inventory()) inv.add(NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, tag));
			root.add("inventory", inv);
			JsonArray blocks = new JsonArray();
			for (Map.Entry<BlockPos, BlockState> entry : saved.blocks().entrySet()) {
				JsonObject b = new JsonObject();
				b.addProperty("pos", entry.getKey().asLong());
				b.add("state", serializeBlock(entry.getValue()));
				blocks.add(b);
			}
			root.add("blocks", blocks);
			JsonArray containers = new JsonArray();
			for (Map.Entry<BlockPos, List<Tag>> entry : saved.containers().entrySet()) {
				JsonObject c = new JsonObject();
				c.addProperty("pos", entry.getKey().asLong());
				JsonArray items = new JsonArray();
				for (Tag tag : entry.getValue()) items.add(NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, tag));
				c.add("items", items);
				containers.add(c);
			}
			root.add("containers", containers);
			JsonArray ender = new JsonArray();
			for (Tag tag : saved.ender()) ender.add(NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, tag));
			root.add("ender", ender);
			JsonArray drops = new JsonArray();
			for (Drop drop : saved.drops()) {
				JsonObject d = new JsonObject();
				d.addProperty("x", drop.pos().x);
				d.addProperty("y", drop.pos().y);
				d.addProperty("z", drop.pos().z);
				d.add("stack", NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, drop.stack()));
				drops.add(d);
			}
			root.add("drops", drops);
			JsonArray crystals = new JsonArray();
			for (Vec3 pos : saved.crystals()) {
				JsonObject c = new JsonObject();
				c.addProperty("x", pos.x);
				c.addProperty("y", pos.y);
				c.addProperty("z", pos.z);
				crystals.add(c);
			}
			root.add("crystals", crystals);
			Files.writeString(SAVE_FILE, GSON.toJson(root));
		} catch (IOException | RuntimeException e) {
			FemboyWare.LOGGER.error("Failed to save larp state", e);
		}
	}

	private void loadFromDisk() {
		if (!Files.exists(SAVE_FILE)) return;
		try {
			JsonObject root = JsonParser.parseString(Files.readString(SAVE_FILE)).getAsJsonObject();
			String world = root.get("world").getAsString();
			Vec3 pos = new Vec3(root.get("x").getAsDouble(), root.get("y").getAsDouble(), root.get("z").getAsDouble());
			float yaw = root.get("yaw").getAsFloat();
			float pitch = root.get("pitch").getAsFloat();
			GameType mode = switch (root.get("mode").getAsString()) {
				case "creative" -> GameType.CREATIVE;
				case "adventure" -> GameType.ADVENTURE;
				case "spectator" -> GameType.SPECTATOR;
				default -> GameType.SURVIVAL;
			};
			int slot = root.get("slot").getAsInt();
			List<Tag> inventory = new ArrayList<>();
			for (JsonElement el : root.getAsJsonArray("inventory"))
				inventory.add(JsonOps.INSTANCE.convertTo(NbtOps.INSTANCE, el));
			Map<BlockPos, BlockState> blocks = new HashMap<>();
			for (JsonElement el : root.getAsJsonArray("blocks")) {
				JsonObject b = el.getAsJsonObject();
				blocks.put(BlockPos.of(b.get("pos").getAsLong()), deserializeBlock(b.getAsJsonObject("state")));
			}
			Map<BlockPos, List<Tag>> containers = new HashMap<>();
			for (JsonElement el : root.getAsJsonArray("containers")) {
				JsonObject c = el.getAsJsonObject();
				List<Tag> items = new ArrayList<>();
				for (JsonElement item : c.getAsJsonArray("items"))
					items.add(JsonOps.INSTANCE.convertTo(NbtOps.INSTANCE, item));
				containers.put(BlockPos.of(c.get("pos").getAsLong()), items);
			}
			List<Tag> ender = new ArrayList<>();
			for (JsonElement el : root.getAsJsonArray("ender"))
				ender.add(JsonOps.INSTANCE.convertTo(NbtOps.INSTANCE, el));
			List<Drop> drops = new ArrayList<>();
			for (JsonElement el : root.getAsJsonArray("drops")) {
				JsonObject d = el.getAsJsonObject();
				drops.add(new Drop(new Vec3(d.get("x").getAsDouble(), d.get("y").getAsDouble(), d.get("z").getAsDouble()),
					JsonOps.INSTANCE.convertTo(NbtOps.INSTANCE, d.get("stack"))));
			}
			List<Vec3> crystals = new ArrayList<>();
			for (JsonElement el : root.getAsJsonArray("crystals")) {
				JsonObject c = el.getAsJsonObject();
				crystals.add(new Vec3(c.get("x").getAsDouble(), c.get("y").getAsDouble(), c.get("z").getAsDouble()));
			}
			saved = new Saved(world, pos, yaw, pitch, mode, inventory, slot, blocks, containers, ender, drops, crystals);
		} catch (IOException | RuntimeException e) {
			FemboyWare.LOGGER.error("Failed to load larp state", e);
		}
	}

	private static JsonObject serializeBlock(BlockState state) {
		JsonObject obj = new JsonObject();
		obj.addProperty("n", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
		JsonObject props = new JsonObject();
		for (net.minecraft.world.level.block.state.properties.Property<?> prop : state.getBlock().getStateDefinition().getProperties())
			props.addProperty(prop.getName(), getPropertyValueName(state, prop));
		if (!props.entrySet().isEmpty()) obj.add("p", props);
		return obj;
	}

	@SuppressWarnings("unchecked")
	private static <T extends Comparable<T>> String getPropertyValueName(BlockState state, net.minecraft.world.level.block.state.properties.Property<T> prop) {
		return prop.getName(state.getValue(prop));
	}

	private static BlockState deserializeBlock(JsonObject obj) {
		Identifier id = Identifier.tryParse(obj.get("n").getAsString());
		if (id == null) return Blocks.AIR.defaultBlockState();
		Block block = BuiltInRegistries.BLOCK.getOptional(id).orElse(Blocks.AIR);
		BlockState state = block.defaultBlockState();
		if (obj.has("p")) {
			JsonObject props = obj.getAsJsonObject("p");
			for (net.minecraft.world.level.block.state.properties.Property<?> prop : block.getStateDefinition().getProperties()) {
				if (props.has(prop.getName())) state = applyPropertyValue(state, prop, props.get(prop.getName()).getAsString());
			}
		}
		return state;
	}

	@SuppressWarnings("unchecked")
	private static <T extends Comparable<T>> BlockState applyPropertyValue(BlockState state, net.minecraft.world.level.block.state.properties.Property<T> prop, String value) {
		return prop.getValue(value).map(v -> state.setValue(prop, v)).orElse(state);
	}
}
