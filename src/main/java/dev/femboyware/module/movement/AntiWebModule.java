package dev.femboyware.module.movement;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;

/** Removes the slowdown from cobwebs and similar blocks. Applied by the entity mixin. */
public class AntiWebModule extends Module {
	private final BoolSetting cobweb = add(new BoolSetting("Cobweb", "No cobweb slowdown", true));
	private final BoolSetting berryBush = add(new BoolSetting("Berry Bush", "No sweet berry bush slowdown", true));
	private final BoolSetting powderSnow = add(new BoolSetting("Powder Snow", "No powder snow slowdown", true));

	public AntiWebModule() {
		super("Anti Web", "Walk through cobwebs at full speed", Category.RAGE);
	}

	public boolean ignores(BlockState state) {
		if (!isEnabled()) return false;
		if (state.isOf(Blocks.COBWEB)) return cobweb.get();
		if (state.isOf(Blocks.SWEET_BERRY_BUSH)) return berryBush.get();
		if (state.isOf(Blocks.POWDER_SNOW)) return powderSnow.get();
		return false;
	}
}
