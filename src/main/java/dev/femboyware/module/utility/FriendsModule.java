package dev.femboyware.module.utility;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.util.ChatUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.EntityHitResult;

/**
 * Keeps your friends safe and easy to spot: combat modules never target them, and ESP and Tracers
 * draw them in their own colour. Add friends by middle clicking them, or with {@code .friend add}.
 */
public class FriendsModule extends Module {
	private final BoolSetting protect = add(new BoolSetting("Don't Attack", "Combat modules never target friends", true));
	private final BoolSetting middleClick = add(new BoolSetting("Middle Click", "Middle click a player to add or remove them as a friend", true));
	private final ColorSetting color = add(new ColorSetting("Color", "How friends show in ESP and Tracers", 0xFF66E0FF));

	public FriendsModule() {
		super("Friends", "Combat modules leave your friends alone; ESP shows them in their own colour", Category.MISC);
		setEnabled(true);
	}

	@Override
	public String getInfo() {
		return Integer.toString(FemboyWare.getFriends().names().size());
	}

	/** Whether combat modules should leave {@code entity} alone. */
	public static boolean protects(Entity entity) {
		FriendsModule module = FemboyWare.getModules().get(FriendsModule.class);
		return module.isEnabled() && module.protect.get() && FemboyWare.getFriends().isFriend(entity);
	}

	/** The friend colour for {@code entity}, or {@code fallback} if they aren't a friend. */
	public static int color(Entity entity, int fallback) {
		FriendsModule module = FemboyWare.getModules().get(FriendsModule.class);
		return module.isEnabled() && FemboyWare.getFriends().isFriend(entity) ? module.color.get() : fallback;
	}

	/**
	 * Called on a middle click: adds or removes the player under the crosshair as a friend. True if
	 * it did, so the click isn't used for anything else.
	 */
	public boolean onMiddleClick() {
		if (!isEnabled() || !middleClick.get() || !(mc.crosshairTarget instanceof EntityHitResult hit) || !(hit.getEntity() instanceof PlayerEntity player)) return false;
		String name = player.getGameProfile().name();
		ChatUtil.info(FemboyWare.getFriends().toggle(name) ? name + " is now your friend <3" : name + " is no longer your friend");
		return true;
	}

	@Override
	public void onTick() {
		// Middle Click Extra handles middle clicks itself when it's on; otherwise catch the ones aimed at players here
		if (FemboyWare.isEnabled(MiddleClickExtraModule.class) || mc.currentScreen != null) return;
		if (mc.crosshairTarget instanceof EntityHitResult hit && hit.getEntity() instanceof PlayerEntity && middleClick.get()) {
			boolean clicked = false;
			while (mc.options.pickItemKey.wasPressed()) clicked = true;
			if (clicked) onMiddleClick();
		}
	}
}
