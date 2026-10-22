package dev.femboyware.module.render;

import dev.femboyware.event.PacketEvent;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.ColorUtil;
import dev.femboyware.util.RenderUtil;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BrewingStandBlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.ChiseledBookshelfBlockEntity;
import net.minecraft.block.entity.CrafterBlockEntity;
import net.minecraft.block.entity.DecoratedPotBlockEntity;
import net.minecraft.block.entity.DispenserBlockEntity;
import net.minecraft.block.entity.EnderChestBlockEntity;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.block.entity.TrappedChestBlockEntity;
import net.minecraft.block.enums.ChestType;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Highlights chests, shulkers and other containers, and can draw tracers to them.
 * Ported from Meteor Client's StorageESP (https://github.com/MeteorDevelopment/meteor-client), GPL-3.0.
 */
public class StorageEspModule extends Module {
	private static final double CHEST_INSET = 1.0 / 16.0;

	private final BoolSetting tracers = add(new BoolSetting("Tracers", "Draw lines to storage blocks", false));
	private final NumberSetting fillOpacity = add(new NumberSetting("Fill Opacity", "Opacity of the box fill", 50, 0, 255, 1));
	private final NumberSetting fadeDistance = add(new NumberSetting("Fade Distance", "Boxes fade out closer than this", 6, 0, 12, 0.5));
	private final ColorSetting chest = add(new ColorSetting("Chest", "Chest colour, alpha 0 hides them", 0xFFFFA000));
	private final ColorSetting trappedChest = add(new ColorSetting("Trapped Chest", "Trapped chest colour, alpha 0 hides them", 0xFFFF0000));
	private final ColorSetting barrel = add(new ColorSetting("Barrel", "Barrel colour, alpha 0 hides them", 0xFFFFA000));
	private final ColorSetting shulker = add(new ColorSetting("Shulker", "Shulker box colour, alpha 0 hides them", 0xFFFF77C8));
	private final ColorSetting enderChest = add(new ColorSetting("Ender Chest", "Ender chest colour, alpha 0 hides them", 0xFF7800FF));
	private final ColorSetting other = add(new ColorSetting("Other", "Furnaces, hoppers, dispensers and the like, alpha 0 hides them", 0xFF8C8C8C));
	private final BoolSetting hideOpened = add(new BoolSetting("Hide Opened", "Hide containers you have opened", false));
	private final ColorSetting openedColor = add(new ColorSetting("Opened Color", "Colour of opened containers, alpha 0 keeps their normal colour", 0x00CB5ACB)).visibleWhen(() -> !hideOpened.get());

	/** Containers we've right clicked. Written from the packet hook, read while rendering. */
	private final Set<BlockPos> opened = ConcurrentHashMap.newKeySet();
	private int count;

	public StorageEspModule() {
		super("Storage Esp", "Highlights chests and other containers", Category.ESP);
	}

	@Override
	public String getInfo() {
		return Integer.toString(count);
	}

	@Override
	protected void onDisable() {
		opened.clear();
	}

	@Override
	public void onPacketSend(PacketEvent event) {
		if (!(event.getPacket() instanceof PlayerInteractBlockC2SPacket packet)) return;
		BlockPos pos = packet.getBlockHitResult().getBlockPos();
		if (mc.world.getBlockEntity(pos) == null) return;
		opened.add(pos);
		BlockPos otherHalf = otherChestHalf(mc.world.getBlockState(pos), pos);
		if (otherHalf != null) opened.add(otherHalf);
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		count = 0;
		Vec3d tracerStart = RenderUtil.tracerOrigin(event);
		ChunkPos center = mc.player.getChunkPos();
		int radius = mc.options.getClampedViewDistance();
		for (int x = -radius; x <= radius; x++) {
			for (int z = -radius; z <= radius; z++) {
				if (!mc.world.getChunkManager().isChunkLoaded(center.x + x, center.z + z)) continue;
				WorldChunk chunk = mc.world.getChunk(center.x + x, center.z + z);
				for (BlockEntity blockEntity : chunk.getBlockEntities().values()) render(event, blockEntity, tracerStart);
			}
		}
	}

	private void render(Render3DEvent event, BlockEntity blockEntity, Vec3d tracerStart) {
		BlockPos pos = blockEntity.getPos();
		boolean wasOpened = opened.contains(pos);
		if (wasOpened && hideOpened.get()) return;
		int color = getColor(blockEntity);
		if (wasOpened && (openedColor.get() >>> 24) != 0) color = openedColor.get();
		if ((color >>> 24) == 0) return;

		BlockState state = blockEntity.getCachedState();
		Box box = new Box(pos);
		if (blockEntity instanceof ChestBlockEntity || blockEntity instanceof EnderChestBlockEntity) {
			box = chestBox(pos);
			BlockPos otherHalf = otherChestHalf(state, pos);
			if (otherHalf != null) {
				// Both halves would draw the same double chest; let the left half draw it
				if (state.get(ChestBlock.CHEST_TYPE) == ChestType.RIGHT) return;
				box = box.union(chestBox(otherHalf));
			}
		}

		Vec3d center = box.getCenter();
		double distance = mc.gameRenderer.getCamera().getCameraPos().distanceTo(center);
		double alpha = fadeDistance.get() <= 0 ? 1 : Math.min(1, distance / fadeDistance.get());
		if (alpha < 0.075) return;
		int line = ColorUtil.withAlpha(color, (int) ((color >>> 24) * alpha));
		int fill = ColorUtil.withAlpha(color, (int) (fillOpacity.get() * alpha));

		RenderUtil.drawBox(event, box, fill, line, 1.5f, true);
		if (tracers.get()) RenderUtil.drawLine(event, tracerStart, center, line, 1.5f, true);
		count++;
	}

	private int getColor(BlockEntity blockEntity) {
		// Trapped chests are chests too, so they have to be checked first
		if (blockEntity instanceof TrappedChestBlockEntity) return trappedChest.get();
		if (blockEntity instanceof ChestBlockEntity) return chest.get();
		if (blockEntity instanceof BarrelBlockEntity) return barrel.get();
		if (blockEntity instanceof ShulkerBoxBlockEntity) return shulker.get();
		if (blockEntity instanceof EnderChestBlockEntity) return enderChest.get();
		if (blockEntity instanceof AbstractFurnaceBlockEntity || blockEntity instanceof BrewingStandBlockEntity
			|| blockEntity instanceof ChiseledBookshelfBlockEntity || blockEntity instanceof CrafterBlockEntity
			|| blockEntity instanceof DispenserBlockEntity || blockEntity instanceof DecoratedPotBlockEntity
			|| blockEntity instanceof HopperBlockEntity) return other.get();
		return 0;
	}

	/** Chests are a pixel smaller than a block on each side and two pixels short. */
	private static Box chestBox(BlockPos pos) {
		return new Box(pos.getX() + CHEST_INSET, pos.getY(), pos.getZ() + CHEST_INSET,
			pos.getX() + 1 - CHEST_INSET, pos.getY() + 1 - CHEST_INSET * 2, pos.getZ() + 1 - CHEST_INSET);
	}

	private static BlockPos otherChestHalf(BlockState state, BlockPos pos) {
		if (!(state.getBlock() instanceof ChestBlock) || state.get(ChestBlock.CHEST_TYPE) == ChestType.SINGLE) return null;
		return pos.offset(ChestBlock.getFacing(state));
	}
}
