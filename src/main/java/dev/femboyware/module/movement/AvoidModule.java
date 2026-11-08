package dev.femboyware.module.movement;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.SoulFireBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Gives harmful blocks a solid collision box for you only, so you can't walk into them. Applied
 * by the block state mixin.
 */
public class AvoidModule extends Module {
	private final BoolSetting cactus = add(new BoolSetting("Cactus", "Don't touch cactus", true));
	private final BoolSetting fire = add(new BoolSetting("Fire", "Don't walk into fire", true));
	private final BoolSetting lava = add(new BoolSetting("Lava", "Treat lava as solid ground", true));
	private final BoolSetting berryBush = add(new BoolSetting("Berry Bush", "Don't walk into sweet berry bushes", true));
	private final BoolSetting witherRose = add(new BoolSetting("Wither Rose", "Don't walk into wither roses", true));
	private final BoolSetting powderSnow = add(new BoolSetting("Powder Snow", "Don't sink into powder snow", false));
	private final BoolSetting cobweb = add(new BoolSetting("Cobweb", "Don't walk into cobwebs", false));

	public AvoidModule() {
		super("Avoid", "Stops you from walking into dangerous blocks", Category.BASIC);
	}

	public boolean shouldBlock(BlockState state) {
		if (state.is(Blocks.CACTUS)) return cactus.get();
		if (state.getBlock() instanceof FireBlock || state.getBlock() instanceof SoulFireBlock) return fire.get();
		if (state.is(Blocks.LAVA)) return lava.get();
		if (state.is(Blocks.SWEET_BERRY_BUSH)) return berryBush.get();
		if (state.is(Blocks.WITHER_ROSE)) return witherRose.get();
		if (state.is(Blocks.POWDER_SNOW)) return powderSnow.get();
		if (state.is(Blocks.COBWEB)) return cobweb.get();
		return false;
	}
}
