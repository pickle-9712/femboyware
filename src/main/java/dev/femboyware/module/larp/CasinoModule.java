package dev.femboyware.module.larp;

import dev.femboyware.FemboyWare;
import dev.femboyware.manager.CommandManager;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.module.utility.FakeEconomy;
import dev.femboyware.module.utility.ServerLarpModule;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.StringSetting;
import dev.femboyware.util.ChatUtil;
import dev.femboyware.util.Mc;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.ChatFormatting;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * DonutSMP style dispenser casino games, larped: while Server Larp is on, powering a dispenser plays
 * the picked game. What comes out is the game's outcome, the host calls it in chat, and the bet goes
 * on or off your fake balance. All of it is on your screen only, like the rest of the larp; nothing
 * real is dispensed, bet or paid, and no one else's game is touched.
 */
public class CasinoModule extends Module {
	/** Who wins the round: the game's own odds, or forced either way for the recording. */
	public enum Outcome { FAIR, WIN, LOSE }

	/** Which Paper dispenser rolls the higher number. */
	public enum Side { RANDOM, LEFT, RIGHT }

	public enum Game {
		FIFTY_FIFTY("50-50", "green", "Fill it with green and red (wool, emeralds, redstone...). Pick green or red; right pays 2x."),
		FORTY_FIVE_TEN("45-45-10", "green", "Green, red and gold items. Pick one: green and red pay 2x, gold pays 10x."),
		ARMOR_GAME("Armor Game", "diamond", "Fill it with armor. Diamond or netherite out pays 3x."),
		ARROW_GAME("Arrow Game", "spectral", "Fill it with arrows. The kind you pick (spectral by default) out pays 4x."),
		HORSE_RACING("Horse Racing", "1", "Each slot is a horse. Pick slot 1 to 9; your slot firing pays 4x."),
		PAPER("Paper", "left", "Two dispensers with the lever between them, each fires from a slot. Pick left or right: the higher slot number wins 2x."),
		RUSSIAN_ROULETTE("Russian Roulette", "survive", "Blanks and a TNT. Anything but the TNT pays 1.2x."),
		EVEN_OR_ODD("Even or Odd", "even", "Pick even or odd: the slot number that fires decides it, 2x.");

		final String title, defaultPick, rules;

		Game(String title, String defaultPick, String rules) {
			this.title = title;
			this.defaultPick = defaultPick;
			this.rules = rules;
		}
	}

	/** One way a round can end: what comes out, what it's called, and what it pays if it's yours. */
	private record Result(ItemStack stack, String name, boolean win, double pays, boolean boom, boolean push) {
		Result(ItemStack stack, String name, boolean win, double pays, boolean boom) {
			this(stack, name, win, pays, boom, false);
		}
	}

	private final EnumSetting<Game> game = add(new EnumSetting<>("Game", "Which game the dispenser plays", Game.FIFTY_FIFTY));
	private final StringSetting pick = add(new StringSetting("Pick", "Your pick: green/red/gold, a slot 1-9, even/odd, left/right, spectral... (empty uses the game's default)", ""));
	private final StringSetting bet = add(new StringSetting("Bet", "What you bet each round, like 250k or 1.5m", "1m"));
	private final EnumSetting<Outcome> outcome = add(new EnumSetting<>("Outcome", "Fair: the game's odds. Win or Lose: forced, for the recording", Outcome.FAIR));
	private final EnumSetting<Side> paperHigher = add(new EnumSetting<>("Paper Higher", "Paper: which dispenser fires the higher slot (left and right as you face them). Random follows Outcome", Side.RANDOM));
	private final BoolSetting host = add(new BoolSetting("Host Calls", "The host calls each round in chat", true));
	private final BoolSetting payOut = add(new BoolSetting("Pay Out", "Wins and losses go on your fake balance (see /fakebal)", true));

	/** The round waiting to be called, and when. */
	private Result calling;
	private BlockPos callAt;
	private int callIn;

	public CasinoModule() {
		super("Casino", "While larping, dispensers play DonutSMP casino games on your screen: " + CommandManager.PREFIX + "casino <game> [pick] [bet]", Category.LARP);
	}

	@Override
	public String getInfo() {
		return game.get().title;
	}

	private String pick() {
		String p = pick.get().trim().toLowerCase(Locale.ROOT);
		return p.isEmpty() ? game.get().defaultPick : p;
	}

	/** From ForceDispenser when a dispenser fires while larping: true if a round was played. */
	boolean play(BlockPos pos, BlockState state, ForceDispenserModule dispenser) {
		if (!isEnabled() || calling != null) return isEnabled();
		if (game.get() == Game.PAPER && paper(pos, state, dispenser)) return true;
		// The real thing: one item out of one of its slots. Forcing only picks which slot
		ServerLarpModule larp = FemboyWare.getModules().get(ServerLarpModule.class);
		List<Integer> slots = larp.filledDispenserSlots(pos);
		if (slots.isEmpty()) {
			mc.level.levelEvent(1001, pos, 0);
			if (host.get()) say(Component.literal("It's empty: open it while larping and fill it first").withStyle(ChatFormatting.GRAY));
			return true;
		}
		String p = pick();
		List<Integer> winning = new ArrayList<>(), losing = new ArrayList<>();
		for (int slot : slots) (judge(larp.dispenserItem(pos, slot), slot, p).win() ? winning : losing).add(slot);
		List<Integer> from = switch (outcome.get()) {
			case WIN -> winning.isEmpty() ? slots : winning;
			case LOSE -> losing.isEmpty() ? slots : losing;
			case FAIR -> slots;
		};
		int slot = from.get(ThreadLocalRandom.current().nextInt(from.size()));
		Result result = judge(larp.dispenserItem(pos, slot).copy(), slot, p);
		dispenser.shoot(pos, state, larp.takeFromDispenserSlot(pos, slot), 1);
		calling = result;
		callAt = pos;
		// Called once it's landed and everyone's seen it
		callIn = 25;
		if (host.get()) say(Component.literal("Rolling " + game.get().title + "... ").withStyle(ChatFormatting.GRAY)
			.append(Component.literal("you're on " + p + " for " + FakeEconomy.money(betAmount())).withStyle(ChatFormatting.WHITE)));
		return true;
	}

	@Override
	public void onTick() {
		if (calling == null || --callIn > 0) return;
		Result r = calling;
		calling = null;
		if (mc.level == null) return;
		double stake = betAmount();
		if (r.push) {
			// No round (empty dispensers) or a tie: nothing changes hands
			if (host.get() && !r.name.isEmpty()) say(Component.literal(r.name).withStyle(ChatFormatting.YELLOW));
			return;
		}
		if (r.boom) {
			mc.level.addParticle(ParticleTypes.EXPLOSION_EMITTER, callAt.getX() + 0.5, callAt.getY() + 1, callAt.getZ() + 0.5, 0, 0, 0);
			mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.GENERIC_EXPLODE.value(), 1, 0.8f));
		}
		if (r.win) {
			double won = stake * r.pays;
			if (host.get()) say(Component.literal(r.name + "! ").withStyle(ChatFormatting.GOLD)
				.append(Component.literal("You win ").withStyle(ChatFormatting.WHITE))
				.append(Component.literal(FakeEconomy.money(won)).withStyle(ChatFormatting.GREEN))
				.append(Component.literal(" (" + trim(r.pays) + "x)").withStyle(ChatFormatting.GRAY)));
			if (payOut.get()) FakeEconomy.add(won - stake);
			mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.PLAYER_LEVELUP, 1.2f, 0.7f));
			for (int i = 0; i < 12; i++) {
				var rand = ThreadLocalRandom.current();
				mc.level.addParticle(ParticleTypes.HAPPY_VILLAGER, callAt.getX() + rand.nextDouble(), callAt.getY() + 1 + rand.nextDouble(), callAt.getZ() + rand.nextDouble(), 0, 0.05, 0);
			}
		} else {
			if (host.get()) say(Component.literal(r.name + ". ").withStyle(ChatFormatting.RED)
				.append(Component.literal("House wins, you lose ").withStyle(ChatFormatting.WHITE))
				.append(Component.literal(FakeEconomy.money(stake)).withStyle(ChatFormatting.RED)));
			if (payOut.get()) FakeEconomy.add(-stake);
			mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.VILLAGER_NO, 1, 0.6f));
		}
	}

	@Override
	protected void onDisable() {
		calling = null;
	}

	// ---------------------------------------------------------------- the games

	/** How the round goes if {@code stack} comes out of {@code slot} (0 to 8), for your pick. */
	private Result judge(ItemStack stack, int slot, String p) {
		String what = (BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath() + " " + stack.getHoverName().getString()).toLowerCase(Locale.ROOT);
		String name = stack.getHoverName().getString();
		return switch (game.get()) {
			case FIFTY_FIFTY -> new Result(stack, name, colour(what).equals(colour(p)), 2, false);
			case FORTY_FIVE_TEN -> {
				String c = colour(what);
				yield new Result(stack, name, c.equals(colour(p)), c.equals("gold") ? 10 : 2, false);
			}
			case ARMOR_GAME -> new Result(stack, name, what.contains("diamond") || what.contains("netherite"), 3, false);
			case ARROW_GAME -> new Result(stack, name, what.contains(p), 4, false);
			case HORSE_RACING -> new Result(stack, "Horse " + (slot + 1) + " takes it", slot + 1 == number(p), 4, false);
			case PAPER -> new Result(stack, name, true, 2, false);
			case RUSSIAN_ROULETTE -> what.contains("tnt") ? new Result(stack, "BANG", false, 1.2, true) : new Result(stack, "Click, a blank", true, 1.2, false);
			case EVEN_OR_ODD -> {
				boolean even = (slot + 1) % 2 == 0;
				yield new Result(stack, "Slot " + (slot + 1) + ", " + (even ? "even" : "odd"), even == !p.startsWith("o"), 2, false);
			}
		};
	}

	/** Green, red or gold, from an item's id and name (or a pick). */
	private static String colour(String text) {
		if (text.contains("gold") || text.contains("yellow")) return "gold";
		if (text.contains("green") || text.contains("lime") || text.contains("emerald")) return "green";
		if (text.contains("red")) return "red";
		return text.trim();
	}

	private static int number(String text) {
		try {
			return Integer.parseInt(text.replaceAll("[^0-9]", ""));
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	/**
	 * Paper with two dispensers side by side on one lever: each fires one item from one of its filled
	 * slots, as vanilla would, and the higher slot number wins. What's in them is what comes out;
	 * forcing only picks the slots: the losing side fires its lowest filled slot and the winner a
	 * higher one. The other dispenser's own fire is swallowed while the round's called. False if
	 * there's no second dispenser beside this one (then it's the one-dispenser game).
	 */
	private boolean paper(BlockPos pos, BlockState state, ForceDispenserModule dispenser) {
		Direction facing = state.getValue(DispenserBlock.FACING);
		if (facing.getAxis() == Direction.Axis.Y) return false;
		// Facing the dispensers' fronts, your left is their facing turned clockwise
		Direction toLeft = facing.getClockWise();
		// The other one's beside it, or two along with the lever between them
		BlockPos left = null, right = null;
		for (int gap = 1; gap <= 2 && left == null; gap++) {
			if (mc.level.getBlockState(pos.relative(toLeft, gap)).getBlock() instanceof DispenserBlock) {
				left = pos.relative(toLeft, gap);
				right = pos;
			} else if (mc.level.getBlockState(pos.relative(toLeft.getOpposite(), gap)).getBlock() instanceof DispenserBlock) {
				left = pos;
				right = pos.relative(toLeft.getOpposite(), gap);
			}
		}
		if (left == null) return false;
		ServerLarpModule larp = FemboyWare.getModules().get(ServerLarpModule.class);
		List<Integer> leftSlots = larp.filledDispenserSlots(left), rightSlots = larp.filledDispenserSlots(right);
		callAt = pos;
		callIn = 35;
		if (leftSlots.isEmpty() || rightSlots.isEmpty()) {
			// Nothing to fire on a side: both just click, like empty dispensers
			mc.level.levelEvent(1001, left, 0);
			mc.level.levelEvent(1001, right, 0);
			if (host.get()) say(Component.literal("Fill both dispensers first (open them while larping)").withStyle(ChatFormatting.GRAY));
			calling = new Result(ItemStack.EMPTY, "", false, 0, false, true);
			return true;
		}
		boolean mineLeft = !pick().startsWith("r");
		Boolean leftHigher = switch (paperHigher.get()) {
			case LEFT -> true;
			case RIGHT -> false;
			case RANDOM -> switch (outcome.get()) {
				case WIN -> mineLeft;
				case LOSE -> !mineLeft;
				case FAIR -> null;
			};
		};
		var rand = ThreadLocalRandom.current();
		int leftSlot, rightSlot;
		if (leftHigher == null) {
			leftSlot = leftSlots.get(rand.nextInt(leftSlots.size()));
			rightSlot = rightSlots.get(rand.nextInt(rightSlots.size()));
		} else {
			List<Integer> winSlots = leftHigher ? leftSlots : rightSlots, loseSlots = leftHigher ? rightSlots : leftSlots;
			// The loser's lowest slot, and one of the winner's above it (its top one if none are)
			int lose = loseSlots.get(0);
			List<Integer> above = new ArrayList<>();
			for (int slot : winSlots) if (slot > lose) above.add(slot);
			int win = above.isEmpty() ? winSlots.get(winSlots.size() - 1) : above.get(rand.nextInt(above.size()));
			leftSlot = leftHigher ? win : lose;
			rightSlot = leftHigher ? lose : win;
		}
		dispenser.shoot(left, mc.level.getBlockState(left), larp.takeFromDispenserSlot(left, leftSlot), 1);
		dispenser.shoot(right, mc.level.getBlockState(right), larp.takeFromDispenserSlot(right, rightSlot), 1);
		if (host.get()) say(Component.literal("Paper! ").withStyle(ChatFormatting.GRAY)
			.append(Component.literal("you're on " + (mineLeft ? "left" : "right") + " for " + FakeEconomy.money(betAmount())).withStyle(ChatFormatting.WHITE)));
		String name = "Left slot " + (leftSlot + 1) + ", right slot " + (rightSlot + 1);
		calling = leftSlot == rightSlot
			? new Result(ItemStack.EMPTY, name + ", a tie: bets back", false, 0, false, true)
			: new Result(ItemStack.EMPTY, name, (leftSlot > rightSlot) == mineLeft, 2, false, false);
		return true;
	}

	private static void option(List<Result> results, List<Double> weights, double weight, Result result) {
		results.add(result);
		weights.add(weight);
	}

	private static ItemStack stack(Item item, int count) {
		return new ItemStack(item, count);
	}

	private static int horse(String pick) {
		try {
			return Integer.parseInt(pick.replaceAll("[^0-9]", "")) - 1;
		} catch (NumberFormatException e) {
			return switch (pick) {
				case "leather" -> 0;
				case "iron" -> 1;
				case "gold", "golden" -> 2;
				case "diamond" -> 3;
				default -> -1;
			};
		}
	}

	// ---------------------------------------------------------------- bits

	private double betAmount() {
		try {
			return Math.max(0, parse(bet.get()));
		} catch (RuntimeException e) {
			return 0;
		}
	}

	private static void say(Component message) {
		Mc.addChat(Component.literal("[Casino] ").withStyle(ChatFormatting.LIGHT_PURPLE).append(message));
	}

	private static String trim(double v) {
		return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
	}

	/** 150, 1.5k, 5m, 2b. */
	private static double parse(String text) {
		String clean = text.replace(",", "").replace("$", "").trim().toLowerCase(Locale.ROOT);
		double scale = switch (clean.isEmpty() ? ' ' : clean.charAt(clean.length() - 1)) {
			case 'k' -> 1e3;
			case 'm' -> 1e6;
			case 'b' -> 1e9;
			case 't' -> 1e12;
			default -> 1;
		};
		if (scale != 1) clean = clean.substring(0, clean.length() - 1);
		return Double.parseDouble(clean) * scale;
	}

	/** Handles {@code .casino <game> [pick] [bet]}, {@code .casino win|lose|fair} and {@code .casino off}. */
	public void command(String[] args) {
		if (args.length < 2) {
			ChatUtil.info("Casino: " + game.get().title + ", pick " + pick() + ", bet " + bet.get() + ", " + outcome.getDisplayName()
				+ (isEnabled() ? "" : " (off)") + ". " + game.get().rules);
			StringBuilder games = new StringBuilder();
			for (Game g : Game.values()) games.append(games.isEmpty() ? "" : ", ").append(g.name().toLowerCase(Locale.ROOT).replace('_', '-'));
			ChatUtil.info("Usage: " + CommandManager.PREFIX + "casino <game> [pick] [bet] | win | lose | fair | off | paper force left|right|off. Games: " + games);
			return;
		}
		String word = args[1].toLowerCase(Locale.ROOT);
		switch (word) {
			case "off" -> {
				setEnabled(false);
				ChatUtil.info("Casino off");
				return;
			}
			case "win", "lose", "fair" -> {
				outcome.set(Outcome.valueOf(word.toUpperCase(Locale.ROOT)));
				if (!isEnabled()) setEnabled(true);
				FemboyWare.getConfig().save();
				ChatUtil.info("Casino rounds: " + outcome.getDisplayName());
				return;
			}
			default -> {
			}
		}
		Game chosen = null;
		String wanted = word.replace("-", "").replace("_", "");
		for (Game g : Game.values()) {
			if (g.name().replace("_", "").equalsIgnoreCase(wanted) || g.title.replace("-", "").replace(" ", "").equalsIgnoreCase(wanted)) chosen = g;
		}
		if (chosen == null) {
			ChatUtil.info("No game called " + args[1] + ". Try " + CommandManager.PREFIX + "casino");
			return;
		}
		game.set(chosen);
		if (chosen == Game.PAPER && args.length > 2 && args[2].equalsIgnoreCase("force")) {
			String side = args.length > 3 ? args[3].toLowerCase(Locale.ROOT) : "off";
			paperHigher.set(side.startsWith("l") ? Side.LEFT : side.startsWith("r") ? Side.RIGHT : Side.RANDOM);
			if (!isEnabled()) setEnabled(true);
			FemboyWare.getConfig().save();
			ChatUtil.info(paperHigher.get() == Side.RANDOM ? "Paper: neither dispenser forced" : "Paper: the " + paperHigher.getDisplayName().toLowerCase(Locale.ROOT) + " dispenser gets the higher number");
			return;
		}
		pick.set(args.length > 2 ? args[2] : "");
		if (args.length > 3) bet.set(args[3]);
		if (!isEnabled()) setEnabled(true);
		FemboyWare.getConfig().save();
		ChatUtil.info(chosen.title + ": " + chosen.rules + " You're on " + pick() + " for " + FakeEconomy.money(betAmount()) + ". Power a dispenser while larping to play.");
	}
}
