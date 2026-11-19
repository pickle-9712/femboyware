package dev.femboyware.module.movement;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.NumberSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Right click a block (with nothing usable in hand) to teleport on top of it, up to ~200 blocks:
 * sends a few empty movement packets first so the server allows the long jump. Ported from the
 * Anubis client's Click TP (GPL-3.0, by 4ldenz, https://github.com/4ldenz/Anubis-client).
 */
public class ClickTpModule extends Module {
	private static final double BLOCKS_PER_PACKET = 10;
	private static final int MAX_PADDING = 19;

	private final NumberSetting range = add(new NumberSetting("Range", "How far you can click", 210, 10, 210, 1));
	private boolean wasDown;

	public ClickTpModule() {
		super("Click TP", "Right click a block to teleport to it", Category.RAGE);
	}

	@Override
	public void onTick() {
		boolean down = mc.options.keyUse.isDown();
		boolean pressed = down && !wasDown;
		wasDown = down;
		if (!pressed || mc.player == null || mc.level == null || dev.femboyware.util.Mc.screen() != null) return;
		if (mc.player.getMainHandItem().getUseAnimation() != ItemUseAnimation.NONE) return;
		HitResult crosshair = mc.hitResult;
		if (crosshair instanceof EntityHitResult) return;
		if (crosshair != null && crosshair.getType() == HitResult.Type.BLOCK && mc.player.getMainHandItem().getItem() instanceof BlockItem) return;
		Vec3 eye = mc.player.getEyePosition();
		Vec3 end = eye.add(mc.player.getViewVector(1f).scale(range.get()));
		BlockHitResult hit = mc.level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
		if (hit.getType() != HitResult.Type.BLOCK) return;
		BlockPos pos = hit.getBlockPos();
		Direction side = hit.getDirection();
		// Clicking a door, chest or button uses it instead
		if (mc.level.getBlockState(pos).useWithoutItem(mc.level, mc.player, hit) != InteractionResult.PASS) return;
		BlockState state = mc.level.getBlockState(pos);
		VoxelShape shape = state.getCollisionShape(mc.level, pos);
		if (shape.isEmpty()) shape = state.getShape(mc.level, pos);
		double height = shape.isEmpty() ? 1 : shape.max(Direction.Axis.Y);
		Vec3 to = new Vec3(pos.getX() + 0.5 + side.getStepX(), pos.getY() + height, pos.getZ() + 0.5 + side.getStepZ());
		int padding = (int) Math.ceil(mc.player.position().distanceTo(to) / BLOCKS_PER_PACKET) - 1;
		if (padding > MAX_PADDING) padding = 0;
		for (int i = 0; i < padding; i++) mc.player.connection.send(new ServerboundMovePlayerPacket.StatusOnly(true, true));
		mc.player.connection.send(new ServerboundMovePlayerPacket.Pos(to.x, to.y, to.z, true, true));
		mc.player.setPos(to);
		mc.player.swing(InteractionHand.MAIN_HAND);
	}
}
