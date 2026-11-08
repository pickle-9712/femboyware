package dev.femboyware.module.utility;

import dev.femboyware.util.Mc;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.setting.StringSetting;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.client.KeyMapping;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Walks to a spot next to a player and rocks forward and backward into them, using the real
 * movement keys so it moves exactly like a player would. Optionally says cute things while at it.
 * The target is set by name in the config; blank means the nearest player.
 */
public class AutoHugModule extends Module {
	/** Where around them to stand, as degrees clockwise from directly behind. */
	public enum Side {
		BEHIND(0), BEHIND_RIGHT(45), RIGHT(90), FRONT_RIGHT(135), FRONT(180), FRONT_LEFT(225), LEFT(270), BEHIND_LEFT(315), CUSTOM(0);

		final double angle;

		Side(double angle) {
			this.angle = angle;
		}
	}

	/** How close a key has to get you before it lets go, so you don't jitter on the spot. */
	private static final double DEADZONE = 0.06;

	private final StringSetting target = add(new StringSetting("Target", "Who to hug, blank for the nearest player", ""));
	private final EnumSetting<Side> side = add(new EnumSetting<>("Side", "Where around them to hug from", Side.BEHIND));
	private final NumberSetting angle = add(new NumberSetting("Angle", "Degrees clockwise from behind them", 0, 0, 345, 15)).visibleWhen(() -> side.is(Side.CUSTOM));
	private final NumberSetting range = add(new NumberSetting("Range", "How far away a target can be", 8, 2, 32, 0.5));
	private final NumberSetting speed = add(new NumberSetting("Speed", "Hugs per second (you can only walk so fast, so high speeds go shallower)", 1.5, 0.25, 5, 0.25));
	private final NumberSetting distance = add(new NumberSetting("Distance", "How close you get to them", 0.6, 0.2, 3, 0.05));
	private final NumberSetting depth = add(new NumberSetting("Depth", "How far you move forward and back", 0.35, 0.05, 1.5, 0.05));
	private final BoolSetting face = add(new BoolSetting("Face Them", "Turn to look at them", true));
	private final BoolSetting message = add(new BoolSetting("Message", "Say things while hugging", true));
	private final BoolSetting whisper = add(new BoolSetting("Whisper", "Say them with /msg instead of in public chat", false)).visibleWhen(message::get);
	private final NumberSetting delay = add(new NumberSetting("Message Delay", "Seconds between messages", 8, 3, 60, 1)).visibleWhen(message::get);
	private final StringSetting messages = add(new StringSetting("Messages", "What to say, separated by ;. {player} is who you're hugging",
		"*hugs {player}* :3; *squeezes {player}*; {player} you're so warm :3; *nuzzles {player}*; never letting go of {player} :3; *snuggles into {player}*; huggies for {player} :3; *holds {player} tight*")).visibleWhen(message::get);

	private final Random random = new Random();
	private final List<KeyMapping> held = new ArrayList<>();
	private double phase;
	private long nextMessage;
	private String last;

	public AutoHugModule() {
		super("Auto Hug", "Walks up and hugs a player, forward and back", Category.MISC);
	}

	@Override
	protected void onEnable() {
		phase = 0;
		nextMessage = 0;
	}

	@Override
	protected void onDisable() {
		release();
	}

	@Override
	public void onTick() {
		if (nullCheck()) return;
		Player hugging = Mc.screen() == null ? findTarget() : null;
		if (hugging == null) {
			release();
			return;
		}

		// One full forward-and-back per hug, 20 ticks a second
		phase += speed.get() * Math.PI * 2 / 20;
		if (phase > Math.PI * 2) phase -= Math.PI * 2;

		// The direction from them out to our spot: straight behind them, turned by the side's angle
		double degrees = side.is(Side.CUSTOM) ? angle.get() : side.get().angle;
		double yaw = Math.toRadians(hugging.getVisualRotationYInDegrees() + 180 + degrees);
		Vec3 out = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
		double back = distance.get() + depth.get() * (0.5 - 0.5 * Math.cos(phase));
		Vec3 goal = hugging.position().add(out.scale(back));

		Vec3 pos = mc.player.position();
		double dx = goal.x - pos.x, dz = goal.z - pos.z;
		double away = Math.sqrt(dx * dx + dz * dz);

		if (face.get()) {
			Vec3 to = hugging.position().subtract(pos);
			if (to.horizontalDistanceSqr() > 1e-4) {
				float look = (float) Math.toDegrees(Math.atan2(-to.x, to.z));
				// Ease round rather than snapping, it looks more human
				float current = mc.player.getYRot();
				mc.player.setYRot(current + Mth.clamp(Mth.wrapDegrees(look - current), -30, 30));
			}
		}

		// Split the way to the spot into forward/back and left/right, relative to where we face
		double ownYaw = Math.toRadians(mc.player.getYRot());
		double sin = Math.sin(ownYaw), cos = Math.cos(ownYaw);
		double forward = -dx * sin + dz * cos;
		double left = dx * cos + dz * sin;
		press(mc.options.keyUp, forward > DEADZONE);
		press(mc.options.keyDown, forward < -DEADZONE);
		press(mc.options.keyLeft, left > DEADZONE);
		press(mc.options.keyRight, left < -DEADZONE);
		// Sprint to catch up when far, walk once close so the rocking stays gentle
		press(mc.options.keySprint, away > 3);
		if (away <= 3) mc.player.setSprinting(false);
		// Hop up blocks that are in the way
		press(mc.options.keyJump, mc.player.horizontalCollision && mc.player.onGround());

		if (message.get() && away < 1.5) talk(hugging);
	}

	private void talk(Player hugging) {
		long now = System.currentTimeMillis();
		if (now < nextMessage) return;
		// A little jitter on the delay so it doesn't look like clockwork
		nextMessage = now + (long) (delay.get() * 1000 * (0.8 + random.nextDouble() * 0.4));
		String name = hugging.getGameProfile().name();
		String line = pick(name);
		if (line == null) return;
		if (whisper.get()) mc.getConnection().sendCommand("msg " + name + " " + line);
		else mc.getConnection().sendChat(line);
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

	private void press(KeyMapping key, boolean down) {
		key.setDown(down);
		if (down) {
			if (!held.contains(key)) held.add(key);
		} else {
			held.remove(key);
		}
	}

	private void release() {
		for (KeyMapping key : held) key.setDown(false);
		held.clear();
	}

	private Player findTarget() {
		String name = target.get().trim();
		Player best = null;
		double bestDist = range.get();
		for (Player player : mc.level.players()) {
			if (player == mc.player || !player.isAlive()) continue;
			if (!name.isEmpty() && !player.getGameProfile().name().equalsIgnoreCase(name)) continue;
			double dist = mc.player.distanceTo(player);
			if (dist <= bestDist) {
				best = player;
				bestDist = dist;
			}
		}
		return best;
	}
}
