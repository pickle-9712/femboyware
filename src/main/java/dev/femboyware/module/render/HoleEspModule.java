package dev.femboyware.module.render;

import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.RenderUtil;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * Shows safe holes: pits walled in by bedrock or obsidian, where crystals can't reach your feet.
 * Ported from Meteor Client's HoleESP (https://github.com/MeteorDevelopment/meteor-client), GPL-3.0.
 */
public class HoleEspModule extends Module {
	private enum Type { BEDROCK, OBSIDIAN, MIXED }

	private record Hole(Box box, Type type) {}

	/** Obsidian and up survive crystal explosions. */
	private static final float BLAST_PROOF = 600f;

	private final NumberSetting horizontalRadius = add(new NumberSetting("Horizontal Radius", "Horizontal search radius", 10, 0, 32, 1));
	private final NumberSetting verticalRadius = add(new NumberSetting("Vertical Radius", "Vertical search radius", 5, 0, 32, 1));
	private final NumberSetting holeHeight = add(new NumberSetting("Hole Height", "Open blocks needed above the hole floor", 3, 1, 5, 1));
	private final BoolSetting doubles = add(new BoolSetting("Doubles", "Show two-block holes you can stand across", true));
	private final BoolSetting ignoreOwn = add(new BoolSetting("Ignore Own", "Don't show the hole you're standing in", false));
	private final BoolSetting webs = add(new BoolSetting("Webs", "Show holes with cobwebs in them", false));

	private final NumberSetting height = add(new NumberSetting("Height", "Height of the highlight", 0.2, 0, 1, 0.05));
	private final BoolSetting topQuad = add(new BoolSetting("Top Quad", "Fill the top of the highlight", true));
	private final ColorSetting bedrockTop = add(new ColorSetting("Bedrock Top", "Top colour of all-bedrock holes", 0xC864FF00));
	private final ColorSetting bedrockBottom = add(new ColorSetting("Bedrock Bottom", "Bottom colour of all-bedrock holes", 0x0064FF00));
	private final ColorSetting obsidianTop = add(new ColorSetting("Obsidian Top", "Top colour of all-obsidian holes", 0xC8FF0000));
	private final ColorSetting obsidianBottom = add(new ColorSetting("Obsidian Bottom", "Bottom colour of all-obsidian holes", 0x00FF0000));
	private final ColorSetting mixedTop = add(new ColorSetting("Mixed Top", "Top colour of mixed holes", 0xC8FF7F00));
	private final ColorSetting mixedBottom = add(new ColorSetting("Mixed Bottom", "Bottom colour of mixed holes", 0x00FF7F00));

	private final List<Hole> holes = new ArrayList<>();

	public HoleEspModule() {
		super("Hole Esp", "Shows holes that protect you from crystals", Category.ESP);
	}

	@Override
	protected void onDisable() {
		holes.clear();
	}

	@Override
	public void onTick() {
		holes.clear();
		BlockPos center = mc.player.getBlockPos();
		int h = horizontalRadius.getInt();
		int v = verticalRadius.getInt();
		for (BlockPos pos : BlockPos.iterate(center.add(-h, -v, -h), center.add(h, v, h))) {
			Hole hole = probe(pos.toImmutable());
			if (hole != null) holes.add(hole);
		}
	}

	private Hole probe(BlockPos pos) {
		if (!isOpenHole(pos)) return null;
		int bedrock = 0, obsidian = 0;
		Direction air = null;
		for (Direction direction : Direction.values()) {
			if (direction == Direction.UP) continue;
			BlockPos side = pos.offset(direction);
			BlockState state = mc.world.getBlockState(side);
			if (isBedrock(state)) bedrock++;
			else if (isObsidian(state)) obsidian++;
			else if (direction == Direction.DOWN || !doubles.get() || air != null || !isOpenHole(side)) return null;
			else {
				// One open side may lead into a second hole; its other walls must hold too
				for (Direction other : Direction.values()) {
					if (other == direction.getOpposite() || other == Direction.UP) continue;
					BlockState wall = mc.world.getBlockState(side.offset(other));
					if (isBedrock(wall)) bedrock++;
					else if (isObsidian(wall)) obsidian++;
					else return null;
				}
				air = direction;
			}
		}

		int total = air == null ? 5 : 8;
		if (bedrock + obsidian != total) return null;
		Type type = obsidian == total ? Type.OBSIDIAN : bedrock == total ? Type.BEDROCK : Type.MIXED;
		Box box = new Box(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + height.get(), pos.getZ() + 1);
		if (air == null) return new Hole(box, type);
		// Both halves find the same double hole; only the north-west half keeps it
		if (air == Direction.NORTH || air == Direction.WEST) return null;
		return new Hole(box.union(box.offset(air.getOffsetX(), 0, air.getOffsetZ())), type);
	}

	private boolean isOpenHole(BlockPos pos) {
		if (ignoreOwn.get() && mc.player.getBlockPos().equals(pos)) return false;
		if (!webs.get() && mc.world.getBlockState(pos).isOf(Blocks.COBWEB)) return false;
		for (int i = 0; i < holeHeight.getInt(); i++) {
			BlockPos above = pos.up(i);
			if (!mc.world.getBlockState(above).getCollisionShape(mc.world, above).isEmpty()) return false;
		}
		return true;
	}

	private static boolean isBedrock(BlockState state) {
		return !state.isAir() && state.getBlock().getHardness() < 0;
	}

	private static boolean isObsidian(BlockState state) {
		return !state.isAir() && state.getBlock().getHardness() >= 0 && state.getBlock().getBlastResistance() >= BLAST_PROOF;
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		for (Hole hole : holes) {
			int top = switch (hole.type) {
				case BEDROCK -> bedrockTop.get();
				case OBSIDIAN -> obsidianTop.get();
				case MIXED -> mixedTop.get();
			};
			int bottom = switch (hole.type) {
				case BEDROCK -> bedrockBottom.get();
				case OBSIDIAN -> obsidianBottom.get();
				case MIXED -> mixedBottom.get();
			};
			// Halved alpha on the faces keeps the outline readable, as in Meteor
			RenderUtil.drawGradientBox(event, hole.box, halfAlpha(top), halfAlpha(bottom), top, topQuad.get(), 1.5f);
		}
	}

	private static int halfAlpha(int color) {
		return (color & 0x00FFFFFF) | ((color >>> 25) << 24);
	}
}
