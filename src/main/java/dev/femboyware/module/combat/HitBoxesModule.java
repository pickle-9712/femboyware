package dev.femboyware.module.combat;

import dev.femboyware.util.Teams;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.TargetUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * Makes other entities' boxes bigger for your crosshair only (Entity.getPickRadius, which the
 * crosshair's entity pick inflates boxes by), so they're easier to hit. Reach stays the same.
 */
public class HitBoxesModule extends Module {
	private final NumberSetting expand = add(new NumberSetting("Expand", "Blocks added on every side", 0.2, 0.05, 1, 0.05));
	private final BoolSetting teamCheck = add(new BoolSetting("Team Check", "Leave players on your team alone (same scoreboard team, name colour or helmet colour)", false));
	private final BoolSetting players = add(new BoolSetting("Players", "Bigger boxes on players", true));
	private final BoolSetting mobs = add(new BoolSetting("Mobs", "Bigger boxes on mobs and animals", false));

	public HitBoxesModule() {
		super("Hit Boxes", "Enlarge other entities' hitboxes for your crosshair; reach is unchanged", Category.ATTACK);
	}

	/** Called from EntityMixin: what to add to {@code entity}'s pick radius. */
	public static float extra(Entity entity) {
		if (FemboyWare.getModules() == null) return 0;
		HitBoxesModule module = FemboyWare.getModules().get(HitBoxesModule.class);
		if (!module.isEnabled() || entity == Minecraft.getInstance().player || nullCheck()) return 0;
		if (entity instanceof Player player) return module.players.get() && TargetUtil.isValid(player, module.teamCheck.get()) ? module.expand.getFloat() : 0;
		return entity instanceof LivingEntity && module.mobs.get() ? module.expand.getFloat() : 0;
	}
}
