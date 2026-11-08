package dev.femboyware.module.combat;

import dev.femboyware.util.Mc;
import dev.femboyware.mixin.MinecraftClientAccessor;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;

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
		if (Mc.screen() != null || !mc.options.keyUse.isDown() || crystalHand() == null) return;

		MinecraftClientAccessor clicks = (MinecraftClientAccessor) mc;
		if (mc.hitResult instanceof EntityHitResult hit && hit.getEntity() instanceof EndCrystal) {
			if (breakWait == 0) {
				clicks.femboyware$leftClick();
				breakWait = breakDelay.getInt() + jitter();
			}
		} else if (mc.hitResult instanceof BlockHitResult hit && canPlaceOn(hit.getBlockPos())) {
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
	private InteractionHand crystalHand() {
		if (mc.player.getMainHandItem().is(Items.END_CRYSTAL)) return InteractionHand.MAIN_HAND;
		if (mc.player.getOffhandItem().is(Items.END_CRYSTAL)) return InteractionHand.OFF_HAND;
		return null;
	}

	/** Crystals go on obsidian or bedrock with air above. */
	private boolean canPlaceOn(BlockPos pos) {
		BlockState state = mc.level.getBlockState(pos);
		return (state.is(Blocks.OBSIDIAN) || state.is(Blocks.BEDROCK)) && mc.level.isEmptyBlock(pos.above());
	}
}
