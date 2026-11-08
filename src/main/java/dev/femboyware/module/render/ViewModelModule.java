package dev.femboyware.module.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.NumberSetting;
import net.minecraft.world.entity.HumanoidArm;

/** Moves, scales and rotates your first person hands. Applied by the held item renderer mixin. */
public class ViewModelModule extends Module {
	private final NumberSetting x = add(new NumberSetting("X", "Sideways offset (mirrored for the left hand)", 0, -2, 2, 0.01));
	private final NumberSetting y = add(new NumberSetting("Y", "Vertical offset", 0, -2, 2, 0.01));
	private final NumberSetting z = add(new NumberSetting("Z", "Forward offset", 0, -2, 2, 0.01));
	private final NumberSetting scale = add(new NumberSetting("Scale", "Hand size", 1, 0.1, 3, 0.01));
	private final NumberSetting rotateX = add(new NumberSetting("Rotate X", "Pitch in degrees", 0, -180, 180, 1));
	private final NumberSetting rotateY = add(new NumberSetting("Rotate Y", "Yaw in degrees (mirrored for the left hand)", 0, -180, 180, 1));
	private final NumberSetting rotateZ = add(new NumberSetting("Rotate Z", "Roll in degrees (mirrored for the left hand)", 0, -180, 180, 1));

	public ViewModelModule() {
		super("View Model", "Customise your hand position", Category.COSMETIC);
	}

	public void apply(PoseStack matrices, HumanoidArm arm) {
		int side = arm == HumanoidArm.RIGHT ? 1 : -1;
		matrices.translate(x.getFloat() * side, y.getFloat(), -z.getFloat());
		matrices.mulPose(Axis.XP.rotationDegrees(rotateX.getFloat()));
		matrices.mulPose(Axis.YP.rotationDegrees(rotateY.getFloat() * side));
		matrices.mulPose(Axis.ZP.rotationDegrees(rotateZ.getFloat() * side));
		float s = scale.getFloat();
		matrices.scale(s, s, s);
	}
}
