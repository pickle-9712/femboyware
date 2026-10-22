package dev.femboyware.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/** Projects world positions onto the HUD using the matrices of the last rendered frame. */
public final class ProjectionUtil {
	private static final Matrix4f view = new Matrix4f();
	private static final Matrix4f projection = new Matrix4f();
	private static Vec3d cameraPos = Vec3d.ZERO;

	private ProjectionUtil() {
	}

	public static void update(Matrix4f viewMatrix, Matrix4f projectionMatrix, Vec3d camera) {
		view.set(viewMatrix);
		projection.set(projectionMatrix);
		cameraPos = camera;
	}

	/** Scaled-pixel screen coordinates of {@code pos}, or null when it is behind the camera. */
	public static Vec3d toScreen(Vec3d pos) {
		Vector4f vector = new Vector4f((float) (pos.x - cameraPos.x), (float) (pos.y - cameraPos.y), (float) (pos.z - cameraPos.z), 1f);
		view.transform(vector);
		projection.transform(vector);
		if (vector.w <= 0.05f) return null;

		MinecraftClient mc = MinecraftClient.getInstance();
		double width = mc.getWindow().getScaledWidth();
		double height = mc.getWindow().getScaledHeight();
		double x = (vector.x / vector.w + 1.0) / 2.0 * width;
		double y = (1.0 - vector.y / vector.w) / 2.0 * height;
		return new Vec3d(x, y, vector.w);
	}
}
