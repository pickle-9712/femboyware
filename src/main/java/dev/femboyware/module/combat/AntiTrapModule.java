package dev.femboyware.module.combat;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Stops your right clicks on things that are often traps: trapped chests, beds where they blow up,
 * charged respawn anchors outside the Nether, and lighting TNT.
 */
public class AntiTrapModule extends Module {
	private final BoolSetting trappedChests = add(new BoolSetting("Trapped Chests", "Don't open trapped chests, which can set off redstone", true));
	private final BoolSetting beds = add(new BoolSetting("Beds", "Don't use beds outside the Overworld, where they explode", true));
	private final BoolSetting anchors = add(new BoolSetting("Anchors", "Don't use a charged respawn anchor outside the Nether unless you're adding glowstone", true));
	private final BoolSetting tnt = add(new BoolSetting("TNT", "Don't light TNT with flint and steel or a fire charge", true));
	private final BoolSetting sneakBypass = add(new BoolSetting("Sneak Bypass", "Sneaking lets the click through", true));

	public AntiTrapModule() {
		super("Anti Trap", "Skip interactions with selected trap objects", Category.PROTECT);
	}

	/** Called from the useItemOn mixin: true cancels the click. */
	public static boolean blocks(InteractionHand hand, BlockHitResult hit) {
		if (FemboyWare.getModules() == null) return false;
		AntiTrapModule module = FemboyWare.getModules().get(AntiTrapModule.class);
		return module.isEnabled() && !nullCheck() && module.isTrap(hand, hit.getBlockPos());
	}

	private boolean isTrap(InteractionHand hand, BlockPos pos) {
		if (sneakBypass.get() && mc.player.isShiftKeyDown()) return false;
		BlockState state = mc.level.getBlockState(pos);
		ItemStack held = mc.player.getItemInHand(hand);
		if (trappedChests.get() && state.is(Blocks.TRAPPED_CHEST)) return true;
		if (beds.get() && state.getBlock() instanceof BedBlock && mc.level.dimension() != Level.OVERWORLD) return true;
		if (anchors.get() && state.getBlock() instanceof RespawnAnchorBlock && state.getValue(RespawnAnchorBlock.CHARGE) > 0
			&& mc.level.dimension() != Level.NETHER && !held.is(Items.GLOWSTONE) && !anchorPvp()) return true;
		return tnt.get() && state.is(Blocks.TNT) && (held.is(Items.FLINT_AND_STEEL) || held.is(Items.FIRE_CHARGE));
	}

	/** Auto Anchor and Legit Anchor blow anchors up on purpose. */
	private static boolean anchorPvp() {
		return FemboyWare.getModules().get(AutoAnchorModule.class).isEnabled() || FemboyWare.getModules().get(LegitAnchorModule.class).isEnabled();
	}
}
