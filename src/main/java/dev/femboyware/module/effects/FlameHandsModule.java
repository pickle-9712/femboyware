package dev.femboyware.module.effects;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.FxRender;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Your hands on fire: tongues of flame lick up off them and embers drift away, in third person
 * from where the hands hang and swing, and in first person in front of you where your arm is.
 */
public class FlameHandsModule extends PlayerFxModule {
	public enum Hands { BOTH, MAIN, OFF }

	private final EnumSetting<Hands> hands = add(new EnumSetting<>("Hands", "Which hands burn", Hands.BOTH));
	private final NumberSetting size = add(new NumberSetting("Size", "How big the flames are", 1, 0.3, 2.5, 0.05));
	private final NumberSetting rate = add(new NumberSetting("Rate", "How many flames a second, per hand", 70, 10, 200, 5));
	private final BoolSetting embers = add(new BoolSetting("Embers", "Sparks drifting up off the flames", true));
	private final BoolSetting firstPerson = add(new BoolSetting("First Person", "Flames on your hand in first person too", true));
	private final Palette palette = palette(0xFFFFB13B, 0xFFFF3B2E);
	private final EnumSetting<ShowOn> showOn = showOnSetting();

	private static final class Flame {
		Vec3 pos;
		final Vec3 drift;
		final long born;
		final float life, scale, hue;
		final boolean ember;

		Flame(Vec3 pos, Vec3 drift, long born, float life, float scale, float hue, boolean ember) {
			this.pos = pos;
			this.drift = drift;
			this.born = born;
			this.life = life;
			this.scale = scale;
			this.hue = hue;
			this.ember = ember;
		}
	}

	private final List<Flame> flames = new ArrayList<>();
	private long lastFrame;
	private double owed;

	public FlameHandsModule() {
		super("Flame Hands", "Your hands burn with flame");
	}

	@Override
	protected void onDisable() {
		flames.clear();
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		long now = System.nanoTime();
		float dt = lastFrame == 0 ? 0 : Math.min(0.1f, (now - lastFrame) / 1e9f);
		lastFrame = now;
		float tickDelta = tickDelta();
		ThreadLocalRandom r = ThreadLocalRandom.current();

		// New flames at each burning hand
		owed += rate.get() * dt;
		int spawn = (int) owed;
		owed -= spawn;
		if (spawn > 0) {
			List<Vec3> spots = new ArrayList<>();
			for (Player player : wearers(firstPerson.get())) {
				boolean mine = player == mc.player && mc.options.getCameraType().isFirstPerson();
				for (HumanoidArm arm : HumanoidArm.values()) {
					boolean main = arm == player.getMainArm();
					if (hands.is(Hands.MAIN) && !main || hands.is(Hands.OFF) && main) continue;
					if (mine && !main) continue;
					spots.add(mine ? firstPersonHand(tickDelta) : hand(player, arm, tickDelta));
				}
			}
			for (Vec3 spot : spots) {
				for (int i = 0; i < spawn; i++) {
					Vec3 at = spot.add(r.nextGaussian() * 0.035, r.nextGaussian() * 0.03, r.nextGaussian() * 0.035);
					boolean ember = embers.get() && r.nextFloat() < 0.08f;
					Vec3 drift = new Vec3(r.nextGaussian() * 0.06, 0.35 + r.nextDouble() * 0.35 + (ember ? 0.4 : 0), r.nextGaussian() * 0.06);
					flames.add(new Flame(at, drift, now, ember ? 0.9f + r.nextFloat() * 0.6f : 0.28f + r.nextFloat() * 0.2f,
						ember ? 0.022f : 0.1f + r.nextFloat() * 0.07f, r.nextFloat(), ember));
				}
			}
			while (flames.size() > 1500) flames.remove(0);
		}
		if (flames.isEmpty()) return;

		Vec3 cam = event.camera().pos;
		VertexConsumer glow = FxRender.textured(event, FxRender.GLOW, true, false);
		float s = size.getFloat();
		for (int i = flames.size() - 1; i >= 0; i--) {
			Flame f = flames.get(i);
			float age = (now - f.born) / 1e9f / f.life;
			if (age >= 1) {
				flames.remove(i);
				continue;
			}
			// Rise, speeding up a little like hot air
			f.pos = f.pos.add(f.drift.x * dt, f.drift.y * dt * (1 + age), f.drift.z * dt);
			float x = (float) (f.pos.x - cam.x), y = (float) (f.pos.y - cam.y), z = (float) (f.pos.z - cam.z);
			if (f.ember) {
				float flicker = 0.6f + 0.4f * (float) Math.sin(age * 40 + f.hue * 20);
				FxRender.billboard(event, glow, x, y, z, f.scale * s, 0, FxRender.fade(FxRender.lighten(palette.at(0.2f), 0.3f), (1 - age) * flicker));
				continue;
			}
			// Hot and white-yellow at the base, then the palette, shrinking as it rises
			int base = palette.at(Math.min(1, age * 1.3f + f.hue * 0.2f));
			int color = age < 0.2f ? FxRender.mix(0xFFFFF4C8, base, age / 0.2f) : base;
			float radius = f.scale * s * (1 - age * 0.7f);
			float alpha = age < 0.15f ? age / 0.15f : 1 - (age - 0.15f) / 0.85f;
			FxRender.billboard(event, glow, x, y, z, radius, f.hue * 6, FxRender.fade(color, alpha * 0.8f));
			// A hot core, so it reads in daylight as well as at night
			if (age < 0.5f) FxRender.billboard(event, glow, x, y, z, radius * 0.45f, 0, FxRender.fade(0xFFFFF0C0, alpha * 0.6f));
		}
	}

	/** Where a player's hand is in third person: hanging at their side, swinging as they walk and when they swing. */
	private static Vec3 hand(Player player, HumanoidArm arm, float tickDelta) {
		Vec3 feet = player.getPosition(tickDelta);
		float bodyYaw = (float) Math.toRadians(player.yBodyRotO + (player.yBodyRot - player.yBodyRotO) * tickDelta);
		float side = arm == HumanoidArm.RIGHT ? -1 : 1;
		float scale = player.getScale();
		// The arm swings from the shoulder: forward and back with walking, up with an attack
		float walk = player.walkAnimation.position(tickDelta), amount = player.walkAnimation.speed(tickDelta);
		float swing = (float) Math.cos(walk * 0.6662f + (arm == HumanoidArm.RIGHT ? Math.PI : 0)) * 1.2f * Math.min(1, amount);
		if (arm == player.getMainArm()) swing -= (float) Math.sin(player.getAttackAnim(tickDelta) * Math.PI) * 1.6f;
		float crouch = player.isCrouching() ? -0.3f : 0;
		double shoulderY = 1.4 + crouch, armLength = 0.62;
		double forward = Math.sin(-swing) * armLength, down = Math.cos(swing) * armLength;
		double localX = side * 0.36, localZ = forward + 0.02;
		double sin = Math.sin(bodyYaw), cos = Math.cos(bodyYaw);
		double wx = localX * cos - localZ * sin, wz = localX * sin + localZ * cos;
		return feet.add(wx * scale, (shoulderY - down) * scale, wz * scale);
	}

	/** Where your hand shows in first person: low on the right of the view. */
	private Vec3 firstPersonHand(float tickDelta) {
		Vec3 eye = mc.player.getEyePosition(tickDelta);
		Vec3 look = mc.player.getViewVector(tickDelta);
		Vec3 up = mc.player.getUpVector(tickDelta);
		Vec3 right = look.cross(up).normalize();
		float sideways = mc.player.getMainArm() == HumanoidArm.RIGHT ? 1 : -1;
		return eye.add(look.scale(0.6)).add(right.scale(0.27 * sideways)).add(up.scale(-0.24));
	}
}
