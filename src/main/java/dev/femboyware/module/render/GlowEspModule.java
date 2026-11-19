package dev.femboyware.module.render;

import dev.femboyware.FemboyWare;
import dev.femboyware.gui.Theme;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ChoiceSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.NumberSetting;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;

/**
 * Outlines entities through walls with a glow: players (friends in their own colour), passive mobs,
 * items and crystals, and whoever you're fighting. The settings and picking are ported from the
 * Anubis client's Glow ESP (GPL-3.0, by 4ldenz, https://github.com/4ldenz/Anubis-client); the glow
 * itself is the game's own outline, since Anubis's shader pass is tied to one renderer.
 */
public class GlowEspModule extends Module {
	private static final long TARGET_MEMORY_MS = 3500;

	private final BoolSetting players = add(new BoolSetting("Players", "Outline players", true));
	private final BoolSetting passives = add(new BoolSetting("Passives", "Outline passive mobs", false));
	private final BoolSetting items = add(new BoolSetting("Items", "Outline dropped items", false));
	private final BoolSetting crystals = add(new BoolSetting("Crystals", "Outline end crystals", true));
	private final BoolSetting markTarget = add(new BoolSetting("Mark Target", "Who you're hitting gets the target colour", true));
	private final ChoiceSetting colorMode = add(new ChoiceSetting("Color", "Theme colours or your own", "Theme", () -> List.of("Theme", "Manual")));
	private final ColorSetting playerColor = add(new ColorSetting("Player Color", "Players", 0xFF3478F6));
	private final ColorSetting friendColor = add(new ColorSetting("Friend Color", "Friends", 0xFF8FB5FA));
	private final ColorSetting passiveColor = add(new ColorSetting("Passive Color", "Passive mobs", 0xFF38B2E8));
	private final ColorSetting itemColor = add(new ColorSetting("Item Color", "Items", 0xFF4CC9D8));
	private final ColorSetting crystalColor = add(new ColorSetting("Crystal Color", "End crystals", 0xFFA27CF6));
	private final ColorSetting targetColor = add(new ColorSetting("Target Color", "Your target", 0xFFD6E4FD));
	private final NumberSetting distance = add(new NumberSetting("Distance", "Furthest entity to outline", 64, 8, 192, 4));
	private final NumberSetting limit = add(new NumberSetting("Limit", "Most entities outlined at once", 48, 4, 96, 1));

	private final Map<Entity, Integer> picked = new HashMap<>();
	private Entity target;
	private long targetAt;

	public GlowEspModule() {
		super("Glow ESP", "Outlines entities through walls with a glow", Category.ESP);
		playerColor.visibleWhen(() -> players.get() && manual());
		friendColor.visibleWhen(() -> players.get() && manual());
		passiveColor.visibleWhen(() -> passives.get() && manual());
		itemColor.visibleWhen(() -> items.get() && manual());
		crystalColor.visibleWhen(() -> crystals.get() && manual());
		targetColor.visibleWhen(() -> markTarget.get() && manual());
	}

	private boolean manual() {
		return colorMode.get().equals("Manual");
	}

	public static GlowEspModule active() {
		if (FemboyWare.getModules() == null) return null;
		GlowEspModule module = FemboyWare.getModules().get(GlowEspModule.class);
		return module != null && module.isEnabled() ? module : null;
	}

	@Override
	protected void onDisable() {
		picked.clear();
		target = null;
	}

	@Override
	public void onTick() {
		picked.clear();
		if (mc.level == null || mc.player == null) return;
		long now = System.currentTimeMillis();
		if (mc.hitResult instanceof EntityHitResult hit && mc.options.keyAttack.isDown()) {
			target = hit.getEntity();
			targetAt = now;
		}
		if (target != null && (now - targetAt > TARGET_MEMORY_MS || !target.isAlive())) target = null;
		double max = distance.get() * distance.get();
		List<Entity> candidates = new ArrayList<>();
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (entity == mc.player || entity.distanceToSqr(mc.player) > max) continue;
			if (colorFor(entity) != 0) candidates.add(entity);
		}
		candidates.sort(Comparator.comparingDouble(e -> e.distanceToSqr(mc.player)));
		int cap = limit.get().intValue();
		for (int i = 0; i < candidates.size() && i < cap; i++) picked.put(candidates.get(i), colorFor(candidates.get(i)));
	}

	private int colorFor(Entity entity) {
		int accent = Theme.accent() | 0xFF000000;
		if (markTarget.get() && entity == target) return manual() ? targetColor.get() : lift(accent, 0.8f);
		if (entity instanceof Player player) {
			if (!players.get() || player.isSpectator()) return 0;
			if (FemboyWare.getFriends().isFriend(player)) return manual() ? friendColor.get() : lift(accent, 0.45f);
			return manual() ? playerColor.get() : accent;
		}
		if (entity instanceof EndCrystal) return crystals.get() ? (manual() ? crystalColor.get() : accent) : 0;
		if (entity instanceof ItemEntity) return items.get() ? (manual() ? itemColor.get() : accent) : 0;
		if (entity instanceof net.minecraft.world.entity.Mob && !(entity instanceof net.minecraft.world.entity.monster.Enemy))
			return passives.get() ? (manual() ? passiveColor.get() : accent) : 0;
		return 0;
	}

	private static int lift(int argb, float t) {
		int r = (argb >> 16) & 255, g = (argb >> 8) & 255, b = argb & 255;
		r += (int) ((255 - r) * t);
		g += (int) ((255 - g) * t);
		b += (int) ((255 - b) * t);
		return 0xFF000000 | r << 16 | g << 8 | b;
	}

	/** Should this entity glow, and in what colour (0: leave it alone). */
	public int glow(Entity entity) {
		Integer color = picked.get(entity);
		return color == null ? 0 : color;
	}
}
