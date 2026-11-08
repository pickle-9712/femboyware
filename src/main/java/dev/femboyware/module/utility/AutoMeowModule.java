package dev.femboyware.module.utility;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.module.combat.AutoCrystalModule;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.setting.StringSetting;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * Meows in chat :3 Every so often on a random timer, and after you kill someone.
 *
 * <p>A kill is a player you hit, or Auto Crystal was targeting, dying within a few seconds.
 * That's worked out client-side, so it doesn't depend on a server's death message format.
 */
public class AutoMeowModule extends Module {
	/** How long after a hit a death still counts as your kill. */
	private static final long KILL_WINDOW_MILLIS = 10_000;
	/** Letters that sound right stretched: meeeow, mrrrp, nyaaa. */
	private static final String STRETCHY = "eaiouwr";

	private final StringSetting messages = add(new StringSetting("Messages", "Messages separated by ;", "meow :3; mreiow :3; mrrp :3; nya~ :3; mew :3; meow meow :3"));
	private final BoolSetting timed = add(new BoolSetting("Timed", "Meow on a random timer", true));
	private final NumberSetting minDelay = add(new NumberSetting("Min Delay", "Shortest wait between timed meows, in seconds", 30, 5, 600, 5)).visibleWhen(timed::get);
	private final NumberSetting maxDelay = add(new NumberSetting("Max Delay", "Longest wait between timed meows, in seconds", 90, 5, 600, 5)).visibleWhen(timed::get);
	private final BoolSetting onKill = add(new BoolSetting("On Kill", "Meow when you kill a player", true));
	private final StringSetting killMessages = add(new StringSetting("Kill Messages", "Messages after a kill, separated by ;. {player} is who died", "meow :3 gg {player}; mrrp~ sit {player} :3; nya~ {player} :3")).visibleWhen(onKill::get);
	private final NumberSetting killDelay = add(new NumberSetting("Kill Delay", "Most seconds to wait after a kill, picked at random", 1.5, 0, 10, 0.5)).visibleWhen(onKill::get);
	private final BoolSetting variations = add(new BoolSetting("Variations", "Stretch letters now and then, like meeeow or mrrrp", true));
	private final NumberSetting minGap = add(new NumberSetting("Min Gap", "Never send two meows closer than this many seconds, so servers don't kick for spam", 3, 0, 30, 0.5));

	private final Random random = new Random();
	/** Player entity id to when we last hit them. */
	private final Map<Integer, Long> recentTargets = new HashMap<>();
	/**
	 * Player entity ids whose death we already meowed about. Their body stays around for a while
	 * (longer with Tung Sahur) and Auto Crystal keeps targeting it, so without this every tick
	 * would count the same death again.
	 */
	private final Set<Integer> celebrated = new HashSet<>();
	private final List<String> queued = new ArrayList<>();
	private long nextTimed;
	private long nextQueued;
	private long lastSent;
	private int sent;

	public AutoMeowModule() {
		super("Auto Meow", "Meows in chat on a timer and after kills :3", Category.MISC);
	}

	@Override
	public String getInfo() {
		return Integer.toString(sent);
	}

	@Override
	protected void onEnable() {
		scheduleTimed();
		recentTargets.clear();
		celebrated.clear();
		queued.clear();
	}

	@Override
	public void onPostAttack(Entity target) {
		if (target instanceof Player player && player != mc.player && !player.isDeadOrDying()) track(player, System.currentTimeMillis());
	}

	@Override
	public void onTick() {
		long now = System.currentTimeMillis();
		if (onKill.get()) checkKills(now);
		else recentTargets.clear();

		// Kill meows first; they're the fun ones
		if (!queued.isEmpty() && now >= nextQueued && now - lastSent >= minGap.get() * 1000) {
			send(queued.removeFirst());
			return;
		}
		if (timed.get() && now >= nextTimed && now - lastSent >= minGap.get() * 1000) {
			String message = pick(messages.get(), null);
			if (message != null) send(message);
			scheduleTimed();
		}
	}

	private void checkKills(long now) {
		AutoCrystalModule crystal = FemboyWare.getModules().get(AutoCrystalModule.class);
		if (crystal.isEnabled() && crystal.getTarget() instanceof Player target && !target.isDeadOrDying()) track(target, now);
		// Forget a death once its body is gone or back alive; respawned players usually get a new id anyway
		celebrated.removeIf(id -> !(mc.level.getEntity(id) instanceof Player player) || !player.isDeadOrDying());
		recentTargets.entrySet().removeIf(entry -> {
			if (now - entry.getValue() > KILL_WINDOW_MILLIS) return true;
			Entity entity = mc.level.getEntity(entry.getKey());
			if (!(entity instanceof Player player) || !player.isDeadOrDying()) return false;
			if (!celebrated.add(entry.getKey())) return true;
			String message = pick(killMessages.get(), player.getName().getString());
			if (message != null) {
				queued.add(message);
				nextQueued = now + (long) (random.nextDouble() * killDelay.get() * 1000);
			}
			return true;
		});
	}

	private void track(Player player, long now) {
		if (!celebrated.contains(player.getId())) recentTargets.put(player.getId(), now);
	}

	/** A random message from {@code list}, with {player} filled in and maybe stretched. */
	private String pick(String list, String player) {
		List<String> options = new ArrayList<>();
		for (String message : list.split(";")) {
			if (!message.isBlank()) options.add(message.trim());
		}
		if (options.isEmpty()) return null;
		String message = options.get(random.nextInt(options.size()));
		if (player != null) message = message.replace("{player}", player);
		return variations.get() ? stretch(message) : message;
	}

	/** Half the time, repeats one stretchy letter of the first word one to three extra times. */
	private String stretch(String message) {
		if (random.nextBoolean()) return message;
		int wordEnd = message.indexOf(' ');
		if (wordEnd < 0) wordEnd = message.length();
		List<Integer> spots = new ArrayList<>();
		for (int i = 0; i < wordEnd; i++) {
			if (STRETCHY.indexOf(Character.toLowerCase(message.charAt(i))) >= 0) spots.add(i);
		}
		if (spots.isEmpty()) return message;
		int at = spots.get(random.nextInt(spots.size()));
		String extra = String.valueOf(message.charAt(at)).repeat(1 + random.nextInt(3));
		return message.substring(0, at + 1) + extra + message.substring(at + 1);
	}

	private void send(String message) {
		if (message.startsWith("/")) mc.getConnection().sendCommand(message.substring(1));
		else mc.getConnection().sendChat(message);
		lastSent = System.currentTimeMillis();
		sent++;
	}

	private void scheduleTimed() {
		double min = Math.min(minDelay.get(), maxDelay.get());
		double max = Math.max(minDelay.get(), maxDelay.get());
		nextTimed = System.currentTimeMillis() + (long) ((min + random.nextDouble() * (max - min)) * 1000);
	}
}
