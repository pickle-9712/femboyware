package dev.femboyware.module.tung;

import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.module.render.TungSahurModule;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Take a big hit and Tung Tung Tung Sahur's face lunges right up to your screen with a bonk, then
 * he's gone again. First person only.
 */
public class SahurJumpscareModule extends Module {
	private static final long LENGTH = 700;

	private final NumberSetting damage = add(new NumberSetting("Min Damage", "Hearts lost in one go before he shows up", 3, 0.5, 10, 0.5));
	private final NumberSetting cooldown = add(new NumberSetting("Cooldown", "Seconds before he can show up again", 10, 0, 60, 1));
	private final BoolSetting flash = add(new BoolSetting("Flash", "The screen flashes as he lunges", true));

	private float lastHealth = -1;
	private long start = -1;

	public SahurJumpscareModule() {
		super("Sahur Jumpscare", "His face lunges at your screen when you take a big hit", Category.COSMETIC);
	}

	/** Shows him now, as if you'd just been hit. */
	public void scare() {
		start = System.currentTimeMillis();
		if (mc.player == null) return;
		mc.level.playLocalSound(mc.player.getX(), mc.player.getEyeY(), mc.player.getZ(), SoundEvents.ZOMBIE_ATTACK_WOODEN_DOOR, SoundSource.PLAYERS, 1, 0.8f, false);
		mc.level.playLocalSound(mc.player.getX(), mc.player.getEyeY(), mc.player.getZ(), SoundEvents.NOTE_BLOCK_BASEDRUM.value(), SoundSource.PLAYERS, 1, 0.5f, false);
	}

	@Override
	public void onTick() {
		if (mc.player == null) return;
		float health = mc.player.getHealth() + mc.player.getAbsorptionAmount();
		long now = System.currentTimeMillis();
		if (lastHealth >= 0 && lastHealth - health >= damage.get() * 2 && (start < 0 || now - start > cooldown.get() * 1000)) scare();
		lastHealth = mc.player.isDeadOrDying() ? -1 : health;
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		long since = System.currentTimeMillis() - start;
		if (start < 0 || since > LENGTH || mc.player == null || !mc.options.getCameraType().isFirstPerson()) return;
		float tickDelta = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		// Lunge in from a few blocks off, hold right up close, then drop away
		float t = since / (float) LENGTH;
		float distance = t < 0.25f ? Mth.lerp(t / 0.25f, 3f, 0.75f) : t < 0.75f ? 0.75f : Mth.lerp((t - 0.75f) / 0.25f, 0.75f, 2.5f);
		float drop = t > 0.75f ? (t - 0.75f) * 3 : 0;
		Vec3 look = mc.player.getViewVector(tickDelta);
		Vec3 face = look.scale(distance).subtract(0, drop, 0);
		float yaw = (float) Math.atan2(-look.x, -look.z);
		// A shake as he lands
		float shake = t < 0.4f ? (float) Math.sin(since * 0.12) * 0.03f * (1 - t / 0.4f) : 0;
		float size = 0.85f;
		TungSahurModule.drawSahur(event, TungSahurModule.feetForFace(face, yaw, size).add(shake, 0, 0), yaw, 0, -4, size);
	}

	@Override
	public void onRender2D(GuiGraphics context, float tickDelta) {
		long since = System.currentTimeMillis() - start;
		if (start < 0 || since > 250 || !flash.get()) return;
		int alpha = (int) (120 * (1 - since / 250f));
		context.fill(0, 0, context.guiWidth(), context.guiHeight(), alpha << 24 | 0xFFFFFF);
	}

	@Override
	protected void onDisable() {
		lastHealth = -1;
		start = -1;
	}
}
