package dev.femboyware.module.render;

import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.RenderUtil;
import java.util.ArrayDeque;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Leaves a trail behind where you've been.
 * Ported from Meteor Client's Breadcrumbs (https://github.com/MeteorDevelopment/meteor-client), GPL-3.0.
 */
public class BreadcrumbsModule extends Module {
	private final ColorSetting color = add(new ColorSetting("Color", "Trail colour", 0xFFFF77C8));
	private final NumberSetting maxSections = add(new NumberSetting("Max Sections", "Longest trail, in sections", 1000, 1, 5000, 10));
	private final NumberSetting sectionLength = add(new NumberSetting("Section Length", "Blocks moved before a new point is added", 0.5, 0.1, 2, 0.05));

	private final ArrayDeque<Vec3> points = new ArrayDeque<>();
	private ResourceKey<Level> dimension;

	public BreadcrumbsModule() {
		super("Breadcrumbs", "Shows a trail of where you've walked", Category.COSMETIC);
	}

	@Override
	protected void onDisable() {
		points.clear();
		dimension = null;
	}

	@Override
	public void onTick() {
		if (mc.level.dimension() != dimension) {
			points.clear();
			dimension = mc.level.dimension();
		}
		Vec3 pos = mc.player.position();
		Vec3 last = points.peekLast();
		double length = sectionLength.get();
		if (last != null && Math.abs(pos.x - last.x) < length && Math.abs(pos.y - last.y) < length && Math.abs(pos.z - last.z) < length) return;
		points.addLast(pos);
		while (points.size() > maxSections.getInt() + 1) points.pollFirst();
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		Vec3 previous = null;
		for (Vec3 point : points) {
			if (previous != null) RenderUtil.drawLine(event, previous, point, color.get(), 2f);
			previous = point;
		}
	}
}
