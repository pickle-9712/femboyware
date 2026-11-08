package dev.femboyware.module.utility;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.setting.StringSetting;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * Moans in chat when you take damage, with a cooldown so a long fight doesn't flood chat or get you
 * kicked. Messages with {player} name whoever hit you, so they're only used when a player did and the
 * game says who.
 */
public class AutoMoanModule extends Module {
	/** A hit's attacker only applies to a moan this soon after it, in milliseconds. */
	private static final long ATTACKER_FRESH = 1000;

	private final StringSetting messages = add(new StringSetting("Messages", "Messages separated by ;. Any with {player} are only used when a player hit you", "ahh~; mmh~ >_<; nyaa~ that hurt; hnngh~; ow~ uwu; ahh~ be gentle; mmf~"));
	private final StringSetting playerMessages = add(new StringSetting("Player Messages", "Messages for when a player hit you, separated by ;. {player} is their name", "owww that hurt {player}; stop {player} that hurts!; {player} be gentle >_<; ow {player} why :(; nyaa~ {player} stop it"));
	private final NumberSetting playerChance = add(new NumberSetting("Player Message Chance", "When a player hit you, percent of moans that come from Player Messages", 70, 0, 100, 5));
	private final NumberSetting cooldown = add(new NumberSetting("Cooldown", "Fewest seconds between moans, so servers don't kick for spam", 3, 0.5, 30, 0.5));
	private final NumberSetting chance = add(new NumberSetting("Chance", "Percent of hits that get a moan", 100, 1, 100, 1));

	private final Random random = new Random();
	private int lastHurtTime;
	private long lastSent;
	/** Who last hit us, and when; only trusted right after the hit. */
	private String attacker;
	private long attackedAt;

	public AutoMoanModule() {
		super("Auto Moan", "Moans in chat when you take damage", Category.MISC);
	}

	/** Called for every entity the client sees take damage. Arrows and the like count as whoever fired them. */
	public void onDamaged(LivingEntity victim, DamageSource source) {
		if (!isEnabled() || victim != mc.player) return;
		attacker = source.getEntity() instanceof Player player && player != mc.player ? player.getGameProfile().name() : null;
		attackedAt = System.currentTimeMillis();
	}

	@Override
	public void onTick() {
		// hurtTime jumps up the moment a hit lands, then counts down
		int hurtTime = mc.player.hurtTime;
		boolean hit = hurtTime > lastHurtTime;
		lastHurtTime = hurtTime;
		if (!hit || mc.player.isDeadOrDying()) return;
		long now = System.currentTimeMillis();
		if (now - lastSent < cooldown.get() * 1000 || random.nextInt(100) >= chance.getInt()) return;

		String name = now - attackedAt <= ATTACKER_FRESH ? attacker : null;
		String message = null;
		if (name != null && random.nextInt(100) < playerChance.getInt()) message = pick(playerMessages.get(), name);
		if (message == null) message = pick(messages.get(), name);
		if (message == null) return;
		mc.getConnection().sendChat(message);
		lastSent = now;
	}

	/** A random message from a ;-separated list. Those with {player} name {@code name}, and are left out if there's no name. */
	private String pick(String list, String name) {
		List<String> options = new ArrayList<>();
		for (String message : list.split(";")) {
			if (!message.isBlank() && (name != null || !message.contains("{player}"))) options.add(message.trim());
		}
		if (options.isEmpty()) return null;
		String message = options.get(random.nextInt(options.size()));
		return name == null ? message : message.replace("{player}", name);
	}
}
