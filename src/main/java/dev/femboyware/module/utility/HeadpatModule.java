package dev.femboyware.module.utility;

import dev.femboyware.util.Mc;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.StringSetting;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;

/**
 * Right click a player to pat their head: a few gentle swings that everyone sees, hearts over their
 * head and a purr that only you see and hear, and optionally a message to them.
 */
public class HeadpatModule extends Module {
	private static final int PATS = 4, TICKS_PER_PAT = 5;
	private static final long MESSAGE_GAP = 5_000;

	private final BoolSetting message = add(new BoolSetting("Message", "Tell them you patted them", true));
	private final BoolSetting whisper = add(new BoolSetting("Whisper", "Tell them with /msg instead of in public chat", true)).visibleWhen(message::get);
	private final StringSetting messages = add(new StringSetting("Messages", "What to say, separated by ;. {player} is who you patted", "*pats {player}* :3; *headpats {player}*; pat pat {player} :3; *gently pats {player}* good job :3; {player} *pat pat*; *ruffles {player}'s hair* :3")).visibleWhen(message::get);
	private final BoolSetting hearts = add(new BoolSetting("Hearts", "Hearts over their head, only you see them", true));
	private final BoolSetting purr = add(new BoolSetting("Purr", "A soft purr, only you hear it", true));

	private final Random random = new Random();
	private Player patting;
	private int patTicks;
	private long lastMessage;
	private String last;

	public HeadpatModule() {
		super("Headpat", "Right click a player to pat their head :3", Category.MISC);
	}

	@Override
	protected void onDisable() {
		patting = null;
	}

	@Override
	public void onTick() {
		// Runs before vanilla reads the click, so taking it here stops the right click doing anything else
		if (Mc.screen() == null && mc.hitResult instanceof EntityHitResult hit && hit.getEntity() instanceof Player player && player.isAlive()) {
			boolean clicked = false;
			while (mc.options.keyUse.consumeClick()) clicked = true;
			if (clicked && patting == null) startPat(player);
		}
		if (patting == null) return;
		if (patting.isRemoved() || !patting.isAlive() || mc.player.distanceTo(patting) > 6) {
			patting = null;
			return;
		}
		if (patTicks % TICKS_PER_PAT == 0) pat();
		if (++patTicks >= PATS * TICKS_PER_PAT) patting = null;
	}

	private void startPat(Player player) {
		patting = player;
		patTicks = 0;
		if (purr.get()) mc.level.playLocalSound(player.getX(), player.getY(), player.getZ(), Mc.catSound(Mc.CatSound.PURR), SoundSource.PLAYERS, 1f, 1f, false);
		long now = System.currentTimeMillis();
		if (message.get() && now - lastMessage >= MESSAGE_GAP) {
			String line = pick(player.getGameProfile().name());
			if (line != null) {
				lastMessage = now;
				if (whisper.get()) mc.getConnection().sendCommand("msg " + player.getGameProfile().name() + " " + line);
				else mc.getConnection().sendChat(line);
			}
		}
	}

	/** One pat: a swing, and a heart or two popping up off their head. */
	private void pat() {
		mc.player.swing(InteractionHand.MAIN_HAND);
		if (!hearts.get()) return;
		double top = patting.getY() + patting.getBbHeight() + 0.2;
		for (int i = 0; i < 1 + random.nextInt(2); i++) {
			mc.level.addParticle(ParticleTypes.HEART, patting.getX() + random.nextGaussian() * 0.25, top + random.nextDouble() * 0.2, patting.getZ() + random.nextGaussian() * 0.25, 0, 0.05, 0);
		}
	}

	/** A random line with their name in, never the same one twice in a row. */
	private String pick(String name) {
		List<String> options = new ArrayList<>();
		for (String line : messages.get().split(";")) {
			if (!line.isBlank()) options.add(line.trim());
		}
		if (options.size() > 1) options.remove(last);
		if (options.isEmpty()) return null;
		last = options.get(random.nextInt(options.size()));
		return last.replace("{player}", name);
	}
}
