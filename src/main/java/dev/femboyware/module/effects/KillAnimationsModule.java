package dev.femboyware.module.effects;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.femboyware.FemboyWare;
import dev.femboyware.event.PacketEvent;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.render.TungSahurModule;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.FxRender;
import dev.femboyware.util.IsraelFlag;
import dev.femboyware.util.RenderUtil;
import it.unimi.dsi.fastutil.ints.IntList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import net.minecraft.client.gui.Font;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.phys.Vec3;

/**
 * Big animations where someone you were fighting dies. Money larp: Rug Pull (a stock line pumps
 * out of them, then crashes into their corpse as the rug's yanked away), Margin Call (their
 * leveraged position gets liquidated), Exit Liquidity (their money streams into you) and Card
 * Declined. Tung Sahur comes and bonks them. And blue and white: a Star of David rising and
 * bursting, the Israeli flag raised over them, and star-shaped blue and white fireworks.
 */
public class KillAnimationsModule extends TargetFxModule {
	public enum Animation {
		RUG_PULL(3800), MARGIN_CALL(3200), EXIT_LIQUIDITY(3000), CARD_DECLINED(2900), TUNG_SAHUR(0),
		STAR_OF_DAVID(2800), ISRAEL_FLAG(4200), BLUE_AND_WHITE(0), RANDOM(0);

		final long length;

		Animation(long length) {
			this.length = length;
		}
	}

	private static final long ENGAGE_MEMORY = 3000;
	private static final Animation[] RANDOM_POOL = {Animation.RUG_PULL, Animation.MARGIN_CALL, Animation.EXIT_LIQUIDITY, Animation.CARD_DECLINED,
		Animation.TUNG_SAHUR, Animation.STAR_OF_DAVID, Animation.ISRAEL_FLAG, Animation.BLUE_AND_WHITE};

	private static final int MONEY_GREEN = 0xFF3CFF8A;
	private static final int CRASH_RED = 0xFFFF2F5F;
	private static final int BEAM_PINK = 0xFFFF5C9A;
	private static final int GOLD = 0xFFFFC93C;
	private static final int ISRAEL_BLUE = 0xFF0038B8;
	private static final int STAR_BLUE = 0xFF3D7BFF;

	private final EnumSetting<Animation> animation = add(new EnumSetting<>("Animation", "What plays when they die", Animation.RUG_PULL));
	private final BoolSetting playersOnly = add(new BoolSetting("Players Only", "Only on players, not mobs", true));
	private final BoolSetting sounds = add(new BoolSetting("Sounds", "Sound effects with each animation", true));
	private final NumberSetting scale = add(new NumberSetting("Scale", "How big the animations are", 1, 0.5, 2, 0.05));
	private final BoolSetting walls = targetSettings();

	/** One kill playing out. */
	private static final class Kill {
		final Animation animation;
		final Vec3 feet;
		final float height;
		final String name;
		final long start, seed;
		/** Which way is towards you, fixed when they died, so upright things face you. */
		final float yaw;
		/** Their made-up net worth, from their name, so it's the same every time. */
		final double worth;
		/** Sounds already played. */
		int cue;

		Kill(Animation animation, LivingEntity entity, float yaw) {
			this.animation = animation;
			feet = entity.position();
			height = entity.getBbHeight();
			name = entity.getName().getString();
			start = System.currentTimeMillis();
			seed = System.nanoTime() ^ name.hashCode();
			this.yaw = yaw;
			Random random = new Random(name.hashCode() * 31L);
			worth = 40_000 + Math.pow(random.nextDouble(), 2) * 2_400_000;
		}

		float age() {
			return (System.currentTimeMillis() - start) / 1000f;
		}
	}

	private final Map<LivingEntity, Long> engaged = new HashMap<>();
	private final List<Kill> kills = new ArrayList<>();
	private final Random random = new Random();

	public KillAnimationsModule() {
		super("Kill Animations", "Rug pulls, margin calls, Tung Sahur, Star of David and more when you kill someone");
	}

	@Override
	public String getInfo() {
		return animation.getDisplayName();
	}

	@Override
	protected void onDisable() {
		super.onDisable();
		engaged.clear();
		kills.clear();
	}

	private TungSahurModule tung() {
		return FemboyWare.getModules().get(TungSahurModule.class);
	}

	// ---------------------------------------------------------------- spotting kills

	@Override
	public void onTick() {
		if (mc.level == null || mc.player == null) return;
		long now = System.currentTimeMillis();
		for (LivingEntity target : targets()) engaged.put(target, now);
		Iterator<Map.Entry<LivingEntity, Long>> iterator = engaged.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<LivingEntity, Long> entry = iterator.next();
			LivingEntity entity = entry.getKey();
			if (entity.isDeadOrDying() || entity.getHealth() <= 0) {
				if (!playersOnly.get() || entity instanceof Player) start(entity);
				iterator.remove();
			} else if (entity.isRemoved() || now - entry.getValue() > ENGAGE_MEMORY) {
				iterator.remove();
			}
		}
		kills.removeIf(kill -> kill.age() * 1000 > kill.animation.length);
		for (Kill kill : kills) cues(kill);
		// Sahur's visits keep going with his own module off
		TungSahurModule tung = tung();
		if (!tung.isEnabled() && tung.running()) tung.onTick();
	}

	@Override
	public void onPacketReceive(PacketEvent event) {
		TungSahurModule tung = tung();
		if (!tung.isEnabled() && tung.running()) tung.onPacketReceive(event);
	}

	private void start(LivingEntity entity) {
		Animation picked = animation.get();
		if (picked == Animation.RANDOM) picked = RANDOM_POOL[random.nextInt(RANDOM_POOL.length)];
		Vec3 toYou = mc.player.position().subtract(entity.position());
		float yaw = (float) Math.atan2(toYou.x, toYou.z);
		switch (picked) {
			case TUNG_SAHUR -> tung().visit(entity);
			case BLUE_AND_WHITE -> blueAndWhite(entity);
			default -> kills.add(new Kill(picked, entity, yaw));
		}
		while (kills.size() > 8) kills.remove(0);
	}

	/** Sounds at the right moments of each animation. */
	private void cues(Kill kill) {
		if (!sounds.get()) return;
		float t = kill.age();
		float[] times = switch (kill.animation) {
			case RUG_PULL -> new float[]{0, 1.4f, 1.55f, 2.4f};
			case MARGIN_CALL -> new float[]{0, 1.2f};
			case EXIT_LIQUIDITY -> new float[]{0.3f, 0.7f, 1.1f, 1.5f, 2f};
			case CARD_DECLINED -> new float[]{0.6f, 0.85f, 1.05f, 1.45f};
			case STAR_OF_DAVID -> new float[]{0, 0.6f, 1.45f};
			case ISRAEL_FLAG -> new float[]{0, 1.3f};
			default -> new float[0];
		};
		while (kill.cue < times.length && t >= times[kill.cue]) {
			int i = kill.cue++;
			switch (kill.animation) {
				case RUG_PULL -> play(kill, i == 0 ? SoundEvents.EXPERIENCE_ORB_PICKUP : i == 1 ? SoundEvents.ANVIL_LAND : i == 2 ? SoundEvents.WOOL_BREAK : SoundEvents.VILLAGER_NO, i == 1 ? 0.6f : 1f);
				case MARGIN_CALL -> play(kill, i == 0 ? SoundEvents.NOTE_BLOCK_PLING.value() : SoundEvents.GLASS_BREAK, i == 0 ? 0.6f : 0.8f);
				case EXIT_LIQUIDITY -> play(kill, SoundEvents.EXPERIENCE_ORB_PICKUP, 0.9f + i * 0.15f);
				case CARD_DECLINED -> play(kill, i == 0 ? SoundEvents.UI_STONECUTTER_TAKE_RESULT : i == 3 ? SoundEvents.ITEM_BREAK.value() : SoundEvents.NOTE_BLOCK_BASS.value(), i == 0 ? 1.4f : 0.6f);
				case STAR_OF_DAVID -> play(kill, i == 2 ? SoundEvents.PLAYER_LEVELUP : SoundEvents.AMETHYST_BLOCK_CHIME, 0.8f + i * 0.3f);
				case ISRAEL_FLAG -> play(kill, i == 0 ? SoundEvents.ARMOR_EQUIP_IRON.value() : SoundEvents.PLAYER_LEVELUP, 1f);
				default -> {
				}
			}
		}
	}

	private void play(Kill kill, SoundEvent sound, float pitch) {
		mc.level.playLocalSound(kill.feet.x, kill.feet.y + kill.height, kill.feet.z, sound, SoundSource.PLAYERS, 0.9f, pitch, false);
	}

	/** Star-shaped fireworks in blue and white, and a big ball, over their body. */
	private void blueAndWhite(LivingEntity entity) {
		IntList colors = IntList.of(ISRAEL_BLUE & 0xFFFFFF, 0xFFFFFF, STAR_BLUE & 0xFFFFFF);
		List<FireworkExplosion> bursts = List.of(
			new FireworkExplosion(FireworkExplosion.Shape.STAR, colors, IntList.of(0xFFFFFF), true, true),
			new FireworkExplosion(FireworkExplosion.Shape.LARGE_BALL, IntList.of(ISRAEL_BLUE & 0xFFFFFF, 0xFFFFFF), IntList.of(STAR_BLUE & 0xFFFFFF), false, true));
		Vec3 at = entity.position();
		//? if >=26.3 {
		/*mc.level.createFireworks(at.x, at.y + entity.getBbHeight() + 1, at.z, 0, 0, 0, bursts, true);
		*///?} else {
		mc.level.createFireworks(at.x, at.y + entity.getBbHeight() + 1, at.z, 0, 0, 0, bursts);
		//?}
	}

	// ---------------------------------------------------------------- drawing

	@Override
	public void onRender3D(Render3DEvent event) {
		TungSahurModule tung = tung();
		if (!tung.isEnabled() && tung.running()) tung.onRender3D(event);
		if (kills.isEmpty() || mc.level == null) return;
		Vec3 cam = event.camera().pos;
		boolean xray = walls.get();

		// Solid-ish things first (the rug, bills, cards, the flag), then light, then glows, then words
		// The flag's cloth is solid, so the star on it is properly in front
		if (kills.stream().anyMatch(kill -> kill.animation == Animation.ISRAEL_FLAG)) {
			VertexConsumer solid = FxRender.solidQuads(event);
			for (Kill kill : kills) if (kill.animation == Animation.ISRAEL_FLAG) flagFlat(event, solid, kill, cam, kill.age());
		}
		VertexConsumer flat = FxRender.quads(event, false, xray);
		for (Kill kill : kills) {
			float t = kill.age();
			switch (kill.animation) {
				case RUG_PULL -> rugFlat(event, flat, kill, cam, t);
				case MARGIN_CALL -> marginFlat(event, flat, kill, cam, t);
				case CARD_DECLINED -> cardFlat(event, flat, kill, cam, t);
				default -> {
				}
			}
		}
		VertexConsumer light = FxRender.quads(event, true, xray);
		for (Kill kill : kills) {
			float t = kill.age();
			switch (kill.animation) {
				case RUG_PULL -> rugLight(event, light, kill, cam, t);
				case MARGIN_CALL -> marginLight(event, light, kill, cam, t);
				case STAR_OF_DAVID -> starLight(event, light, kill, cam, t);
				default -> {
				}
			}
		}
		VertexConsumer glow = FxRender.textured(event, FxRender.GLOW, true, xray);
		for (Kill kill : kills) {
			float t = kill.age();
			switch (kill.animation) {
				case RUG_PULL -> rugGlow(event, glow, kill, t);
				case MARGIN_CALL -> marginGlow(event, glow, kill, t);
				case EXIT_LIQUIDITY -> liquidityGlow(event, glow, kill, t);
				case CARD_DECLINED -> cardGlow(event, glow, kill, t);
				case STAR_OF_DAVID -> starGlow(event, glow, kill, t);
				case ISRAEL_FLAG -> flagGlow(event, glow, kill, t);
				default -> {
				}
			}
		}
		for (Kill kill : kills) {
			float t = kill.age();
			switch (kill.animation) {
				case RUG_PULL -> rugText(event, kill, t);
				case MARGIN_CALL -> marginText(event, kill, t);
				case EXIT_LIQUIDITY -> liquidityText(event, kill, t);
				case CARD_DECLINED -> cardText(event, kill, t);
				default -> {
				}
			}
		}
	}

	// ---------------------------------------------------------------- shared helpers

	private float size() {
		return scale.getFloat();
	}

	/** Fades everything out over the last half second. */
	private static float ending(Kill kill, float t) {
		return clamp((kill.animation.length / 1000f - t) / 0.5f);
	}

	private static float clamp(float v) {
		return Math.max(0, Math.min(1, v));
	}

	private static float easeOut(float t) {
		t = clamp(t);
		return 1 - (1 - t) * (1 - t) * (1 - t);
	}

	/** Sets up a pose at {@code at} turned so its XY plane faces where you were when they died. */
	private static PoseStack.Pose facing(Render3DEvent event, Kill kill, Vec3 at, Vec3 cam) {
		PoseStack matrices = event.matrices();
		matrices.pushPose();
		matrices.translate((float) (at.x - cam.x), (float) (at.y - cam.y), (float) (at.z - cam.z));
		matrices.mulPose(Axis.YP.rotation(kill.yaw));
		return matrices.last();
	}

	/** The world point {@code x} right and {@code y} up from {@code base}, in the plane facing you. */
	private static Vec3 inPlane(Kill kill, Vec3 base, double x, double y) {
		return base.add(Math.cos(kill.yaw) * x, y, -Math.sin(kill.yaw) * x);
	}

	private static String money(double value) {
		return (value < 0 ? "-$" : "$") + String.format(Locale.ROOT, "%,d", Math.round(Math.abs(value)));
	}

	private static String shortMoney(double value) {
		double v = Math.abs(value);
		String s = v >= 1e6 ? String.format(Locale.ROOT, "%.1fM", v / 1e6) : v >= 1e3 ? String.format(Locale.ROOT, "%.1fK", v / 1e3) : String.format(Locale.ROOT, "%.0f", v);
		return (value < 0 ? "-$" : "$") + s;
	}

	private static int argb(int color, float alpha) {
		return FxRender.fade(color, clamp(alpha));
	}

	/** Text that never quite vanishes (fully clear text draws solid). */
	private static void text(Render3DEvent event, String text, Vec3 at, int color, float alpha, float size) {
		if (alpha <= 0.02f) return;
		RenderUtil.drawText(event, text, at, argb(color, alpha), size);
	}

	// ---------------------------------------------------------------- Rug Pull

	/** The chart's price at {@code u} of the way along: a choppy climb to the peak. */
	private static double rugSeries(Kill kill, double u) {
		Random random = new Random(kill.seed);
		double noise = 0;
		for (int k = 1; k <= 3; k++) noise += Math.sin(u * (7 + random.nextInt(6)) * k + random.nextDouble() * 6) * 0.05 / k;
		return Math.max(0, Math.pow(u, 1.5) + noise * u);
	}

	private static double rugPeak(Kill kill) {
		return 400 + Math.abs(kill.seed % 600);
	}

	/** Chart coordinates in its plane: left edge, width, and its bottom and top heights. */
	private float chartLeft() {
		return -1.45f * size();
	}

	private float chartWidth() {
		return 2.6f * size();
	}

	private float chartBottom(Kill kill) {
		return kill.height * 0.4f;
	}

	private float chartHeight() {
		return 1.9f * size();
	}

	private void rugFlat(Render3DEvent event, VertexConsumer c, Kill kill, Vec3 cam, float t) {
		float fade = ending(kill, t);
		// The rug they're standing on, yanked out sideways when the crash hits
		float pull = t < 1.35f ? 0 : easeOut((t - 1.35f) / 0.3f);
		float rugAlpha = (t < 1.35f ? Math.min(1, t * 4) : 1 - pull) * fade;
		if (rugAlpha > 0.01f) {
			float s = size();
			Vec3 at = kill.feet.add(0, 0.03, 0);
			PoseStack matrices = event.matrices();
			matrices.pushPose();
			matrices.translate((float) (at.x - cam.x), (float) (at.y - cam.y), (float) (at.z - cam.z));
			matrices.mulPose(Axis.YP.rotation(kill.yaw));
			matrices.translate(-pull * 4 * s, 0, 0);
			matrices.mulPose(Axis.ZP.rotationDegrees(pull * 25));
			PoseStack.Pose pose = matrices.last();
			float w = 1.1f * s, d = 0.72f * s;
			flatRect(c, pose, -w, -d, w, d, argb(0xFFE8B23A, rugAlpha));
			flatRect(c, pose, -w + 0.06f * s, -d + 0.06f * s, w - 0.06f * s, d - 0.06f * s, argb(0xFFC0182E, rugAlpha));
			flatRect(c, pose, -w + 0.12f * s, -d + 0.12f * s, w - 0.12f * s, d - 0.12f * s, argb(0xFF8E0F22, rugAlpha));
			// The gold diamond in the middle and little ones at the ends
			diamond(c, pose, 0, 0, 0.3f * s, 0.22f * s, argb(GOLD, rugAlpha));
			diamond(c, pose, 0, 0, 0.16f * s, 0.11f * s, argb(0xFFC0182E, rugAlpha));
			diamond(c, pose, -0.48f * s, 0, 0.08f * s, 0.08f * s, argb(GOLD, rugAlpha));
			diamond(c, pose, 0.48f * s, 0, 0.08f * s, 0.08f * s, argb(GOLD, rugAlpha));
			// Tassels
			for (int i = 0; i <= 8; i++) {
				float z = -d + 2 * d * i / 8f;
				flatRect(c, pose, -w - 0.08f * s, z - 0.012f, -w, z + 0.012f, argb(0xFFF4E4C0, rugAlpha));
				flatRect(c, pose, w, z - 0.012f, w + 0.08f * s, z + 0.012f, argb(0xFFF4E4C0, rugAlpha));
			}
			matrices.popPose();
		}
		// Cash flying off the line as it pumps
		if (t < 2.2f) {
			Random random = new Random(kill.seed * 13);
			PoseStack.Pose pose = facing(event, kill, kill.feet, cam);
			for (int i = 0; i < 16; i++) {
				float born = i * 0.08f, life = t - born;
				float vx = (random.nextFloat() - 0.6f) * 1.6f, vy = 0.6f + random.nextFloat(), vz = (random.nextFloat() - 0.5f) * 1.2f;
				float spin = random.nextFloat() * 6;
				if (life <= 0 || life > 0.9f) continue;
				float u = Math.min(1, born / 1.3f);
				float x0 = chartLeft() + chartWidth() * u, y0 = chartBottom(kill) + chartHeight() * (float) rugSeries(kill, u);
				float x = x0 + vx * life, y = y0 + vy * life - 2.5f * life * life, z = vz * life;
				float a = (1 - life / 0.9f) * fade;
				float rot = spin + life * 8, bw = 0.17f * size(), bh = 0.085f * size();
				float cx = (float) Math.cos(rot), sx = (float) Math.sin(rot);
				int green = argb(0xFF5DBB63, a), dark = argb(0xFF2E7D32, a);
				FxRender.quad(c, pose, x - bw * cx, y - bh, z - bw * sx, green, x + bw * cx, y - bh, z + bw * sx, green,
					x + bw * cx, y + bh, z + bw * sx, dark, x - bw * cx, y + bh, z - bw * sx, dark);
			}
			event.matrices().popPose();
		}
	}

	private static void flatRect(VertexConsumer c, PoseStack.Pose pose, float x1, float z1, float x2, float z2, int color) {
		FxRender.quad(c, pose, x1, 0, z1, color, x1, 0, z2, color, x2, 0, z2, color, x2, 0, z1, color);
	}

	private static void diamond(VertexConsumer c, PoseStack.Pose pose, float cx, float cz, float rx, float rz, int color) {
		FxRender.quad(c, pose, cx - rx, 0.002f, cz, color, cx, 0.002f, cz + rz, color, cx + rx, 0.002f, cz, color, cx, 0.002f, cz - rz, color);
	}

	private void rugLight(Render3DEvent event, VertexConsumer c, Kill kill, Vec3 cam, float t) {
		float fade = ending(kill, t);
		PoseStack.Pose pose = facing(event, kill, kill.feet, cam);
		float left = chartLeft(), width = chartWidth(), bottom = chartBottom(kill), height = chartHeight();
		// Faint gridlines at $0, half and the top
		for (int k = 0; k <= 2; k++) {
			float y = bottom + height * k / 2f;
			FxRender.strokeXY(c, pose, left, y, left + width + 0.25f * size(), y, 0.008f, argb(0xFFFFFFFF, 0.25f * fade * Math.min(1, t * 3)), argb(0xFFFFFFFF, 0.08f * fade));
		}
		// The line pumping up, then the last of it dropping off a cliff
		float reveal = clamp(t / 1.35f);
		int segments = 40;
		for (int i = 0; i < segments; i++) {
			float u1 = i / (float) segments, u2 = (i + 1) / (float) segments;
			if (u1 >= reveal) break;
			u2 = Math.min(u2, reveal);
			float x1 = left + width * u1, x2 = left + width * u2;
			float y1 = bottom + height * (float) rugSeries(kill, u1), y2 = bottom + height * (float) rugSeries(kill, u2);
			int color = argb(FxRender.mix(0xFF2EE8C8, MONEY_GREEN, u1), fade);
			FxRender.strokeXY(c, pose, x1, y1, x2, y2, 0.085f * size(), color, color);
		}
		if (t >= 1.4f) {
			float tipX = left + width, tipY = bottom + height * (float) rugSeries(kill, 1);
			// The crash: a beam straight down into them
			float drop = easeOut((t - 1.4f) / 0.12f);
			float beamAlpha = fade * (t < 1.6f ? 1 : 0.75f + 0.25f * (float) Math.sin(t * 20));
			float beamX = 0.06f * size();
			float bottomY = tipY + (0.05f - tipY) * drop;
			FxRender.quad(c, pose, tipX - beamX, bottomY, 0, argb(BEAM_PINK, beamAlpha), tipX + beamX, bottomY, 0, argb(BEAM_PINK, beamAlpha),
				tipX + beamX, tipY, 0, argb(0xFFFFFFFF, beamAlpha), tipX - beamX, tipY, 0, argb(0xFFFFFFFF, beamAlpha));
			FxRender.quad(c, pose, tipX - beamX * 4, bottomY, 0, argb(BEAM_PINK, 0), tipX - beamX, bottomY, 0, argb(BEAM_PINK, beamAlpha * 0.5f),
				tipX - beamX, tipY, 0, argb(BEAM_PINK, beamAlpha * 0.5f), tipX - beamX * 4, tipY, 0, argb(BEAM_PINK, 0));
			FxRender.quad(c, pose, tipX + beamX, bottomY, 0, argb(BEAM_PINK, beamAlpha * 0.5f), tipX + beamX * 4, bottomY, 0, argb(BEAM_PINK, 0),
				tipX + beamX * 4, tipY, 0, argb(BEAM_PINK, 0), tipX + beamX, tipY, 0, argb(BEAM_PINK, beamAlpha * 0.5f));
		}
		event.matrices().popPose();

		// A red shockwave and cracks across the ground where the beam lands
		if (t >= 1.5f) {
			float since = t - 1.5f;
			Vec3 ground = inPlane(kill, kill.feet, chartLeft() + chartWidth(), 0.03);
			PoseStack matrices = event.matrices();
			matrices.pushPose();
			matrices.translate((float) (ground.x - cam.x), (float) (ground.y - cam.y), (float) (ground.z - cam.z));
			PoseStack.Pose g = matrices.last();
			float r = 2.4f * size() * easeOut(since / 0.6f), ringAlpha = clamp(1 - since / 0.9f) * fade;
			FxRender.ring(c, g, Math.max(0, r - 0.25f), r, 0, 56, (a, inner) -> argb(CRASH_RED, inner ? 0 : ringAlpha));
			FxRender.ring(c, g, 0, 0.35f * size(), 0, 24, (a, inner) -> argb(BEAM_PINK, inner ? 0.9f * fade : 0));
			Random random = new Random(kill.seed * 5);
			float grow = easeOut(since / 0.25f), crackAlpha = clamp(1.2f - since / 1.6f) * fade;
			for (int k = 0; k < 8; k++) {
				double a = random.nextDouble() * Math.PI * 2;
				float x = 0, z = 0, len = (0.9f + random.nextFloat()) * size() * grow;
				for (int seg = 0; seg < 4; seg++) {
					a += (random.nextDouble() - 0.5) * 0.9;
					float nx = x + (float) Math.cos(a) * len / 4, nz = z + (float) Math.sin(a) * len / 4;
					FxRender.strokeXZ(c, g, x, z, nx, nz, 0, 0.03f * (1 - seg * 0.2f), argb(0xFFFFE0EA, crackAlpha), argb(CRASH_RED, crackAlpha * 0.6f));
					x = nx;
					z = nz;
				}
			}
			matrices.popPose();
		}
	}

	private void rugGlow(Render3DEvent event, VertexConsumer glow, Kill kill, float t) {
		float fade = ending(kill, t);
		float reveal = clamp(t / 1.35f);
		float tipX = chartLeft() + chartWidth() * reveal, tipY = chartBottom(kill) + chartHeight() * (float) rugSeries(kill, reveal);
		Vec3 tip = inPlane(kill, kill.feet, tipX, tipY);
		if (t < 1.4f) FxRender.glow(event, glow, tip, 0.3f * size(), argb(0xFFFFFFFF, 0.9f));
		else {
			float since = t - 1.4f;
			Vec3 ground = inPlane(kill, kill.feet, chartLeft() + chartWidth(), 0.1);
			FxRender.glow(event, glow, ground, (0.8f + 0.6f * clamp(since / 0.2f)) * size(), argb(BEAM_PINK, clamp(1 - since / 1.2f) * fade + 0.3f * fade));
			FxRender.glow(event, glow, ground, 0.45f * size(), argb(0xFFFFFFFF, 0.8f * fade));
			Vec3 top = inPlane(kill, kill.feet, chartLeft() + chartWidth(), chartBottom(kill) + chartHeight() * rugSeries(kill, 1));
			FxRender.glow(event, glow, top, 0.4f * size(), argb(0xFFFFFFFF, 0.7f * fade));
		}
	}

	private void rugText(Render3DEvent event, Kill kill, float t) {
		float fade = ending(kill, t), s = size();
		double peak = rugPeak(kill);
		float left = chartLeft(), width = chartWidth(), bottom = chartBottom(kill), height = chartHeight();
		// Axis labels at the right of the gridlines
		for (int k = 0; k <= 2; k++) {
			String label = k == 0 ? "$0" : shortMoney(peak * k / 2 * 1.02);
			text(event, label, inPlane(kill, kill.feet, left + width + 0.6f * s, bottom + height * k / 2f), 0xFFFFFFFF, 0.5f * fade * Math.min(1, t * 3), 0.5f * s);
		}
		if (t < 1.4f) {
			float reveal = clamp(t / 1.35f);
			double price = peak * rugSeries(kill, reveal) / rugSeries(kill, 1);
			Vec3 tip = inPlane(kill, kill.feet, left + width * reveal, bottom + height * rugSeries(kill, reveal) + 0.45 * s);
			text(event, money(price), tip, MONEY_GREEN, 1, 1.4f * s);
			return;
		}
		float since = t - 1.4f;
		double loss = Math.round(peak * 68 / 1000.0) * 1000;
		double shown = peak + (-loss - peak) * easeOut(since / 0.45f);
		double top = bottom + height * rugSeries(kill, 1);
		Vec3 above = inPlane(kill, kill.feet, left + width * 0.6, top + 0.65 * s);
		text(event, money(shown), above, CRASH_RED, fade, 1.7f * s);
		text(event, String.format(Locale.ROOT, "%,d%%", -Math.round(loss / peak * 100 / 100) * 100), above.add(0, -0.42 * s, 0), CRASH_RED, fade * 0.85f, 0.6f * s);

		// "YOU JUST GOT RUG PULLED" flying in a letter at a time along an arc, then settling in a line
		String line = "YOU JUST GOT RUG PULLED";
		Font font = mc.font;
		float letterScale = 0.9f * s, unit = letterScale / 16f;
		float total = font.width(line) * unit, x = -total / 2;
		double lineY = top - 0.25 * s;
		for (int i = 0; i < line.length(); i++) {
			char ch = line.charAt(i);
			float w = font.width(String.valueOf(ch)) * unit;
			float arrive = 0.15f + i * 0.035f, p = easeOut((since - arrive) / 0.35f);
			if (since >= arrive && ch != ' ') {
				double angle = Math.PI * (0.15 + 0.7 * i / line.length());
				double fromX = left + width * 0.6 - Math.cos(angle) * 1.6 * s, fromY = lineY + Math.sin(angle) * 1.6 * s;
				double toX = left + width * 0.6 + x + w / 2;
				Vec3 at = inPlane(kill, kill.feet, fromX + (toX - fromX) * p, fromY + (lineY - fromY) * p);
				text(event, String.valueOf(ch), at, CRASH_RED, fade * Math.min(1, (since - arrive) * 6), letterScale);
			}
			x += w;
		}
		if (since > 1f) {
			float pop = easeOut((since - 1f) / 0.2f);
			text(event, "LOSER.", inPlane(kill, kill.feet, left + width * 0.6, lineY - 0.5 * s), 0xFFFFFFFF, fade * pop, (0.8f + 0.25f * (1 - pop)) * s);
		}
	}

	// ---------------------------------------------------------------- Margin Call

	private float panelBottom(Kill kill) {
		return kill.height + 0.35f;
	}

	private void marginFlat(Render3DEvent event, VertexConsumer c, Kill kill, Vec3 cam, float t) {
		float fade = ending(kill, t), s = size();
		float w = 1.25f * s, h = 0.8f * s, y0 = panelBottom(kill);
		float appear = easeOut(t / 0.25f);
		PoseStack.Pose pose = facing(event, kill, kill.feet, cam);
		if (t < 1.25f) {
			float liq = clamp((t - 0.2f) / 1f);
			int border = argb(FxRender.mix(MONEY_GREEN, CRASH_RED, liq), fade * appear);
			panel(c, pose, -w * appear, y0, w * appear, y0 + h * 2 * appear, argb(0xFF0E1420, 0.85f * fade * appear), border, s);
			// The position bar: entry in the middle, the liquidation line on the left, the price sliding down to it
			float barY = y0 + 0.16f * s, barL = -w + 0.12f * s, barR = w - 0.12f * s;
			FxRender.quad(c, pose, barL, barY, 0.002f, argb(0xFF2A3344, fade), barR, barY, 0.002f, argb(0xFF2A3344, fade),
				barR, barY + 0.06f * s, 0.002f, argb(0xFF2A3344, fade), barL, barY + 0.06f * s, 0.002f, argb(0xFF2A3344, fade));
			float mid = (barL + barR) / 2, price = mid + (barL + 0.08f * s - mid) * liq * liq;
			int fill = argb(FxRender.mix(MONEY_GREEN, CRASH_RED, liq), fade);
			FxRender.quad(c, pose, Math.min(price, mid), barY, 0.004f, fill, Math.max(price, mid), barY, 0.004f, fill,
				Math.max(price, mid), barY + 0.06f * s, 0.004f, fill, Math.min(price, mid), barY + 0.06f * s, 0.004f, fill);
		} else {
			// The card shatters
			Random random = new Random(kill.seed * 3);
			float since = t - 1.25f;
			for (int i = 0; i < 18; i++) {
				float cx = (random.nextFloat() * 2 - 1) * w, cy = y0 + random.nextFloat() * h * 2;
				float vx = cx * 2.2f + (random.nextFloat() - 0.5f), vy = 1.2f + random.nextFloat() * 1.5f, vz = (random.nextFloat() - 0.3f) * 2;
				float x = cx + vx * since, y = cy + vy * since - 6 * since * since, z = vz * since;
				float sz = (0.06f + random.nextFloat() * 0.1f) * s, rot = since * (4 + random.nextFloat() * 8);
				float cr = (float) Math.cos(rot) * sz, sr = (float) Math.sin(rot) * sz;
				int col = argb(i % 3 == 0 ? CRASH_RED : 0xFF0E1420, clamp(1 - since / 1.4f) * fade);
				FxRender.triangle(c, pose, x + cr, y + sr, z, col, x - sr, y + cr, z, col, x - cr, y - sr, z + sz * 0.3f, col);
			}
		}
		event.matrices().popPose();
	}

	/** A dark rounded-ish panel with a coloured border. */
	private static void panel(VertexConsumer c, PoseStack.Pose pose, float x1, float y1, float x2, float y2, int fill, int border, float s) {
		float b = 0.025f * s;
		FxRender.quad(c, pose, x1, y1, 0, border, x2, y1, 0, border, x2, y2, 0, border, x1, y2, 0, border);
		FxRender.quad(c, pose, x1 + b, y1 + b, 0.001f, fill, x2 - b, y1 + b, 0.001f, fill, x2 - b, y2 - b, 0.001f, fill, x1 + b, y2 - b, 0.001f, fill);
	}

	private void marginLight(Render3DEvent event, VertexConsumer c, Kill kill, Vec3 cam, float t) {
		if (t < 1.2f) return;
		// A red pulse ring rolling out from them as they're liquidated
		float since = t - 1.2f;
		PoseStack matrices = event.matrices();
		matrices.pushPose();
		matrices.translate((float) (kill.feet.x - cam.x), (float) (kill.feet.y - cam.y) + 0.03f, (float) (kill.feet.z - cam.z));
		float r = 2.2f * size() * easeOut(since / 0.5f), a = clamp(1 - since / 0.8f);
		FxRender.ring(c, matrices.last(), Math.max(0, r - 0.2f), r, 0, 48, (u, inner) -> argb(CRASH_RED, inner ? 0 : a));
		matrices.popPose();
	}

	private void marginGlow(Render3DEvent event, VertexConsumer glow, Kill kill, float t) {
		if (t < 1.2f) return;
		float since = t - 1.2f;
		Vec3 middle = kill.feet.add(0, kill.height * 0.6, 0);
		FxRender.glow(event, glow, middle, (1 + 1.2f * easeOut(since / 0.2f)) * size(), argb(CRASH_RED, clamp(1 - since / 0.7f)));
	}

	private void marginText(Render3DEvent event, Kill kill, float t) {
		float fade = ending(kill, t), s = size();
		float y0 = panelBottom(kill), h = 0.8f * s;
		if (t < 1.25f) {
			float appear = easeOut(t / 0.25f);
			float liq = clamp((t - 0.2f) / 1f);
			double entry = kill.worth / 40, liqPrice = entry * 0.99;
			double price = entry - (entry - liqPrice) * liq * liq;
			text(event, "LONG 100x  " + kill.name.toUpperCase(Locale.ROOT), inPlane(kill, kill.feet, 0, y0 + h * 1.72), 0xFFFFFFFF, fade * appear, 0.5f * s);
			text(event, "Entry " + money(entry) + "   Liq. " + money(liqPrice), inPlane(kill, kill.feet, 0, y0 + h * 1.4), 0xFFAAB4C8, fade * appear, 0.34f * s);
			int pnlColor = liq < 0.5f ? MONEY_GREEN : CRASH_RED;
			double pnl = (price - entry) / entry * 100 * 100;
			text(event, String.format(Locale.ROOT, "%+.1f%%", pnl), inPlane(kill, kill.feet, 0, y0 + h * 0.88), pnlColor, fade * appear, 1.05f * s);
			text(event, money(price), inPlane(kill, kill.feet, 0, y0 + h * 0.52), 0xFFFFFFFF, fade * appear * 0.8f, 0.48f * s);
		}
		if (t >= 1.2f) {
			float since = t - 1.2f, pop = easeOut(since / 0.15f);
			text(event, "LIQUIDATED", inPlane(kill, kill.feet, 0, y0 + h), CRASH_RED, fade, (1.6f + 1.4f * (1 - pop)) * s);
			if (since > 0.5f) text(event, "-" + shortMoney(kill.worth).substring(1) + " gone", inPlane(kill, kill.feet, 0, y0 + h * 0.45), 0xFFFFFFFF, fade * clamp((since - 0.5f) * 4), 0.65f * s);
		}
	}

	// ---------------------------------------------------------------- Exit Liquidity

	private Vec3 yourChest() {
		float tickDelta = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		return mc.player.getPosition(tickDelta).add(0, mc.player.getBbHeight() * 0.6, 0);
	}

	private void liquidityGlow(Render3DEvent event, VertexConsumer glow, Kill kill, float t) {
		Vec3 from = kill.feet.add(0, kill.height * 0.6, 0), to = yourChest();
		Random random = new Random(kill.seed * 11);
		int coins = 36;
		for (int i = 0; i < coins; i++) {
			float born = i * 0.05f, flight = 0.55f + random.nextFloat() * 0.25f;
			Vec3 bend = new Vec3(random.nextGaussian(), 0.8 + random.nextFloat() * 1.2, random.nextGaussian()).scale(size());
			float p = (t - born) / flight;
			if (p <= 0 || p >= 1) continue;
			float e = p * p * (3 - 2 * p);
			Vec3 mid = from.add(to).scale(0.5).add(bend);
			Vec3 at = from.lerp(mid, e).lerp(mid.lerp(to, e), e);
			float spin = (float) Math.abs(Math.sin(t * 12 + i));
			FxRender.glow(event, glow, at, (0.13f + 0.05f * spin) * size(), argb(GOLD, 0.95f));
			FxRender.glow(event, glow, at, 0.06f * size(), argb(0xFFFFFFFF, 0.8f * spin));
		}
		// Gold glow round them while the money's leaving, and a flash on you as it lands
		float leaving = clamp(1 - Math.abs(t - 1f) / 1f);
		FxRender.glow(event, glow, from, 1.1f * size(), argb(GOLD, 0.3f * leaving));
		FxRender.glow(event, glow, to.add(mc.player.getViewVector(1).scale(0.6)), 0.5f, argb(GOLD, 0.25f * leaving));
	}

	private void liquidityText(Render3DEvent event, Kill kill, float t) {
		float fade = ending(kill, t), s = size();
		float drained = clamp(t / 2f);
		double left = kill.worth * (1 - drained);
		Vec3 above = kill.feet.add(0, kill.height + 0.8 * s, 0);
		text(event, shortMoney(left), above, drained < 1 ? 0xFFFFE08A : CRASH_RED, fade, 1.2f * s);
		Vec3 halfway = kill.feet.add(0, kill.height * 0.5, 0).lerp(yourChest(), 0.4).add(0, 0.6 * s, 0);
		text(event, "+" + shortMoney(kill.worth * drained), halfway, MONEY_GREEN, fade * Math.min(1, t * 4), 0.95f * s);
		if (t > 2f) {
			float pop = easeOut((t - 2f) / 0.2f);
			text(event, "EXIT LIQUIDITY", above.add(0, 0.6 * s, 0), 0xFFFFFFFF, fade * pop, (1.1f + 0.4f * (1 - pop)) * s);
		}
	}

	// ---------------------------------------------------------------- Card Declined

	private float cardY(Kill kill) {
		return kill.height + 1.1f;
	}

	private void cardFlat(Render3DEvent event, VertexConsumer c, Kill kill, Vec3 cam, float t) {
		float fade = ending(kill, t), s = size() * 1.6f;
		float w = 0.43f * s, h = 0.27f * s;
		float y = cardY(kill);
		PoseStack matrices = event.matrices();
		facing(event, kill, kill.feet, cam);
		// The card reader under it
		float readerAlpha = clamp(t / 0.3f) * fade;
		float rx = 0.5f * s, ry = y - 0.55f * s;
		boolean declined = t >= 0.85f;
		FxRender.quad(c, matrices.last(), -rx, ry - 0.12f * s, 0, argb(0xFF1A1A1E, readerAlpha), rx, ry - 0.12f * s, 0, argb(0xFF1A1A1E, readerAlpha),
			rx, ry + 0.05f * s, 0, argb(0xFF2C2C33, readerAlpha), -rx, ry + 0.05f * s, 0, argb(0xFF2C2C33, readerAlpha));
		int led = declined ? CRASH_RED : t > 0.5f ? 0xFFFFD34A : 0xFF444444;
		FxRender.quad(c, matrices.last(), rx - 0.14f * s, ry - 0.07f * s, 0.003f, argb(led, readerAlpha), rx - 0.06f * s, ry - 0.07f * s, 0.003f, argb(led, readerAlpha),
			rx - 0.06f * s, ry - 0.01f * s, 0.003f, argb(led, readerAlpha), rx - 0.14f * s, ry - 0.01f * s, 0.003f, argb(led, readerAlpha));

		if (t < 1.45f) {
			// Spins in, then swipes down through the reader and back up
			float spinIn = easeOut(t / 0.45f);
			float swipe = t < 0.5f ? 0 : t < 0.7f ? (t - 0.5f) / 0.2f : Math.max(0, 1 - (t - 0.7f) / 0.25f);
			matrices.pushPose();
			matrices.translate(0, y - swipe * 0.45f * s, 0);
			matrices.mulPose(Axis.ZP.rotationDegrees((1 - spinIn) * 540));
			matrices.scale(spinIn, spinIn, spinIn);
			card(c, matrices.last(), -w, w, h, fade, s);
			matrices.popPose();
		} else {
			// Snapped in half, the halves twisting apart and falling
			float since = t - 1.45f;
			for (int side = -1; side <= 1; side += 2) {
				matrices.pushPose();
				matrices.translate(side * since * 0.6f * s, y - 3 * since * since, side * since * 0.2f);
				matrices.mulPose(Axis.ZP.rotationDegrees(-side * since * 160));
				float a = clamp(1 - since / 1.2f) * fade;
				card(c, matrices.last(), side < 0 ? -w : 0, side < 0 ? 0 : w, h, a, s);
				matrices.popPose();
			}
		}
		matrices.popPose();
	}

	/** The part of a black card from {@code x1} to {@code x2}, with its gold chip and stripe. */
	private static void card(VertexConsumer c, PoseStack.Pose pose, float x1, float x2, float h, float alpha, float s) {
		int top = argb(0xFF2B2B31, alpha), bottom = argb(0xFF0B0B0E, alpha);
		FxRender.quad(c, pose, x1, -h, 0, bottom, x2, -h, 0, bottom, x2, h, 0, top, x1, h, 0, top);
		// Chip
		float cx1 = -0.3f * s, cx2 = -0.18f * s;
		if (cx2 > x1 && cx1 < x2) {
			float l = Math.max(cx1, x1), r = Math.min(cx2, x2);
			FxRender.quad(c, pose, l, 0.02f * s, 0.002f, argb(0xFFD9A93A, alpha), r, 0.02f * s, 0.002f, argb(0xFFD9A93A, alpha),
				r, 0.11f * s, 0.002f, argb(0xFFF3D27A, alpha), l, 0.11f * s, 0.002f, argb(0xFFF3D27A, alpha));
		}
		// A thin gold line along the bottom
		FxRender.quad(c, pose, x1, -h + 0.04f * s, 0.002f, argb(0xFFC9A24A, alpha * 0.8f), x2, -h + 0.04f * s, 0.002f, argb(0xFFC9A24A, alpha * 0.8f),
			x2, -h + 0.055f * s, 0.002f, argb(0xFFC9A24A, alpha * 0.8f), x1, -h + 0.055f * s, 0.002f, argb(0xFFC9A24A, alpha * 0.8f));
	}

	private void cardGlow(Render3DEvent event, VertexConsumer glow, Kill kill, float t) {
		if (t < 0.85f) return;
		float since = t - 0.85f;
		Vec3 at = inPlane(kill, kill.feet, 0, cardY(kill));
		FxRender.glow(event, glow, at, (0.8f + 0.5f * easeOut(since / 0.15f)) * size(), argb(CRASH_RED, clamp(1 - since / 0.9f) * 0.8f));
	}

	private void cardText(Render3DEvent event, Kill kill, float t) {
		float fade = ending(kill, t), s = size() * 1.4f;
		if (t > 0.3f && t < 1.45f) text(event, kill.name.toUpperCase(Locale.ROOT), inPlane(kill, kill.feet, 0, cardY(kill) - 0.12f * s), 0xFFE8E8EE, fade * clamp((t - 0.3f) * 4), 0.26f * s);
		if (t >= 0.85f) {
			float since = t - 0.85f, pop = easeOut(since / 0.12f);
			text(event, "DECLINED", inPlane(kill, kill.feet, 0, cardY(kill) + 0.5f * s), CRASH_RED, fade, (0.95f + 0.6f * (1 - pop)) * s);
			if (since > 0.35f) text(event, "Insufficient funds", inPlane(kill, kill.feet, 0, cardY(kill) + 0.25f * s), 0xFFFFFFFF, fade * clamp((since - 0.35f) * 4), 0.38f * s);
		}
	}

	// ---------------------------------------------------------------- Star of David

	/** The six points of the star, outer radius 1, as two triangles. */
	private static float[][] starPoints(boolean down) {
		float[][] p = new float[3][2];
		for (int i = 0; i < 3; i++) {
			double a = Math.PI / 2 + (down ? Math.PI : 0) + Math.PI * 2 * i / 3;
			p[i][0] = (float) Math.cos(a);
			p[i][1] = (float) Math.sin(a);
		}
		return p;
	}

	private void starLight(Render3DEvent event, VertexConsumer c, Kill kill, Vec3 cam, float t) {
		float fade = ending(kill, t), s = size();
		// Drawn on the ground first, one stroke after another, inside a circle
		float groundAlpha = (t < 1.45f ? 1 : clamp(1 - (t - 1.45f) / 0.8f)) * fade;
		if (groundAlpha > 0.01f) {
			PoseStack matrices = event.matrices();
			matrices.pushPose();
			matrices.translate((float) (kill.feet.x - cam.x), (float) (kill.feet.y - cam.y) + 0.04f, (float) (kill.feet.z - cam.z));
			matrices.mulPose(Axis.YP.rotation(kill.yaw));
			PoseStack.Pose pose = matrices.last();
			float r = 1.4f * s, draw = clamp(t / 0.6f) * 6;
			FxRender.ring(c, pose, r * 1.08f, r * 1.12f, 0, 64, (a, inner) -> argb(STAR_BLUE, groundAlpha * clamp(t / 0.3f)));
			int stroke = 0;
			for (int tri = 0; tri < 2; tri++) {
				float[][] p = starPoints(tri == 1);
				for (int i = 0; i < 3; i++, stroke++) {
					float part = clamp(draw - stroke);
					if (part <= 0) continue;
					float x1 = p[i][0] * r, z1 = p[i][1] * r, x2 = p[(i + 1) % 3][0] * r, z2 = p[(i + 1) % 3][1] * r;
					float ex = x1 + (x2 - x1) * part, ez = z1 + (z2 - z1) * part;
					FxRender.strokeXZ(c, pose, x1, z1, ex, ez, 0, 0.09f * s, argb(STAR_BLUE, groundAlpha), argb(STAR_BLUE, groundAlpha));
					FxRender.strokeXZ(c, pose, x1, z1, ex, ez, 0.003f, 0.035f * s, argb(0xFFFFFFFF, groundAlpha), argb(0xFFFFFFFF, groundAlpha));
				}
			}
			matrices.popPose();
		}
		// Then a star stands up out of it, rising over them and turning, until it bursts
		if (t >= 0.6f && t < 1.5f) {
			float rise = easeOut((t - 0.6f) / 0.8f);
			Vec3 at = kill.feet.add(0, (kill.height + 0.9f) * rise, 0);
			PoseStack matrices = event.matrices();
			matrices.pushPose();
			matrices.translate((float) (at.x - cam.x), (float) (at.y - cam.y), (float) (at.z - cam.z));
			matrices.mulPose(Axis.YP.rotation(kill.yaw + rise * (float) Math.PI * 2));
			PoseStack.Pose pose = matrices.last();
			float r = (0.35f + 0.35f * rise) * s;
			for (int tri = 0; tri < 2; tri++) {
				float[][] p = starPoints(tri == 1);
				for (int i = 0; i < 3; i++) {
					float x1 = p[i][0] * r, y1 = p[i][1] * r, x2 = p[(i + 1) % 3][0] * r, y2 = p[(i + 1) % 3][1] * r;
					FxRender.strokeXY(c, pose, x1, y1, x2, y2, 0.08f * s, argb(STAR_BLUE, fade), argb(STAR_BLUE, fade));
					FxRender.strokeXY(c, pose, x1, y1, x2, y2, 0.03f * s, argb(0xFFFFFFFF, fade), argb(0xFFFFFFFF, fade));
				}
			}
			matrices.popPose();
		}
	}

	private void starGlow(Render3DEvent event, VertexConsumer glow, Kill kill, float t) {
		float fade = ending(kill, t), s = size();
		if (t < 0.6f) {
			FxRender.glow(event, glow, kill.feet.add(0, 0.1, 0), 1.6f * s, argb(STAR_BLUE, 0.3f * clamp(t / 0.6f)));
			return;
		}
		Vec3 top = kill.feet.add(0, kill.height + 0.9f, 0);
		if (t < 1.5f) {
			float rise = easeOut((t - 0.6f) / 0.8f);
			Vec3 at = kill.feet.add(0, (kill.height + 0.9f) * rise, 0);
			FxRender.glow(event, glow, at, (0.8f + 0.5f * rise) * s, argb(STAR_BLUE, 0.6f * fade));
			FxRender.glow(event, glow, at, 0.4f * s, argb(0xFFFFFFFF, 0.6f * fade));
			return;
		}
		// The burst: a white flash and blue and white sparks flying out in every direction
		float since = t - 1.5f;
		FxRender.glow(event, glow, top, (1 + 1.6f * easeOut(since / 0.2f)) * s, argb(0xFFFFFFFF, clamp(1 - since / 0.5f)));
		FxRender.glow(event, glow, top, 2.4f * s, argb(STAR_BLUE, 0.5f * clamp(1 - since / 0.8f)));
		Random random = new Random(kill.seed * 7);
		for (int i = 0; i < 36; i++) {
			Vec3 v = new Vec3(random.nextGaussian(), random.nextGaussian() * 0.7 + 0.3, random.nextGaussian()).normalize().scale((2 + random.nextFloat() * 2.5) * s);
			Vec3 at = top.add(v.scale(since)).add(0, -2 * since * since, 0);
			float a = clamp(1 - since / (0.8f + random.nextFloat() * 0.5f)) * fade;
			FxRender.glow(event, glow, at, 0.12f * s, argb(i % 2 == 0 ? STAR_BLUE : 0xFFFFFFFF, a));
		}
	}

	// ---------------------------------------------------------------- Israel flag

	private void flagFlat(Render3DEvent event, VertexConsumer c, Kill kill, Vec3 cam, float t) {
		// Drawn solid so the star sits properly on the cloth; at the end it sinks back into the ground instead of fading
		float stay = easeOut(ending(kill, t)), s = size();
		Vec3 base = inPlane(kill, kill.feet, 0.75f * s, 0);
		PoseStack matrices = event.matrices();
		facing(event, kill, base, cam);
		PoseStack.Pose pose = matrices.last();
		// The pole shoots up out of the ground, then the flag runs up it and waves
		float poleH = 3.1f * s * easeOut(t / 0.3f) * stay, pw = 0.035f * s;
		IsraelFlag.pole(c, pose, poleH, pw);
		float raise = easeOut((t - 0.3f) / 1f) * stay;
		if (t > 0.3f) {
			float fw = 1.5f * s * stay, fh = 1.08f * s * stay;
			float top = poleH - 0.05f * s;
			IsraelFlag.cloth(c, pose, pw, (top - fh) * raise, fw, fh, t, s);
		}
		matrices.popPose();
	}

	private void flagGlow(Render3DEvent event, VertexConsumer glow, Kill kill, float t) {
		float fade = ending(kill, t), s = size();
		Vec3 base = inPlane(kill, kill.feet, 0.75f * s, 0);
		// The gold ball on top, and blue and white sparkles once it's up
		FxRender.glow(event, glow, base.add(0, 3.1f * s * easeOut(t / 0.3f) * easeOut(fade) + 0.05, 0), 0.25f * s, argb(GOLD, fade));
		if (t < 1.3f) return;
		float since = t - 1.3f;
		Random random = new Random(kill.seed * 17);
		Vec3 middle = inPlane(kill, kill.feet, 0.75f * s + 0.75f * s, 2.5f * s);
		FxRender.glow(event, glow, middle, (1.2f + 0.6f * easeOut(since / 0.3f)) * s, argb(0xFFFFFFFF, 0.4f * clamp(1 - since / 0.8f) * fade));
		for (int i = 0; i < 24; i++) {
			double a = random.nextDouble() * Math.PI * 2, r = (0.8 + random.nextDouble() * 1.2) * s;
			double life = (since * (0.6 + random.nextDouble() * 0.6) + random.nextDouble()) % 1;
			Vec3 at = middle.add(Math.cos(a) * r, (life - 0.5) * 1.6 * s, Math.sin(a) * r * 0.4);
			float twinkle = (float) Math.sin(life * Math.PI);
			FxRender.glow(event, glow, at, 0.1f * s, argb(i % 2 == 0 ? STAR_BLUE : 0xFFFFFFFF, twinkle * fade));
		}
	}
}
