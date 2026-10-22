package dev.femboyware.module.render;

import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.ColorUtil;
import dev.femboyware.util.EntityKind;
import dev.femboyware.util.ProjectionUtil;
import dev.femboyware.util.RenderUtil;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;

/**
 * Lines from the crosshair to entities, or arrows at the screen edge pointing at them.
 * Ported from Meteor Client's Tracers (https://github.com/MeteorDevelopment/meteor-client), GPL-3.0.
 */
public class TracersModule extends Module {
	public enum Style { LINES, OFFSCREEN }
	public enum Target { HEAD, BODY, FEET }

	private final BoolSetting players = add(new BoolSetting("Players", "Trace players", true));
	private final BoolSetting animals = add(new BoolSetting("Animals", "Trace animals", false));
	private final BoolSetting waterAnimals = add(new BoolSetting("Water Animals", "Trace water animals", false));
	private final BoolSetting monsters = add(new BoolSetting("Monsters", "Trace monsters", false));
	private final BoolSetting ambient = add(new BoolSetting("Ambient", "Trace bats and other ambient mobs", false));
	private final BoolSetting misc = add(new BoolSetting("Misc", "Trace items, projectiles, vehicles and the rest", false));
	private final BoolSetting showInvisible = add(new BoolSetting("Show Invisible", "Trace invisible entities", true));
	private final NumberSetting maxDistance = add(new NumberSetting("Max Distance", "Furthest entity to trace", 256, 0, 256, 1));

	private final EnumSetting<Style> style = add(new EnumSetting<>("Style", "Lines from the crosshair, or arrows for entities off screen", Style.LINES));
	private final EnumSetting<Target> target = add(new EnumSetting<>("Target", "Which part of the entity lines point at", Target.BODY)).visibleWhen(() -> style.is(Style.LINES));
	private final BoolSetting stem = add(new BoolSetting("Stem", "Draw a vertical line through the entity", true)).visibleWhen(() -> style.is(Style.LINES));
	private final NumberSetting offscreenDistance = add(new NumberSetting("Offscreen Distance", "How far from the centre arrows sit", 100, 20, 250, 5)).visibleWhen(() -> style.is(Style.OFFSCREEN));
	private final NumberSetting offscreenSize = add(new NumberSetting("Offscreen Size", "Arrow size", 6, 2, 25, 1)).visibleWhen(() -> style.is(Style.OFFSCREEN));
	private final BoolSetting blink = add(new BoolSetting("Blink", "Pulse the arrows", true)).visibleWhen(() -> style.is(Style.OFFSCREEN));
	private final NumberSetting blinkSpeed = add(new NumberSetting("Blink Speed", "How fast arrows pulse", 4, 1, 15, 0.5)).visibleWhen(() -> style.is(Style.OFFSCREEN) && blink.get());

	private final BoolSetting distanceColors = add(new BoolSetting("Distance Colors", "Colour by distance, red close to green far", false));
	private final ColorSetting playersColor = add(new ColorSetting("Players Color", "Player colour", 0xC0FF77C8)).visibleWhen(() -> !distanceColors.get());
	private final ColorSetting animalsColor = add(new ColorSetting("Animals Color", "Animal colour", 0x7F91FF91)).visibleWhen(() -> !distanceColors.get());
	private final ColorSetting waterAnimalsColor = add(new ColorSetting("Water Animals Color", "Water animal colour", 0x7F9191FF)).visibleWhen(() -> !distanceColors.get());
	private final ColorSetting monstersColor = add(new ColorSetting("Monsters Color", "Monster colour", 0x7FFF9191)).visibleWhen(() -> !distanceColors.get());
	private final ColorSetting ambientColor = add(new ColorSetting("Ambient Color", "Ambient mob colour", 0x7F4B4B4B)).visibleWhen(() -> !distanceColors.get());
	private final ColorSetting miscColor = add(new ColorSetting("Misc Color", "Everything else", 0x7F919191)).visibleWhen(() -> !distanceColors.get());

	private final long startTime = System.currentTimeMillis();
	private int count;

	public TracersModule() {
		super("Tracers", "Draws lines to entities", Category.ESP);
	}

	@Override
	public String getInfo() {
		return Integer.toString(count);
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		if (!style.is(Style.LINES) || mc.options.hudHidden) return;
		count = 0;
		float tickDelta = mc.getRenderTickCounter().getTickProgress(true);
		Vec3d origin = RenderUtil.tracerOrigin(event);
		for (Entity entity : mc.world.getEntities()) {
			if (shouldSkip(entity)) continue;
			int color = getColor(entity);
			Vec3d feet = entity.getLerpedPos(tickDelta);
			double height = entity.getBoundingBox().getLengthY();
			Vec3d end = switch (target.get()) {
				case HEAD -> feet.add(0, height, 0);
				case BODY -> feet.add(0, height / 2, 0);
				case FEET -> feet;
			};
			RenderUtil.drawLine(event, origin, end, color, 1.5f, true);
			if (stem.get()) RenderUtil.drawLine(event, feet, feet.add(0, height, 0), color, 1.5f, true);
			count++;
		}
	}

	@Override
	public void onRender2D(DrawContext context, float tickDelta) {
		if (!style.is(Style.OFFSCREEN) || mc.options.hudHidden) return;
		count = 0;
		int width = mc.getWindow().getScaledWidth();
		int height = mc.getWindow().getScaledHeight();
		float cameraYaw = mc.gameRenderer.getCamera().getYaw();
		Vec3d camera = mc.gameRenderer.getCamera().getCameraPos();
		int size = offscreenSize.getInt();

		for (Entity entity : mc.world.getEntities()) {
			if (shouldSkip(entity)) continue;
			Vec3d pos = entity.getLerpedPos(tickDelta).add(0, entity.getHeight() / 2, 0);
			Vec3d screen = ProjectionUtil.toScreen(pos);
			if (screen != null && screen.x > 0 && screen.x < width && screen.y > 0 && screen.y < height) continue;

			// Angle around the crosshair, 0 pointing up, from the entity's direction relative to where we look
			double worldYaw = Math.toDegrees(Math.atan2(pos.z - camera.z, pos.x - camera.x)) - 90;
			float angle = (float) MathHelper.wrapDegrees(worldYaw - cameraYaw);
			int color = getColor(entity);
			if (blink.get()) color = ColorUtil.withAlpha(color, (int) ((color >>> 24) * blinkAlpha()));

			context.getMatrices().pushMatrix();
			context.getMatrices().translate(width / 2f, height / 2f);
			context.getMatrices().rotate((float) Math.toRadians(angle));
			context.getMatrices().translate(0, -offscreenDistance.getFloat());
			// Upward-pointing triangle, one row at a time
			for (int row = 0; row < size * 2; row++) {
				int half = row / 2;
				context.fill(-half, -size + row, half + 1, -size + row + 1, color);
			}
			context.getMatrices().popMatrix();
			count++;
		}
	}

	private boolean shouldSkip(Entity entity) {
		if (entity == mc.player) return true;
		if (!showInvisible.get() && entity.isInvisible()) return true;
		if (mc.player.distanceTo(entity) > maxDistance.get()) return true;
		return !switch (EntityKind.of(entity)) {
			case PLAYER -> players.get();
			case ANIMAL -> animals.get();
			case WATER_ANIMAL -> waterAnimals.get();
			case MONSTER -> monsters.get();
			case AMBIENT -> ambient.get();
			case MISC -> misc.get();
		};
	}

	private int getColor(Entity entity) {
		if (distanceColors.get()) return ColorUtil.withAlpha(EntityKind.distanceColor(mc.player.distanceTo(entity)), 0xC0);
		return switch (EntityKind.of(entity)) {
			case PLAYER -> playersColor.get();
			case ANIMAL -> animalsColor.get();
			case WATER_ANIMAL -> waterAnimalsColor.get();
			case MONSTER -> monstersColor.get();
			case AMBIENT -> ambientColor.get();
			case MISC -> miscColor.get();
		};
	}

	private float blinkAlpha() {
		double elapsed = (System.currentTimeMillis() - startTime) * blinkSpeed.get() / 4.0;
		return (float) Math.abs((elapsed % 1000) - 500) / 500f;
	}
}
