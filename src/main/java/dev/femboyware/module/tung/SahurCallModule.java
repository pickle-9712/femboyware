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
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * The sahur wake-up call: "TUNG... TUNG... TUNG... SAHUR!" on the drum, then Tung Tung Tung Sahur
 * himself pops up right in front of you with his bat, giving it a swing before he goes. Press the
 * key for it, or let him come round every night.
 */
public class SahurCallModule extends Module {
	/** When each drum beat lands, in milliseconds; the last is SAHUR! and he turns up. */
	private static final long[] BEATS = {0, 450, 900, 1500};
	private static final long ARRIVE = BEATS[BEATS.length - 1];

	private final KeySetting key = add(new KeySetting("Call Key", "Press to call him", -1));
	private final BoolSetting nightly = add(new BoolSetting("Every Night", "He comes round by himself as night falls", true));
	private final BoolSetting onEnable = add(new BoolSetting("Call On Enable", "Switching the module on calls him straight away", true));
	private final NumberSetting stay = add(new NumberSetting("Stay", "Seconds he stands there", 4, 1, 15, 0.5));
	private final NumberSetting height = add(new NumberSetting("Size", "How tall he is, in blocks", 3, 1.5, 6, 0.25));

	private long start = -1;
	private int beats;
	private Vec3 spot;
	private float yaw;
	private long lastTime = -1;

	public SahurCallModule() {
		super("Sahur Call", "TUNG TUNG TUNG SAHUR! on the drum, and he's stood in front of you", Category.COSMETIC);
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
		// A few blocks in front of you, facing you
		double rad = Math.toRadians(mc.player.getYRot());
		Vec3 forward = new Vec3(-Math.sin(rad), 0, Math.cos(rad));
		spot = mc.player.position().add(forward.scale(2.2 + height.get() * 0.4));
		yaw = (float) Math.atan2(-forward.x, -forward.z);
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
		while (beats < BEATS.length && elapsed >= BEATS[beats]) {
			boolean sahur = beats == BEATS.length - 1;
			Vec3 at = mc.player.position();
			mc.level.playLocalSound(at.x, at.y + 1, at.z, SoundEvents.NOTE_BLOCK_BASEDRUM.value(), SoundSource.PLAYERS, 1, sahur ? 0.6f : 0.85f, false);
			if (sahur) mc.level.playLocalSound(at.x, at.y + 1, at.z, SoundEvents.ZOMBIE_ATTACK_WOODEN_DOOR, SoundSource.PLAYERS, 0.8f, 1.2f, false);
			beats++;
		}
		if (elapsed > ARRIVE + stay.get() * 1000 + 400) start = -1;
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		if (start < 0 || spot == null) return;
		long elapsed = System.currentTimeMillis() - start;
		if (elapsed < ARRIVE) return;
		long here = elapsed - ARRIVE;
		long leave = (long) (stay.get() * 1000);
		// Pops up out of the ground, shrinks back into it when he goes
		float grow = Mth.clamp(here / 200f, 0, 1);
		float shrink = Mth.clamp((here - leave) / 400f, 0, 1);
		float scale = grow * (1.15f - 0.15f * grow) * (1 - shrink);
		if (scale <= 0) return;
		// A big swing as he arrives, then one more before he leaves
		float arm = -4;
		if (here < 700) arm = TungSahurModule.chop(here / 700f, arm);
		else if (here > leave - 900 && here < leave - 200) arm = TungSahurModule.chop((here - leave + 900) / 700f, arm);
		TungSahurModule.drawSahur(event, spot.subtract(event.camera().pos), yaw, 0, arm, height.getFloat() / 2.5f * scale);
	}

	@Override
	public void onRender2D(GuiGraphics context, float tickDelta) {
		if (start < 0) return;
		long elapsed = System.currentTimeMillis() - start;
		for (int i = 0; i < BEATS.length; i++) {
			long since = elapsed - BEATS[i];
			if (since < 0 || since > 600) continue;
			boolean sahur = i == BEATS.length - 1;
			String word = sahur ? "SAHUR!" : "TUNG";
			float pop = Mth.clamp(since / 120f, 0, 1);
			float fade = 1 - Mth.clamp((since - 350) / 250f, 0, 1);
			float scale = (sahur ? 5 : 3.5f) * (0.75f + 0.25f * pop);
			int color = Math.max(4, (int) (255 * fade)) << 24 | (sahur ? Theme.accent() & 0xFFFFFF : 0xFFE0B0);
			// The three TUNGs go across the top, SAHUR! lands in the middle
			float x = sahur ? context.guiWidth() / 2f : context.guiWidth() * (0.3f + i * 0.2f);
			float y = sahur ? context.guiHeight() * 0.42f : context.guiHeight() * 0.22f;
			context.pose().pushMatrix();
			context.pose().translate(x, y);
			context.pose().scale(scale, scale);
			context.drawString(mc.font, word, -mc.font.width(word) / 2, -mc.font.lineHeight / 2, color, true);
			context.pose().popMatrix();
		}
	}

	@Override
	protected void onDisable() {
		start = -1;
		lastTime = -1;
	}
}
