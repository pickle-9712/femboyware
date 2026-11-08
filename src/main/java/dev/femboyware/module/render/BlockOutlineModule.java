package dev.femboyware.module.render;

import dev.femboyware.util.Mc;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.RenderUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** The outline round the block you're looking at, in your colour and thickness, with a soft fill. Replaces Minecraft's thin black one. */
public class BlockOutlineModule extends Module {
	private final ColorSetting line = add(new ColorSetting("Line Color", "Colour of the outline", 0xFFFF77C8));
	private final ColorSetting fill = add(new ColorSetting("Fill Color", "Tint inside the block; alpha is how strong", 0x30FF77C8));
	private final NumberSetting width = add(new NumberSetting("Width", "How thick the outline is", 2.5, 1, 6, 0.5));

	public BlockOutlineModule() {
		super("Block Outline", "A thicker, coloured outline on the block you look at", Category.COSMETIC);
	}

	/** Whether to hide the vanilla outline; read by WorldRendererMixin. */
	public boolean replaces() {
		return isEnabled();
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		if (Mc.hudHidden() || !(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;
		BlockPos pos = hit.getBlockPos();
		BlockState state = mc.level.getBlockState(pos);
		if (state.isAir() || !mc.level.getWorldBorder().isWithinBounds(pos)) return;
		VoxelShape shape = state.getShape(mc.level, pos, CollisionContext.of(mc.player));
		if (shape.isEmpty()) return;
		// A hair bigger than the block, so the fill doesn't flicker against its faces
		for (AABB box : shape.toAabbs()) {
			RenderUtil.drawBox(event, box.move(pos).inflate(0.002), fill.get(), line.get(), width.getFloat());
		}
	}
}
