package dev.femboyware.module.utility;

import dev.femboyware.gui.GoliathPickerScreen;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.util.ChatUtil;
import dev.femboyware.util.DonutGoliaths;
import dev.femboyware.util.Mc;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.world.level.Level;

/**
 * DonutSMP: opens a map of every goliath; pick as many as you like and it keeps using /rtp (in the
 * region most of your picks are in) until you land on one of them. Ported from the Anubis
 * client's Goliath RTP (GPL-3.0, by 4ldenz, https://github.com/4ldenz/Anubis-client).
 */
public class GoliathRtpModule extends Module {
	/** Which region to /rtp to next when the one you're in has none of your picks. */
	private static final int[] HOP = {1, 0, 0, 4, 3, 6, 5};
	private static final int SETTLE_TICKS = 15, RESEND_TICKS = 100;
	private static final double LANDED_SQ = 10_000;

	private static Set<DonutGoliaths.Goliath> lastPick = Set.of();
	private Set<DonutGoliaths.Goliath> targets;
	private String pending;
	private int sinceSent, settle, sent;
	private double lastX, lastZ;

	public GoliathRtpModule() {
		super("Goliath RTP", "Pick goliaths on a map and it /rtps until you land on one", Category.MISC);
	}

	@Override
	protected void onEnable() {
		targets = null;
		pending = null;
		if (mc.player == null || mc.getConnection() == null) {
			ChatUtil.info("Goliath RTP: join the server first");
			setEnabled(false);
			return;
		}
		mc.execute(() -> {
			if (isEnabled()) Mc.setScreen(new GoliathPickerScreen(lastPick, this::picked));
		});
	}

	@Override
	protected void onDisable() {
		targets = null;
		pending = null;
	}

	private void picked(Set<DonutGoliaths.Goliath> chosen) {
		if (!isEnabled()) return;
		if (chosen == null || chosen.isEmpty()) {
			setEnabled(false);
			return;
		}
		targets = chosen;
		lastPick = chosen;
		pending = null;
		settle = 0;
		sent = 0;
		if (mc.player != null) {
			lastX = mc.player.getX();
			lastZ = mc.player.getZ();
		}
		if (chosen.size() == 1) {
			DonutGoliaths.Goliath only = chosen.iterator().next();
			String rtp = DonutGoliaths.RTP[only.region()];
			ChatUtil.info("Goliath RTP: going to " + only.label() + (rtp == null ? " (Europe has no /rtp, it'll try nearby regions)" : " with /rtp " + rtp));
		} else {
			Map<String, Integer> perRegion = new TreeMap<>();
			for (DonutGoliaths.Goliath g : chosen) perRegion.merge(DonutGoliaths.REGIONS[g.region()], 1, Integer::sum);
			StringBuilder list = new StringBuilder();
			perRegion.forEach((region, n) -> list.append(list.isEmpty() ? "" : ", ").append(region).append(' ').append(n));
			ChatUtil.info("Goliath RTP: going to any of " + chosen.size() + " goliaths (" + list + ")");
		}
	}

	@Override
	public void onTick() {
		if (targets == null || mc.player == null || mc.level == null || mc.getConnection() == null) return;
		double dx = mc.player.getX() - lastX, dz = mc.player.getZ() - lastZ;
		boolean teleported = dx * dx + dz * dz > LANDED_SQ;
		lastX = mc.player.getX();
		lastZ = mc.player.getZ();
		if (pending != null) {
			if (!teleported) {
				if (++sinceSent >= RESEND_TICKS) send(pending);
				return;
			}
			pending = null;
			settle = SETTLE_TICKS;
		}
		DonutGoliaths.Goliath here = here();
		if (here != null && targets.contains(here)) {
			ChatUtil.info("Goliath RTP: landed on " + here.label() + " after " + sent + " /rtp");
			setEnabled(false);
			return;
		}
		if (settle > 0) {
			settle--;
			return;
		}
		int current = here == null ? -1 : here.region();
		int next = bestRegion(current);
		if (next < 0) next = current >= 0 ? HOP[current] : HOP[0];
		send(DonutGoliaths.RTP[next]);
	}

	private int bestRegion(int current) {
		int[] count = new int[DonutGoliaths.REGIONS.length];
		for (DonutGoliaths.Goliath g : targets) count[g.region()]++;
		int best = -1;
		double bestShare = 0;
		for (int region = 0; region < count.length; region++) {
			if (region == current || count[region] == 0 || DonutGoliaths.RTP[region] == null) continue;
			double share = (double) count[region] / DonutGoliaths.CELLS[region].length;
			if (share > bestShare) {
				best = region;
				bestShare = share;
			}
		}
		return best;
	}

	private void send(String region) {
		mc.getConnection().sendCommand("rtp " + region);
		pending = region;
		sinceSent = 0;
		sent++;
	}

	private DonutGoliaths.Goliath here() {
		if (mc.level.dimension() == Level.END) return null;
		double scale = mc.level.dimension() == Level.NETHER ? 8 : 1;
		return DonutGoliaths.at(mc.player.getX() * scale, mc.player.getZ() * scale);
	}
}
