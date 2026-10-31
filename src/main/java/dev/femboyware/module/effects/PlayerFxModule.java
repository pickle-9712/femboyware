package dev.femboyware.module.effects;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.Category;
import dev.femboyware.setting.EnumSetting;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.client.util.math.MatrixStack;

import java.util.ArrayList;
import java.util.List;

/** Base for cosmetics players wear: you, you and your friends, or everyone. Only you see them. */
public abstract class PlayerFxModule extends FxModule {
	public enum ShowOn { YOU, FRIENDS, EVERYONE }

	private EnumSetting<ShowOn> showOn;

	protected PlayerFxModule(String name, String description) {
		super(name, description, Category.EFFECTS);
	}

	/** Adds the Show On setting at this point in the settings list; call once, from a field. */
	protected EnumSetting<ShowOn> showOnSetting() {
		showOn = add(new EnumSetting<>("Show On", "You, you and your friends, or every player", ShowOn.YOU));
		return showOn;
	}

	/**
	 * The players wearing the effect. You're left out in first person when {@code inFirstPerson} is
	 * false, for effects that would sit in front of the camera.
	 */
	protected List<PlayerEntity> wearers(boolean inFirstPerson) {
		List<PlayerEntity> wearers = new ArrayList<>();
		for (PlayerEntity player : mc.world.getPlayers()) {
			if (player.isSpectator() || player.isInvisible() || player.isRemoved()) continue;
			if (player == mc.player) {
				if (!inFirstPerson && mc.options.getPerspective().isFirstPerson()) continue;
			} else if (showOn.is(ShowOn.YOU) || showOn.is(ShowOn.FRIENDS) && !FemboyWare.getFriends().isFriend(player)) {
				continue;
			}
			wearers.add(player);
		}
		return wearers;
	}

	/**
	 * Moves {@code matrices} from the camera to the top of {@code player}'s head, turned with it:
	 * +Y points out of the top of the head and +Z the way they look.
	 */
	protected static void toHead(MatrixStack matrices, PlayerEntity player, double camX, double camY, double camZ, float tickDelta) {
		double x = MathHelper.lerp(tickDelta, player.lastRenderX, player.getX());
		double y = MathHelper.lerp(tickDelta, player.lastRenderY, player.getY());
		double z = MathHelper.lerp(tickDelta, player.lastRenderZ, player.getZ());
		// The neck sits a little under the eyes, and the head is half a block tall above it
		float neck = player.getEyeHeight(player.getPose()) - 0.12f;
		matrices.translate((float) (x - camX), (float) (y - camY) + neck, (float) (z - camZ));
		matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-MathHelper.lerpAngleDegrees(tickDelta, player.lastHeadYaw, player.headYaw)));
		matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(player.getPitch(tickDelta)));
		matrices.translate(0, 0.5f, 0);
	}

	/**
	 * Moves {@code matrices} from the camera to {@code player}'s feet, turned and leaned with their
	 * body: +Y runs up the spine, +Z points out of their chest and +X to their left.
	 */
	protected static void toBody(MatrixStack matrices, PlayerEntity player, double camX, double camY, double camZ, float tickDelta) {
		double x = MathHelper.lerp(tickDelta, player.lastRenderX, player.getX());
		double y = MathHelper.lerp(tickDelta, player.lastRenderY, player.getY());
		double z = MathHelper.lerp(tickDelta, player.lastRenderZ, player.getZ());
		matrices.translate((float) (x - camX), (float) (y - camY), (float) (z - camZ));
		matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-MathHelper.lerpAngleDegrees(tickDelta, player.lastBodyYaw, player.bodyYaw)));
		if (player.isGliding() || player.isInSwimmingPose()) {
			// Lying along the way they look, pivoting at the feet like the vanilla model does
			matrices.translate(0, 0.3f, 0);
			matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(90 + player.getPitch(tickDelta)));
			matrices.translate(0, -0.3f, 0);
		} else if (player.isInSneakingPose()) {
			matrices.translate(0, -0.2f, 0);
			matrices.translate(0, 0.75f, 0);
			matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(28));
			matrices.translate(0, -0.75f, 0);
		}
	}
}
