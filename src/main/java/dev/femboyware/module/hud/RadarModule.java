package dev.femboyware.module.hud;

import dev.femboyware.FemboyWare;
import dev.femboyware.gui.Glass;
import dev.femboyware.gui.Theme;
import dev.femboyware.module.client.FakePlayerModule;
import dev.femboyware.module.utility.FriendsModule;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.NumberSetting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.util.Mth;

/**
 * A round radar of the players around you, turning with you (or north up), with N/E/S/W and range
 * rings. Ported from the Anubis client's Radar (GPL-3.0, by 4ldenz,
 * https://github.com/4ldenz/Anubis-client), drawn with femboyware's glass.
 */
public class RadarModule extends HudElement {
	private static final String[] LETTERS = {"N", "E", "S", "W"};
	private static final int[] DX = {0, 1, 0, -1}, DZ = {-1, 0, 1, 0};

	private final NumberSetting range = add(new NumberSetting("Range", "Blocks from the centre to the edge", 48, 8, 256, 1));
	private final NumberSetting size = add(new NumberSetting("Size", "How big it is", 110, 60, 240, 1));
	private final BoolSetting rotate = add(new BoolSetting("Rotate", "Turn with you, so up is where you look (off: north is up)", true));
	private final BoolSetting names = add(new BoolSetting("Names", "Write names next to the dots", false));
	private final ColorSetting dot = add(new ColorSetting("Player Color", "Colour of other players (friends use the Friends colour)", 0xFFFF4566));

	public RadarModule() {
		super("Radar", "A round radar of the players around you", 1, 120);
	}

	@Override
	public int width(boolean editing) {
		return size.get().intValue();
	}

	@Override
	public int height(boolean editing) {
		return size.get().intValue();
	}

	@Override
	protected void draw(GuiGraphics context, int left, int top, boolean editing) {
		int s = size.get().intValue();
		float r = s / 2f, cx = left + r, cy = top + r;
		Glass.ellipse(context, left, top, left + s, top + s, backgroundColor(Theme.list()));
		int hair = 0x30FFFFFF;
		// Range rings at a third and two thirds
		for (float ring : new float[]{1 / 3f, 2 / 3f}) {
			int rr = Math.round((r - 6) * ring);
			Glass.ellipse(context, Math.round(cx - rr), Math.round(cy - rr), Math.round(cx + rr), Math.round(cy + rr), 0x14FFFFFF);
		}
		context.fill(Math.round(cx), Math.round(cy - r + 12), Math.round(cx) + 1, Math.round(cy + r - 12), hair);
		context.fill(Math.round(cx - r + 12), Math.round(cy), Math.round(cx + r - 12), Math.round(cy) + 1, hair);
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		double yaw = mc.player != null && rotate.get() ? Math.toRadians(mc.player.getViewYRot(partial)) : Math.PI;
		float sin = (float) Math.sin(yaw), cos = (float) Math.cos(yaw);
		float letterR = r - 7;
		for (int i = 0; i < 4; i++) {
			float ox = -DX[i] * cos - DZ[i] * sin, oy = DX[i] * sin - DZ[i] * cos;
			int w = mc.font.width(LETTERS[i]);
			context.drawString(mc.font, LETTERS[i], Math.round(cx + ox * letterR - w / 2f), Math.round(cy + oy * letterR - 4), i == 0 ? accentColor() : mutedColor(), false);
		}
		// You
		context.fill(Math.round(cx) - 1, Math.round(cy) - 1, Math.round(cx) + 2, Math.round(cy) + 2, 0xFFFFFFFF);
		if (mc.player == null || mc.level == null) return;
		double rangeSq = range.get() * range.get();
		float perBlock = (r - 12) / range.get().floatValue();
		double px = Mth.lerp(partial, mc.player.xo, mc.player.getX()), pz = Mth.lerp(partial, mc.player.zo, mc.player.getZ());
		FakePlayerModule fakes = FemboyWare.getModules().get(FakePlayerModule.class);
		for (AbstractClientPlayer other : mc.level.players()) {
			if (other == mc.player || other.isRemoved() || other.isDeadOrDying() || other.isSpectator()) continue;
			double dx = Mth.lerp(partial, other.xo, other.getX()) - px, dz = Mth.lerp(partial, other.zo, other.getZ()) - pz;
			if (dx * dx + dz * dz > rangeSq) continue;
			// World to radar: up on the radar is where you look
			float sx = (float) (rotate.get() ? -dx * cos - dz * sin : dx), sy = (float) (rotate.get() ? dx * sin - dz * cos : dz);
			int x = Math.round(cx + sx * perBlock), y = Math.round(cy + sy * perBlock);
			int color = FriendsModule.color(other, fakes.isFake(other.getId()) ? 0xFFAAAAAA : dot.get());
			context.fill(x - 2, y - 2, x + 2, y + 2, 0x90000000);
			context.fill(x - 1, y - 1, x + 2, y + 2, color | 0xFF000000);
			if (names.get()) context.drawString(mc.font, other.getGameProfile().name(), x + 4, y - 4, textColor(), true);
		}
	}
}
