package dev.femboyware.module.effects;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.FxRender;
import org.joml.Vector3f;

/**
 * Colour grades the world (not your hand or the HUD): a preset look or your own colour washed over
 * everything, with a gradient from the top of the view to the bottom for the sunset looks.
 */
public class WorldColorModule extends FxModule {
	public enum Look { WARM, COLD, PINK, SUNSET, VAPORWAVE, MATRIX, BLOOD_MOON, CUSTOM }

	private final EnumSetting<Look> look = add(new EnumSetting<>("Look", "The colour grade", Look.PINK));
	private final NumberSetting strength = add(new NumberSetting("Strength", "How strong the colour is", 0.25, 0.02, 0.8, 0.01));
	private final ColorSetting top = add(new ColorSetting("Custom Top", "Custom: the colour at the top of the view", 0xFFFF7AC8));
	private final ColorSetting bottom = add(new ColorSetting("Custom Bottom", "Custom: the colour at the bottom", 0xFF7A5CFF));

	public WorldColorModule() {
		super("World Color", "Colour grade the world", Category.SKY);
	}

	@Override
	public String getInfo() {
		return look.getDisplayName();
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		int up, down;
		switch (look.get()) {
			case WARM -> up = down = 0xFFFFA24A;
			case COLD -> up = down = 0xFF5AA8FF;
			case PINK -> up = down = 0xFFFF6FC8;
			case SUNSET -> { up = 0xFFFF8A3D; down = 0xFF9A3DFF; }
			case VAPORWAVE -> { up = 0xFF3DE7FF; down = 0xFFFF3DC8; }
			case MATRIX -> up = down = 0xFF3DFF6A;
			case BLOOD_MOON -> { up = 0xFFFF2A2A; down = 0xFF5A0010; }
			default -> { up = top.get(); down = bottom.get(); }
		}
		float a = strength.getFloat();
		// A sheet just in front of the camera covering the whole view, over the world but under the hand
		VertexConsumer quads = FxRender.quads(event, false, true);
		PoseStack.Pose pose = event.matrices().last();
		var o = event.camera().orientation;
		float d = 0.2f, w = 0.6f, h = 0.6f;
		Vector3f tl = new Vector3f(-w, h, -d).rotate(o), tr = new Vector3f(w, h, -d).rotate(o), br = new Vector3f(w, -h, -d).rotate(o), bl = new Vector3f(-w, -h, -d).rotate(o);
		int cu = FxRender.fade(up, a), cd = FxRender.fade(down, a);
		FxRender.quad(quads, pose, bl.x, bl.y, bl.z, cd, br.x, br.y, br.z, cd, tr.x, tr.y, tr.z, cu, tl.x, tl.y, tl.z, cu);
		FxRender.quad(quads, pose, tl.x, tl.y, tl.z, cu, tr.x, tr.y, tr.z, cu, br.x, br.y, br.z, cd, bl.x, bl.y, bl.z, cd);
	}
}
