package dev.femboyware.gui;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.render.MainMenuModule;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * The animated pink background behind the main menu and every other out-of-world screen: the
 * swirl slowly turning and drifting, with big soft blobs of pink, magenta, lavender and peach
 * floating around over it like a lava lamp, each gently swelling and shrinking.
 */
public final class SwirlBackground {
	private static final Identifier SWIRL = Identifier.fromNamespaceAndPath("femboyware", "textures/gui/swirl.png");
	private static final int SWIRL_SIZE = 1024;
	private static final Identifier BLOB = Identifier.fromNamespaceAndPath("femboyware", "textures/gui/blob.png");
	private static final int BLOB_SIZE = 256;
	/** How fast everything moves at Swirl Speed 1: slow and calm, but quick enough to see it moving. */
	private static final double BASE_SPEED = 2;
	/** One shared clock, so the background doesn't jump when moving between screens. */
	private static final long START = System.currentTimeMillis();

	/**
	 * One drifting blob: its colour (alpha is how strong), size as a share of the screen, how far
	 * it wanders across and down (as a share of the screen) and how quickly, and where in its
	 * loop it starts. Different speeds keep the blobs from ever lining up into a pattern.
	 */
	private record Blob(int color, float size, float rangeX, float rangeY, float speedX, float speedY, float phase) {}

	private static final Blob[] BLOBS = {
		new Blob(0x99FF5FB4, 0.95f, 0.32f, 0.26f, 0.025f, 0.018f, 0.0f),
		new Blob(0x80C23BE0, 0.85f, 0.36f, 0.30f, 0.015f, 0.023f, 2.1f),
		new Blob(0x70FF9AC8, 0.70f, 0.30f, 0.34f, 0.021f, 0.014f, 4.0f),
		new Blob(0x55FFB38A, 0.60f, 0.38f, 0.24f, 0.013f, 0.020f, 5.3f),
		new Blob(0x669070FF, 0.75f, 0.28f, 0.32f, 0.018f, 0.011f, 1.2f),
	};

	private SwirlBackground() {
	}

	public static void draw(GuiGraphics context, int width, int height) {
		float speed = FemboyWare.getModules() == null ? 1 : FemboyWare.getModules().get(MainMenuModule.class).swirlSpeed();
		// All in double, so it stays smooth however long the game has been open
		double time = (System.currentTimeMillis() - START) / 1000.0 * speed * BASE_SPEED;
		float extent = Math.max(width, height);

		// The swirl: turning about a centre that wanders a little, big enough to cover the screen wherever it is
		float driftX = width * 0.06f * (float) Math.sin(time * 0.065);
		float driftY = height * 0.06f * (float) Math.cos(time * 0.055);
		int size = (int) Math.ceil(Math.hypot(width, height) + 2 * Math.hypot(driftX, driftY)) + 4;
		context.pose().pushMatrix();
		context.pose().translate(width / 2f + driftX, height / 2f + driftY);
		context.pose().rotate((float) (time * 0.025 % (Math.PI * 2)));
		context.blit(RenderPipelines.GUI_TEXTURED, SWIRL, -size / 2, -size / 2, 0, 0, size, size, SWIRL_SIZE, SWIRL_SIZE, SWIRL_SIZE, SWIRL_SIZE);
		context.pose().popMatrix();

		// The blobs, each moved by the matrix rather than whole GUI units so they glide smoothly
		for (Blob blob : BLOBS) {
			float x = width * (0.5f + blob.rangeX * (float) Math.sin(time * blob.speedX * Math.PI * 2 + blob.phase));
			float y = height * (0.5f + blob.rangeY * (float) Math.cos(time * blob.speedY * Math.PI * 2 + blob.phase * 1.3f));
			float swell = 1 + 0.1f * (float) Math.sin(time * 0.18 + blob.phase);
			int blobSize = Math.round(extent * blob.size * swell);
			context.pose().pushMatrix();
			context.pose().translate(x, y);
			context.blit(RenderPipelines.GUI_TEXTURED, BLOB, -blobSize / 2, -blobSize / 2, 0, 0, blobSize, blobSize,
				BLOB_SIZE, BLOB_SIZE, BLOB_SIZE, BLOB_SIZE, blob.color);
			context.pose().popMatrix();
		}
	}
}
