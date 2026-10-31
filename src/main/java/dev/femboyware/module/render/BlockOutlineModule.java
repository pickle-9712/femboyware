package dev.femboyware.module.render;

import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.RenderUtil;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.shape.VoxelShape;

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
		if (mc.options.hudHidden || !(mc.crosshairTarget instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;
		BlockPos pos = hit.getBlockPos();
		BlockState state = mc.world.getBlockState(pos);
		if (state.isAir() || !mc.world.getWorldBorder().contains(pos)) return;
		VoxelShape shape = state.getOutlineShape(mc.world, pos, ShapeContext.of(mc.player));
		if (shape.isEmpty()) return;
		// A hair bigger than the block, so the fill doesn't flicker against its faces
		for (Box box : shape.getBoundingBoxes()) {
			RenderUtil.drawBox(event, box.offset(pos).expand(0.002), fill.get(), line.get(), width.getFloat());
		}
	}
}
