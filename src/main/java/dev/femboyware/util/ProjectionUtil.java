package dev.femboyware.util;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/** Projects world positions onto the HUD using the matrices of the last rendered frame. */
public final class ProjectionUtil {
	private static final Matrix4f view = new Matrix4f();
	private static final Matrix4f projection = new Matrix4f();
	private static Vec3 cameraPos = Vec3.ZERO;

	private ProjectionUtil() {
	}

	public static void update(Matrix4f viewMatrix, Matrix4f projectionMatrix, Vec3 camera) {
		view.set(viewMatrix);
		projection.set(projectionMatrix);
		cameraPos = camera;
	}

	public static Matrix4f view() {
		return view;
	}

	public static Matrix4f projection() {
		return projection;
	}

	public static Vec3 camera() {
		return cameraPos;
	}

	/** Scaled-pixel screen coordinates of {@code pos}, or null when it is behind the camera. */
	public static Vec3 toScreen(Vec3 pos) {
		Vector4f vector = new Vector4f((float) (pos.x - cameraPos.x), (float) (pos.y - cameraPos.y), (float) (pos.z - cameraPos.z), 1f);
		view.transform(vector);
		projection.transform(vector);
		if (vector.w <= 0.05f) return null;

		Minecraft mc = Minecraft.getInstance();
		double width = mc.getWindow().getGuiScaledWidth();
		double height = mc.getWindow().getGuiScaledHeight();
		double x = (vector.x / vector.w + 1.0) / 2.0 * width;
		double y = (1.0 - vector.y / vector.w) / 2.0 * height;
		return new Vec3(x, y, vector.w);
	}
}
