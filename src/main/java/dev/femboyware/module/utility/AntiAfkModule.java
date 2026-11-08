package dev.femboyware.module.utility;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.setting.StringSetting;
import dev.femboyware.util.Rotation;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.world.InteractionHand;

/**
 * Jumps, swings, sneaks, strafes, spins and chats so the server doesn't kick you for idling.
 * Ported from Meteor Client's AntiAFK (https://github.com/MeteorDevelopment/meteor-client), GPL-3.0.
 */
public class AntiAfkModule extends Module {
	/** Low, so any combat module's rotation wins over spinning. */
	private static final int ROTATION_PRIORITY = 1;

	public enum SpinMode { SERVER, CLIENT }

	private final BoolSetting jump = add(new BoolSetting("Jump", "Jump now and then", true));
	private final BoolSetting swing = add(new BoolSetting("Swing", "Swing your hand now and then", false));
	private final BoolSetting sneak = add(new BoolSetting("Sneak", "Sneak and unsneak", false));
	private final NumberSetting sneakTime = add(new NumberSetting("Sneak Time", "Ticks to stay sneaking", 5, 1, 40, 1)).visibleWhen(sneak::get);
	private final BoolSetting strafe = add(new BoolSetting("Strafe", "Strafe left and right", false));
	private final BoolSetting spin = add(new BoolSetting("Spin", "Turn in place", true));
	private final EnumSetting<SpinMode> spinMode = add(new EnumSetting<>("Spin Mode", "Server only turns what the server sees, Client turns your camera", SpinMode.SERVER)).visibleWhen(spin::get);
	private final NumberSetting spinSpeed = add(new NumberSetting("Spin Speed", "Degrees per tick", 7, 1, 90, 1)).visibleWhen(spin::get);
	private final NumberSetting pitch = add(new NumberSetting("Pitch", "Pitch sent while spinning", 0, -90, 90, 1)).visibleWhen(() -> spin.get() && spinMode.is(SpinMode.SERVER));
	private final BoolSetting sendMessages = add(new BoolSetting("Messages", "Send chat messages", false));
	private final BoolSetting randomMessage = add(new BoolSetting("Random Message", "Pick messages at random instead of in order", false)).visibleWhen(sendMessages::get);
	private final NumberSetting messageDelay = add(new NumberSetting("Message Delay", "Seconds between messages", 15, 1, 300, 1)).visibleWhen(sendMessages::get);
	private final StringSetting messages = add(new StringSetting("Message List", "Messages separated by ;", "femboyware on top!")).visibleWhen(sendMessages::get);

	private final Random random = new Random();
	private int messageTimer;
	private int messageIndex;
	private int sneakTimer;
	private int strafeTimer;
	private boolean strafeRight;
	private float yaw;

	public AntiAfkModule() {
		super("Anti Afk", "Moves around so you don't get kicked for being idle", Category.PLAYER);
	}

	@Override
	protected void onEnable() {
		if (!nullCheck()) yaw = mc.player.getYRot();
		messageTimer = messageDelay.getInt() * 20;
	}

	@Override
	protected void onDisable() {
		if (strafe.get()) {
			mc.options.keyLeft.setDown(false);
			mc.options.keyRight.setDown(false);
		}
		if (sneak.get()) mc.options.keyShift.setDown(false);
	}

	@Override
	public void onTick() {
		if (jump.get()) {
			if (mc.options.keyJump.isDown()) mc.options.keyJump.setDown(false);
			else if (random.nextInt(99) == 0) mc.options.keyJump.setDown(true);
		}
		if (swing.get() && random.nextInt(99) == 0) mc.player.swing(InteractionHand.MAIN_HAND);
		if (sneak.get()) {
			if (sneakTimer++ >= sneakTime.getInt()) {
				mc.options.keyShift.setDown(false);
				// Sneak again after about five seconds
				if (random.nextInt(99) == 0) sneakTimer = 0;
			} else mc.options.keyShift.setDown(true);
		}
		if (strafe.get() && strafeTimer-- <= 0) {
			mc.options.keyLeft.setDown(!strafeRight);
			mc.options.keyRight.setDown(strafeRight);
			strafeRight = !strafeRight;
			strafeTimer = 20;
		}
		if (spin.get() && spinMode.is(SpinMode.CLIENT)) {
			yaw += spinSpeed.getFloat();
			mc.player.setYRot(yaw);
		}
		if (sendMessages.get() && messageTimer-- <= 0) {
			List<String> list = messageList();
			if (!list.isEmpty()) {
				messageIndex = randomMessage.get() ? random.nextInt(list.size()) : (messageIndex + 1) % list.size();
				mc.getConnection().sendChat(list.get(messageIndex));
			}
			messageTimer = messageDelay.getInt() * 20;
		}
	}

	@Override
	public void onPreMotion() {
		if (!spin.get() || !spinMode.is(SpinMode.SERVER)) return;
		yaw += spinSpeed.getFloat();
		FemboyWare.getRotations().request(new Rotation(yaw, pitch.getFloat()), ROTATION_PRIORITY, 180, false, false);
	}

	private List<String> messageList() {
		List<String> list = new ArrayList<>();
		for (String message : messages.get().split(";")) {
			if (!message.isBlank()) list.add(message.trim());
		}
		return list;
	}
}
