package dev.femboyware.module.effects;

import dev.femboyware.mixin.WalkAnimationStateAccessor;
import dev.femboyware.module.Category;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.NumberSetting;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Fading copies of you trailing behind as you move: see-through afterimages that replay where you
 * were a moment ago, step for step, each fainter than the one before. Only on your screen.
 */
public class PlayerTrailModule extends FxModule {
	private static final int FIRST_ID = -1_970_000;

	private final NumberSetting copies = add(new NumberSetting("Copies", "How many afterimages", 5, 1, 10, 1));
	private final NumberSetting spacing = add(new NumberSetting("Spacing", "Ticks between afterimages", 2, 1, 8, 1));
	private final NumberSetting opacity = add(new NumberSetting("Opacity", "How solid the nearest copy is", 0.5, 0.1, 1, 0.05));
	private final BoolSetting tint = add(new BoolSetting("Tint", "Colour the copies", true));
	private final ColorSetting color = add(new ColorSetting("Color", "The nearest copy's colour", 0xFFFF7AD0));
	private final ColorSetting color2 = add(new ColorSetting("Color 2", "The colour the last copy fades to", 0xFF7A9CFF));

	private record Snapshot(Vec3 pos, float yRot, float xRot, float head, float body, float walkSpeed, float walkPos, Pose pose, ItemStack[] gear) {
	}

	private final ArrayDeque<Snapshot> history = new ArrayDeque<>();
	private final List<RemotePlayer> echoes = new ArrayList<>();
	private static PlayerTrailModule instance;

	public PlayerTrailModule() {
		super("Player Trail", "Fading copies of you trail behind as you move", Category.EFFECTS);
		instance = this;
	}

	@Override
	protected void onDisable() {
		clear();
	}

	private void clear() {
		for (RemotePlayer echo : echoes) echo.discard();
		echoes.clear();
		history.clear();
	}

	@Override
	public void onTick() {
		if (mc.player == null || mc.level == null) {
			echoes.clear();
			history.clear();
			return;
		}
		if (!echoes.isEmpty() && echoes.get(0).level() != mc.level) {
			echoes.clear();
			history.clear();
		}
		var p = mc.player;
		ItemStack[] gear = new ItemStack[EquipmentSlot.values().length];
		for (EquipmentSlot slot : EquipmentSlot.values()) gear[slot.ordinal()] = p.getItemBySlot(slot);
		history.addFirst(new Snapshot(p.position(), p.getYRot(), p.getXRot(), p.getYHeadRot(), p.yBodyRot, p.walkAnimation.speed(), p.walkAnimation.position(), p.getPose(), gear));
		int n = copies.getInt(), gap = spacing.getInt();
		while (history.size() > n * gap + 2) history.removeLast();
		while (echoes.size() < n) echoes.add(spawn(echoes.size()));
		while (echoes.size() > n) echoes.remove(echoes.size() - 1).discard();
		List<Snapshot> h = new ArrayList<>(history);
		for (int i = 0; i < n; i++) {
			RemotePlayer echo = echoes.get(i);
			int at = (i + 1) * gap;
			if (at + 1 >= h.size()) {
				hide(echo);
				continue;
			}
			Snapshot now = h.get(at), before = h.get(at + 1);
			// Standing still, the copies would sit inside you
			if (now.pos.distanceTo(p.position()) < 0.35 * (i + 1)) {
				hide(echo);
				continue;
			}
			echo.setInvisible(false);
			echo.setPos(now.pos.x, now.pos.y, now.pos.z);
			echo.xo = echo.xOld = before.pos.x;
			echo.yo = echo.yOld = before.pos.y;
			echo.zo = echo.zOld = before.pos.z;
			echo.yRotO = before.yRot;
			echo.xRotO = before.xRot;
			echo.yHeadRotO = before.head;
			echo.yBodyRotO = before.body;
			echo.setYRot(now.yRot);
			echo.setXRot(now.xRot);
			echo.setYHeadRot(now.head);
			echo.yBodyRot = now.body;
			echo.setPose(now.pose);
			WalkAnimationStateAccessor walk = (WalkAnimationStateAccessor) echo.walkAnimation;
			walk.femboyware$setSpeedOld(before.walkSpeed);
			walk.femboyware$setSpeed(now.walkSpeed);
			walk.femboyware$setPosition(now.walkPos);
			for (EquipmentSlot slot : EquipmentSlot.values()) echo.setItemSlot(slot, now.gear[slot.ordinal()]);
		}
	}

	private void hide(RemotePlayer echo) {
		echo.setInvisible(true);
		echo.setPos(mc.player.getX(), mc.player.getY() - 50, mc.player.getZ());
	}

	private RemotePlayer spawn(int index) {
		// Its own id: the world won't take a second entity with yours (it wears your skin anyway, see skinFor)
		RemotePlayer echo = new RemotePlayer(mc.level, new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), mc.player.getGameProfile().name()));
		echo.setId(FIRST_ID - index);
		echo.snapTo(mc.player.getX(), mc.player.getY() - 50, mc.player.getZ(), 0, 0);
		echo.setInvisible(true);
		mc.level.addEntity(echo);
		return echo;
	}

	private static int indexOf(int id) {
		if (instance == null || !instance.isEnabled()) return -1;
		int i = FIRST_ID - id;
		return i >= 0 && i < instance.echoes.size() && instance.echoes.get(i).getId() == id ? i : -1;
	}

	/** Whether this is one of the copies (they get no name tag). */
	public static boolean isEcho(Entity entity) {
		return indexOf(entity.getId()) >= 0;
	}

	/** From AbstractClientPlayerEntityMixin: a copy wears your skin. */
	public static PlayerSkin skinFor(AbstractClientPlayer player, PlayerSkin textures) {
		var you = Minecraft.getInstance().player;
		return indexOf(player.getId()) >= 0 && you != null ? you.getSkin() : textures;
	}

	/** From LivingEntityRendererMixin: a copy's colour and see-through alpha. */
	public static int tint(LivingEntityRenderState state, int original) {
		if (!(state instanceof AvatarRenderState avatar)) return original;
		int i = indexOf(avatar.id);
		if (i < 0) return original;
		int n = instance.echoes.size();
		float t = n <= 1 ? 0 : i / (float) (n - 1);
		float alpha = instance.opacity.getFloat() * (1 - t * 0.85f);
		int c = instance.tint.get() ? dev.femboyware.util.FxRender.mix(instance.color.get(), instance.color2.get(), t) : 0xFFFFFFFF;
		return (c & 0x00FFFFFF) | Math.max(0, Math.min(255, (int) (alpha * 255))) << 24;
	}

	/** Whether this is a copy, drawn see-through. */
	public static boolean ghost(LivingEntityRenderState state) {
		return state instanceof AvatarRenderState avatar && indexOf(avatar.id) >= 0;
	}
}
