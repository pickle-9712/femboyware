package dev.femboyware.module.larp;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.Mc;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Film-style camera moves round your player for clips: orbits, sweeps, dolly pushes, crane lifts and
 * a low hero angle, or Showcase cutting between them. You keep full control of your player; the
 * camera just films. Applied by CameraMixin.
 */
public class CinematicCameraModule extends Module {
	public enum Shot { ORBIT, SWEEP, DOLLY, CRANE, LOW_ANGLE, SHOWCASE }

	/** Where the camera is and which way it looks. */
	public record View(Vec3 pos, float yaw, float pitch) {}

	private static final Shot[] SHOWCASE_ORDER = {Shot.ORBIT, Shot.LOW_ANGLE, Shot.SWEEP, Shot.CRANE, Shot.DOLLY};

	private final EnumSetting<Shot> shot = add(new EnumSetting<>("Shot", "The camera move. Showcase cuts between all of them", Shot.SHOWCASE));
	private final NumberSetting speed = add(new NumberSetting("Speed", "How fast the camera moves", 1, 0.2, 3, 0.05));
	private final NumberSetting distance = add(new NumberSetting("Distance", "How far from you, in blocks", 4, 1.5, 14, 0.25));
	private final NumberSetting height = add(new NumberSetting("Height", "How far above your chest, in blocks", 0.4, -1.5, 5, 0.1));
	private final NumberSetting cutEvery = add(new NumberSetting("Cut Every", "Seconds per shot in Showcase", 7, 3, 30, 0.5)).visibleWhen(() -> shot.is(Shot.SHOWCASE));
	private final BoolSetting hideHud = add(new BoolSetting("Hide HUD", "Hide the HUD while filming, like F1", true));
	private final BoolSetting walls = add(new BoolSetting("Stop At Walls", "Pull in instead of going through blocks", true));

	private long started;
	/** The way you faced when filming started, so sweeps and pushes keep framing you from the front. */
	private float frontYaw;
	private boolean hidHud;
	private boolean byEmote;

	public CinematicCameraModule() {
		super("Cinematic Camera", "Film-style camera moves round you for clips", Category.LARP);
	}

	@Override
	public String getInfo() {
		return shot.getDisplayName();
	}

	@Override
	protected void onEnable() {
		if (mc.player == null) {
			setEnabled(false);
			return;
		}
		started = System.nanoTime();
		frontYaw = mc.player.getYRot();
		hidHud = hideHud.get() && !Mc.hudHidden();
		if (hidHud) Mc.setHudHidden(true);
	}

	@Override
	protected void onDisable() {
		if (hidHud) Mc.setHudHidden(false);
		hidHud = false;
		byEmote = false;
	}

	/** For Emotes: films while you emote, and stops when you stop, unless you'd turned it on yourself. */
	public void startForEmote() {
		if (isEnabled()) return;
		setEnabled(true);
		byEmote = true;
	}

	public void stopForEmote() {
		if (byEmote) setEnabled(false);
	}

	/** Where to put the camera this frame, or null when not filming. */
	public View view(float tickDelta) {
		if (!isEnabled() || mc.player == null || mc.level == null) return null;
		float t = (System.nanoTime() - started) / 1e9f * speed.getFloat();
		Vec3 target = mc.player.getPosition(tickDelta).add(0, 1.1, 0);
		Shot current = shot.get();
		float shotTime = t;
		if (current == Shot.SHOWCASE) {
			float length = cutEvery.getFloat() * speed.getFloat();
			int index = (int) (t / length);
			current = SHOWCASE_ORDER[index % SHOWCASE_ORDER.length];
			shotTime = t - index * length;
		}
		double dist = distance.get(), up = height.get();
		double front = Math.toRadians(frontYaw);
		Vec3 pos = switch (current) {
			// Circles you
			case ORBIT -> around(target, front + shotTime * 0.35, dist, up);
			// Swings side to side in front of you
			case SWEEP -> around(target, front + Mth.sin(shotTime * 0.4f) * 1.1, dist, up);
			// Pushes in close, then pulls back out
			case DOLLY -> around(target, front + 0.35, dist * (0.95 + 0.5 * Mth.cos(shotTime * 0.35f)), up);
			// Rises up high and looks down on you
			case CRANE -> around(target, front - 0.5 + shotTime * 0.05, dist * 1.1, up + (0.5 - 0.5 * Mth.cos(shotTime * 0.3f)) * 4);
			// Down by your feet looking up, slowly circling
			case LOW_ANGLE -> around(target, front + 0.6 + shotTime * 0.12, dist * 0.8, -0.95);
			default -> target;
		};
		if (walls.get()) {
			HitResult hit = mc.level.clip(new ClipContext(target, pos, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, mc.player));
			if (hit.getType() != HitResult.Type.MISS) pos = hit.getLocation().add(target.subtract(pos).normalize().scale(0.2));
		}
		Vec3 look = (current == Shot.LOW_ANGLE ? target.add(0, 0.5, 0) : target).subtract(pos);
		float yaw = (float) Math.toDegrees(Math.atan2(-look.x, look.z));
		float pitch = (float) Math.toDegrees(-Math.atan2(look.y, Math.sqrt(look.x * look.x + look.z * look.z)));
		return new View(pos, yaw, pitch);
	}

	/** A spot {@code dist} out from {@code target} at {@code angle} (Minecraft yaw, radians: 0 is in front of south-facing you). */
	private static Vec3 around(Vec3 target, double angle, double dist, double up) {
		return target.add(-Math.sin(angle) * dist, up, Math.cos(angle) * dist);
	}
}
