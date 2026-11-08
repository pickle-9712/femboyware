package dev.femboyware.module.combat;

import dev.femboyware.module.combat.GuardCommandModule.GuardStatus;
import dev.femboyware.util.ChatUtil;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * Runs team fights between the guards from the principal's game: picks teams, lines them up where
 * you're standing, starts each round, sees who's left from their status reports, and keeps score and
 * play-money bets. The guards are only ever told where to stand, who's on which side and when to go.
 */
public class BotArena {
	public enum Phase { OFF, LINING_UP, FIGHTING, BETWEEN }

	public static final String PINK = "Pink", BLUE = "Blue";
	private static final long LINE_UP_TIME = 8_000;
	private static final long BETWEEN_TIME = 6_000;
	private static final long ROUND_TIME = 120_000;
	/** Reports take a moment to arrive, so nobody counts as out straight after a round starts. */
	private static final long COUNT_AFTER = 3_000;
	private static final int STARTING_COINS = 100;

	private final GuardCommandModule command;
	private Phase phase = Phase.OFF;
	private long phaseStarted;
	private List<String> pink = List.of(), blue = List.of();
	private int pinkScore, blueScore, round, firstTo;
	private Vec3 center;
	private double radius;
	/** Who's died this round and been dropped from it, so the other side stops going after them once they respawn. */
	private final Set<String> out = new HashSet<>();
	private int coins = STARTING_COINS;
	private String betOn;
	private int bet;

	BotArena(GuardCommandModule command) {
		this.command = command;
	}

	public Phase phase() {
		return phase;
	}

	public int pinkScore() {
		return pinkScore;
	}

	public int blueScore() {
		return blueScore;
	}

	public int round() { 
		return round;
	}

	public int firstTo() {
		return firstTo;
	}

	public int coins() {
		return coins;
	}

	/** The current bet as text, or null. */
	public String betText() {
		return betOn == null ? null : bet + " on " + betOn;
	}

	public int pinkAlive() {
		return alive(pink);
	}

	public int blueAlive() {
		return alive(blue);
	}

	public int pinkSize() {
		return pink.size();
	}

	public int blueSize() {
		return blue.size();
	}

	void start(int rounds, double fightRadius) {
		List<String> names = new ArrayList<>();
		for (GuardStatus guard : command.guards()) {
			if (guard.age() <= GuardCommandModule.STATUS_LOST) names.add(guard.name());
		}
		if (names.size() < 2) {
			ChatUtil.info("The arena needs at least 2 connected guards");
			return;
		}
		Collections.shuffle(names);
		int half = names.size() / 2;
		pink = List.copyOf(names.subList(0, half));
		blue = List.copyOf(names.subList(half, names.size()));
		center = Minecraft.getInstance().player.position();
		radius = fightRadius;
		firstTo = rounds;
		pinkScore = blueScore = round = 0;
		ChatUtil.info(PINK + ": " + String.join(", ", pink));
		ChatUtil.info(BLUE + ": " + String.join(", ", blue));
		ChatUtil.info("First to " + rounds + ". Bet with .g bet <pink|blue> <coins>; you have " + coins);
		lineUp();
	}

	void stop() {
		if (phase == Phase.OFF) return;
		phase = Phase.OFF;
		command.sendToGuards("arena end");
		refundBet();
	}

	private void lineUp() {
		round++;
		out.clear();
		enter(Phase.LINING_UP);
		command.sendToGuards(String.format(Locale.ROOT, "arena setup %.2f %.2f %.2f %.1f %s %s", center.x, center.y, center.z, radius, String.join(",", pink), String.join(",", blue)));
	}

	private void enter(Phase next) {
		phase = next;
		phaseStarted = System.currentTimeMillis();
	}

	void tick() {
		if (phase == Phase.OFF) return;
		long elapsed = System.currentTimeMillis() - phaseStarted;
		switch (phase) {
			case LINING_UP -> {
				if (elapsed >= LINE_UP_TIME) {
					command.sendToGuards("arena fight");
					enter(Phase.FIGHTING);
					ChatUtil.info("Round " + round + ": fight!");
				}
			}
			case FIGHTING -> {
				if (elapsed < COUNT_AFTER) return;
				for (GuardStatus guard : command.guards()) {
					if (guard.state().equals("Out") && out.add(guard.name())) command.sendToGuards("arena out " + guard.name());
				}
				int pinkLeft = pinkAlive(), blueLeft = blueAlive();
				if (pinkLeft == 0 && blueLeft == 0 || elapsed >= ROUND_TIME && pinkLeft == blueLeft) endRound(null);
				else if (blueLeft == 0 || elapsed >= ROUND_TIME && pinkLeft > blueLeft) endRound(PINK);
				else if (pinkLeft == 0 || elapsed >= ROUND_TIME) endRound(BLUE);
			}
			case BETWEEN -> {
				if (elapsed >= BETWEEN_TIME) lineUp();
			}
			default -> {
			}
		}
	}

	private void endRound(String winner) {
		if (PINK.equals(winner)) pinkScore++;
		else if (BLUE.equals(winner)) blueScore++;
		ChatUtil.info((winner == null ? "Round " + round + " is a draw" : winner + " wins round " + round) + " (" + PINK + " " + pinkScore + " - " + blueScore + " " + BLUE + ")");
		settleBet(winner);
		if (pinkScore >= firstTo || blueScore >= firstTo) {
			ChatUtil.info((pinkScore > blueScore ? PINK : BLUE) + " wins the match! You have " + coins + " coins");
			phase = Phase.OFF;
			command.sendToGuards("arena end");
		} else enter(Phase.BETWEEN);
	}

	private void settleBet(String winner) {
		if (betOn == null) return;
		if (winner == null) refundBet();
		else {
			if (betOn.equals(winner)) {
				coins += bet * 2;
				ChatUtil.info("You won " + bet + " coins!");
			} else ChatUtil.info("You lost " + bet + " coins");
			betOn = null;
		}
		// Going broke shouldn't end the fun; it's play money
		if (coins == 0) coins = STARTING_COINS;
	}

	private void refundBet() {
		if (betOn != null) coins += bet;
		betOn = null;
	}

	/** Bets are placed before a round starts: {@code bet <pink|blue> <coins>}. */
	void bet(String[] words) {
		if (phase == Phase.OFF || phase == Phase.FIGHTING) {
			ChatUtil.info("Bets are open between rounds and while teams line up");
			return;
		}
		if (betOn != null) {
			ChatUtil.info("You already bet " + betText());
			return;
		}
		String team = words.length > 1 ? words[1].toLowerCase(Locale.ROOT) : "";
		String side = team.startsWith("p") ? PINK : team.startsWith("b") ? BLUE : null;
		int amount;
		try {
			amount = words.length > 2 ? Integer.parseInt(words[2]) : 0;
		} catch (NumberFormatException e) {
			amount = 0;
		}
		if (side == null || amount <= 0 || amount > coins) {
			ChatUtil.info("Usage: .g bet <pink|blue> <coins>; you have " + coins);
			return;
		}
		coins -= amount;
		bet = amount;
		betOn = side;
		ChatUtil.info("Bet " + betText());
	}

	private int alive(List<String> team) {
		Predicate<GuardStatus> standing = guard -> team.contains(guard.name()) && guard.age() <= GuardCommandModule.STATUS_LOST && !guard.state().equals("Out") && guard.health() > 0;
		return (int) command.guards().stream().filter(standing).count();
	}
}
