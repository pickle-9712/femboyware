package dev.femboyware.module.combat;

import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.DamageCalculator;
import dev.femboyware.util.HoleUtil;
import dev.femboyware.util.InventoryUtil.SwitchMode;
import dev.femboyware.util.PlaceUtil;
import dev.femboyware.util.RenderUtil;
import dev.femboyware.util.TargetUtil;
import dev.femboyware.util.Timer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/**
 * Places an obsidian base next to the target when there is nowhere good to put a crystal, so
 * Auto Crystal always has a spot.
 */
public class AutoBasePlaceModule extends Module {
	private final NumberSetting targetRange = add(new NumberSetting("Target Range", "How far away targets can be", 8, 2, 12, 0.5));
	private final NumberSetting minDamage = add(new NumberSetting("Min Damage", "A base must let a crystal deal at least this much", 6, 1, 20, 0.5));
	private final NumberSetting maxSelfDamage = add(new NumberSetting("Max Self Damage", "Skip bases whose crystal would hurt you more than this", 8, 0, 20, 0.5));
	private final NumberSetting delay = add(new NumberSetting("Delay", "Milliseconds between placements", 250, 0, 1000, 10));
	private final BoolSetting rotate = add(new BoolSetting("Rotate", "Send a rotation packet before placing", true));
	private final BoolSetting strict = add(new BoolSetting("Strict", "Only click faces you can see", false));
	private final EnumSetting<SwitchMode> switchMode = add(new EnumSetting<>("Switch", "How to switch to obsidian", SwitchMode.SILENT));
	private final BoolSetting render = add(new BoolSetting("Render", "Highlight the chosen base", true));
	private final ColorSetting color = add(new ColorSetting("Color", "Base highlight colour", 0x50B077FF)).visibleWhen(render::get);

	private final Timer timer = new Timer();
	private BlockPos lastBase;

	public AutoBasePlaceModule() {
		super("Auto Base Place", "Places obsidian bases for crystals next to your target", Category.ATTACK);
	}

	@Override
	protected void onDisable() {
		lastBase = null;
	}

	@Override
	public void onTick() {
		PlayerEntity target = TargetUtil.closestPlayer(targetRange.get());
		if (target == null) {
			lastBase = null;
			return;
		}
		if (bestExistingDamage(target) >= minDamage.get() || !timer.passed(delay.get())) return;

		BlockPos best = null;
		float bestDamage = minDamage.getFloat();
		BlockPos feet = target.getBlockPos();
		for (BlockPos base : BlockPos.iterate(feet.add(-2, -1, -2), feet.add(2, 0, 2))) {
			if (!PlaceUtil.canPlace(base, true) || !hasCrystalRoom(base) || isInside(target, base)) continue;
			float damage = DamageCalculator.crystalDamage(target, base.toImmutable(), false, false);
			if (damage < bestDamage) continue;
			if (DamageCalculator.crystalDamage(mc.player, base.toImmutable(), false, false) > maxSelfDamage.get()) continue;
			bestDamage = damage;
			best = base.toImmutable();
		}
		if (best != null && PlaceUtil.place(best, PlaceUtil.OBSIDIAN, switchMode.get(), rotate.get(), strict.get())) {
			lastBase = best;
			timer.reset();
		}
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		if (render.get() && lastBase != null) {
			RenderUtil.drawBox(event, new Box(lastBase), color.get(), color.withAlpha(255), 1.5f);
		}
	}

	/** Damage of the best crystal spot that already exists around the target. */
	private float bestExistingDamage(PlayerEntity target) {
		float best = 0;
		BlockPos feet = target.getBlockPos();
		for (BlockPos base : BlockPos.iterate(feet.add(-3, -2, -3), feet.add(3, 1, 3))) {
			if (!HoleUtil.isBlastProof(mc.world.getBlockState(base)) || !hasCrystalRoom(base)) continue;
			if (mc.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(base)) > PlaceUtil.RANGE * PlaceUtil.RANGE) continue;
			best = Math.max(best, DamageCalculator.crystalDamage(target, base.toImmutable(), false, false));
		}
		return best;
	}

	private boolean hasCrystalRoom(BlockPos base) {
		BlockPos above = base.up();
		if (!mc.world.isAir(above)) return false;
		Box room = new Box(above).stretch(0, 1, 0);
		for (Entity entity : mc.world.getOtherEntities(null, room)) {
			if (entity.isAlive()) return false;
		}
		return true;
	}

	private static boolean isInside(PlayerEntity target, BlockPos pos) {
		return target.getBoundingBox().intersects(new Box(pos));
	}
}
