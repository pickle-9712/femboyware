package dev.femboyware.module.effects;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.Category;
import dev.femboyware.setting.EnumSetting;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;

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
	protected List<Player> wearers(boolean inFirstPerson) {
		List<Player> wearers = new ArrayList<>();
		for (Player player : mc.level.players()) {
			if (player.isSpectator() || player.isInvisible() || player.isRemoved()) continue;
			if (player == mc.player) {
				if (!inFirstPerson && mc.options.getCameraType().isFirstPerson()) continue;
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
	protected static void toHead(PoseStack matrices, Player player, double camX, double camY, double camZ, float tickDelta) {
		double x = Mth.lerp(tickDelta, player.xOld, player.getX());
		double y = Mth.lerp(tickDelta, player.yOld, player.getY());
		double z = Mth.lerp(tickDelta, player.zOld, player.getZ());
		// The neck sits a little under the eyes, and the head is half a block tall above it
		float neck = player.getEyeHeight(player.getPose()) - 0.12f;
		matrices.translate((float) (x - camX), (float) (y - camY) + neck, (float) (z - camZ));
		matrices.mulPose(Axis.YP.rotationDegrees(-Mth.rotLerp(tickDelta, player.yHeadRotO, player.yHeadRot)));
		matrices.mulPose(Axis.XP.rotationDegrees(player.getViewXRot(tickDelta)));
		matrices.translate(0, 0.5f, 0);
	}

	/**
	 * Moves {@code matrices} from the camera to {@code player}'s feet, turned and leaned with their
	 * body: +Y runs up the spine, +Z points out of their chest and +X to their left.
	 */
	protected static void toBody(PoseStack matrices, Player player, double camX, double camY, double camZ, float tickDelta) {
		double x = Mth.lerp(tickDelta, player.xOld, player.getX());
		double y = Mth.lerp(tickDelta, player.yOld, player.getY());
		double z = Mth.lerp(tickDelta, player.zOld, player.getZ());
		matrices.translate((float) (x - camX), (float) (y - camY), (float) (z - camZ));
		matrices.mulPose(Axis.YP.rotationDegrees(-Mth.rotLerp(tickDelta, player.yBodyRotO, player.yBodyRot)));
		if (player.isFallFlying() || player.isVisuallySwimming()) {
			// Lying along the way they look, pivoting at the feet like the vanilla model does
			matrices.translate(0, 0.3f, 0);
			matrices.mulPose(Axis.XP.rotationDegrees(90 + player.getViewXRot(tickDelta)));
			matrices.translate(0, -0.3f, 0);
		} else if (player.isCrouching()) {
			matrices.translate(0, -0.2f, 0);
			matrices.translate(0, 0.75f, 0);
			matrices.mulPose(Axis.XP.rotationDegrees(28));
			matrices.translate(0, -0.75f, 0);
		}
	}
}
