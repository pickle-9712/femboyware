package dev.femboyware.module.render;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.StringSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * A pet that follows you round, only on your screen: it trots after you at heel, catches up when
 * you run, sits when you stand still, and flying ones bob along at your shoulder. Never sent to the
 * server, and it goes when you turn it off or change worlds.
 */
public class PetsModule extends Module {
	public enum Pet {
		CAT(EntityType.CAT, false), WOLF(EntityType.WOLF, false), FOX(EntityType.FOX, false), AXOLOTL(EntityType.AXOLOTL, false),
		FROG(EntityType.FROG, false), RABBIT(EntityType.RABBIT, false), PIG(EntityType.PIG, false), SNIFFER(EntityType.SNIFFER, false),
		CAMEL(EntityType.CAMEL, false), BEE(EntityType.BEE, true), ALLAY(EntityType.ALLAY, true), PARROT(EntityType.PARROT, true);

		final EntityType<?> type;
		final boolean flies;

		Pet(EntityType<?> type, boolean flies) {
			this.type = type;
			this.flies = flies;
		}
	}

	private static final int PET_ID = -1_960_000;

	private final EnumSetting<Pet> pet = add(new EnumSetting<>("Pet", "Which pet", Pet.CAT));
	private final StringSetting name = add(new StringSetting("Name", "Its name tag (empty for none)", ""));
	private final BoolSetting baby = add(new BoolSetting("Baby", "A little one, where there are babies", false));
	private final BoolSetting sits = add(new BoolSetting("Sits", "Sits down when you stand still (cats and wolves)", true));
	private final BoolSetting speech = add(new BoolSetting("Speech", "Says things now and then, in a bubble over its head", true));

	/** What it says: a pet that knows how rich you are. */
	private static final String[] LINES = {
		"we're so rich", "is that a new Rolex?", "buy the dip", "Lambo when?", "nice drip", "I only eat wagyu",
		"your balance is crazy", "HODL", "one more flip", "who let them in the mansion", "send it to the yacht",
		"cold wallet, warm heart", "the jet's fuelled up", "that's a lot of zeros", "we don't do economy",
		"another day another million", "keep it on the Ledger", "the AP or the Rolex today?"};

	private Entity entity;
	private Pet spawned;
	private String spawnedName;
	private boolean spawnedBaby;
	private int still;
	/** Ticks until it next speaks, and how long what it's saying stays up. */
	private int nextLine = 200, lineLeft;

	public PetsModule() {
		super("Pets", "A pet that follows you round (only on your screen)", Category.COSMETIC);
	}

	@Override
	public String getInfo() {
		return pet.getDisplayName();
	}

	@Override
	protected void onDisable() {
		remove();
	}

	private void remove() {
		if (entity != null && mc.level != null) entity.discard();
		entity = null;
		spawned = null;
	}

	@Override
	public void onTick() {
		if (mc.player == null || mc.level == null) {
			entity = null;
			return;
		}
		// A new world, or the settings changed: a new pet
		if (entity != null && (entity.level() != mc.level || entity.isRemoved() || spawned != pet.get()
			|| !name.get().equals(spawnedName) || baby.get() != spawnedBaby)) remove();
		if (entity == null) spawn();
		if (entity == null) return;

		Vec3 you = mc.player.position();
		float yaw = mc.player.yBodyRot;
		double rad = Math.toRadians(yaw);
		// At heel: a little behind you and to your left
		Vec3 heel = you.add(-Math.sin(rad) * -1.3 + Math.cos(rad) * 0.9, 0, Math.cos(rad) * -1.3 + Math.sin(rad) * 0.9);
		Vec3 at = entity.position();
		if (at.distanceToSqr(you) > 20 * 20) {
			entity.snapTo(heel.x, heel.y, heel.z, yaw, 0);
			return;
		}
		Vec3 flat = new Vec3(heel.x - at.x, 0, heel.z - at.z);
		double gap = flat.length();
		boolean moving = mc.player.getDeltaMovement().horizontalDistanceSqr() > 0.0004;
		still = moving ? 0 : still + 1;
		Vec3 next = at;
		float face = entity.getYRot();
		if (gap > 0.35) {
			// Faster the further behind it is, so it keeps up when you sprint
			double step = Math.min(gap, 0.12 + gap * 0.12);
			next = at.add(flat.normalize().scale(step));
			face = (float) Math.toDegrees(Math.atan2(-flat.x, flat.z));
		} else if (!moving) {
			// Settled: turn to look the way you are
			face = rotateToward(face, yaw, 6);
		}
		double y = spawned.flies ? you.y + 1.6 + Math.sin(mc.player.tickCount * 0.15) * 0.12 : ground(next, you.y);
		next = new Vec3(next.x, y, next.z);
		entity.moveOrInterpolateTo(next, face, 0);
		entity.setYHeadRot(face);
		if (entity instanceof net.minecraft.world.entity.LivingEntity living) living.yBodyRot = face;
		if (entity instanceof TamableAnimal tame) tame.setInSittingPose(sits.get() && still > 40 && gap < 1.2);
		speak();
	}

	private void spawn() {
		Pet kind = pet.get();
		Entity made = kind.type.create(mc.level, EntitySpawnReason.LOAD);
		if (made == null) return;
		made.setId(PET_ID);
		Vec3 you = mc.player.position();
		made.snapTo(you.x, you.y, you.z, mc.player.getYRot(), 0);
		if (!name.get().isBlank()) {
			made.setCustomName(Component.literal(name.get()));
			made.setCustomNameVisible(true);
		}
		if (baby.get() && made instanceof net.minecraft.world.entity.AgeableMob ageable) ageable.setBaby(true);
		if (made instanceof net.minecraft.world.entity.Mob mob) mob.setNoAi(true);
		made.setSilent(true);
		mc.level.addEntity(made);
		entity = made;
		spawned = kind;
		spawnedName = name.get();
		spawnedBaby = baby.get();
	}

	/** Every so often a line in a bubble over its head (its name tag), then back to its name. */
	private void speak() {
		if (lineLeft > 0 && --lineLeft == 0) showName(name.get());
		if (!speech.get() || --nextLine > 0) return;
		var r = java.util.concurrent.ThreadLocalRandom.current();
		nextLine = 300 + r.nextInt(400);
		lineLeft = 80;
		entity.setCustomName(Component.literal("\u201C" + LINES[r.nextInt(LINES.length)] + "\u201D").withStyle(net.minecraft.ChatFormatting.WHITE));
		entity.setCustomNameVisible(true);
	}

	private void showName(String text) {
		if (text.isBlank()) {
			entity.setCustomName(null);
			entity.setCustomNameVisible(false);
		} else {
			entity.setCustomName(Component.literal(text));
			entity.setCustomNameVisible(true);
		}
	}

	/** The top of the ground under {@code at}, looking a few blocks either side of your height. */
	private double ground(Vec3 at, double yourY) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int x = (int) Math.floor(at.x), z = (int) Math.floor(at.z);
		for (int y = (int) Math.floor(yourY) + 2; y >= (int) Math.floor(yourY) - 4; y--) {
			pos.set(x, y, z);
			BlockState state = mc.level.getBlockState(pos);
			var shape = state.getCollisionShape(mc.level, pos);
			if (!shape.isEmpty()) return y + shape.max(Direction.Axis.Y);
		}
		return yourY;
	}

	private static float rotateToward(float from, float to, float max) {
		float diff = ((to - from) % 360 + 540) % 360 - 180;
		return from + Math.max(-max, Math.min(max, diff));
	}
}
