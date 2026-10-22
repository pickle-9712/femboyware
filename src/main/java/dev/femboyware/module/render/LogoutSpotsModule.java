package dev.femboyware.module.render;

import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.ProjectionUtil;
import dev.femboyware.util.RenderUtil;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Marks where players logged out, with their name and health at the time.
 * Ported from Meteor Client's LogoutSpots (https://github.com/MeteorDevelopment/meteor-client), GPL-3.0.
 */
public class LogoutSpotsModule extends Module {
	private record Spot(UUID uuid, String name, Box box, int health, int maxHealth) {}

	private final NumberSetting scale = add(new NumberSetting("Scale", "Label size", 1, 0.3, 3, 0.05));
	private final BoolSetting fullHeight = add(new BoolSetting("Full Height", "Draw the player's whole hitbox, not just the floor", true));
	private final ColorSetting fillColor = add(new ColorSetting("Fill", "Box fill colour", 0x37FF00FF));
	private final ColorSetting lineColor = add(new ColorSetting("Line", "Box outline colour", 0xFFFF00FF));
	private final ColorSetting nameColor = add(new ColorSetting("Name Color", "Label text colour", 0xFFFFFFFF));
	private final ColorSetting backgroundColor = add(new ColorSetting("Background", "Label background colour", 0x4B000000));

	private final List<Spot> spots = new ArrayList<>();
	/** Players we could see last tick, so we still know where they were when they leave the tab list. */
	private final Map<UUID, PlayerEntity> lastPlayers = new HashMap<>();
	private Set<UUID> lastTab = new HashSet<>();
	private RegistryKey<World> dimension;

	public LogoutSpotsModule() {
		super("Logout Spots", "Marks where players logged out", Category.ESP);
	}

	@Override
	public String getInfo() {
		return Integer.toString(spots.size());
	}

	@Override
	protected void onDisable() {
		spots.clear();
		lastPlayers.clear();
		lastTab = new HashSet<>();
		dimension = null;
	}

	@Override
	public void onTick() {
		if (mc.world.getRegistryKey() != dimension) {
			spots.clear();
			dimension = mc.world.getRegistryKey();
		}

		Set<UUID> tab = new HashSet<>();
		for (PlayerListEntry entry : mc.getNetworkHandler().getPlayerList()) tab.add(entry.getProfile().id());
		for (UUID uuid : lastTab) {
			if (tab.contains(uuid)) continue;
			PlayerEntity player = lastPlayers.get(uuid);
			if (player != null) {
				spots.removeIf(spot -> spot.uuid.equals(uuid));
				spots.add(new Spot(uuid, player.getName().getString(), player.getBoundingBox(),
					Math.round(player.getHealth() + player.getAbsorptionAmount()), Math.round(player.getMaxHealth() + player.getAbsorptionAmount())));
			}
		}
		lastTab = tab;

		lastPlayers.clear();
		for (PlayerEntity player : mc.world.getPlayers()) {
			if (player == mc.player) continue;
			lastPlayers.put(player.getUuid(), player);
			// They're back
			spots.removeIf(spot -> spot.uuid.equals(player.getUuid()));
		}
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		for (Spot spot : spots) {
			Box box = fullHeight.get() ? spot.box : spot.box.withMaxY(spot.box.minY + 0.05);
			RenderUtil.drawBox(event, box, fillColor.get(), lineColor.get(), 1.5f);
		}
	}

	@Override
	public void onRender2D(DrawContext context, float tickDelta) {
		if (mc.options.hudHidden) return;
		TextRenderer font = mc.textRenderer;
		for (Spot spot : spots) {
			Vec3d screen = ProjectionUtil.toScreen(new Vec3d(spot.box.getCenter().x, spot.box.maxY + 0.5, spot.box.getCenter().z));
			if (screen == null) continue;

			float percent = (float) spot.health / Math.max(1, spot.maxHealth);
			int healthColor = percent <= 0.333f ? 0xFFE11919 : percent <= 0.666f ? 0xFFE16919 : 0xFF19E119;
			String health = " " + spot.health;
			int width = font.getWidth(spot.name) + font.getWidth(health);

			context.getMatrices().pushMatrix();
			context.getMatrices().translate((float) screen.x, (float) screen.y);
			context.getMatrices().scale(scale.getFloat(), scale.getFloat());
			int x = -width / 2;
			context.fill(x - 2, -2, x + width + 2, font.fontHeight, backgroundColor.get());
			context.drawTextWithShadow(font, spot.name, x, 0, nameColor.get());
			context.drawTextWithShadow(font, health, x + font.getWidth(spot.name), 0, healthColor);
			context.getMatrices().popMatrix();
		}
	}
}
