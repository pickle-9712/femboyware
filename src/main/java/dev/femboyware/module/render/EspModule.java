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
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Draws entities through walls as world boxes or flat screen boxes.
 * Ported from Meteor Client's ESP (https://github.com/MeteorDevelopment/meteor-client), GPL-3.0.
 */
public class EspModule extends Module {
	public enum Mode { BOX, FLAT }
	public enum ColorMode { ENTITY_TYPE, DISTANCE, HEALTH }

	private final EnumSetting<Mode> mode = add(new EnumSetting<>("Mode", "Box draws world hitboxes, Flat draws 2D boxes on screen", Mode.BOX));
	private final BoolSetting players = add(new BoolSetting("Players", "Show players", true));
	private final BoolSetting animals = add(new BoolSetting("Animals", "Show animals", false));
	private final BoolSetting waterAnimals = add(new BoolSetting("Water Animals", "Show water animals", false));
	private final BoolSetting monsters = add(new BoolSetting("Monsters", "Show monsters", false));
	private final BoolSetting ambient = add(new BoolSetting("Ambient", "Show bats and other ambient mobs", false));
	private final BoolSetting misc = add(new BoolSetting("Misc", "Show items, projectiles, vehicles and the rest", false));
	private final BoolSetting ignoreSelf = add(new BoolSetting("Ignore Self", "Don't draw yourself in third person or freecam", true));
	private final NumberSetting fillOpacity = add(new NumberSetting("Fill Opacity", "Opacity of the box fill", 0.3, 0, 1, 0.05));
	private final NumberSetting fadeDistance = add(new NumberSetting("Fade Distance", "Boxes fade out closer than this", 3, 0, 12, 0.5));
	private final BoolSetting highlightTarget = add(new BoolSetting("Highlight Target", "Colour the entity under the crosshair differently", false));
	private final ColorSetting targetColor = add(new ColorSetting("Target Color", "Colour of the entity under the crosshair", 0xFFC8C8C8)).visibleWhen(highlightTarget::get);

	private final EnumSetting<ColorMode> colorMode = add(new EnumSetting<>("Color Mode", "Colour by entity type, distance, or health", ColorMode.ENTITY_TYPE));
	private final ColorSetting playersColor = add(new ColorSetting("Players Color", "Player colour", 0xFFFF77C8)).visibleWhen(() -> colorMode.is(ColorMode.ENTITY_TYPE));
	private final ColorSetting animalsColor = add(new ColorSetting("Animals Color", "Animal colour", 0xFF19FF19)).visibleWhen(() -> colorMode.is(ColorMode.ENTITY_TYPE));
	private final ColorSetting waterAnimalsColor = add(new ColorSetting("Water Animals Color", "Water animal colour", 0xFF1919FF)).visibleWhen(() -> colorMode.is(ColorMode.ENTITY_TYPE));
	private final ColorSetting monstersColor = add(new ColorSetting("Monsters Color", "Monster colour", 0xFFFF1919)).visibleWhen(() -> colorMode.is(ColorMode.ENTITY_TYPE));
	private final ColorSetting ambientColor = add(new ColorSetting("Ambient Color", "Ambient mob colour", 0xFF191919)).visibleWhen(() -> colorMode.is(ColorMode.ENTITY_TYPE));
	private final ColorSetting miscColor = add(new ColorSetting("Misc Color", "Everything else", 0xFFAFAFAF)).visibleWhen(() -> colorMode.is(ColorMode.ENTITY_TYPE));
	private final ColorSetting nonLivingColor = add(new ColorSetting("Non Living Color", "Colour of entities without health", 0xFF191919)).visibleWhen(() -> colorMode.is(ColorMode.HEALTH));

	private int count;

	public EspModule() {
		super("Esp", "Shows entities through walls", Category.ESP);
	}

	@Override
	public String getInfo() {
		return Integer.toString(count);
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		if (!mode.is(Mode.BOX)) return;
		count = 0;
		float tickDelta = mc.getRenderTickCounter().getTickProgress(true);
		for (Entity entity : mc.world.getEntities()) {
			if (shouldSkip(entity)) continue;
			int color = getColor(entity);
			if ((color >>> 24) == 0) continue;
			RenderUtil.drawBox(event, lerpedBox(entity, tickDelta), fill(color), color, 1.5f, true);
			count++;
		}
	}

	@Override
	public void onRender2D(DrawContext context, float tickDelta) {
		if (!mode.is(Mode.FLAT) || mc.options.hudHidden) return;
		count = 0;
		for (Entity entity : mc.world.getEntities()) {
			if (shouldSkip(entity)) continue;
			int color = getColor(entity);
			if ((color >>> 24) == 0) continue;

			// Screen bounds of all eight corners; skip anything partly behind the camera
			Box box = lerpedBox(entity, tickDelta);
			double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
			boolean behind = false;
			for (int i = 0; i < 8 && !behind; i++) {
				Vec3d corner = new Vec3d((i & 1) == 0 ? box.minX : box.maxX, (i & 2) == 0 ? box.minY : box.maxY, (i & 4) == 0 ? box.minZ : box.maxZ);
				Vec3d screen = ProjectionUtil.toScreen(corner);
				if (screen == null) {
					behind = true;
					continue;
				}
				minX = Math.min(minX, screen.x);
				minY = Math.min(minY, screen.y);
				maxX = Math.max(maxX, screen.x);
				maxY = Math.max(maxY, screen.y);
			}
			if (behind) continue;

			int x1 = (int) minX, y1 = (int) minY, x2 = (int) maxX, y2 = (int) maxY;
			context.fill(x1, y1, x2, y2, fill(color));
			context.fill(x1, y1, x2, y1 + 1, color);
			context.fill(x1, y2 - 1, x2, y2, color);
			context.fill(x1, y1, x1 + 1, y2, color);
			context.fill(x2 - 1, y1, x2, y2, color);
			count++;
		}
	}

	private boolean shouldSkip(Entity entity) {
		if (isTarget(entity)) return false;
		if (entity == mc.player && (ignoreSelf.get() || mc.options.getPerspective().isFirstPerson())) return true;
		return !switch (EntityKind.of(entity)) {
			case PLAYER -> players.get();
			case ANIMAL -> animals.get();
			case WATER_ANIMAL -> waterAnimals.get();
			case MONSTER -> monsters.get();
			case AMBIENT -> ambient.get();
			case MISC -> misc.get();
		};
	}

	private boolean isTarget(Entity entity) {
		return highlightTarget.get() && mc.crosshairTarget instanceof EntityHitResult hit && hit.getEntity() == entity;
	}

	private int getColor(Entity entity) {
		if (isTarget(entity)) return targetColor.get();
		int color = switch (colorMode.get()) {
			case ENTITY_TYPE -> switch (EntityKind.of(entity)) {
				case PLAYER -> playersColor.get();
				case ANIMAL -> animalsColor.get();
				case WATER_ANIMAL -> waterAnimalsColor.get();
				case MONSTER -> monstersColor.get();
				case AMBIENT -> ambientColor.get();
				case MISC -> miscColor.get();
			};
			case DISTANCE -> EntityKind.distanceColor(mc.gameRenderer.getCamera().getCameraPos().distanceTo(entity.getEntityPos()));
			case HEALTH -> healthColor(entity);
		};

		// Fade out entities right next to the camera so they don't cover the screen
		double distance = mc.gameRenderer.getCamera().getCameraPos().distanceTo(entity.getEyePos());
		double alpha = fadeDistance.get() <= 0 ? 1 : MathHelper.clamp(distance / fadeDistance.get(), 0, 1);
		if (alpha < 0.075) return 0;
		return ColorUtil.withAlpha(color, (int) ((color >>> 24) * alpha));
	}

	private int healthColor(Entity entity) {
		if (!(entity instanceof LivingEntity living)) return nonLivingColor.get();
		float percent = (living.getHealth() + living.getAbsorptionAmount()) / living.getMaxHealth();
		if (percent > 0.66f) return 0xFF19FF19;
		if (percent > 0.33f) return 0xFFFFAA19;
		return 0xFFFF1919;
	}

	private int fill(int color) {
		return ColorUtil.withAlpha(color, (int) ((color >>> 24) * fillOpacity.get()));
	}

	private static Box lerpedBox(Entity entity, float tickDelta) {
		return entity.getBoundingBox().offset(entity.getLerpedPos(tickDelta).subtract(entity.getEntityPos()));
	}
}
