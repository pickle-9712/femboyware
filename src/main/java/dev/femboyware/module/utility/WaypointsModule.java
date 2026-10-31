package dev.femboyware.module.utility;

import dev.femboyware.FemboyWare;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.manager.WaypointManager;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.KeySetting;
import dev.femboyware.util.ChatUtil;
import dev.femboyware.util.RenderUtil;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.Locale;

/**
 * Shows your saved spots in the world: a beam of light and a label with the name and distance,
 * visible from any distance. Save spots with the Add Key or {@code .waypoint add <name>}; with
 * Death Waypoints on, the spot where you last died is saved for you.
 */
public class WaypointsModule extends Module {
	/** Labels further than this are drawn at this distance along the way, so the far plane never hides them. */
	private static final double LABEL_DISTANCE = 48;

	private final KeySetting addKey = add(new KeySetting("Add Key", "Press to save a waypoint where you stand", -1));
	private final BoolSetting deaths = add(new BoolSetting("Death Waypoints", "Save the spot where you last died", true));
	private final BoolSetting beam = add(new BoolSetting("Beam", "A column of light at each waypoint", true));
	private final BoolSetting distance = add(new BoolSetting("Distance", "Show how far away each waypoint is", true));
	private final ColorSetting color = add(new ColorSetting("Color", "Colour of your waypoints", 0xFFFF77C8));
	private final ColorSetting deathColor = add(new ColorSetting("Death Color", "Colour of the death waypoint", 0xFFFF4060));

	private boolean wasDead;

	public WaypointsModule() {
		super("Waypoints", "Shows saved spots with a beam and label; saves where you died", Category.WORLD);
		setEnabled(true);
	}

	@Override
	public String getInfo() {
		return Integer.toString(FemboyWare.getWaypoints().here().size());
	}

	@Override
	public void onKey(int key) {
		if (!addKey.is(key)) return;
		var waypoint = FemboyWare.getWaypoints().add("Waypoint " + (FemboyWare.getWaypoints().onThisWorld().size() + 1), mc.player.getBlockPos(), false);
		ChatUtil.info("Saved waypoint \"" + waypoint.name() + "\" at " + ChatUtil.coords(waypoint.pos()));
	}

	@Override
	public void onTick() {
		boolean dead = mc.player.isDead();
		if (dead && !wasDead && deaths.get()) {
			FemboyWare.getWaypoints().add("Death", mc.player.getBlockPos(), true);
			ChatUtil.info("Saved where you died: " + ChatUtil.coords(mc.player.getBlockPos()));
		}
		wasDead = dead;
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		Vec3d camera = event.camera().pos;
		for (WaypointManager.Waypoint waypoint : FemboyWare.getWaypoints().here()) {
			int tint = waypoint.death() ? deathColor.get() : color.get();
			Vec3d base = Vec3d.ofBottomCenter(waypoint.pos());
			if (beam.get()) {
				int top = mc.world.getTopYInclusive();
				RenderUtil.drawBox(event, new Box(base.x - 0.12, base.y, base.z - 0.12, base.x + 0.12, top, base.z + 0.12), (tint & 0xFFFFFF) | 0x50000000, 0, 1);
			}

			// Labels keep about the same size on screen however far away the waypoint is
			Vec3d head = base.add(0, 2, 0);
			double away = camera.distanceTo(head);
			Vec3d label = away > LABEL_DISTANCE ? camera.add(head.subtract(camera).normalize().multiply(LABEL_DISTANCE)) : head;
			float scale = (float) Math.max(0.6, camera.distanceTo(label) * 0.045);
			String text = distance.get() ? String.format(Locale.ROOT, "%s (%.0fm)", waypoint.name(), mc.player.getEntityPos().distanceTo(base)) : waypoint.name();
			RenderUtil.drawText(event, text, label, tint | 0xFF000000, scale);
		}
	}
}
