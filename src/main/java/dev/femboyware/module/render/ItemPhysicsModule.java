package dev.femboyware.module.render;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import org.joml.Quaternionf;

/**
 * Dropped items lie on the ground instead of floating and spinning. The renderer change is in
 * ItemEntityRendererMixin.
 * Ported from Meteor Client's ItemPhysics (https://github.com/MeteorDevelopment/meteor-client), GPL-3.0.
 */
public class ItemPhysicsModule extends Module {
	/** One model pixel; flat items like swords are this thin. */
	private static final double PIXEL = 1.0 / 16.0;

	private final BoolSetting randomRotation = add(new BoolSetting("Random Rotation", "Turn each item a random way", true));

	public ItemPhysicsModule() {
		super("Item Physics", "Makes dropped items lie flat on the ground", Category.COSMETIC);
	}

	/** Flat models (most items) get laid down; 3D ones (blocks) stand as they are. */
	public static boolean isFlat(Box model) {
		return model.getLengthX() > PIXEL && model.getLengthY() > PIXEL && model.getLengthZ() <= PIXEL + 1e-4;
	}

	/** Height to lift the item by so it rests on the ground instead of bobbing. */
	public float restingHeight(Box model) {
		// Laid flat, the model's depth becomes its height
		return isFlat(model) ? (float) model.maxZ + 0.01f : (float) -model.minY;
	}

	/** Turn for the item: a steady random spin around Y, then laid down if it's flat. */
	public Quaternionf rotation(Box model, float uniqueOffset) {
		Quaternionf rotation = new Quaternionf();
		if (randomRotation.get()) rotation.rotateY(uniqueOffset * 37f % MathHelper.TAU);
		if (isFlat(model)) rotation.rotateX(MathHelper.HALF_PI);
		return rotation;
	}
}
