package dev.femboyware.module.effects;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.FxRender;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Glowing orbs with comet tails swirling round your target. Each tail is the orb's own path a moment
 * ago, so it needs no state and always follows the shape exactly.
 */
public class TargetSwirlModule extends TargetFxModule {
	public enum Mode { HELIX, TORNADO, ATOM, RINGS, SPIRITS }

	private final EnumSetting<Mode> mode = add(new EnumSetting<>("Mode", "Helix: a DNA spiral up and down. Tornado: a widening funnel. Atom: tilted orbits. Rings: stacked orbits. Spirits: wandering wisps", Mode.HELIX));
	private final NumberSetting strands = add(new NumberSetting("Strands", "How many orbs swirl", 3, 1, 8, 1));
	private final NumberSetting speed = add(new NumberSetting("Speed", "How fast they swirl", 1, 0.1, 4, 0.05));
	private final NumberSetting radius = add(new NumberSetting("Radius", "How far out from the body", 1, 0.4, 2.5, 0.05));
	private final NumberSetting trail = add(new NumberSetting("Trail", "Length of each tail", 18, 1, 50, 1));
	private final NumberSetting size = add(new NumberSetting("Size", "Size of each orb", 0.16, 0.03, 0.5, 0.01));
	private final BoolSetting glow = add(new BoolSetting("Glow", "A soft halo of light round each orb", true));
	private final Palette palette = palette(0xFFFF77C8, 0xFF8AB8FF);
	private final BoolSetting throughWalls = targetSettings();

	public TargetSwirlModule() {
		super("Target Swirl", "Glowing orbs swirl round whoever you're fighting");
	}

	@Override
	public String getInfo() {
		return mode.getDisplayName();
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		Map<LivingEntity, Float> targets = fadedTargets();
		if (targets.isEmpty()) return;
		float tickDelta = tickDelta();
		double now = time() * speed.get();
		int count = strands.getInt();
		int length = trail.getInt();
		// Tails cover the same stretch of path whatever the speed
		double spacing = 0.03;
		List<Orb> orbs = new ArrayList<>();

		for (Map.Entry<LivingEntity, Float> entry : targets.entrySet()) {
			LivingEntity target = entry.getKey();
			float fade = ease(entry.getValue());
			Vec3 feet = target.getPosition(tickDelta);
			float height = target.getBbHeight();
			float r = (target.getBbWidth() * 0.5f + 0.35f) * radius.getFloat();

			for (int strand = 0; strand < count; strand++) {
				for (int k = length - 1; k >= 0; k--) {
					float along = (float) k / length;
					Vec3 offset = position(strand, count, now - k * spacing, r, height);
					if (offset == null) continue;
					float tail = 1 - along;
					float alpha = fade * (float) Math.pow(tail, 1.4) * envelope(strand, count, now - k * spacing);
					if (alpha <= 0.01f) continue;
					int color = palette.at((float) strand / count + along * 0.35f);
					float orb = size.getFloat() * (0.35f + 0.65f * (float) Math.pow(tail, 0.6));
					orbs.add(new Orb(feet.add(offset), orb, color, alpha, k % 3 == 0));
				}
			}
		}

		VertexConsumer core = FxRender.textured(event, FxRender.GLOW, false, throughWalls.get());
		for (Orb orb : orbs) FxRender.glow(event, core, orb.pos, orb.size, FxRender.fade(orb.color, orb.alpha));
		if (!glow.get()) return;
		VertexConsumer halo = FxRender.textured(event, FxRender.GLOW, true, throughWalls.get());
		for (Orb orb : orbs) {
			if (orb.halo) FxRender.glow(event, halo, orb.pos, orb.size * 2.6f, FxRender.fade(orb.color, orb.alpha * 0.35f));
		}
	}

	private record Orb(Vec3 pos, float size, int color, float alpha, boolean halo) {}

	/** Where strand {@code i} is at time {@code t}, from the target's feet. */
	private Vec3 position(int i, int count, double t, float r, float height) {
		double share = Math.PI * 2 * i / count;
		switch (mode.get()) {
			case HELIX -> {
				double angle = t * 2.4 + share;
				double y = height * (0.5 - 0.5 * Math.cos(t * 1.1));
				return new Vec3(Math.cos(angle) * r, y, Math.sin(angle) * r);
			}
			case TORNADO -> {
				double climb = frac(t * 0.35 + (double) i / count);
				double angle = t * 4 + share + climb * 6;
				double spread = r * (0.3 + 0.95 * climb);
				return new Vec3(Math.cos(angle) * spread, climb * height * 1.15 - 0.05, Math.sin(angle) * spread);
			}
			case ATOM -> {
				// Each orbit is a circle tipped over and turned round the body
				double angle = t * 3.2 + share;
				double tilt = Math.toRadians(60);
				double turn = share / 2;
				double x = Math.cos(angle) * r * 1.1, z = Math.sin(angle) * r * 1.1;
				double y = z * Math.sin(tilt);
				z *= Math.cos(tilt);
				double rx = x * Math.cos(turn) - z * Math.sin(turn), rz = x * Math.sin(turn) + z * Math.cos(turn);
				return new Vec3(rx, height * 0.55 + y, rz);
			}
			case RINGS -> {
				double angle = t * 3 * (i % 2 == 0 ? 1 : -1) + i * 1.3;
				double y = height * (i + 0.5) / count;
				return new Vec3(Math.cos(angle) * r, y, Math.sin(angle) * r);
			}
			case SPIRITS -> {
				double angle = t * (1.4 + 0.3 * i) + i * 2.4;
				double spread = r * (0.8 + 0.3 * Math.sin(t * 1.7 + i * 1.3));
				double y = height * (0.5 + 0.42 * Math.sin(t * 1.1 + i * 2.1));
				return new Vec3(Math.cos(angle) * spread, y, Math.sin(angle) * spread);
			}
		}
		return null;
	}

	/** Tornado orbs fade in at the bottom and out at the top, so the jump back down is hidden. */
	private float envelope(int i, int count, double t) {
		if (!mode.is(Mode.TORNADO)) return 1;
		return (float) Math.sin(Math.PI * frac(t * 0.35 + (double) i / count));
	}

	private static double frac(double value) {
		return value - Math.floor(value);
	}
}
