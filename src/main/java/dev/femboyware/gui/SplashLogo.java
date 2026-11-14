package dev.femboyware.gui;

import dev.femboyware.FemboyWare;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import javax.imageio.ImageIO;

/**
 * Draws the loading screen's logo from the client name. Minecraft shows the logo as two halves,
 * the left one above the right one, so the name is written across a wide strip and then cut in
 * half the same way.
 */
public final class SplashLogo {
	private static final int SIZE = 512;

	private SplashLogo() {
	}

	/** A PNG of the logo, or null if it couldn't be drawn. */
	public static InputStream draw(String name) {
		try (InputStream fontFile = FemboyWare.class.getResourceAsStream("/assets/femboyware/font/poppins-semibold.ttf")) {
			Font base = fontFile == null ? new Font(Font.SANS_SERIF, Font.BOLD, 10) : Font.createFont(Font.TRUETYPE_FONT, fontFile);

			// The whole name on one strip twice as wide as the logo, as large as fits
			BufferedImage strip = new BufferedImage(SIZE * 2, SIZE / 2, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = strip.createGraphics();
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
			float size = 160;
			Font font = base.deriveFont(size);
			FontMetrics metrics = g.getFontMetrics(font);
			float room = SIZE * 2 * 0.92f;
			if (metrics.stringWidth(name) > room) {
				font = base.deriveFont(size * room / metrics.stringWidth(name));
				metrics = g.getFontMetrics(font);
			}
			g.setFont(font);
			g.setColor(Color.WHITE);
			int x = (SIZE * 2 - metrics.stringWidth(name)) / 2;
			int y = (SIZE / 2 - metrics.getHeight()) / 2 + metrics.getAscent();
			g.drawString(name, x, y);
			g.dispose();

			BufferedImage logo = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
			Graphics2D halves = logo.createGraphics();
			halves.drawImage(strip.getSubimage(0, 0, SIZE, SIZE / 2), 0, 0, null);
			halves.drawImage(strip.getSubimage(SIZE, 0, SIZE, SIZE / 2), 0, SIZE / 2, null);
			halves.dispose();

			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ImageIO.write(logo, "png", out);
			return new ByteArrayInputStream(out.toByteArray());
		} catch (Exception | LinkageError e) {
			FemboyWare.LOGGER.warn("Couldn't draw the loading logo", e);
			return null;
		}
	}
}
