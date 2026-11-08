package dev.femboyware.module.utility;

import dev.femboyware.FemboyWare;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.RenderUtil;
import dev.femboyware.util.Rotation;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Mines blocks with packets: click once and the block breaks on its own, no holding needed.
 * Ported from Meteor Client's PacketMine (https://github.com/MeteorDevelopment/meteor-client), GPL-3.0.
 */
public class PacketMineModule extends Module {
	/** Ticks after a block should have broken before we give up on it. */
	private static final int TIMEOUT_TICKS = 50;

	private final NumberSetting delay = add(new NumberSetting("Delay", "Ticks before mining a queued block", 1, 0, 20, 1));
	private final BoolSetting rotate = add(new BoolSetting("Rotate", "Look at the block when mining starts", true));
	private final BoolSetting autoSwitch = add(new BoolSetting("Auto Switch", "Silently hold the best tool once the block is ready", false));
	private final BoolSetting notOnUse = add(new BoolSetting("Not On Use", "Don't switch while using an item", true)).visibleWhen(autoSwitch::get);
	private final BoolSetting obscure = add(new BoolSetting("Obscure Progress", "Spam abort packets to hide the crack animation from others", false));
	private final BoolSetting render = add(new BoolSetting("Render", "Highlight blocks being mined", true));
	private final ColorSetting fillColor = add(new ColorSetting("Fill", "Fill while mining", 0x1ACC0000)).visibleWhen(render::get);
	private final ColorSetting lineColor = add(new ColorSetting("Line", "Outline while mining", 0xFFCC0000)).visibleWhen(render::get);
	private final ColorSetting readyFillColor = add(new ColorSetting("Ready Fill", "Fill once the block can break", 0x1A00CC00)).visibleWhen(render::get);
	private final ColorSetting readyLineColor = add(new ColorSetting("Ready Line", "Outline once the block can break", 0xFF00CC00)).visibleWhen(render::get);

	private final List<MineBlock> blocks = new ArrayList<>();
	private boolean swapped;
	private boolean restoreSlot;

	public PacketMineModule() {
		super("Packet Mine", "Click a block once to mine it with packets", Category.WORLD);
	}

	@Override
	protected void onEnable() {
		swapped = false;
	}

	@Override
	protected void onDisable() {
		blocks.clear();
		if (restoreSlot && !nullCheck()) mc.getConnection().send(new ServerboundSetCarriedItemPacket(mc.player.getInventory().getSelectedSlot()));
		restoreSlot = false;
	}

	@Override
	public boolean onStartBreaking(BlockPos pos, Direction side) {
		BlockState state = mc.level.getBlockState(pos);
		if (mc.player.isCreative() || state.isAir() || state.getDestroySpeed(mc.level, pos) < 0) return false;
		swapped = false;
		if (blocks.stream().noneMatch(block -> block.pos.equals(pos))) blocks.add(new MineBlock(pos.immutable(), side, state));
		return true;
	}

	@Override
	public void onTick() {
		blocks.removeIf(MineBlock::shouldRemove);
		if (restoreSlot) {
			mc.getConnection().send(new ServerboundSetCarriedItemPacket(mc.player.getInventory().getSelectedSlot()));
			restoreSlot = false;
			swapped = false;
		}
		if (blocks.isEmpty()) return;

		MineBlock block = blocks.getFirst();
		block.mine();
		if (block.isReady() && !swapped && autoSwitch.get() && (!mc.player.isUsingItem() || !notOnUse.get())) {
			int slot = FemboyWare.getModules().get(AutoToolModule.class).bestSlot(block.state);
			if (slot == -1 || slot == mc.player.getInventory().getSelectedSlot()) return;
			// Server-side only: the server breaks with this tool, we keep our slot on screen
			mc.getConnection().send(new ServerboundSetCarriedItemPacket(slot));
			swapped = true;
			restoreSlot = true;
		}
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		if (!render.get()) return;
		for (MineBlock block : blocks) {
			VoxelShape shape = mc.level.getBlockState(block.pos).getShape(mc.level, block.pos);
			AABB box = shape.isEmpty() ? new AABB(block.pos) : shape.bounds().move(block.pos);
			boolean ready = block.isReady();
			RenderUtil.drawBox(event, box, ready ? readyFillColor.get() : fillColor.get(), ready ? readyLineColor.get() : lineColor.get(), 1.5f);
		}
	}

	private class MineBlock {
		private final BlockPos pos;
		private final Direction side;
		private final BlockState state;
		private final Block block;
		private int timer;
		private int startAge;
		private boolean mining;

		private MineBlock(BlockPos pos, Direction side, BlockState state) {
			this.pos = pos;
			this.side = side;
			this.state = state;
			this.block = state.getBlock();
			this.timer = delay.getInt();
		}

		private boolean shouldRemove() {
			boolean broken = mc.level.getBlockState(pos).getBlock() != block;
			boolean timedOut = progress() > 2 && mc.player.tickCount - startAge > TIMEOUT_TICKS;
			Vec3 face = Vec3.atCenterOf(pos).add(side.getStepX() * 0.5, side.getStepY() * 0.5, side.getStepZ() * 0.5);
			boolean outOfRange = mc.player.getEyePosition().distanceTo(face) > mc.player.blockInteractionRange();
			return broken || timedOut || outOfRange;
		}

		private boolean isReady() {
			return progress() >= 1;
		}

		/** Break progress so far, as if mining with the best tool we have. */
		private double progress() {
			if (!mining) return 0;
			int slot = FemboyWare.getModules().get(AutoToolModule.class).bestSlot(state);
			Inventory inventory = mc.player.getInventory();
			int previous = inventory.getSelectedSlot();
			if (slot != -1) inventory.setSelectedSlot(slot);
			float delta = state.getDestroyProgress(mc.player, mc.level, pos);
			inventory.setSelectedSlot(previous);
			return delta * (mc.player.tickCount - startAge + 1);
		}

		private void mine() {
			if (timer > 0) {
				timer--;
			} else if (!mining) {
				if (rotate.get()) FemboyWare.getRotations().sendPacketRotation(Rotation.between(mc.player.getEyePosition(), Vec3.atCenterOf(pos)));
				send(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK);
				send(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK);
				mining = true;
				startAge = mc.player.tickCount;
			}
			if (mining && obscure.get()) send(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK);
		}

		private void send(ServerboundPlayerActionPacket.Action action) {
			mc.getConnection().send(new ServerboundPlayerActionPacket(action, pos, side));
		}
	}
}
