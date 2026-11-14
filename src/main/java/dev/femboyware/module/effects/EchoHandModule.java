package dev.femboyware.module.effects;

import dev.femboyware.module.Category;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.FxRender;
import dev.femboyware.util.Mc;
import java.util.ArrayDeque;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.util.Mth;

/**
 * A coloured echo trails your first-person hands: when you turn or swing, glowing copies of where
 * your hand just was hang behind it and fade, like a long exposure.
 */
public class EchoHandModule extends FxModule {
	private final NumberSetting length = add(new NumberSetting("Length", "How many echoes trail behind", 12, 3, 30, 1));
	private final NumberSetting size = add(new NumberSetting("Size", "How big each echo is", 1, 0.3, 3, 0.05));
	private final NumberSetting opacity = add(new NumberSetting("Opacity", "How strong the echoes are", 0.6, 0.1, 1, 0.05));
	private final BoolSetting offhand = add(new BoolSetting("Offhand", "Echo your offhand too when it holds something", true));
	private final Palette palette = palette(0xFFFF77C8, 0xFF8AD8FF);

	private record Frame(float yaw, float pitch, float swingMain, float swingOff) {
	}

	private final ArrayDeque<Frame> history = new ArrayDeque<>();

	public EchoHandModule() {
		super("Echo Hand", "Colored motion history around first-person hands and items", Category.EFFECTS);
	}

	@Override
	protected void onDisable() {
		history.clear();
	}

	@Override
	public void onRender2D(GuiGraphics context, float tickDelta) {
		if (!mc.options.getCameraType().isFirstPerson() || Mc.hudHidden()) {
			history.clear();
			return;
		}
		float yaw = mc.player.getViewYRot(tickDelta), pitch = mc.player.getViewXRot(tickDelta);
		boolean mainRight = mc.player.getMainArm() == net.minecraft.world.entity.HumanoidArm.RIGHT;
		float swing = mc.player.getAttackAnim(tickDelta);
		// Swings are drawn on the main hand
		float swingMain = swing;
		float swingOff = 0;
		history.addFirst(new Frame(yaw, pitch, swingMain, swingOff));
		while (history.size() > length.getInt()) history.removeLast();

		int width = context.guiWidth(), height = context.guiHeight();
		float perDegree = height / (float) Math.max(30, mc.options.fov().get());
		boolean drawOff = offhand.get() && !mc.player.getOffhandItem().isEmpty();
		int i = 0;
		for (Frame frame : history) {
			if (i++ == 0) continue;
			float age = i / (float) history.size();
			// Where the hand was, relative to where the camera looks now
			float dx = Mth.wrapDegrees(frame.yaw() - yaw) * perDegree;
			float dy = (frame.pitch() - pitch) * perDegree;
			float alpha = opacity.getFloat() * (1 - age);
			int color = FxRender.fade(palette.at(age + (float) time() * 0.3f), alpha);
			float s = size.getFloat() * height * 0.16f * (1 - age * 0.5f);
			echo(context, width * (mainRight ? 0.78f : 0.22f), height * 0.8f, dx, dy, frame.swingMain(), mainRight, s, color);
			if (drawOff) echo(context, width * (mainRight ? 0.22f : 0.78f), height * 0.82f, dx, dy, frame.swingOff(), !mainRight, s * 0.85f, color);
		}
	}

	private static void echo(GuiGraphics context, float baseX, float baseY, float dx, float dy, float swing, boolean right, float s, int color) {
		// A swing carries the hand up and across the screen
		float lift = (float) Math.sin(Math.PI * swing);
		float across = (float) Math.sin(Math.PI * Math.sqrt(swing));
		float x = baseX - dx + (right ? -1 : 1) * across * s * 0.6f;
		float y = baseY + dy - lift * s * 0.5f;
		int half = Math.max(2, Math.round(s / 2));
		context.blit(RenderPipelines.GUI_TEXTURED, FxRender.GLOW, Math.round(x) - half, Math.round(y) - half, 0, 0, half * 2, half * 2, 64, 64, 64, 64, color);
	}
}
