package dev.femboyware.module.effects;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.FxRender;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

/**
 * Ender pearls in flight leave a glowing trail of stardust, and where one lands it bursts in a ring
 * of light, so you can follow every pearl (yours and everyone's).
 */
public class PearlParticlesModule extends FxModule {
	private final NumberSetting length = add(new NumberSetting("Length", "Seconds the trail lasts", 1.2, 0.2, 4, 0.05));
	private final NumberSetting width = add(new NumberSetting("Width", "How wide the trail glows", 0.22, 0.05, 0.8, 0.01));
	private final BoolSetting sparkles = add(new BoolSetting("Sparkles", "Specks scattering off the trail", true));
	private final BoolSetting burst = add(new BoolSetting("Landing Burst", "A ring of light where the pearl lands", true));
	private final BoolSetting onlyYours = add(new BoolSetting("Only Yours", "Just your own pearls", false));
	private final Palette palette = palette(0xFF5CFFD8, 0xFFB45CFF);

	private record Point(Vec3 pos, long time) {
	}

	private record Burst(Vec3 pos, long time) {
	}

	private final Map<Integer, ArrayDeque<Point>> trails = new HashMap<>();
	/** Trails whose pearl is gone, left to fade. */
	private final List<ArrayDeque<Point>> fading = new ArrayList<>();
	private final List<Burst> bursts = new ArrayList<>();

	public PearlParticlesModule() {
		super("Pearl Particles", "Ender pearls leave a glowing trail and burst where they land", Category.EFFECTS);
	}

	@Override
	protected void onDisable() {
		trails.clear();
		fading.clear();
		bursts.clear();
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		if (mc.level == null) return;
		long now = System.nanoTime();
		long life = (long) (length.get() * 1e9);
		float tickDelta = tickDelta();
		Set<Integer> present = new HashSet<>();
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (entity.getType() != EntityType.ENDER_PEARL) continue;
			if (onlyYours.get() && (entity instanceof net.minecraft.world.entity.projectile.Projectile p) && p.getOwner() != mc.player) continue;
			present.add(entity.getId());
			ArrayDeque<Point> trail = trails.computeIfAbsent(entity.getId(), id -> new ArrayDeque<>());
			Vec3 pos = entity.getPosition(tickDelta);
			if (trail.isEmpty() || trail.peekLast().pos.distanceToSqr(pos) > 0.0025) trail.addLast(new Point(pos, now));
		}
		// Pearls that are gone landed (or teleported someone): their trails fade where they are
		for (var it = trails.entrySet().iterator(); it.hasNext(); ) {
			var entry = it.next();
			if (present.contains(entry.getKey())) continue;
			it.remove();
			if (!entry.getValue().isEmpty()) {
				if (burst.get()) bursts.add(new Burst(entry.getValue().peekLast().pos, now));
				fading.add(entry.getValue());
			}
		}
		VertexConsumer glow = FxRender.textured(event, FxRender.GLOW, true, false);
		Vec3 cam = event.camera().pos;
		List<ArrayDeque<Point>> all = new ArrayList<>(trails.values());
		all.addAll(fading);
		for (ArrayDeque<Point> trail : all) {
			while (!trail.isEmpty() && now - trail.peekFirst().time > life) trail.pollFirst();
			int i = 0, n = trail.size();
			Point before = null;
			for (Point p : trail) {
				float age = (now - p.time) / (float) life;
				// Not right in your face as you throw it
				float near = (float) Math.max(0, Math.min(1, (p.pos.distanceTo(cam) - 1.5) / 3));
				float fade = (1 - age) * near;
				int color = palette.at((float) ((i / (float) Math.max(1, n) + time() * 0.3) % 1));
				// Fill the gaps between points so a fast pearl still leaves a solid line
				int steps = before == null ? 1 : Math.max(1, Math.min(8, (int) (before.pos.distanceTo(p.pos) / 0.12)));
				for (int s = 1; s <= steps; s++) {
					Vec3 at = before == null ? p.pos : before.pos.lerp(p.pos, s / (double) steps);
					FxRender.glow(event, glow, at, width.getFloat() * (0.4f + 0.6f * fade), FxRender.fade(color, fade * 0.5f));
				}
				if (sparkles.get() && (i * 7919 + (int) (p.time >> 20)) % 5 == 0) {
					double seed = p.time % 1000 / 1000.0 * Math.PI * 2;
					Vec3 off = new Vec3(Math.cos(seed + i), Math.sin(seed * 1.7 + i) * 0.5, Math.sin(seed + i)).scale(0.25 * age);
					FxRender.glow(event, glow, p.pos.add(off), 0.04f, FxRender.fade(FxRender.lighten(color, 0.6f), fade));
				}
				before = p;
				i++;
			}
		}
		fading.removeIf(ArrayDeque::isEmpty);
		if (bursts.isEmpty()) return;
		VertexConsumer light = FxRender.quads(event, true, false);
		bursts.removeIf(b -> now - b.time > 600_000_000L);
		for (Burst b : bursts) {
			float progress = (now - b.time) / 6e8f;
			float outer = 0.3f + 1.4f * (1 - (1 - progress) * (1 - progress)), inner = outer * 0.75f;
			float alpha = 1 - progress;
			event.matrices().pushPose();
			event.matrices().translate((float) (b.pos.x - cam.x), (float) (b.pos.y - cam.y) + 0.05f, (float) (b.pos.z - cam.z));
			FxRender.ring(light, event.matrices().last(), inner, outer, 0, 40, (t, in) -> FxRender.fade(palette.at(t), in ? 0 : alpha * 0.8f));
			event.matrices().popPose();
			FxRender.glow(event, glow, b.pos.add(0, 0.3, 0), 0.6f * (1 - progress), FxRender.fade(FxRender.lighten(palette.at(0), 0.5f), alpha));
		}
	}
}
