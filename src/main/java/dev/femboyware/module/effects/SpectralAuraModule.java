package dev.femboyware.module.effects;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.setting.Setting;
import dev.femboyware.util.FxRender;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Spectral magic round you as you walk about, in one module: soul flames licking off your body,
 * spirit wisps circling with comet tails, glowing runes orbiting your waist, glowing footprints
 * left where you step, and embers spiralling up. Each is a layer with its own settings, and they
 * share one palette.
 */
public class SpectralAuraModule extends PlayerFxModule {
	public enum FlameStyle { SOUL, ARCANE, VOID }

	/** Rune glyphs: line segments (x1, y1, x2, y2) in a square from -1 to 1. */
	static final float[][] RUNES = {
		{0, -1, 0, 1, 0, 0.2f, 0.7f, 0.8f, 0, -0.2f, -0.7f, -0.8f},
		{-0.6f, -1, -0.6f, 1, -0.6f, 1, 0.6f, 0.3f, 0.6f, 0.3f, -0.6f, -0.2f},
		{0, -1, 0, 1, -0.7f, 0.5f, 0.7f, 0.5f, -0.7f, -0.3f, 0.7f, -0.3f},
		{-0.7f, -1, 0, 1, 0, 1, 0.7f, -1, -0.4f, -0.2f, 0.4f, -0.2f},
		{-0.6f, 1, 0.6f, 1, 0, 1, 0, -1, -0.6f, -1, 0.6f, -1},
		{-0.7f, -1, 0.7f, 1, 0.7f, -1, -0.7f, 1, 0, -1, 0, 1},
		{0, 1, 0.7f, 0, 0.7f, 0, 0, -1, 0, -1, -0.7f, 0, -0.7f, 0, 0, 1},
		{-0.6f, -1, -0.6f, 1, 0.6f, -1, 0.6f, 1, -0.6f, 0.2f, 0.6f, -0.2f}};

	private <T, S extends Setting<T>> S under(BoolSetting layer, S setting) {
		add(setting);
		return setting.visibleWhen(layer::get);
	}

	// ---------------------------------------------------------------- soul flames
	private final BoolSetting flames = add(new BoolSetting("Soul Flames", "Spectral flames licking up off your body", true));
	private final EnumSetting<FlameStyle> flameStyle = under(flames, new EnumSetting<>("Flame Style",
		"Soul: pale blue-white fire. Arcane: in your palette. Void: dark smoke with a bright rim", FlameStyle.ARCANE));
	private final NumberSetting flameHeight = under(flames, new NumberSetting("Flame Height", "How high the flames reach, times your height", 0.8, 0.2, 1.6, 0.05));
	private final NumberSetting flameIntensity = under(flames, new NumberSetting("Flame Intensity", "How bright they burn", 0.55, 0.1, 1, 0.05));

	// ---------------------------------------------------------------- wisps
	private final BoolSetting wisps = add(new BoolSetting("Spirit Wisps", "Ghostly wisps circling you, trailing comet tails", true));
	private final NumberSetting wispCount = under(wisps, new NumberSetting("Wisps", "How many wisps", 4, 1, 10, 1));
	private final NumberSetting wispSpeed = under(wisps, new NumberSetting("Wisp Speed", "How fast they circle", 1, 0.2, 4, 0.05));
	private final NumberSetting wispRadius = under(wisps, new NumberSetting("Wisp Radius", "How far out they fly", 0.9, 0.4, 2.5, 0.05));
	private final NumberSetting wispTail = under(wisps, new NumberSetting("Wisp Tail", "How long each tail is", 14, 2, 40, 1));

	// ---------------------------------------------------------------- runes
	private final BoolSetting runes = add(new BoolSetting("Rune Orbit", "Glowing runes orbiting your waist on a tilted ring", false));
	private final NumberSetting runeCount = under(runes, new NumberSetting("Runes", "How many runes", 8, 3, 16, 1));
	private final NumberSetting runeRadius = under(runes, new NumberSetting("Rune Radius", "How far out the ring is", 0.85, 0.4, 2, 0.05));
	private final NumberSetting runeSpeed = under(runes, new NumberSetting("Rune Speed", "How fast the ring turns", 1, 0, 4, 0.05));
	private final NumberSetting runeTilt = under(runes, new NumberSetting("Rune Tilt", "How far the ring leans, in degrees", 14, 0, 45, 1));

	// ---------------------------------------------------------------- footprints
	private final BoolSetting footprints = add(new BoolSetting("Footprints", "Glowing footprints left where you step, fading away", true));
	private final NumberSetting printLife = under(footprints, new NumberSetting("Footprint Life", "How long they stay, in seconds", 3, 0.5, 10, 0.5));
	private final NumberSetting printSize = under(footprints, new NumberSetting("Footprint Size", "How big they are", 1, 0.5, 2, 0.05));

	// ---------------------------------------------------------------- embers
	private final BoolSetting embers = add(new BoolSetting("Spirit Embers", "Embers spiralling up round you, flickering as they rise", false));
	private final NumberSetting emberCount = under(embers, new NumberSetting("Embers", "How many embers", 18, 4, 60, 1));
	private final NumberSetting emberSpeed = under(embers, new NumberSetting("Ember Speed", "How fast they rise", 1, 0.2, 3, 0.05));

	private final Palette palette = palette(0xFF7FE8FF, 0xFFB98AFF);
	private final EnumSetting<ShowOn> showOn = showOnSetting();

	private record Print(Vec3 pos, float yaw, boolean left, long born) {
	}

	/** Each wearer's footprints, newest last, and where their last one went. */
	private final Map<Integer, ArrayDeque<Print>> prints = new HashMap<>();
	private final Map<Integer, Vec3> lastStep = new HashMap<>();
	private final Map<Integer, Boolean> nextLeft = new HashMap<>();

	public SpectralAuraModule() {
		super("Spectral Aura", "Soul flames, spirit wisps, orbiting runes, glowing footprints and embers round you");
	}

	@Override
	public String getInfo() {
		int on = 0;
		for (BoolSetting layer : new BoolSetting[]{flames, wisps, runes, footprints, embers}) if (layer.get()) on++;
		return on + (on == 1 ? " layer" : " layers");
	}

	@Override
	protected void onDisable() {
		prints.clear();
		lastStep.clear();
		nextLeft.clear();
	}

	@Override
	public void onTick() {
		if (mc.level == null || !footprints.get()) return;
		long now = System.currentTimeMillis();
		long life = (long) (printLife.get() * 1000);
		for (Player player : wearers(true)) {
			int id = player.getId();
			ArrayDeque<Print> list = prints.computeIfAbsent(id, k -> new ArrayDeque<>());
			while (!list.isEmpty() && now - list.peekFirst().born() > life) list.removeFirst();
			if (!player.onGround()) continue;
			Vec3 at = player.position();
			Vec3 last = lastStep.get(id);
			if (last != null && last.distanceToSqr(at) < 0.55 * 0.55) continue;
			lastStep.put(id, at);
			if (last == null) continue;
			boolean left = nextLeft.getOrDefault(id, true);
			nextLeft.put(id, !left);
			// Off to the side of the way you're walking
			float yaw = (float) Math.toDegrees(Math.atan2(-(at.x - last.x), at.z - last.z));
			double rad = Math.toRadians(yaw), side = left ? 0.14 : -0.14;
			Vec3 foot = at.add(Math.cos(rad) * side, 0.015, Math.sin(rad) * side);
			list.addLast(new Print(foot, yaw, left, now));
			while (list.size() > 60) list.removeFirst();
		}
		prints.keySet().removeIf(id -> prints.get(id).isEmpty() && mc.level.getEntity(id) == null);
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		if (mc.level == null) return;
		List<Player> all = wearers(true);
		if (all.isEmpty()) return;
		// Flames, wisps and runes would sit in front of your eyes in first person
		List<Player> shown = wearers(false);
		float tickDelta = tickDelta();
		Vec3 cam = event.camera().pos;
		double t = time();

		// Pass one: flat light (flames, rune strokes, footprints)
		VertexConsumer light = FxRender.quads(event, true, false);
		if (footprints.get()) for (Player p : all) footprints(event, light, p, cam);
		for (Player p : shown) {
			if (flames.get()) flames(event, light, p, cam, tickDelta, t);
			if (runes.get()) runes(event, light, p, cam, tickDelta, t);
		}
		// Pass two: soft glows (wisps, embers, rune and flame cores)
		VertexConsumer glow = FxRender.textured(event, FxRender.GLOW, true, false);
		for (Player p : shown) {
			if (wisps.get()) wisps(event, glow, p, cam, tickDelta, t);
			if (embers.get()) embers(event, glow, p, cam, tickDelta, t);
			if (flames.get() && flameStyle.get() != FlameStyle.VOID) {
				Vec3 feet = p.getPosition(tickDelta);
				FxRender.glow(event, glow, feet.add(0, 0.05, 0), 0.9f, FxRender.fade(flameColor(0.3f, t), flameIntensity.getFloat() * 0.35f));
			}
		}
	}

	private int flameColor(float along, double t) {
		return switch (flameStyle.get()) {
			case SOUL -> FxRender.mix(0xFF5FD8FF, 0xFFE8FBFF, along);
			case VOID -> FxRender.mix(0xFF2A0A3C, palette.at((float) (t * 0.1)), along);
			case ARCANE -> palette.at(along + (float) (t * 0.05));
		};
	}

	/** Tongues of flame round the body: tapered strips rising and flickering, brightest at the root. */
	private void flames(Render3DEvent event, VertexConsumer light, Player p, Vec3 cam, float tickDelta, double t) {
		Vec3 feet = p.getPosition(tickDelta);
		PoseStack matrices = event.matrices();
		matrices.pushPose();
		matrices.translate((float) (feet.x - cam.x), (float) (feet.y - cam.y), (float) (feet.z - cam.z));
		PoseStack.Pose pose = matrices.last();
		float height = p.getBbHeight() * flameHeight.getFloat(), intensity = flameIntensity.getFloat();
		boolean dark = flameStyle.get() == FlameStyle.VOID;
		int tongues = 22;
		for (int i = 0; i < tongues; i++) {
			double a = Math.PI * 2 * i / tongues + Math.sin(t * 0.7 + i) * 0.15;
			float r = 0.36f + 0.05f * (float) Math.sin(t * 3 + i * 1.7);
			float flicker = 0.55f + 0.45f * (float) (0.5 + 0.5 * Math.sin(t * 6.3 + i * 2.39)) * (float) (0.6 + 0.4 * Math.sin(t * 2.1 + i));
			float top = height * flicker;
			float sway = 0.08f * (float) Math.sin(t * 4 + i * 0.9);
			float cx = (float) Math.cos(a) * r, cz = (float) Math.sin(a) * r;
			float tx = (float) Math.cos(a + Math.PI / 2) * 0.09f, tz = (float) Math.sin(a + Math.PI / 2) * 0.09f;
			int root = FxRender.fade(flameColor(0, t), intensity * (dark ? 0.8f : 0.55f));
			int tip = FxRender.fade(flameColor(1, t), 0);
			float topX = (float) Math.cos(a) * (r * 0.6f) + sway, topZ = (float) Math.sin(a) * (r * 0.6f) + sway * 0.5f;
			FxRender.quad(light, pose, cx - tx, 0.02f, cz - tz, root, cx + tx, 0.02f, cz + tz, root, topX, top, topZ, tip, topX, top, topZ, tip);
		}
		matrices.popPose();
	}

	/** Wisps on looping orbits, each with a tail made of where it was a moment ago. */
	private void wisps(Render3DEvent event, VertexConsumer glow, Player p, Vec3 cam, float tickDelta, double t) {
		Vec3 feet = p.getPosition(tickDelta);
		int n = wispCount.getInt(), tail = wispTail.getInt();
		float radius = wispRadius.getFloat(), h = p.getBbHeight();
		for (int w = 0; w < n; w++) {
			int color = palette.at(w / (float) n);
			for (int k = tail; k >= 0; k--) {
				double tt = (t - k * 0.025) * wispSpeed.get();
				double a = tt * 1.6 + Math.PI * 2 * w / n;
				double bob = Math.sin(tt * 1.3 + w * 2.1);
				float r = radius * (float) (0.85 + 0.15 * Math.sin(tt * 0.9 + w));
				Vec3 at = feet.add(Math.cos(a) * r, h * (0.55 + 0.3 * bob), Math.sin(a) * r);
				float fade = 1 - k / (float) (tail + 1);
				float size = (k == 0 ? 0.2f : 0.11f) * (0.4f + 0.6f * fade);
				FxRender.glow(event, glow, at, size, FxRender.fade(k == 0 ? FxRender.lighten(color, 0.5f) : color, fade * (k == 0 ? 0.95f : 0.5f)));
			}
		}
	}

	/** Runes standing on a tilted ring round the waist, each turned to face out, pulsing in turn. */
	private void runes(Render3DEvent event, VertexConsumer light, Player p, Vec3 cam, float tickDelta, double t) {
		Vec3 feet = p.getPosition(tickDelta);
		PoseStack matrices = event.matrices();
		int n = runeCount.getInt();
		float radius = runeRadius.getFloat(), spin = (float) (t * 40 * runeSpeed.get());
		matrices.pushPose();
		matrices.translate((float) (feet.x - cam.x), (float) (feet.y - cam.y) + p.getBbHeight() * 0.5f, (float) (feet.z - cam.z));
		matrices.mulPose(Axis.XP.rotationDegrees(runeTilt.getFloat() * (float) Math.sin(t * 0.5)));
		matrices.mulPose(Axis.ZP.rotationDegrees(runeTilt.getFloat() * (float) Math.cos(t * 0.5)));
		// A faint band the runes ride on
		FxRender.ring(light, matrices.last(), radius - 0.015f, radius + 0.015f, 0, 48, (a, inner) -> FxRender.fade(palette.at(a), 0.35f));
		for (int i = 0; i < n; i++) {
			matrices.pushPose();
			matrices.mulPose(Axis.YP.rotationDegrees(spin + 360f * i / n));
			matrices.translate(radius, 0, 0);
			matrices.mulPose(Axis.YP.rotationDegrees(90));
			PoseStack.Pose pose = matrices.last();
			float pulse = 0.55f + 0.45f * (float) Math.max(0, Math.sin(t * 3 - i * 0.8));
			int color = FxRender.fade(palette.at(i / (float) n), 0.9f * pulse);
			float[] rune = RUNES[i % RUNES.length];
			float s = 0.11f;
			for (int k = 0; k + 3 < rune.length; k += 4) {
				FxRender.strokeXY(light, pose, rune[k] * s, rune[k + 1] * s, rune[k + 2] * s, rune[k + 3] * s, 0.022f, color, color);
			}
			matrices.popPose();
		}
		matrices.popPose();
	}

	/** Each footprint: a sole and a heel, glowing and fading as it ages. */
	private void footprints(Render3DEvent event, VertexConsumer light, Player p, Vec3 cam) {
		ArrayDeque<Print> list = prints.get(p.getId());
		if (list == null || list.isEmpty()) return;
		long now = System.currentTimeMillis();
		float life = printLife.getFloat() * 1000, size = printSize.getFloat();
		PoseStack matrices = event.matrices();
		int i = 0;
		for (Print print : list) {
			float age = (now - print.born()) / life;
			if (age >= 1) continue;
			float alpha = (1 - age) * (1 - age) * 0.85f;
			int color = palette.at(i++ * 0.07f);
			matrices.pushPose();
			matrices.translate((float) (print.pos().x - cam.x), (float) (print.pos().y - cam.y), (float) (print.pos().z - cam.z));
			matrices.mulPose(Axis.YP.rotationDegrees(-print.yaw()));
			matrices.scale(size, 1, size);
			PoseStack.Pose pose = matrices.last();
			// The ball of the foot ahead, the heel behind: two soft ovals
			oval(light, pose, 0, 0.07f, 0.075f, 0.11f, color, alpha);
			oval(light, pose, 0, -0.1f, 0.06f, 0.07f, color, alpha);
			matrices.popPose();
		}
	}

	private static void oval(VertexConsumer light, PoseStack.Pose pose, float cx, float cz, float rx, float rz, int color, float alpha) {
		int segments = 14;
		int centre = FxRender.fade(FxRender.lighten(color, 0.3f), alpha), edge = FxRender.fade(color, 0);
		for (int s = 0; s < segments; s++) {
			double a1 = Math.PI * 2 * s / segments, a2 = Math.PI * 2 * (s + 1) / segments;
			FxRender.triangle(light, pose, cx, 0, cz, centre,
				cx + (float) Math.cos(a1) * rx, 0, cz + (float) Math.sin(a1) * rz, edge,
				cx + (float) Math.cos(a2) * rx, 0, cz + (float) Math.sin(a2) * rz, edge);
		}
	}

	/** Embers spiralling up from the feet, each on its own loop so they keep coming. */
	private void embers(Render3DEvent event, VertexConsumer glow, Player p, Vec3 cam, float tickDelta, double t) {
		Vec3 feet = p.getPosition(tickDelta);
		int n = emberCount.getInt();
		float h = p.getBbHeight() * 1.3f;
		for (int i = 0; i < n; i++) {
			double seed = i * 12.9898;
			double phase = frac((t * 0.35 * emberSpeed.get()) + frac(Math.sin(seed) * 43758.5453));
			double a = phase * Math.PI * 3 + i * 2.39996;
			float r = 0.35f + 0.35f * (float) frac(Math.sin(seed * 1.7) * 9631.2);
			Vec3 at = feet.add(Math.cos(a) * r, phase * h, Math.sin(a) * r);
			float flicker = 0.6f + 0.4f * (float) Math.sin(t * 11 + i * 3.1);
			float alpha = (float) Math.sin(phase * Math.PI) * flicker;
			FxRender.glow(event, glow, at, 0.06f + 0.04f * flicker, FxRender.fade(palette.at((float) phase), alpha * 0.9f));
		}
	}

	private static double frac(double v) {
		return v - Math.floor(v);
	}
}
