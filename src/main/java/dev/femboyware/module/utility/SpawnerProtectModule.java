package dev.femboyware.module.utility;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.setting.StringSetting;
import dev.femboyware.util.ChatUtil;
import dev.femboyware.util.Mc;
import dev.femboyware.util.Webhook;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * DonutSMP base defence: sit AFK by your spawners with this on. When blocks start getting broken
 * near you (someone digging in), it mines every spawner around you, picks them up and puts them in
 * an ender chest (buying one from /shop if you have none), then leaves the server. Players coming
 * into range only send a webhook message. Off within 100 blocks of spawn. Ported from the Anubis
 * client's Spawner Protect (GPL-3.0, by 4ldenz, https://github.com/4ldenz/Anubis-client).
 */
public class SpawnerProtectModule extends Module {
	private static final int SHOP_END_CATEGORY_SLOT = 11, SHOP_ENDER_CHEST_SLOT = 9, SHOP_CONFIRM_SLOT = 25;

	private enum State {
		WAITING, WORKING, GOING_TO_CHEST, OPENING_CHEST, DEPOSITING, FINAL_EXIT, BUYING_ECHEST, PLACING_ECHEST
	}

	private final NumberSetting stacks = add(new NumberSetting("Stacks To Collect", "Stop mining and stash them once you hold this many stacks of spawners", 35, 1, 35, 1));
	private final NumberSetting range = add(new NumberSetting("Trigger Range", "How far away (sideways) a broken block or a player counts", 64, 16, 128, 1));
	private final NumberSetting rotationSpeed = add(new NumberSetting("Rotation Speed", "Degrees per tick it turns to look at spawners", 15, 1, 30, 0.5));
	private final BoolSetting blockUpdates = add(new BoolSetting("Detect Block Updates", "Start when blocks get broken near you", true));
	private final NumberSetting threshold = add(new NumberSetting("Blocks Broken", "How many broken blocks start it", 5, 1, 20, 1));
	private final NumberSetting window = add(new NumberSetting("Within Seconds", "...within this many seconds", 60, 1, 600, 1));
	private final NumberSetting breakRange = add(new NumberSetting("Break Range", "How close it walks to a spawner before mining", 5.5, 1, 8, 0.1));
	private final StringSetting whitelist = add(new StringSetting("Whitelist", "Your teammates: blocks they break and their arrivals are ignored, comma separated", ""));
	private final StringSetting webhookUrl = add(new StringSetting("Webhook URL", "Your Discord webhook link for alerts (blank for none)", ""));

	private State state = State.WAITING;
	private BlockPos targetBlock, targetChest;
	private int chestTick, breakCooldown, waitTicks, shopSequence, shopTick, miningTicks, broken;
	private long lastBreak;
	private boolean triggered;
	private final Set<UUID> nearby = new HashSet<>();

	public SpawnerProtectModule() {
		super("Spawner Protect", "Mines your spawners into an ender chest and leaves when someone digs into your base", Category.MISC);
	}

	@Override
	protected void onEnable() {
		reset();
	}

	@Override
	protected void onDisable() {
		stopMovement();
		sneak(false);
	}

	@Override
	public String getInfo() {
		return state == State.WAITING ? null : state.name().toLowerCase(Locale.ROOT).replace('_', ' ');
	}

	private void reset() {
		state = State.WAITING;
		triggered = false;
		nearby.clear();
		waitTicks = 0;
		broken = 0;
		lastBreak = 0;
		shopSequence = 0;
		shopTick = 0;
		if (mc.player != null) {
			stopMovement();
			sneak(false);
		}
		clearTargets();
	}

	private void clearTargets() {
		targetChest = null;
		targetBlock = null;
		chestTick = 0;
		breakCooldown = 0;
		miningTicks = 0;
	}

	@Override
	public void onBlockUpdate(BlockPos pos, BlockState newState) {
		if (triggered || mc.player == null || mc.level == null || nearSpawn() || !blockUpdates.get()) return;
		if (pos.getY() <= -64 || !newState.isAir()) return;
		Vec3 center = Vec3.atCenterOf(pos);
		for (Player player : mc.level.players()) if (player.getEyePosition().distanceTo(center) < 8 && whitelisted(player.getGameProfile().name())) return;
		// Your own digging doesn't count
		if (mc.player.getEyePosition().distanceTo(center) < 6) return;
		double dx = mc.player.getX() - pos.getX(), dz = mc.player.getZ() - pos.getZ();
		double sideways = Math.sqrt(dx * dx + dz * dz);
		if (sideways > range.get()) return;
		long now = System.currentTimeMillis();
		if (now - lastBreak > (long) (window.get() * 1000)) broken = 0;
		broken++;
		lastBreak = now;
		if (broken < threshold.get()) return;
		triggered = true;
		state = State.WORKING;
		ChatUtil.chat("Spawner Protect: blocks are being broken " + (int) sideways + " blocks away (y " + pos.getY() + "). Collecting your spawners!");
		webhook("🚨 Protection activated", "The block-break threshold was reached. Spawner collection has started.", 0xED4245,
			new String[]{"Trigger", "Block breaking", "true"}, new String[]{"Blocks broken", broken + " / " + threshold.get().intValue(), "true"},
			new String[]{"Distance", (int) sideways + " blocks", "true"}, new String[]{"Block", Webhook.coords(pos), "false"});
	}

	@Override
	public void onTick() {
		if (mc.player == null || mc.level == null || mc.gameMode == null || Mc.screen() instanceof net.minecraft.client.gui.screens.DeathScreen) {
			if (state != State.WAITING || triggered) reset();
			return;
		}
		if (nearSpawn()) {
			if (triggered) reset();
			return;
		}
		watchPlayers();
		if (state == State.WAITING) return;
		if (state != State.OPENING_CHEST && state != State.DEPOSITING && state != State.BUYING_ECHEST) sneak(true);
		look();
		switch (state) {
			case WORKING -> working();
			case GOING_TO_CHEST -> goingToChest();
			case OPENING_CHEST -> openingChest();
			case DEPOSITING -> depositing();
			case FINAL_EXIT -> finalExit();
			case BUYING_ECHEST -> buyingEChest();
			case PLACING_ECHEST -> placingEChest();
			default -> {
			}
		}
	}

	private void watchPlayers() {
		Set<UUID> present = new HashSet<>();
		for (Player player : mc.level.players()) {
			if (player == mc.player || player.isSpectator() || whitelisted(player.getGameProfile().name()) || mc.player.distanceTo(player) > range.get()) continue;
			if (dev.femboyware.FemboyWare.getModules().get(dev.femboyware.module.client.FakePlayerModule.class).isFake(player.getId())) continue;
			present.add(player.getUUID());
			if (!nearby.contains(player.getUUID()))
				webhook("👤 A player entered your range", "A new player is nearby. This only sends a message.", 0x5865F2,
					new String[]{"Player", "`" + player.getGameProfile().name() + "`", "true"},
					new String[]{"Distance", String.format(Locale.ROOT, "%.1f blocks", mc.player.distanceTo(player)), "true"},
					new String[]{"Protection", triggered ? "In progress" : "Standby", "true"}, new String[]{"Their location", Webhook.coords(player.blockPosition()), "false"});
		}
		nearby.clear();
		nearby.addAll(present);
	}

	private void look() {
		Vec3 target = null;
		if (state == State.WORKING) {
			ItemEntity dropped = droppedSpawner();
			if (dropped != null) target = dropped.position();
			else {
				retarget();
				if (targetBlock != null) target = Vec3.atCenterOf(targetBlock);
			}
		} else if ((state == State.GOING_TO_CHEST || state == State.OPENING_CHEST) && targetChest != null) target = Vec3.atCenterOf(targetChest);
		if (target == null) return;
		Vec3 eyes = mc.player.getEyePosition();
		double dx = target.x - eyes.x, dy = target.y - eyes.y, dz = target.z - eyes.z;
		float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
		float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
		float step = rotationSpeed.get().floatValue();
		mc.player.setYRot(mc.player.getYRot() + Mth.clamp(Mth.wrapDegrees(yaw - mc.player.getYRot()), -step, step));
		mc.player.setXRot(mc.player.getXRot() + Mth.clamp(Mth.wrapDegrees(pitch - mc.player.getXRot()), -step, step));
	}

	private void retarget() {
		if (targetBlock == null || !mc.level.getBlockState(targetBlock).is(Blocks.SPAWNER) || mc.player.blockPosition().distSqr(targetBlock) > 256) {
			targetBlock = randomBlock(Blocks.SPAWNER, 16);
			miningTicks = 0;
		}
	}

	private void working() {
		if (mc.player.containerMenu != mc.player.inventoryMenu) mc.player.closeContainer();
		if (droppedSpawner() != null) {
			waitTicks = 0;
			stopBreaking();
			mc.options.keyUp.setDown(true);
			return;
		}
		int spawners = count(Blocks.SPAWNER);
		if (spawners >= stacks.get() * 64 || spawners > 0 && mc.player.getInventory().getFreeSlot() == -1) {
			goToChest();
			return;
		}
		retarget();
		if (targetBlock == null) {
			stopMovement();
			if (waitTicks < 40) waitTicks++;
			else if (count(Blocks.SPAWNER) > 0) goToChest();
			else {
				state = State.FINAL_EXIT;
				waitTicks = 0;
			}
			return;
		}
		waitTicks = 0;
		if (mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(targetBlock)) > breakRange.get()) {
			stopBreaking();
			mc.options.keyUp.setDown(true);
			return;
		}
		mc.options.keyUp.setDown(false);
		// Stuck on one spawner for 3 seconds: poke it to resync
		if (++miningTicks > 60) {
			mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(targetBlock), Direction.UP, targetBlock, false));
			mc.player.swing(InteractionHand.MAIN_HAND);
			miningTicks = 0;
		}
		if (breakCooldown <= 0) {
			mc.gameMode.startDestroyBlock(targetBlock, Direction.UP);
			mc.player.swing(InteractionHand.MAIN_HAND);
			mc.options.keyAttack.setDown(true);
			breakCooldown = 6;
		} else breakCooldown--;
	}

	private void goToChest() {
		stopMovement();
		targetChest = nearestBlock(Blocks.ENDER_CHEST, 4);
		if (targetChest != null) state = State.GOING_TO_CHEST;
		else if (count(Blocks.ENDER_CHEST) > 0) {
			state = State.PLACING_ECHEST;
			shopTick = 0;
		} else {
			state = State.BUYING_ECHEST;
			shopSequence = 0;
			shopTick = 0;
		}
	}

	private void goingToChest() {
		if (targetChest == null) targetChest = nearestBlock(Blocks.ENDER_CHEST, 4);
		if (targetChest == null) {
			state = State.WORKING;
			return;
		}
		mc.options.keyUp.setDown(true);
		if (mc.player.blockPosition().closerThan(targetChest, 4)) {
			stopMovement();
			state = State.OPENING_CHEST;
		}
	}

	private void openingChest() {
		sneak(false);
		if (chestTick > 200) {
			ChatUtil.chat("Spawner Protect: couldn't open the ender chest, keeping the spawners in your inventory.");
			state = State.FINAL_EXIT;
			waitTicks = 0;
			return;
		}
		if (chestTick % 12 == 0 && targetChest != null)
			mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(targetChest), Direction.UP, targetChest, false));
		chestTick++;
		if (mc.player.containerMenu instanceof ChestMenu) {
			chestTick = 0;
			state = State.DEPOSITING;
			waitTicks = 0;
		}
	}

	/** Buys one ender chest from DonutSMP's /shop (End category). This spends a little of your own money. */
	private void buyingEChest() {
		stopMovement();
		sneak(false);
		shopTick++;
		if (shopTick > 200) {
			ChatUtil.chat("Spawner Protect: the shop timed out, keeping the spawners in your inventory.");
			state = State.FINAL_EXIT;
			waitTicks = 0;
		} else if (shopSequence > 0 && Mc.screen() == null && shopTick > 60) {
			shopSequence = 0;
			shopTick = 0;
		} else if (shopTick >= 30) {
			switch (shopSequence) {
				case 0 -> {
					ChatUtil.chat("Spawner Protect: no ender chest, buying one from /shop");
					mc.getConnection().sendCommand("shop");
					shopSequence = 1;
					shopTick = 0;
				}
				case 1 -> shopStep("SHOP", SHOP_END_CATEGORY_SLOT);
				case 2 -> shopStep("END", SHOP_ENDER_CHEST_SLOT);
				case 3 -> shopStep("ENDER CHEST", SHOP_CONFIRM_SLOT);
				case 4 -> {
					if (count(Blocks.ENDER_CHEST) > 0) {
						mc.player.closeContainer();
						state = State.PLACING_ECHEST;
						shopTick = 0;
					} else if (shopTick > 100) {
						mc.player.closeContainer();
						shopSequence = 0;
						shopTick = 0;
					}
				}
				default -> {
				}
			}
		}
	}

	private void shopStep(String title, int slot) {
		if (Mc.screen() == null || !Mc.screen().getTitle().getString().toUpperCase(Locale.ROOT).contains(title)) return;
		mc.gameMode.handleInventoryMouseClick(mc.player.containerMenu.containerId, slot, 0, ClickType.PICKUP, mc.player);
		shopSequence++;
		shopTick = 0;
	}

	private void placingEChest() {
		if (++shopTick < 10) return;
		int slot = -1;
		for (int i = 0; i < 9; i++) if (mc.player.getInventory().getItem(i).is(Items.ENDER_CHEST)) {
			slot = i;
			break;
		}
		if (slot == -1) {
			for (int i = 9; i < 36; i++) if (mc.player.getInventory().getItem(i).is(Items.ENDER_CHEST)) {
				mc.player.closeContainer();
				mc.gameMode.handleInventoryMouseClick(mc.player.inventoryMenu.containerId, i, 0, ClickType.QUICK_MOVE, mc.player);
				shopTick = 0;
				return;
			}
			state = State.BUYING_ECHEST;
			shopSequence = 0;
			return;
		}
		dev.femboyware.util.InventoryUtil.select(slot);
		BlockPos feet = mc.player.blockPosition(), place = null;
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			BlockPos candidate = feet.relative(direction);
			if (mc.level.getBlockState(candidate).canBeReplaced() && !mc.level.getBlockState(candidate.below()).isAir()) {
				place = candidate;
				break;
			}
		}
		if (place == null) {
			ChatUtil.chat("Spawner Protect: nowhere to put the ender chest!");
			state = State.FINAL_EXIT;
			waitTicks = 0;
			return;
		}
		BlockPos support = place.below();
		mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(support).add(0, 0.5, 0), Direction.UP, support, false));
		mc.player.swing(InteractionHand.MAIN_HAND);
		targetChest = place;
		state = State.GOING_TO_CHEST;
	}

	private void depositing() {
		if (waitTicks < 15) {
			waitTicks++;
			return;
		}
		if (!(mc.player.containerMenu instanceof ChestMenu chest)) {
			state = State.OPENING_CHEST;
			chestTick = 0;
			return;
		}
		int chestSlots = chest.slots.size() - 36;
		boolean space = false;
		for (int i = 0; i < chestSlots; i++) {
			ItemStack stack = chest.getSlot(i).getItem();
			if (stack.isEmpty() || stack.is(Items.SPAWNER) && stack.getCount() < stack.getMaxStackSize()) {
				space = true;
				break;
			}
		}
		for (int i = 0; i < 36; i++) {
			int slotId = chestSlots + i;
			if (!chest.getSlot(slotId).getItem().is(Items.SPAWNER)) continue;
			if (!space) {
				state = State.FINAL_EXIT;
				waitTicks = 0;
				return;
			}
			mc.gameMode.handleInventoryMouseClick(chest.containerId, slotId, 0, ClickType.QUICK_MOVE, mc.player);
			return;
		}
		mc.player.closeContainer();
		if (nearestBlock(Blocks.SPAWNER, 16) != null) state = State.WORKING;
		else {
			state = State.FINAL_EXIT;
			waitTicks = 0;
		}
	}

	private void finalExit() {
		if (waitTicks == 0) {
			int left = count(Blocks.SPAWNER);
			webhook(left > 0 ? "⚠️ Spawners still in your inventory" : "✅ Done", left > 0 ? "Some spawners are still in your inventory. Leaving in two seconds." : "All collected. Leaving in two seconds.",
				left > 0 ? 0xFEE75C : 0x57F287, new String[]{"In inventory", left + " spawners", "true"}, new String[]{"Next", "Disconnect", "true"});
			ChatUtil.chat("Spawner Protect: done, leaving in 2 seconds so the server saves your items...");
		}
		stopMovement();
		sneak(false);
		if (++waitTicks <= 40) return;
		if (mc.getConnection() != null && mc.getConnection().getConnection().isConnected()) {
			AutoReconnectModule.holdOff();
			mc.getConnection().getConnection().disconnect(Component.literal("[Spawner Protect] Done, left safely."));
		}
		reset();
		setEnabled(false);
	}

	private void webhook(String title, String description, int color, String[]... fields) {
		if (webhookUrl.get().isBlank() || mc.player == null || mc.level == null) return;
		Webhook.Embed embed = new Webhook.Embed(title, description, color);
		for (String[] f : fields) embed.field(f[0], f[1], Boolean.parseBoolean(f[2]));
		embed.field("Account", "`" + mc.player.getGameProfile().name() + "`", true);
		embed.field("Dimension", "`" + mc.level.dimension().identifier() + "`", true);
		embed.field("Your location", Webhook.coords(mc.player.blockPosition()), false);
		Webhook.send(webhookUrl.get(), embed, null);
	}

	private void sneak(boolean down) {
		mc.options.keyShift.setDown(down);
	}

	private boolean nearSpawn() {
		return mc.player != null && Math.abs(mc.player.getX()) < 100 && Math.abs(mc.player.getZ()) < 100;
	}

	private boolean whitelisted(String name) {
		for (String entry : whitelist.get().split("[,;\\s]+")) if (!entry.isEmpty() && entry.equalsIgnoreCase(name)) return true;
		return false;
	}

	private BlockPos nearestBlock(Block block, int radius) {
		BlockPos origin = mc.player.blockPosition(), nearest = null;
		double best = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-radius, -radius, -radius), origin.offset(radius, radius, radius))) {
			if (!mc.level.getBlockState(pos).is(block)) continue;
			double d = origin.distSqr(pos);
			if (d < best) {
				best = d;
				nearest = pos.immutable();
			}
		}
		return nearest;
	}

	private BlockPos randomBlock(Block block, int radius) {
		BlockPos origin = mc.player.blockPosition();
		List<BlockPos> found = new ArrayList<>();
		for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-radius, -radius, -radius), origin.offset(radius, radius, radius)))
			if (origin.distSqr(pos) <= radius * radius && mc.level.getBlockState(pos).is(block)) found.add(pos.immutable());
		return found.isEmpty() ? null : found.get(ThreadLocalRandom.current().nextInt(found.size()));
	}

	private int count(Block block) {
		int n = 0;
		for (int i = 0; i < 36; i++) {
			ItemStack stack = mc.player.getInventory().getItem(i);
			if (stack.is(block.asItem())) n += stack.getCount();
		}
		return n;
	}

	private ItemEntity droppedSpawner() {
		ItemEntity nearest = null;
		float best = Float.MAX_VALUE;
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (!(entity instanceof ItemEntity item) || !item.getItem().is(Items.SPAWNER) || item.hasPickUpDelay()) continue;
			float d = mc.player.distanceTo(item);
			if (d < 16 && d < best) {
				best = d;
				nearest = item;
			}
		}
		return nearest;
	}

	private void stopBreaking() {
		mc.options.keyAttack.setDown(false);
		if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
		breakCooldown = 0;
	}

	private void stopMovement() {
		stopBreaking();
		mc.options.keyUp.setDown(false);
	}
}
