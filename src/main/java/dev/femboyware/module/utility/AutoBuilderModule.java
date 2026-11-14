package dev.femboyware.module.utility;

import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.setting.StringSetting;
import dev.femboyware.util.ChatUtil;
import dev.femboyware.util.InventoryUtil;
import dev.femboyware.util.PlaceUtil;
import dev.femboyware.util.RenderUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Builds a shape in front of you from the blocks in your hotbar: a wall, floor, hollow box, sphere,
 * dome, pyramid or tower. The outline shows where it's going when you turn it on; it builds from
 * the bottom up, as fast as you let it, and turns off when it's done.
 */
public class AutoBuilderModule extends Module {
	public enum Shape { WALL, FLOOR, BOX, SPHERE, DOME, PYRAMID, PILLAR, RING }

	private final EnumSetting<Shape> shape = add(new EnumSetting<>("Shape", "What to build", Shape.WALL));
	private final NumberSetting width = add(new NumberSetting("Width", "Across, or the diameter for round shapes", 5, 1, 32, 1));
	private final NumberSetting height = add(new NumberSetting("Height", "How tall", 4, 1, 32, 1));
	private final NumberSetting length = add(new NumberSetting("Length", "How far forward, for floors and boxes", 5, 1, 32, 1));
	private final NumberSetting distance = add(new NumberSetting("Distance", "Blocks in front of you it starts", 2, 0, 16, 1));
	private final StringSetting block = add(new StringSetting("Block", "Item id to build with (blank: any block in your hotbar)", ""));
	private final NumberSetting perTick = add(new NumberSetting("Per Tick", "Blocks placed each tick", 2, 1, 8, 1));
	private final BoolSetting rotate = add(new BoolSetting("Rotate", "Look at each block as you place it", true));
	private final ColorSetting color = add(new ColorSetting("Color", "Outline of what's left to place", 0x5066CCFF));

	private final List<BlockPos> plan = new ArrayList<>();

	public AutoBuilderModule() {
		super("Auto Builder", "Builds walls, floors, spheres and more in front of you", Category.BUILD);
	}

	@Override
	public String getInfo() {
		return Integer.toString(plan.size());
	}

	@Override
	protected void onEnable() {
		if (nullCheck()) {
			setEnabled(false);
			return;
		}
		plan.clear();
		Direction f = mc.player.getDirection();
		Direction side = f.getClockWise();
		BlockPos origin = mc.player.blockPosition().relative(f, distance.getInt());
		int w = width.getInt(), h = height.getInt(), l = length.getInt();
		int half = (w - 1) / 2;
		switch (shape.get()) {
			case WALL -> {
				for (int x = -half; x < w - half; x++) for (int y = 0; y < h; y++) plan.add(origin.relative(side, x).above(y));
			}
			case FLOOR -> {
				for (int x = -half; x < w - half; x++) for (int z = 0; z < l; z++) plan.add(origin.relative(side, x).relative(f, z).below());
			}
			case BOX -> {
				for (int x = -half; x < w - half; x++) for (int z = 0; z < l; z++) for (int y = 0; y < h; y++) {
					boolean edge = x == -half || x == w - half - 1 || z == 0 || z == l - 1 || y == 0 || y == h - 1;
					if (edge) plan.add(origin.relative(side, x).relative(f, z).above(y - 1));
				}
			}
			case SPHERE, DOME -> {
				double r = w / 2.0;
				BlockPos centre = origin.relative(f, (int) Math.ceil(r));
				int rr = (int) Math.ceil(r);
				for (int x = -rr; x <= rr; x++) for (int y = shape.is(Shape.DOME) ? 0 : -rr; y <= rr; y++) for (int z = -rr; z <= rr; z++) {
					double d = Math.sqrt(x * x + y * y + z * z);
					if (d <= r && d > r - 1) plan.add(centre.offset(x, y + (shape.is(Shape.DOME) ? 0 : rr), z));
				}
			}
			case PYRAMID -> {
				BlockPos centre = origin.relative(f, half);
				for (int y = 0; y <= half; y++) {
					int s = half - y;
					for (int x = -s; x <= s; x++) for (int z = -s; z <= s; z++) {
						if (Math.abs(x) == s || Math.abs(z) == s || y == 0) plan.add(centre.offset(x, y, z));
					}
				}
			}
			case PILLAR -> {
				for (int y = 0; y < h; y++) plan.add(origin.above(y));
			}
			case RING -> {
				double r = w / 2.0;
				BlockPos centre = origin.relative(f, (int) Math.ceil(r));
				int rr = (int) Math.ceil(r);
				for (int x = -rr; x <= rr; x++) for (int z = -rr; z <= rr; z++) {
					double d = Math.sqrt(x * x + z * z);
					if (d <= r && d > r - 1) for (int y = 0; y < h; y++) plan.add(centre.offset(x, y, z));
				}
			}
		}
		// Bottom up, so every block has something to go on
		plan.sort((a, b) -> a.getY() != b.getY() ? a.getY() - b.getY() : 0);
	}

	@Override
	protected void onDisable() {
		plan.clear();
	}

	private Predicate<ItemStack> blocks() {
		String id = block.get().trim().toLowerCase(Locale.ROOT).replaceFirst("^minecraft:", "");
		if (id.isEmpty()) return stack -> stack.getItem() instanceof BlockItem;
		return stack -> stack.getItem() instanceof BlockItem && BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().equals(id);
	}

	@Override
	public void onTick() {
		plan.removeIf(pos -> !mc.level.getBlockState(pos).canBeReplaced());
		if (plan.isEmpty()) {
			ChatUtil.info("Auto Builder: done");
			setEnabled(false);
			return;
		}
		Predicate<ItemStack> item = blocks();
		if (InventoryUtil.findHotbar(item) < 0) {
			ChatUtil.info("Auto Builder: out of blocks");
			setEnabled(false);
			return;
		}
		int placed = 0;
		Vec3 eyes = mc.player.getEyePosition();
		for (BlockPos pos : plan) {
			if (eyes.distanceToSqr(Vec3.atCenterOf(pos)) > PlaceUtil.RANGE * PlaceUtil.RANGE) continue;
			if (!PlaceUtil.canPlace(pos, true)) continue;
			if (PlaceUtil.place(pos, item, InventoryUtil.SwitchMode.SILENT, rotate.get(), false) && ++placed >= perTick.getInt()) return;
		}
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		for (BlockPos pos : plan) RenderUtil.drawBox(event, new AABB(pos).deflate(0.05), color.get() & 0x30FFFFFF, color.withAlpha(200), 1f, false);
	}
}
