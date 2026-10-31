package dev.femboyware.module.combat;

import dev.femboyware.mixin.MinecraftClientAccessor;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.BlockPos;

import java.util.Random;

/**
 * While you hold right click with end crystals, clicks for you as fast as you like: a right
 * click to place a crystal when the crosshair is on obsidian or bedrock, a left click to break
 * one when it's on a crystal. Only real left and right clicks, on whatever you're looking at.
 */
public class LegitCrystalModule extends Module {
	private final NumberSetting placeDelay = add(new NumberSetting("Place Delay", "Ticks between right clicks; 0 is every tick", 0, 0, 10, 1));
	private final NumberSetting breakDelay = add(new NumberSetting("Break Delay", "Ticks between left clicks; 0 is every tick", 0, 0, 10, 1));
	private final BoolSetting humanize = add(new BoolSetting("Humanize", "Click on a slightly uneven rhythm, like a real hand, instead of a perfect beat", true));

	private final Random random = new Random();

	private int placeWait;
	private int breakWait;

	public LegitCrystalModule() {
		super("Legit Crystal", "Hold right click with crystals to place and break them fast", Category.LEGIT);
	}

	@Override
	public void onTick() {
		if (placeWait > 0) placeWait--;
		if (breakWait > 0) breakWait--;
		if (mc.currentScreen != null || !mc.options.useKey.isPressed() || crystalHand() == null) return;

		MinecraftClientAccessor clicks = (MinecraftClientAccessor) mc;
		if (mc.crosshairTarget instanceof EntityHitResult hit && hit.getEntity() instanceof EndCrystalEntity) {
			if (breakWait == 0) {
				clicks.femboyware$leftClick();
				breakWait = breakDelay.getInt() + jitter();
			}
		} else if (mc.crosshairTarget instanceof BlockHitResult hit && canPlaceOn(hit.getBlockPos())) {
			if (placeWait == 0) {
				clicks.femboyware$rightClick();
				placeWait = placeDelay.getInt() + jitter();
			}
		}
	}

	/** Now and then an extra tick, so the clicks don't land on a perfectly even beat. */
	private int jitter() {
		return humanize.get() && random.nextInt(4) == 0 ? 1 : 0;
	}

	/** The hand holding end crystals, or null if neither is. */
	private Hand crystalHand() {
		if (mc.player.getMainHandStack().isOf(Items.END_CRYSTAL)) return Hand.MAIN_HAND;
		if (mc.player.getOffHandStack().isOf(Items.END_CRYSTAL)) return Hand.OFF_HAND;
		return null;
	}

	/** Crystals go on obsidian or bedrock with air above. */
	private boolean canPlaceOn(BlockPos pos) {
		BlockState state = mc.world.getBlockState(pos);
		return (state.isOf(Blocks.OBSIDIAN) || state.isOf(Blocks.BEDROCK)) && mc.world.isAir(pos.up());
	}
}
