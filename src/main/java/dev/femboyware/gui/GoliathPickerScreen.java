package dev.femboyware.gui;

import dev.femboyware.util.DonutGoliaths;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;

/**
 * Pick DonutSMP goliaths on a map of the whole world: click or drag over squares to pick or unpick
 * them, then Go. From the Anubis client's goliath picker (GPL-3.0, by 4ldenz,
 * https://github.com/4ldenz/Anubis-client), redrawn with vanilla widgets.
 */
public class GoliathPickerScreen extends Screen {
	private final Set<DonutGoliaths.Goliath> picked;
	private final Consumer<Set<DonutGoliaths.Goliath>> onPick;
	private boolean confirmed, dragging, dragAdds;
	private DonutGoliaths.Goliath dragLast;
	private Button go;
	private int mapX, mapY, cell;

	public GoliathPickerScreen(Set<DonutGoliaths.Goliath> preselected, Consumer<Set<DonutGoliaths.Goliath>> onPick) {
		super(Component.literal("Goliath RTP"));
		this.picked = new LinkedHashSet<>(preselected);
		this.onPick = onPick;
	}

	@Override
	protected void init() {
		cell = Math.max(3, Math.min((height - 90) / DonutGoliaths.GRID, (width - 40) / DonutGoliaths.GRID));
		int side = cell * DonutGoliaths.GRID;
		mapX = (width - side) / 2;
		mapY = 30;
		int buttonsY = mapY + side + 22;
		addRenderableWidget(Button.builder(Component.literal("Clear"), b -> picked.clear()).bounds(mapX, buttonsY, 80, 20).build());
		go = addRenderableWidget(Button.builder(Component.literal("Go"), b -> confirm()).bounds(mapX + side - 80, buttonsY, 80, 20).build());
	}

	@Override
	public void removed() {
		super.removed();
		onPick.accept(confirmed ? Set.copyOf(picked) : null);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private void confirm() {
		if (picked.isEmpty()) return;
		confirmed = true;
		onClose();
	}

	private DonutGoliaths.Goliath at(double mouseX, double mouseY) {
		int u = (int) Math.floor((mouseX - mapX) / cell), v = (int) Math.floor((mouseY - mapY) / cell);
		int found = DonutGoliaths.square(u, v);
		return found < 0 ? null : new DonutGoliaths.Goliath(found / 1000, found % 1000);
	}

	@Override
	public void render(GuiGraphics context, int mouseX, int mouseY, float deltaTicks) {
		//? if >=26.1 {
		/*super.extractRenderState(context, mouseX, mouseY, deltaTicks);
		*///?} else {
		super.render(context, mouseX, mouseY, deltaTicks);
		//?}
		int side = cell * DonutGoliaths.GRID;
		context.drawCenteredString(font, "Goliath RTP", width / 2, 12, 0xFFFFFFFF);
		context.fill(mapX - 2, mapY - 2, mapX + side + 2, mapY + side + 2, 0xC0101018);
		DonutGoliaths.Goliath hover = at(mouseX, mouseY);
		for (int v = 0; v < DonutGoliaths.GRID; v++) {
			for (int u = 0; u < DonutGoliaths.GRID; u++) {
				int found = DonutGoliaths.square(u, v);
				if (found < 0) continue;
				DonutGoliaths.Goliath g = new DonutGoliaths.Goliath(found / 1000, found % 1000);
				int rgb = DonutGoliaths.COLORS[g.region()] & 0xFFFFFF;
				boolean on = picked.contains(g), over = g.equals(hover);
				int alpha = on ? 0xF0 : over ? 0xA0 : g.region() == DonutGoliaths.EUROPE ? 0x30 : 0x55;
				int x = mapX + u * cell, y = mapY + v * cell;
				context.fill(x, y, x + cell, y + cell, alpha << 24 | rgb);
				// Thin dark line between different goliaths
				if (DonutGoliaths.square(u + 1, v) != found) context.fill(x + cell - 1, y, x + cell, y + cell, 0x80000000);
				if (DonutGoliaths.square(u, v + 1) != found) context.fill(x, y + cell - 1, x + cell, y + cell, 0x80000000);
			}
		}
		// Where you are
		if (minecraft.player != null && minecraft.level != null && minecraft.level.dimension() != Level.END) {
			double scale = minecraft.level.dimension() == Level.NETHER ? 8 : 1;
			double px = (minecraft.player.getX() * scale + DonutGoliaths.HALF) / DonutGoliaths.SQUARE * cell + mapX;
			double pz = (minecraft.player.getZ() * scale + DonutGoliaths.HALF) / DonutGoliaths.SQUARE * cell + mapY;
			if (px >= mapX && pz >= mapY && px < mapX + side && pz < mapY + side)
				context.fill((int) px - 2, (int) pz - 2, (int) px + 2, (int) pz + 2, 0xFFFFFFFF);
		}
		String info = hover != null ? hover.label() + (hover.region() == DonutGoliaths.EUROPE ? "  (no /rtp)" : "")
			: picked.isEmpty() ? "Click or drag to pick goliaths" : picked.size() + (picked.size() == 1 ? " goliath picked" : " goliaths picked");
		context.drawCenteredString(font, info, width / 2, mapY + side + 7, 0xFFB8B8C8);
		go.setMessage(Component.literal(picked.isEmpty() ? "Go" : "Go (" + picked.size() + ")"));
		go.active = !picked.isEmpty();
	}

	private void paint(double mouseX, double mouseY) {
		DonutGoliaths.Goliath g = at(mouseX, mouseY);
		if (g == null || g.equals(dragLast)) return;
		dragLast = g;
		if (dragAdds) picked.add(g);
		else picked.remove(g);
	}

	//? if >=1.21.10 {
	@Override
	public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent click, boolean doubled) {
		if (click.button() == 0 && press(click.x(), click.y())) return true;
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent click, double dx, double dy) {
		if (dragging) {
			paint(click.x(), click.y());
			return true;
		}
		return super.mouseDragged(click, dx, dy);
	}

	@Override
	public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent click) {
		dragging = false;
		return super.mouseReleased(click);
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
		if (event.key() == 257 || event.key() == 335) {
			confirm();
			return true;
		}
		return super.keyPressed(event);
	}
	//?}

	private boolean press(double mouseX, double mouseY) {
		DonutGoliaths.Goliath g = at(mouseX, mouseY);
		if (g == null) return false;
		dragging = true;
		dragAdds = !picked.contains(g);
		dragLast = null;
		paint(mouseX, mouseY);
		return true;
	}
}
