package dev.femboyware.module.larp;

import dev.femboyware.FemboyWare;
import dev.femboyware.manager.CommandManager;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.module.utility.ServerLarpModule;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.setting.StringSetting;
import dev.femboyware.util.ChatUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.FireworkRocketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.Random;

/**
 * While Server Larp is on, a dispenser or dropper that gets powered shoots out whatever you pick
 * here, whatever's really in it: items that land and can be picked up, arrows that fly, fireworks
 * that go up. All of it is only on your screen, like everything else while larping.
 */
public class ForceDispenserModule extends Module {
	private final StringSetting item = add(new StringSetting("Item", "What dispensers shoot while larping, like diamond, gold_block or firework_rocket", "diamond"));
	private final NumberSetting count = add(new NumberSetting("Count", "How many each time it fires", 1, 1, 64, 1));
	private final NumberSetting speed = add(new NumberSetting("Speed", "How hard it shoots them out", 1, 0.2, 4, 0.1));
	private final Random random = new Random();

	public ForceDispenserModule() {
		super("Force Dispenser", "While larping, powered dispensers shoot out the item you pick, whatever's in them. Use " + CommandManager.PREFIX + "forcedispenser <item> [count]", Category.LARP);
	}

	@Override
	public String getInfo() {
		Item picked = picked();
		return picked == null ? "?" : BuiltInRegistries.ITEM.getKey(picked).getPath();
	}

	private Item picked() {
		String id = item.get().trim().toLowerCase(Locale.ROOT);
		Identifier key = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
		return key == null ? null : BuiltInRegistries.ITEM.getOptional(key).orElse(null);
	}

	/**
	 * Called by Server Larp's redstone when a dispenser or dropper is powered: with this on, the
	 * forced item; otherwise one item of whatever you put in it while larping, or the empty click.
	 */
	public void fire(BlockPos pos, BlockState state) {
		if (mc.level == null) return;
		// The casino takes what it fires out of the dispenser itself
		if (FemboyWare.getModules().get(CasinoModule.class).play(pos, state, this)) return;
		Item forced = isEnabled() ? picked() : null;
		ServerLarpModule larp = FemboyWare.getModules().get(ServerLarpModule.class);
		if (forced != null) {
			// The rigged item comes out in place of what's in it: what's in the dispenser goes down as
			// it fires, instead of staying put while the rigged item appears from nowhere
			for (int i = 0; i < count.getInt(); i++) {
				if (larp.takeForDispenser(pos).isEmpty()) break;
			}
			shoot(pos, state, forced, count.getInt());
			return;
		}
		ItemStack inside = larp.takeForDispenser(pos);
		if (inside.isEmpty()) {
			mc.level.levelEvent(1001, pos, 0);
			return;
		}
		shoot(pos, state, inside, 1);
	}

	private void shoot(BlockPos pos, BlockState state, Item what, int n) {
		shoot(pos, state, new ItemStack(what, Math.min(n, what.getDefaultMaxStackSize())), n);
	}

	/** Sends {@code stack} out of the dispenser's face: fireworks fly, arrows shoot, anything else drops out. */
	void shoot(BlockPos pos, BlockState state, ItemStack stack, int n) {
		Item what = stack.getItem();
		Direction facing = state.getValue(DispenserBlock.FACING);
		Vec3 dir = new Vec3(facing.getStepX(), facing.getStepY(), facing.getStepZ());
		// Just out of its face, a little low on the sides, as vanilla places what it spits out
		Vec3 at = Vec3.atCenterOf(pos).add(dir.scale(0.7)).add(0, facing.getAxis() == Direction.Axis.Y ? 0 : -0.15, 0);
		ServerLarpModule larp = FemboyWare.getModules().get(ServerLarpModule.class);
		double power = speed.get();
		boolean dropper = state.getBlock() instanceof net.minecraft.world.level.block.DropperBlock;
		if (what instanceof FireworkRocketItem && !dropper) {
			for (int i = 0; i < n; i++) larp.spawnLocalRocket(stack.copyWithCount(1), at, dir);
		} else if (what instanceof ArrowItem && !dropper) {
			for (int i = 0; i < n; i++) larp.spawnLocalArrow(stack.copyWithCount(1), at, dir, (float) (1.1 * power));
		} else {
			// Like DefaultDispenseItemBehavior: out the front with a little scatter
			double push = (0.1 + random.nextDouble() * 0.2) * power;
			Vec3 velocity = new Vec3(dir.x * push + random.nextGaussian() * 0.045, dir.y * push + 0.2 + random.nextGaussian() * 0.045,
				dir.z * push + random.nextGaussian() * 0.045);
			larp.spawnLocalItem(stack.copyWithCount(Math.min(n, what.getDefaultMaxStackSize())), at, velocity);
		}
		mc.level.levelEvent(1000, pos, 0);
		mc.level.levelEvent(2000, pos, facing.get3DDataValue());
	}

	/** Handles {@code .forcedispenser <item> [count]}. */
	public void command(String[] args) {
		if (args.length < 2) {
			ChatUtil.info("Dispensers shoot " + item.get() + " x" + count.getInt() + (isEnabled() ? "" : " (off)") + ". Change: "
				+ CommandManager.PREFIX + "forcedispenser <item> [count]");
			return;
		}
		String id = args[1].toLowerCase(Locale.ROOT);
		Identifier key = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
		if (key == null || BuiltInRegistries.ITEM.getOptional(key).isEmpty()) {
			ChatUtil.info("No item called " + args[1]);
			return;
		}
		item.set(key.getPath().equals(id) || id.contains(":") ? id : key.toString());
		if (args.length > 2) {
			try {
				count.set(Math.max(1, Math.min(64, Double.parseDouble(args[2]))));
			} catch (NumberFormatException ignored) {
			}
		}
		if (!isEnabled()) setEnabled(true);
		FemboyWare.getConfig().save();
		ChatUtil.info("While larping, powered dispensers shoot " + item.get() + " x" + count.getInt());
	}
}
