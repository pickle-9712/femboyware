package dev.femboyware.module.utility;

import dev.femboyware.util.Mc;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.util.ChatUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;

/**
 * Keeps your friends safe and easy to spot: combat modules never target them, and ESP and Tracers
 * draw them in their own colour. Add friends by middle clicking them, or with {@code .friend add}.
 */
public class FriendsModule extends Module {
	private final BoolSetting protect = add(new BoolSetting("Don't Attack", "Combat modules never target friends", true));
	private final BoolSetting guard = add(new BoolSetting("Block My Hits", "Your own hits on a friend don't go through either (Friend Guard, from the Anubis client)", false));
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

	private long lastHint;

	/**
	 * Friend Guard, ported from the Anubis client (GPL-3.0, by 4ldenz,
	 * https://github.com/4ldenz/Anubis-client): true cancels your own hit on a friend.
	 */
	public static boolean blocksHit(Player attacker, Entity target) {
		FriendsModule module = FemboyWare.getModules().get(FriendsModule.class);
		if (!module.isEnabled() || !module.guard.get() || attacker != mc.player || !(target instanceof Player friend) || friend == attacker) return false;
		if (!FemboyWare.getFriends().isFriend(friend)) return false;
		long now = System.currentTimeMillis();
		if (now - module.lastHint >= 1500) {
			module.lastHint = now;
			Mc.actionBar("Held back your hit on " + friend.getGameProfile().name());
		}
		return true;
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
		if (!isEnabled() || !middleClick.get() || !(mc.hitResult instanceof EntityHitResult hit) || !(hit.getEntity() instanceof Player player)) return false;
		String name = player.getGameProfile().name();
		ChatUtil.info(FemboyWare.getFriends().toggle(name) ? name + " is now your friend <3" : name + " is no longer your friend");
		return true;
	}

	@Override
	public void onTick() {
		// Middle Click Extra handles middle clicks itself when it's on; otherwise catch the ones aimed at players here
		if (FemboyWare.isEnabled(MiddleClickExtraModule.class) || Mc.screen() != null) return;
		if (mc.hitResult instanceof EntityHitResult hit && hit.getEntity() instanceof Player && middleClick.get()) {
			boolean clicked = false;
			while (mc.options.keyPickItem.consumeClick()) clicked = true;
			if (clicked) onMiddleClick();
		}
	}
}
