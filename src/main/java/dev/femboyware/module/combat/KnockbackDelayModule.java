package dev.femboyware.module.combat;

import dev.femboyware.event.PacketEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.module.utility.FriendsModule;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.world.entity.player.Player;

/**
 * Takes some hits' knockback a random moment late: a little in the air, longer on the ground, so
 * you stay in a fight without the flat look of Velocity. Ported from the Anubis client's Knockback
 * Delay (GPL-3.0, by 4ldenz, https://github.com/4ldenz/Anubis-client).
 */
public class KnockbackDelayModule extends Module {
	private static final double FIGHT_RANGE = 6;

	private final NumberSetting chance = add(new NumberSetting("Chance", "Percent of hits to delay", 40, 0, 100, 5));
	private final NumberSetting airMin = add(new NumberSetting("Air Min", "Shortest delay in the air (ms)", 50, 0, 500, 10));
	private final NumberSetting airMax = add(new NumberSetting("Air Max", "Longest delay in the air (ms)", 100, 0, 500, 10));
	private final NumberSetting groundMin = add(new NumberSetting("Ground Min", "Shortest delay on the ground (ms)", 200, 0, 1000, 10));
	private final NumberSetting groundMax = add(new NumberSetting("Ground Max", "Longest delay on the ground (ms)", 250, 0, 1000, 10));
	private final BoolSetting notInWater = add(new BoolSetting("Not In Water", "Leave knockback alone while you're in water", false));
	private final BoolSetting onlyFights = add(new BoolSetting("Only Near Enemies", "Only while another player is within 6 blocks", true));

	private record Held(Packet<?> packet, long due) {
	}

	private final Deque<Held> held = new ArrayDeque<>();
	private ClientPacketListener heldFor;
	private volatile boolean fightNear;

	public KnockbackDelayModule() {
		super("Knockback Delay", "Takes some knockback a random moment late", Category.ATTACK);
	}

	@Override
	protected void onEnable() {
		synchronized (held) {
			held.clear();
		}
	}

	@Override
	protected void onDisable() {
		mc.execute(() -> apply(true));
	}

	@Override
	public void onTick() {
		fightNear = mc.player != null && enemyNear(mc.player);
		apply(false);
	}

	@Override
	public void onPacketReceive(PacketEvent event) {
		LocalPlayer player = mc.player;
		if (player == null || !pushesMe(event.getPacket(), player)) return;
		boolean waiting;
		synchronized (held) {
			waiting = !held.isEmpty();
		}
		// Once one is held, everything after it waits too so they stay in order
		if (!waiting && !delays(player)) return;
		boolean ground = player.onGround();
		long wait = waiting ? 0 : between(ground ? groundMin.get() : airMin.get(), ground ? groundMax.get() : airMax.get());
		synchronized (held) {
			if (held.isEmpty()) heldFor = player.connection;
			long due = Math.max(System.currentTimeMillis() + wait, held.isEmpty() ? 0 : held.peekLast().due());
			held.addLast(new Held(event.getPacket(), due));
		}
		event.cancel();
	}

	private static boolean pushesMe(Packet<?> packet, LocalPlayer player) {
		//? if >=26.1 {
		/*if (packet instanceof ClientboundSetEntityMotionPacket motion) return motion.id() == player.getId();
		*///?} else {
		if (packet instanceof ClientboundSetEntityMotionPacket motion) return motion.getId() == player.getId();
		//?}
		return packet instanceof ClientboundExplodePacket explode && explode.playerKnockback().isPresent();
	}

	private boolean delays(LocalPlayer player) {
		if (notInWater.get() && player.isInWater()) return false;
		if (onlyFights.get() && !fightNear) return false;
		return ThreadLocalRandom.current().nextDouble(100) < chance.get();
	}

	private boolean enemyNear(LocalPlayer player) {
		for (Player other : player.level().players())
			if (other != player && other.distanceTo(player) <= FIGHT_RANGE && !FriendsModule.protects(other)) return true;
		return false;
	}

	private static long between(double a, double b) {
		long low = Math.round(Math.min(a, b)), high = Math.round(Math.max(a, b));
		return high <= low ? low : ThreadLocalRandom.current().nextLong(low, high + 1);
	}

	@SuppressWarnings("unchecked")
	private void apply(boolean all) {
		ClientPacketListener connection = mc.getConnection();
		long now = System.currentTimeMillis();
		while (true) {
			Held next;
			synchronized (held) {
				next = held.peekFirst();
				if (next == null || !all && next.due() > now) return;
				held.pollFirst();
				if (connection == null || connection != heldFor) {
					held.clear();
					return;
				}
			}
			((Packet<PacketListener>) next.packet()).handle(connection);
		}
	}
}
