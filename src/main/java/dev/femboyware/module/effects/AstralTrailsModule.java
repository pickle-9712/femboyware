package dev.femboyware.module.effects;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.FxRender;
import dev.femboyware.util.HeldBlades;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Luminous ribbons streaming off your sword: the blade's real drawn position each frame (first or
 * third person, see HeldBlades) is stitched into a band from hilt to tip that fades out behind it,
 * brightest along the edge, with stardust shed from the tip.
 */
public class AstralTrailsModule extends PlayerFxModule {
	private final NumberSetting length = add(new NumberSetting("Length", "How long the ribbon lingers, in seconds", 0.35, 0.1, 1.2, 0.05));
	private final NumberSetting band = add(new NumberSetting("Band", "How much of the blade the ribbon covers, from the tip down", 0.75, 0.2, 1, 0.05));
	private final NumberSetting brightness = add(new NumberSetting("Brightness", "How bright the ribbon is", 0.7, 0.2, 1, 0.05));
	private final NumberSetting firstPerson = add(new NumberSetting("First Person", "How bright it is on your own blade in first person, where it's right in front of you", 0.45, 0, 1, 0.05));
	private final BoolSetting swingsOnly = add(new BoolSetting("Swings Only", "Only trail swings, not every turn of your head", true));
	private final BoolSetting stardust = add(new BoolSetting("Stardust", "Sparks shed from the tip as it moves", true));
	private final BoolSetting edge = add(new BoolSetting("Edge Glow", "A glowing line along the tip's path", true));
	private final Palette palette = palette(0xFF9B7BFF, 0xFF6FF2FF);
	private final EnumSetting<ShowOn> showOn = showOnSetting();

	public AstralTrailsModule() {
		super("Astral Trails", "Luminous ribbons following your sword, axe or mace as it's drawn");
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		Map<Long, ArrayDeque<HeldBlades.Sample>> trails = HeldBlades.trails();
		if (trails.isEmpty() || mc.level == null) return;
		Set<Integer> who = new HashSet<>();
		for (Player player : wearers(true)) who.add(player.getId());
		long now = System.nanoTime();
		double keep = length.get() * 1e9;
		Vec3 cam = event.camera().pos;
		PoseStack.Pose pose = event.matrices().last();
		VertexConsumer ribbon = FxRender.quads(event, true, false);
		List<Spark> sparks = new ArrayList<>();
		float from = 1 - band.getFloat();
		double time = time();

		for (Map.Entry<Long, ArrayDeque<HeldBlades.Sample>> entry : trails.entrySet()) {
			if (!who.contains(HeldBlades.playerOf(entry.getKey()))) continue;
			List<HeldBlades.Sample> samples = new ArrayList<>();
			for (HeldBlades.Sample s : entry.getValue()) if (now - s.nanos() <= keep) samples.add(s);
			if (samples.size() < 2) continue;
			for (int i = 1; i < samples.size(); i++) {
				HeldBlades.Sample a = samples.get(i - 1), b = samples.get(i);
				if (swingsOnly.get() && !(a.midSwing() && b.midSwing())) continue;
				float shine = this.brightness.getFloat() * (b.firstPerson() ? firstPerson.getFloat() : 1);
				// Fades out with age: newest is brightest
				float fa = 1 - (float) ((now - a.nanos()) / keep), fb = 1 - (float) ((now - b.nanos()) / keep);
				fa = fa * fa;
				fb = fb * fb;
				int ca = palette.at((float) (time * 0.25 + i * 0.015) % 1), cb = palette.at((float) (time * 0.25 + (i + 1) * 0.015) % 1);
				Vec3 a0 = lerp(a.hilt(), a.tip(), from), b0 = lerp(b.hilt(), b.tip(), from);
				// Dim at the hilt end of the band, bright at the tip
				FxRender.quad(ribbon, pose,
					rel(a0, cam, 0), rel(a0, cam, 1), rel(a0, cam, 2), FxRender.fade(ca, fa * shine * 0.12f),
					rel(b0, cam, 0), rel(b0, cam, 1), rel(b0, cam, 2), FxRender.fade(cb, fb * shine * 0.12f),
					rel(b.tip(), cam, 0), rel(b.tip(), cam, 1), rel(b.tip(), cam, 2), FxRender.fade(FxRender.lighten(cb, 0.35f), fb * shine),
					rel(a.tip(), cam, 0), rel(a.tip(), cam, 1), rel(a.tip(), cam, 2), FxRender.fade(FxRender.lighten(ca, 0.35f), fa * shine));
				if (edge.get()) {
					// A thin bright seam along the tip's path
					Vec3 ai = lerp(a.hilt(), a.tip(), 0.94), bi = lerp(b.hilt(), b.tip(), 0.94);
					FxRender.quad(ribbon, pose,
						rel(ai, cam, 0), rel(ai, cam, 1), rel(ai, cam, 2), FxRender.fade(0xFFFFFFFF, fa * shine * 0.6f),
						rel(bi, cam, 0), rel(bi, cam, 1), rel(bi, cam, 2), FxRender.fade(0xFFFFFFFF, fb * shine * 0.6f),
						rel(b.tip(), cam, 0), rel(b.tip(), cam, 1), rel(b.tip(), cam, 2), FxRender.fade(0xFFFFFFFF, fb * shine),
						rel(a.tip(), cam, 0), rel(a.tip(), cam, 1), rel(a.tip(), cam, 2), FxRender.fade(0xFFFFFFFF, fa * shine));
				}
				// Stardust where the tip moved fast
				if (stardust.get() && i % 2 == 0 && a.tip().distanceToSqr(b.tip()) > 0.0025) {
					double seed = Math.sin(b.nanos() * 1e-6 + entry.getKey()) * 43758.5453;
					seed -= Math.floor(seed);
					float age = (float) ((now - b.nanos()) / keep);
					Vec3 at = lerp(b.hilt(), b.tip(), 0.7 + 0.3 * seed).add(0, -age * 0.25, 0);
					sparks.add(new Spark(at, fb * shine, cb));
				}
			}
		}
		if (sparks.isEmpty()) return;
		VertexConsumer glow = FxRender.textured(event, FxRender.GLOW, true, false);
		for (Spark s : sparks) FxRender.glow(event, glow, s.at(), 0.05f + 0.04f * s.alpha(), FxRender.fade(FxRender.lighten(s.color(), 0.5f), s.alpha()));
	}

	private record Spark(Vec3 at, float alpha, int color) {
	}

	private static Vec3 lerp(Vec3 a, Vec3 b, double t) {
		return new Vec3(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t);
	}

	private static float rel(Vec3 p, Vec3 cam, int axis) {
		return (float) (axis == 0 ? p.x - cam.x : axis == 1 ? p.y - cam.y : p.z - cam.z);
	}
}
