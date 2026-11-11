package dev.femboyware.module.larp;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.femboyware.FemboyWare;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.util.FxRender;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Keeps the world looking whole while you fly. Server Larp keeps the server thinking you're where
 * you boarded, so it only sends the ground within your view distance of there; fly beyond that and
 * there's nothing. This keeps you and the captain drawn even over ground that isn't there, and
 * (with a vehicle's Far Ground setting on) lays a stand-in ground, tinted like the local grass and
 * fading into the haze with distance, over every bit that isn't loaded.
 */
public final class FarGround {
	private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("femboyware", "textures/larp/far_ground.png");
	/** Tiles of 32 blocks out to this far from the camera. */
	private static final int TILE = 32, REACH = 960;
	private static final int HAZE = 0xFFB9CFEF;

	private FarGround() {
	}

	/**
	 * Draws the stand-in ground at {@code groundY} round the camera, skipping tiles whose chunks are
	 * all loaded (real terrain is there). Tinted with the grass colour at {@code tintAt}.
	 */
	static void draw(Render3DEvent event, double groundY, BlockPos tintAt) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;
		Vec3 cam = event.camera().pos;
		int grass = 0xFF000000 | net.minecraft.client.renderer.BiomeColors.getAverageGrassColor(mc.level, tintAt);
		float light = daylight(mc);
		int ground = FxRender.mix(0xFF000000, grass, light), haze = FxRender.mix(0xFF0A0E18, HAZE, light);
		VertexConsumer c = FxRender.solid(event, TEXTURE);
		PoseStack.Pose pose = event.matrices().last();
		float y = (float) (groundY - 0.07 - cam.y);
		int startX = Math.floorDiv((int) Math.floor(cam.x) - REACH, TILE), endX = Math.floorDiv((int) Math.floor(cam.x) + REACH, TILE);
		int startZ = Math.floorDiv((int) Math.floor(cam.z) - REACH, TILE), endZ = Math.floorDiv((int) Math.floor(cam.z) + REACH, TILE);
		for (int tx = startX; tx <= endX; tx++) {
			for (int tz = startZ; tz <= endZ; tz++) {
				int bx = tx * TILE, bz = tz * TILE;
				if (loaded(mc, bx, bz)) continue;
				float x1 = (float) (bx - cam.x), z1 = (float) (bz - cam.z), x2 = x1 + TILE, z2 = z1 + TILE;
				FxRender.textured(c, pose, x1, y, z1, 0, 0, faded(ground, haze, x1, z1));
				FxRender.textured(c, pose, x1, y, z2, 0, 1, faded(ground, haze, x1, z2));
				FxRender.textured(c, pose, x2, y, z2, 1, 1, faded(ground, haze, x2, z2));
				FxRender.textured(c, pose, x2, y, z1, 1, 0, faded(ground, haze, x2, z1));
			}
		}
	}

	/** Whether all four chunks under a tile are loaded. */
	private static boolean loaded(Minecraft mc, int bx, int bz) {
		for (int dx = 0; dx < TILE; dx += 16) {
			for (int dz = 0; dz < TILE; dz += 16) {
				if (!mc.level.hasChunk((bx + dx) >> 4, (bz + dz) >> 4)) return false;
			}
		}
		return true;
	}

	/** The ground colour fading into the haze the further it is from the camera. */
	private static int faded(int ground, int haze, float x, float z) {
		float t = (float) Math.sqrt(x * x + z * z) / REACH;
		t = Math.max(0, Math.min(1, (t - 0.35f) / 0.6f));
		return FxRender.mix(ground, haze, t * t * (3 - 2 * t));
	}

	/** How light it is out: full at noon, dim at night. */
	private static float daylight(Minecraft mc) {
		//? if >=26.1 {
		/*return 1;
		*///?} else {
		double t = (mc.level.getDayTime() % 24000) / 24000.0;
		double sun = Math.cos((t - 0.25) * Math.PI * 2);
		double s = Math.max(0, Math.min(1, (sun + 0.2) / 0.6));
		return (float) (0.18 + 0.82 * s * s * (3 - 2 * s));
		//?}
	}

	/**
	 * Called from WorldRendererMixin for each chunk section the game asks about: true for the
	 * sections you and the captain are in while you fly, so you're both drawn even where the ground
	 * isn't loaded.
	 */
	public static boolean keepsVisible(BlockPos pos) {
		if (FemboyWare.getModules() == null) return false;
		return inSection(FemboyWare.getModules().get(JetModule.class).flyers(), pos)
			|| inSection(FemboyWare.getModules().get(HelicopterModule.class).flyers(), pos);
	}

	private static boolean inSection(List<Entity> entities, BlockPos pos) {
		for (Entity e : entities) {
			BlockPos at = e.blockPosition();
			if (at.getX() >> 4 == pos.getX() >> 4 && at.getY() >> 4 == pos.getY() >> 4 && at.getZ() >> 4 == pos.getZ() >> 4) return true;
		}
		return false;
	}
}
