/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LiquidBounce is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LiquidBounce. If not, see <https://www.gnu.org/licenses/>.
 *
 * Modified for femboyware: ported from Kotlin (Clicker, ClickPlan, HumanClickTiming,
 * ConstantClickTiming) to Java. Settings are flattened onto the owning module, and the sharing of
 * clicks between several clickers on one key and with the player's own presses is left out.
 */
package dev.femboyware.lb;

import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.setting.Setting;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * LiquidBounce's click scheduler: plans click times a second ahead from a CPS range, either
 * human-like (log-normal intervals around a CPS picked per combo) or constant, and says how many
 * clicks fall into each tick.
 */
public final class ClickScheduler {
	public enum Technique { HUMAN, CONSTANT }

	private static final long TICK_MS = 50L, HORIZON_MS = 1000L, IDLE_MS = 250L;
	private static final int HISTORY = 32;
	private static final long NONE = Long.MIN_VALUE;
	private static final double SIGMA = 0.45;

	private final EnumSetting<Technique> technique;
	private final NumberSetting cpsMin, cpsMax, maxPerTick;

	private final Random random = new Random();
	private final List<Long> times = new ArrayList<>();
	private int next, due, consumed;
	private long comboStart, tickTime, idleSince = NONE;
	private double comboCps;
	private int planMin = -1, planMax = -1, planPerTick = -1;

	@SuppressWarnings("unchecked")
	public ClickScheduler(Function<Setting<?>, Setting<?>> add, BooleanSupplier visible) {
		technique = (EnumSetting<Technique>) add.apply(new EnumSetting<>("Click Technique", "Human varies the gaps like a real hand; Constant clicks evenly", Technique.HUMAN).visibleWhen(visible));
		cpsMin = (NumberSetting) add.apply(new NumberSetting("CPS Min", "Fewest clicks per second", 11, 1, 30, 1).visibleWhen(visible));
		cpsMax = (NumberSetting) add.apply(new NumberSetting("CPS Max", "Most clicks per second", 14, 1, 30, 1).visibleWhen(visible));
		maxPerTick = (NumberSetting) add.apply(new NumberSetting("Max Per Tick", "Most clicks in one tick", 2, 1, 5, 1).visibleWhen(visible));
	}

	private int lo() {
		return Math.min(cpsMin.getInt(), cpsMax.getInt());
	}

	private int hi() {
		return Math.max(cpsMin.getInt(), cpsMax.getInt());
	}

	/** Once per game tick, before asking for clicks. */
	public void tick() {
		long now = System.currentTimeMillis();
		if (planMin != lo() || planMax != hi() || planPerTick != maxPerTick.getInt()) {
			planMin = lo();
			planMax = hi();
			planPerTick = maxPerTick.getInt();
			replan();
		}
		if (consumed > 0) idleSince = NONE;
		else if (due > 0 && idleSince == NONE) idleSince = tickTime;
		if (times.isEmpty() || now - tickTime > IDLE_MS || idleSince != NONE && now - idleSince > IDLE_MS) reset(now);
		long expected = tickTime + TICK_MS;
		tickTime = Math.abs(now - expected) > TICK_MS ? now : expected;
		planUntil(tickTime + HORIZON_MS);
		int count = 0;
		while (next < times.size() && times.get(next) <= tickTime) {
			next++;
			count++;
		}
		due = Math.min(count, maxPerTick.getInt());
		consumed = 0;
		int excess = next - HISTORY;
		if (excess > 0) {
			times.subList(0, excess).clear();
			next -= excess;
		}
	}

	/** Clicks due this tick that haven't been taken yet. */
	public int clicksLeft() {
		return Math.max(0, due - consumed);
	}

	/** Takes one of this tick's clicks; false when there are none left. */
	public boolean take() {
		if (consumed >= due) return false;
		consumed++;
		return true;
	}

	/** Whether {@code tick} ticks from now has a click planned (0 is this tick). */
	public boolean willClickAt(int tick) {
		if (tick <= 0) return due > 0;
		long from = tickTime + (tick - 1) * TICK_MS, to = from + TICK_MS;
		planUntil(to);
		for (int i = next; i < times.size(); i++) {
			long t = times.get(i);
			if (t > to) break;
			if (t > from) return true;
		}
		return false;
	}

	private void reset(long now) {
		times.clear();
		next = due = consumed = 0;
		idleSince = NONE;
		comboStart = now;
		tickTime = now - TICK_MS;
		times.add(now);
		comboCps = 0;
	}

	private void replan() {
		int keep = Math.max(next, 1);
		if (times.size() > keep) times.subList(keep, times.size()).clear();
		comboCps = 0;
	}

	private void planUntil(long time) {
		if (times.isEmpty()) reset(System.currentTimeMillis());
		while (times.get(times.size() - 1) <= time) {
			long last = times.get(times.size() - 1);
			long t = last + interval(last - comboStart);
			int per = maxPerTick.getInt();
			if (times.size() >= per) t = Math.max(t, times.get(times.size() - per) + TICK_MS);
			times.add(t);
		}
	}

	private long interval(long comboMs) {
		if (technique.is(Technique.CONSTANT)) return Math.round(1000.0 / hi());
		if (comboMs == 0L || comboCps == 0) comboCps = lo() + random.nextDouble() * (hi() - lo());
		double mu = Math.log(1000.0 / comboCps) - SIGMA * SIGMA / 2;
		return Mth.clamp(Math.round(Math.exp(mu + SIGMA * random.nextGaussian())), 10L, 1000L);
	}
}
