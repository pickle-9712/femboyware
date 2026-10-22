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
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;

import java.util.ArrayList;
import java.util.List;

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
		if (restoreSlot && !nullCheck()) mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(mc.player.getInventory().getSelectedSlot()));
		restoreSlot = false;
	}

	@Override
	public boolean onStartBreaking(BlockPos pos, Direction side) {
		BlockState state = mc.world.getBlockState(pos);
		if (mc.player.isCreative() || state.isAir() || state.getHardness(mc.world, pos) < 0) return false;
		swapped = false;
		if (blocks.stream().noneMatch(block -> block.pos.equals(pos))) blocks.add(new MineBlock(pos.toImmutable(), side, state));
		return true;
	}

	@Override
	public void onTick() {
		blocks.removeIf(MineBlock::shouldRemove);
		if (restoreSlot) {
			mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(mc.player.getInventory().getSelectedSlot()));
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
			mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot));
			swapped = true;
			restoreSlot = true;
		}
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		if (!render.get()) return;
		for (MineBlock block : blocks) {
			VoxelShape shape = mc.world.getBlockState(block.pos).getOutlineShape(mc.world, block.pos);
			Box box = shape.isEmpty() ? new Box(block.pos) : shape.getBoundingBox().offset(block.pos);
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
			boolean broken = mc.world.getBlockState(pos).getBlock() != block;
			boolean timedOut = progress() > 2 && mc.player.age - startAge > TIMEOUT_TICKS;
			Vec3d face = Vec3d.ofCenter(pos).add(side.getOffsetX() * 0.5, side.getOffsetY() * 0.5, side.getOffsetZ() * 0.5);
			boolean outOfRange = mc.player.getEyePos().distanceTo(face) > mc.player.getBlockInteractionRange();
			return broken || timedOut || outOfRange;
		}

		private boolean isReady() {
			return progress() >= 1;
		}

		/** Break progress so far, as if mining with the best tool we have. */
		private double progress() {
			if (!mining) return 0;
			int slot = FemboyWare.getModules().get(AutoToolModule.class).bestSlot(state);
			PlayerInventory inventory = mc.player.getInventory();
			int previous = inventory.getSelectedSlot();
			if (slot != -1) inventory.setSelectedSlot(slot);
			float delta = state.calcBlockBreakingDelta(mc.player, mc.world, pos);
			inventory.setSelectedSlot(previous);
			return delta * (mc.player.age - startAge + 1);
		}

		private void mine() {
			if (timer > 0) {
				timer--;
			} else if (!mining) {
				if (rotate.get()) FemboyWare.getRotations().sendPacketRotation(Rotation.between(mc.player.getEyePos(), Vec3d.ofCenter(pos)));
				send(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK);
				send(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK);
				mining = true;
				startAge = mc.player.age;
			}
			if (mining && obscure.get()) send(PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK);
		}

		private void send(PlayerActionC2SPacket.Action action) {
			mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(action, pos, side));
		}
	}
}
