package dev.femboyware.module.effects;

import dev.femboyware.util.Mc;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.util.FxRender;
import net.minecraft.resources.Identifier;
import org.joml.Vector3f;

/**
 * Base for things in the sky. They're drawn round the camera, further out than any terrain can be
 * but inside the far plane, so they stay put as you walk while hills, trees and clouds still hide
 * them. Directions are compass bearings: azimuth 0 is north and 90 east, elevation 0 the horizon.
 */
public abstract class SkyFxModule extends FxModule {
	protected SkyFxModule(String name, String description) {
		super(name, description, Category.SKY);
	}

	protected static Identifier texture(String name) {
		return Identifier.fromNamespaceAndPath("femboyware", "textures/sky/" + name + ".png");
	}

	/** How far out sky things are drawn: past the corners of the loaded terrain, well short of the far plane at 4 times the render distance. */
	protected static float skyDistance() {
		return mc.options.getEffectiveRenderDistance() * 16 * 2.2f;
	}

	/** The unit vector towards a compass bearing and an elevation, both in degrees. */
	protected static Vector3f direction(double azimuth, double elevation) {
		double az = Math.toRadians(azimuth), el = Math.toRadians(elevation);
		return new Vector3f((float) (Math.sin(az) * Math.cos(el)), (float) Math.sin(el), (float) (-Math.cos(az) * Math.cos(el)));
	}

	/** Whether this dimension has a sky at all; the Nether doesn't. */
	protected static boolean hasSky() {
		return mc.level.dimensionType().hasSkyLight();
	}

	/** 0 at midday to 1 through the night, easing over dusk and dawn. */
	protected static float night() {
		double day = (Mc.dayTime() % 24000) / 24000.0;
		// 1 at noon, -1 at midnight
		double sun = Math.cos((day - 0.25) * Math.PI * 2);
		return (float) Math.max(0, Math.min(1, (0.3 - sun) / 0.6));
	}

	/** 1 in clear weather, less as rain clouds over. */
	protected static float clearWeather() {
		return 1 - mc.level.getRainLevel(tickDelta()) * 0.85f;
	}

	/** A camera-facing sprite in the sky towards {@code direction}, {@code radius} across as a share of its distance. */
	protected static void sprite(Render3DEvent event, VertexConsumer consumer, Vector3f direction, float distance, float radius, float roll, int color) {
		FxRender.billboard(event, consumer, direction.x * distance, direction.y * distance, direction.z * distance, radius * distance, roll, color);
	}
}
