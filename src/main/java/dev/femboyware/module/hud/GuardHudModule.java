package dev.femboyware.module.hud;

import dev.femboyware.FemboyWare;
import dev.femboyware.gui.Glass;
import dev.femboyware.gui.Theme;
import dev.femboyware.module.combat.BotArena;
import dev.femboyware.module.combat.GuardCommandModule;
import dev.femboyware.module.combat.GuardCommandModule.GuardStatus;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/** A card for each guard connected to Guard Command: health, totems, pots, what it's doing and how far away it is. */
public class GuardHudModule extends HudElement {
	private static final int WIDTH = 150;
	private static final int ROW = 31;
	private static final GuardStatus EXAMPLE = new GuardStatus("ExampleGuard", 16, 20, 4, 2, 12, "Defend", "SomePlayer", 0, 0, 0, System.currentTimeMillis());

	private final BoolSetting distance = add(new BoolSetting("Distance", "How far away each guard is", true));
	private final ColorSetting healthFull = add(new ColorSetting("Health Full Color", "Health bar at full health", 0xFF50FF50)).visibleWhen(this::usesCustomColors);
	private final ColorSetting healthLow = add(new ColorSetting("Health Low Color", "Health bar when nearly dead", 0xFFFF3050)).visibleWhen(this::usesCustomColors);
	private final ColorSetting warning = add(new ColorSetting("Warning Color", "No totems or pots left, or no word from the guard", 0xFFFF6060)).visibleWhen(this::usesCustomColors);

	public GuardHudModule() {
		super("Guard HUD", "Shows your bodyguards' health, totems, pots and targets", 1, 30);
		setEnabled(true);
	}

	private List<GuardStatus> guards(boolean editing) {
		List<GuardStatus> guards = FemboyWare.getModules().get(GuardCommandModule.class).guards();
		return guards.isEmpty() && editing ? List.of(EXAMPLE) : guards;
	}

	/** The arena match, while one's running. */
	private BotArena arena() {
		BotArena arena = FemboyWare.getModules().get(GuardCommandModule.class).arena();
		return arena.phase() == BotArena.Phase.OFF ? null : arena;
	}

	@Override
	public int width(boolean editing) {
		return guards(editing).isEmpty() ? 0 : WIDTH;
	}

	@Override
	public int height(boolean editing) {
		return Math.max(1, guards(editing).size()) * ROW + (arena() != null ? ROW : 0);
	}

	@Override
	protected void draw(GuiGraphics context, int left, int top, boolean editing) {
		int y = top;
		BotArena arena = arena();
		if (arena != null) {
			drawArena(context, left, y, arena);
			y += ROW;
		}
		for (GuardStatus guard : guards(editing)) {
			boolean lost = !editing && guard.age() > GuardCommandModule.STATUS_LOST;
			Glass.draw(context, left, y, left + WIDTH, y + ROW - 2, 4, backgroundColor(Theme.list()), false);
			context.drawString(mc.font, guard.name(), left + 5, y + 4, textColor());
			if (lost) {
				String note = "no signal";
				context.drawString(mc.font, note, left + WIDTH - 5 - mc.font.width(note), y + 4, warning.get());
				y += ROW;
				continue;
			}
			String doing = guard.target().isEmpty() ? guard.state() : guard.target();
			String fit = mc.font.plainSubstrByWidth(doing, 60);
			context.drawString(mc.font, fit, left + WIDTH - 5 - mc.font.width(fit), y + 4, guard.target().isEmpty() ? mutedColor() : accentColor());

			// Health bar, with absorption already counted in its length
			int barLeft = left + 5, barRight = left + WIDTH - 5;
			float fraction = Mth.clamp(guard.health() / Math.max(1, guard.maxHealth()), 0, 1);
			Glass.rounded(context, barLeft, y + 14, barRight, y + 17, 1, 0x40000000);
			int filled = barLeft + Math.round((barRight - barLeft) * fraction);
			if (filled > barLeft) Glass.rounded(context, barLeft, y + 14, filled, y + 17, 1, LyricFont.mix(healthLow.get(), healthFull.get(), fraction));

			context.pose().pushMatrix();
			context.pose().translate(left + 5, y + 20);
			context.pose().scale(0.8f, 0.8f);
			String hearts = String.format(Locale.ROOT, "%.0f hp", guard.health() + guard.absorption());
			String totems = guard.totems() + " totems";
			String pots = guard.pots() + " pots";
			int x = 0;
			context.drawString(mc.font, hearts, x, 0, textColor());
			x += mc.font.width(hearts) + 8;
			context.drawString(mc.font, totems, x, 0, guard.totems() == 0 ? warning.get() : mutedColor());
			x += mc.font.width(totems) + 8;
			context.drawString(mc.font, pots, x, 0, guard.pots() == 0 ? warning.get() : mutedColor());
			x += mc.font.width(pots) + 8;
			if (distance.get() && mc.player != null && !editing) {
				String far = Math.round(Math.sqrt(mc.player.distanceToSqr(guard.x(), guard.y(), guard.z()))) + "m";
				context.drawString(mc.font, far, x, 0, mutedColor());
			}
			context.pose().popMatrix();
			y += ROW;
		}
	}

	/** Score, round and who's still standing on each side, with your bet. */
	private void drawArena(GuiGraphics context, int left, int y, BotArena arena) {
		Glass.draw(context, left, y, left + WIDTH, y + ROW - 2, 4, backgroundColor(Theme.list()), false);
		String score = BotArena.PINK + " " + arena.pinkScore() + " - " + arena.blueScore() + " " + BotArena.BLUE;
		context.drawString(mc.font, score, left + 5, y + 4, accentColor());
		String round = "R" + arena.round() + "/ft" + arena.firstTo();
		context.drawString(mc.font, round, left + WIDTH - 5 - mc.font.width(round), y + 4, mutedColor());
		String phase = switch (arena.phase()) {
			case LINING_UP -> "lining up";
			case FIGHTING -> "fight!";
			default -> "next round soon";
		};
		String bet = arena.betText();
		String line = String.format(Locale.ROOT, "%d/%d vs %d/%d  %s  %s", arena.pinkAlive(), arena.pinkSize(), arena.blueAlive(), arena.blueSize(), phase,
			bet != null ? "bet " + bet : arena.coins() + " coins");
		context.pose().pushMatrix();
		context.pose().translate(left + 5, y + 17);
		context.pose().scale(0.8f, 0.8f);
		context.drawString(mc.font, line, 0, 0, textColor());
		context.pose().popMatrix();
	}
}
