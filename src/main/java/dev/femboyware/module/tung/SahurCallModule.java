package dev.femboyware.module.tung;

import dev.femboyware.event.Render3DEvent;
import dev.femboyware.gui.Theme;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.module.render.TungSahurModule;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.KeySetting;
import dev.femboyware.setting.NumberSetting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * The sahur summoning. The light goes, the faithful's words come up out of the dark, and nine TUNGs
 * beat out a chant that keeps speeding up while a ring of soul fire turns where he'll stand. Then it
 * all goes quiet, a bell tolls, SAHUR., and Tung Tung Tung Sahur rises out of the circle, bat raised.
 * Press the key for it, or let the drum call him every night.
 */
public class SahurCallModule extends Module {
	/** The chant: three slow, three quicker, three fast, in milliseconds. */
	private static final long[] BEATS = {1400, 2500, 3500, 4300, 4950, 5500, 5950, 6300, 6600};
	/** The silence after the last TUNG, then the bell and SAHUR. */
	private static final long BELL = 7700;
	private static final long ARRIVE = BELL + 300;
	/** How long he takes to come up out of the circle. */
	private static final long RISE = 1400;

	private static final String[] GATHER = {"the faithful gather", "the drum remembers", "we wake you before the dawn"};
	private static final String[] ANSWERED = {"he has answered", "the drum is satisfied"};
	private static final int BLOOD = 0xB01818;
	private static final int CANDLE = 0xFFD9A0;

	private final KeySetting key = add(new KeySetting("Call Key", "Press to begin the summoning", -1));
	private final BoolSetting nightly = add(new BoolSetting("Every Night", "The ritual begins by itself as night falls", true));
	private final BoolSetting onEnable = add(new BoolSetting("Call On Enable", "Switching the module on begins the summoning straight away", true));
	private final BoolSetting darkness = add(new BoolSetting("Darkness", "The screen goes dark and red while he's summoned", true));
	private final BoolSetting circle = add(new BoolSetting("Circle", "A ring of soul fire where he'll rise", true));
	private final NumberSetting stay = add(new NumberSetting("Stay", "Seconds he stands there", 5, 1, 15, 0.5));
	private final NumberSetting height = add(new NumberSetting("Size", "How tall he is, in blocks", 3, 1.5, 6, 0.25));

	private long start = -1;
	private int beats;
	private boolean tolled;
	private Vec3 spot;
	private float yaw;
	private long lastTime = -1;

	public SahurCallModule() {
		super("Sahur Call", "A summoning: the chant, the circle, the bell, and he rises", Category.COSMETIC);
	}

	@Override
	protected void onEnable() {
		if (onEnable.get()) call();
	}

	@Override
	public void onKey(int pressed) {
		if (key.is(pressed)) call();
	}

	public void call() {
		if (mc.player == null) return;
		start = System.currentTimeMillis();
		beats = 0;
		tolled = false;
		// A few blocks in front of you, facing you
		double rad = Math.toRadians(mc.player.getYRot());
		Vec3 forward = new Vec3(-Math.sin(rad), 0, Math.cos(rad));
		spot = mc.player.position().add(forward.scale(2.4 + height.get() * 0.4));
		yaw = (float) Math.atan2(-forward.x, -forward.z);
		Vec3 at = mc.player.position();
		// A low drone as the light goes
		mc.level.playLocalSound(at.x, at.y + 1, at.z, SoundEvents.NOTE_BLOCK_DIDGERIDOO.value(), SoundSource.PLAYERS, 0.7f, 0.5f, false);
	}

	private long leaveAt() {
		return ARRIVE + RISE + (long) (stay.get() * 1000);
	}

	@Override
	public void onTick() {
		if (mc.level == null || mc.player == null) return;
		// Nightfall
		long time = dev.femboyware.util.Mc.dayTime() % 24000;
		if (nightly.get() && lastTime >= 0 && lastTime < 13000 && time >= 13000) call();
		lastTime = time;

		if (start < 0) return;
		long elapsed = System.currentTimeMillis() - start;
		Vec3 at = mc.player.position();
		while (beats < BEATS.length && elapsed >= BEATS[beats]) {
			// The drum, and a heartbeat under it; each round of three a little higher
			float pitch = 0.6f + (beats / 3) * 0.08f;
			mc.level.playLocalSound(at.x, at.y + 1, at.z, SoundEvents.NOTE_BLOCK_BASEDRUM.value(), SoundSource.PLAYERS, 1, pitch, false);
			mc.level.playLocalSound(at.x, at.y + 1, at.z, SoundEvents.WARDEN_HEARTBEAT, SoundSource.PLAYERS, 0.6f, 1, false);
			if (beats % 3 == 2) mc.level.playLocalSound(at.x, at.y + 1, at.z, SoundEvents.NOTE_BLOCK_DIDGERIDOO.value(), SoundSource.PLAYERS, 0.5f, 0.5f + beats * 0.03f, false);
			beats++;
		}
		if (!tolled && elapsed >= BELL) {
			tolled = true;
			mc.level.playLocalSound(at.x, at.y + 1, at.z, SoundEvents.BELL_BLOCK, SoundSource.PLAYERS, 1, 0.5f, false);
			mc.level.playLocalSound(at.x, at.y + 1, at.z, SoundEvents.NOTE_BLOCK_BASEDRUM.value(), SoundSource.PLAYERS, 1, 0.5f, false);
			mc.level.playLocalSound(at.x, at.y + 1, at.z, SoundEvents.ZOMBIE_ATTACK_WOODEN_DOOR, SoundSource.PLAYERS, 0.8f, 0.8f, false);
		}
		if (circle.get() && spot != null) particles(elapsed);
		if (elapsed > leaveAt() + 1200) start = -1;
	}

	/** The ring of soul fire, the offerings drawn into it, and the burst when he comes. */
	private void particles(long elapsed) {
		if (elapsed > leaveAt()) return;
		// The ring kindles flame by flame over the chant, and turns faster as it quickens
		float lit = Mth.clamp(elapsed / (float) BEATS[BEATS.length - 1], 0, 1);
		int flames = 3 + (int) (lit * 13);
		double radius = 1.3 + height.get() * 0.15;
		double turn = elapsed / 1000.0 * (0.4 + lit * 1.6);
		for (int i = 0; i < flames; i++) {
			double a = turn + i * Math.PI * 2 / 16;
			mc.level.addParticle(ParticleTypes.SOUL_FIRE_FLAME, spot.x + Math.cos(a) * radius, spot.y + 0.05, spot.z + Math.sin(a) * radius, 0, 0.01, 0);
		}
		// Glyphs drift in toward the middle while they chant
		if (elapsed < BELL && mc.player.tickCount % 2 == 0) {
			double a = Math.random() * Math.PI * 2;
			mc.level.addParticle(ParticleTypes.ENCHANT, spot.x + Math.cos(a) * radius * 1.6, spot.y + 1.2, spot.z + Math.sin(a) * radius * 1.6,
				-Math.cos(a) * radius, -0.6, -Math.sin(a) * radius);
		}
		// He comes up through smoke and souls
		if (elapsed >= BELL && elapsed < ARRIVE + RISE) {
			for (int i = 0; i < 4; i++) {
				double a = Math.random() * Math.PI * 2, r = Math.random() * radius;
				mc.level.addParticle(ParticleTypes.SOUL, spot.x + Math.cos(a) * r, spot.y + 0.1, spot.z + Math.sin(a) * r, 0, 0.06, 0);
				mc.level.addParticle(ParticleTypes.LARGE_SMOKE, spot.x + Math.cos(a) * r, spot.y + 0.1, spot.z + Math.sin(a) * r, 0, 0.04, 0);
			}
		}
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		if (start < 0 || spot == null) return;
		long elapsed = System.currentTimeMillis() - start;
		if (elapsed < ARRIVE) return;
		long here = elapsed - ARRIVE;
		long leave = RISE + (long) (stay.get() * 1000);
		// Rises slowly up out of the circle, and sinks back down into it when he goes
		float rise = Mth.clamp(here / (float) RISE, 0, 1);
		rise = 1 - (1 - rise) * (1 - rise);
		float sink = Mth.clamp((here - leave) / 1000f, 0, 1);
		float up = rise * (1 - sink);
		if (up <= 0) return;
		float size = height.getFloat() / 2.5f;
		Vec3 at = spot.add(0, -height.get() * (1 - up), 0);
		// Bat held high while he rises, then one slow blessing swing, and one more before he goes
		float arm = -4;
		if (here < RISE) arm = -60;
		else if (here < RISE + 900) arm = TungSahurModule.chop((here - RISE) / 900f, -60);
		else if (here > leave - 900 && here < leave) arm = TungSahurModule.chop((here - leave + 900) / 900f, arm);
		TungSahurModule.drawSahur(event, at.subtract(event.camera().pos), yaw, 0, arm, size);
	}

	@Override
	public void onRender2D(GuiGraphics context, float tickDelta) {
		if (start < 0) return;
		long elapsed = System.currentTimeMillis() - start;
		int w = context.guiWidth(), h = context.guiHeight();
		long gone = leaveAt();

		if (darkness.get()) {
			// The light goes over the first second, deepest in the silence before the bell, lifts as he leaves
			float dark = Mth.clamp(elapsed / 1200f, 0, 1) * 0.55f;
			if (elapsed > BEATS[BEATS.length - 1]) dark += Mth.clamp((elapsed - BEATS[BEATS.length - 1]) / (float) (BELL - BEATS[BEATS.length - 1]), 0, 1) * 0.25f;
			if (elapsed > BELL) dark = 0.8f - Mth.clamp((elapsed - BELL) / 1500f, 0, 1) * 0.35f;
			if (elapsed > gone) dark *= 1 - Mth.clamp((elapsed - gone) / 1200f, 0, 1);
			int black = (int) (dark * 255) << 24;
			context.fill(0, 0, w, h, black);
			// A red edge that throbs on every beat
			int pulse = 0;
			for (long beat : BEATS) {
				long since = elapsed - beat;
				if (since >= 0 && since < 400) pulse = Math.max(pulse, (int) (90 * (1 - since / 400f)));
			}
			if (elapsed >= BELL && elapsed < BELL + 800) pulse = Math.max(pulse, (int) (160 * (1 - (elapsed - BELL) / 800f)));
			if (elapsed > gone) pulse = 0;
			int edge = Math.max(pulse, (int) (dark * 60));
			int band = Math.max(8, h / 10);
			context.fillGradient(0, 0, w, band, edge << 24 | BLOOD, BLOOD);
			context.fillGradient(0, h - band, w, h, BLOOD, edge << 24 | BLOOD);
			// The flash when the bell tolls
			if (elapsed >= BELL && elapsed < BELL + 250) context.fill(0, 0, w, h, (int) (120 * (1 - (elapsed - BELL) / 250f)) << 24 | BLOOD);
		}

		// The faithful speak while the light goes
		for (int i = 0; i < GATHER.length; i++) {
			long from = i * 450L, until = BEATS[0] + 600;
			whisper(context, GATHER[i], w / 2f, h * 0.68f + i * (mc.font.lineHeight + 3) * 1.2f, elapsed - from, until - from);
		}

		// TUNG, nine times, in a circle that turns faster as the chant quickens
		for (int i = 0; i < BEATS.length; i++) {
			long since = elapsed - BEATS[i];
			if (since < 0 || since > 900) continue;
			float pop = Mth.clamp(since / 140f, 0, 1);
			float fade = 1 - Mth.clamp((since - 450) / 450f, 0, 1);
			double a = -Math.PI / 2 + i * Math.PI * 2 / 3 + elapsed / 1000.0 * 0.6;
			float x = w / 2f + (float) Math.cos(a) * h * 0.28f;
			float y = h * 0.42f + (float) Math.sin(a) * h * 0.2f;
			float scale = (2.6f + i * 0.18f) * (0.7f + 0.3f * pop);
			int color = Math.max(4, (int) (255 * fade)) << 24 | (i >= 6 ? BLOOD | 0x202020 : CANDLE);
			word(context, "TUNG", x, y, scale, color);
		}

		// The silence: only the dots
		if (elapsed > BEATS[BEATS.length - 1] + 300 && elapsed < BELL) {
			int dots = (int) Mth.clamp((elapsed - BEATS[BEATS.length - 1] - 300) / 300, 0, 3);
			word(context, ".".repeat(dots), w / 2f, h * 0.42f, 4, 0xC0 << 24 | CANDLE);
		}

		// SAHUR.
		long since = elapsed - BELL;
		if (since >= 0 && since < 2200) {
			float pop = Mth.clamp(since / 160f, 0, 1);
			float fade = 1 - Mth.clamp((since - 1500) / 700f, 0, 1);
			int color = Math.max(4, (int) (255 * fade)) << 24 | (Theme.accent() & 0xFFFFFF);
			word(context, "SAHUR.", w / 2f, h * 0.4f, 6.5f - 1.5f * pop, color);
		}

		// What the faithful say once he's come, and as he goes
		whisper(context, ANSWERED[0], w / 2f, h * 0.7f, elapsed - (ARRIVE + RISE), (long) (stay.get() * 1000));
		whisper(context, ANSWERED[1], w / 2f, h * 0.7f, elapsed - gone, 1400);
	}

	/** Small, quiet lettering that fades in and out over {@code length} ms. */
	private void whisper(GuiGraphics context, String text, float x, float y, long since, long length) {
		if (since < 0 || since > length) return;
		float in = Mth.clamp(since / 400f, 0, 1);
		float out = 1 - Mth.clamp((since - (length - 400)) / 400f, 0, 1);
		int alpha = Math.max(4, (int) (200 * Math.min(in, out)));
		word(context, text, x, y, 1.2f, alpha << 24 | CANDLE);
	}

	private void word(GuiGraphics context, String text, float x, float y, float scale, int color) {
		context.pose().pushMatrix();
		context.pose().translate(x, y);
		context.pose().scale(scale, scale);
		context.drawString(mc.font, text, -mc.font.width(text) / 2, -mc.font.lineHeight / 2, color, true);
		context.pose().popMatrix();
	}

	@Override
	protected void onDisable() {
		start = -1;
		lastTime = -1;
	}
}
