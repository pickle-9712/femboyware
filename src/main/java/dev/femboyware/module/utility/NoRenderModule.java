package dev.femboyware.module.utility;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.particle.ParticleType;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributes;

/** Turns off distracting overlays, effects, weather, fog and entities. Each toggle is checked by a render mixin. */
public class NoRenderModule extends Module {
	private final BoolSetting hurtCam = add(new BoolSetting("Hurt Cam", "No camera shake when hurt", true));
	private final BoolSetting fire = add(new BoolSetting("Fire", "No fire overlay", true));
	private final BoolSetting liquid = add(new BoolSetting("Liquid", "No underwater overlay", true));
	private final BoolSetting inWall = add(new BoolSetting("In Wall", "No overlay while your head is inside a block", true));
	private final BoolSetting pumpkin = add(new BoolSetting("Pumpkin", "No pumpkin and powder snow overlays", true));
	private final BoolSetting vignette = add(new BoolSetting("Vignette", "No darkened screen edges", false));
	private final BoolSetting portal = add(new BoolSetting("Portal", "No nether portal and nausea overlays", true));
	private final BoolSetting bossBar = add(new BoolSetting("Boss Bar", "Hide boss bars", false));
	private final BoolSetting scoreboard = add(new BoolSetting("Scoreboard", "Hide the sidebar scoreboard", false));
	private final BoolSetting explosions = add(new BoolSetting("Explosions", "No explosion particles", true));
	private final BoolSetting totemParticles = add(new BoolSetting("Totem Particles", "No green and yellow burst when a totem pops", false));
	private final BoolSetting potionParticles = add(new BoolSetting("Potion Particles", "No swirling particles from potion effects", false));
	private final BoolSetting weather = add(new BoolSetting("Weather", "No rain or snow, and no rain sounds or splashes", false));
	private final BoolSetting fog = add(new BoolSetting("Fog", "No fog in the distance", false));
	private final BoolSetting titles = add(new BoolSetting("Titles", "Hide big title and subtitle text", false));
	private final BoolSetting armor = add(new BoolSetting("Player Armor", "Hide the armour players wear", false));
	private final BoolSetting items = add(new BoolSetting("Dropped Items", "Hide items lying on the ground", false));
	private final BoolSetting xpOrbs = add(new BoolSetting("XP Orbs", "Hide experience orbs", false));
	private final BoolSetting armorStands = add(new BoolSetting("Armor Stands", "Hide armour stands", false));
	private final BoolSetting fallingBlocks = add(new BoolSetting("Falling Blocks", "Hide falling sand, gravel and anvils", true));

	public NoRenderModule() {
		super("No Render", "Stops rendering overlays, effects, weather, fog and chosen entities", Category.WORLD);
	}

	public boolean hurtCam() {
		return isEnabled() && hurtCam.get();
	}

	public boolean fire() {
		return isEnabled() && fire.get();
	}

	public boolean liquid() {
		return isEnabled() && liquid.get();
	}

	public boolean inWall() {
		return isEnabled() && inWall.get();
	}

	public boolean pumpkin() {
		return isEnabled() && pumpkin.get();
	}

	public boolean vignette() {
		return isEnabled() && vignette.get();
	}

	public boolean portal() {
		return isEnabled() && portal.get();
	}

	public boolean bossBar() {
		return isEnabled() && bossBar.get();
	}

	public boolean scoreboard() {
		return isEnabled() && scoreboard.get();
	}

	public boolean explosions() {
		return isEnabled() && explosions.get();
	}

	public boolean weather() {
		return isEnabled() && weather.get();
	}

	public boolean titles() {
		return isEnabled() && titles.get();
	}

	public boolean playerArmor() {
		return isEnabled() && armor.get();
	}

	/** Whether a particle of {@code type} should be skipped; read by ParticleManagerMixin. */
	public boolean hidesParticle(ParticleType<?> type) {
		if (!isEnabled()) return false;
		if (explosions.get() && (type == ParticleTypes.EXPLOSION || type == ParticleTypes.EXPLOSION_EMITTER)) return true;
		if (totemParticles.get() && type == ParticleTypes.TOTEM_OF_UNDYING) return true;
		return potionParticles.get() && (type == ParticleTypes.ENTITY_EFFECT || type == ParticleTypes.EFFECT || type == ParticleTypes.INSTANT_EFFECT);
	}

	/** Whether {@code entity} should be left undrawn; read by EntityRenderManagerMixin. */
	public boolean hidesEntity(Entity entity) {
		if (!isEnabled()) return false;
		return (items.get() && entity instanceof ItemEntity)
			|| (xpOrbs.get() && entity instanceof ExperienceOrbEntity)
			|| (armorStands.get() && entity instanceof ArmorStandEntity)
			|| (fallingBlocks.get() && entity instanceof FallingBlockEntity);
	}

	/** Fog distances pushed far out of sight while Fog is on; read by EnvironmentAttributeInterpolatorMixin. */
	public Object modify(EnvironmentAttribute<?> attribute, Object value) {
		if (!isEnabled() || !fog.get() || !(value instanceof Float)) return value;
		if (attribute == EnvironmentAttributes.FOG_START_DISTANCE_VISUAL || attribute == EnvironmentAttributes.FOG_END_DISTANCE_VISUAL
			|| attribute == EnvironmentAttributes.SKY_FOG_END_DISTANCE_VISUAL || attribute == EnvironmentAttributes.CLOUD_FOG_END_DISTANCE_VISUAL) {
			return 1_000_000f;
		}
		return value;
	}
}
