package dev.femboyware.module.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Walks one fake player around near its home: strolling, hopping, crouching, and going up to people
 * to greet them. Fake players have no physics of their own, so this does its own simple movement
 * and collisions, and moves them the way the game moves other players, which animates them too.
 */
final class FakeWanderer {
	private static final double WALK_SPEED = 0.216, SPRINT_SPEED = 0.28, SNEAK_SPEED = 0.065;
	private static final double GRAVITY = 0.08, DRAG = 0.98, JUMP = 0.42, STEP = 0.6;
	private static final double HALF_WIDTH = 0.3, HEIGHT = 1.8;
	private static final double GREET_RANGE = 10, GREET_DISTANCE = 1.8;
	private static final float HEAD_TURN_SPEED = 30, HEAD_EASE = 0.4f, BODY_TURN_SPEED = 10;
	/** How far the head gets ahead of the body, and how close it has to be to where it's going before it walks. */
	private static final float MOST_HEAD_LEAD = 70, FACING_ANGLE = 20;
	/** How far from home it's allowed before being put back, past its wander radius. */
	private static final double LOST_DISTANCE = 6, LOST_FALL = 10;
	/** Close enough to its formation spot, when to hurry, when to catch up faster than a player, and when to give up walking and be put there. */
	private static final double SPOT_REACHED = 0.25, HURRY_DISTANCE = 3, CATCH_UP_DISTANCE = 8, CATCH_UP_SPEED = 0.4, SPOT_LOST_DISTANCE = 48, SPOT_LOST_FALL = 32;
	private static final int MOST_STUCK_TICKS = 40;
	private static final long GREET_COOLDOWN = 45_000;
	private static final int GREET_TICKS = 30, MOST_WALK_TICKS = 160, MOST_BLOCKED_TICKS = 15;

	private final Random random = new Random();
	private final Map<UUID, Long> greeted = new HashMap<>();
	private Vec3 pos;
	private double fallSpeed;
	private boolean grounded = true;
	private float yaw, headYaw, pitch;

	private Vec3 walkTo;
	private Player goingToGreet;
	private Player lookingAt;
	private int lookTicks;
	private boolean greeting;
	private int wait = 5;
	private int walkTicks, blockedTicks;
	private int sneakTicks;
	private boolean sprinting;
	private boolean wantsJump;
	private boolean moving;
	/** Which way its formation faces, or NaN when it isn't in one. */
	private float faceYaw = Float.NaN;
	private int stuckTicks;

	/** Who it's talking to at the moment, if anyone. */
	Player talkingTo() {
		return lookTicks > 0 ? lookingAt : null;
	}

	/** Stops to look at someone for a moment, like when they say hi. */
	void lookAt(Player player, int ticks) {
		lookingAt = player;
		lookTicks = ticks;
		walkTo = null;
		goingToGreet = null;
		wait = Math.max(wait, ticks);
	}

	void tick(RemotePlayer self, ClientLevel world, Vec3 home, double radius, List<? extends Player> players, Consumer<Player> onGreet) {
		if (pos == null) start(self, world);
		moving = true;
		faceYaw = Float.NaN;
		if (!world.hasChunkAt(BlockPos.containing(pos))) return;
		if (Math.hypot(pos.x - home.x, pos.z - home.z) > radius + LOST_DISTANCE || pos.y < home.y - LOST_FALL) {
			pos = home;
			fallSpeed = 0;
			walkTo = null;
			goingToGreet = null;
		}
		if (sneakTicks > 0) sneakTicks--;
		wantsJump = false;

		double moveX = 0, moveZ = 0;
		if (lookTicks > 0) {
			lookTicks--;
			if (lookingAt == null || lookingAt.isRemoved() || lookingAt.isDeadOrDying()) lookTicks = 0;
			else if (greeting) {
				// Crouch spam and hops and swings, at someone it's saying hi to
				if (lookTicks % 6 < 3) sneakTicks = 1;
				if (random.nextInt(14) == 0) wantsJump = true;
				if (random.nextInt(16) == 0) self.swing(InteractionHand.MAIN_HAND);
			}
			if (lookTicks == 0) {
				greeting = false;
				wait = 5 + random.nextInt(15);
			}
		} else if (walkTo != null) {
			if (goingToGreet != null) {
				if (goingToGreet.isRemoved() || goingToGreet.isDeadOrDying() || goingToGreet.distanceToSqr(pos) > (GREET_RANGE + 4) * (GREET_RANGE + 4)) {
					goingToGreet = null;
					walkTo = null;
				} else walkTo = besideOf(goingToGreet);
			}
			if (walkTo != null) {
				double dx = walkTo.x - pos.x, dz = walkTo.z - pos.z, distance = Math.hypot(dx, dz);
				if (distance < (goingToGreet != null ? 0.6 : 1) || ++walkTicks > MOST_WALK_TICKS || blockedTicks > MOST_BLOCKED_TICKS) arrive(self, onGreet);
				else {
					double speed = sneakTicks > 0 ? SNEAK_SPEED : sprinting ? SPRINT_SPEED : WALK_SPEED;
					moveX = dx / distance * speed;
					moveZ = dz / distance * speed;
					if (random.nextInt(40) == 0) wantsJump = true;
					if (random.nextInt(240) == 0) sneakTicks = 6 + random.nextInt(10);
				}
			}
		} else if (--wait <= 0) {
			pickWhatsNext(self, home, radius, players);
		} else if (random.nextInt(25) == 0) {
			if (random.nextBoolean()) wantsJump = true;
			else sneakTicks = 6 + random.nextInt(14);
		}

		// Looks where it's going first, then sets off, and walking brings the body around
		if (!look(self, moveX, moveZ)) {
			moveX = 0;
			moveZ = 0;
		}
		move(self, world, moveX, moveZ);
		apply(self);
	}

	/** Walks to its spot in a formation and stands there facing the way the formation does. */
	void tickFormation(RemotePlayer self, ClientLevel world, Vec3 spot, float facing) {
		if (pos == null) start(self, world);
		moving = true;
		if (!world.hasChunkAt(BlockPos.containing(pos))) return;
		faceYaw = facing;
		goingToGreet = null;
		lookTicks = 0;
		greeting = false;
		sneakTicks = 0;
		wantsJump = false;

		double dx = spot.x - pos.x, dz = spot.z - pos.z, distance = Math.hypot(dx, dz);
		if (distance > SPOT_LOST_DISTANCE || pos.y < spot.y - SPOT_LOST_FALL) {
			putAt(self, world, spot);
			dx = dz = distance = 0;
		}
		double moveX = 0, moveZ = 0;
		if (distance > SPOT_REACHED) {
			sprinting = distance > HURRY_DISTANCE;
			walkTo = spot;
			double speed = Math.min(distance, distance > CATCH_UP_DISTANCE ? CATCH_UP_SPEED : sprinting ? SPRINT_SPEED : WALK_SPEED);
			moveX = dx / distance * speed;
			moveZ = dz / distance * speed;
		} else {
			sprinting = false;
			walkTo = null;
			stuckTicks = 0;
		}
		if (!look(self, moveX, moveZ)) {
			moveX = 0;
			moveZ = 0;
		}
		Vec3 before = pos;
		move(self, world, moveX, moveZ);
		// A wall or a drop in the way: it can't path around, so don't leave a gap in the shape
		if ((moveX != 0 || moveZ != 0) && Math.hypot(pos.x - before.x, pos.z - before.z) < 0.01) {
			if (++stuckTicks > MOST_STUCK_TICKS) putAt(self, world, spot);
		} else if (moveX != 0 || moveZ != 0) stuckTicks = 0;
		apply(self);
	}

	/** Puts it on a spot, above whatever's there, unless it's solid all the way up. */
	private void putAt(RemotePlayer self, ClientLevel world, Vec3 spot) {
		stuckTicks = 0;
		Vec3 free = spot;
		for (int up = 0; up < 8 && !world.noCollision(self, boxAt(free)); up++) free = free.add(0, 1, 0);
		if (!world.noCollision(self, boxAt(free))) return;
		pos = free;
		fallSpeed = 0;
	}

	/** Goes back to standing, and lands if it was in the air, once crowd mode is off. */
	void stop(RemotePlayer self, ClientLevel world) {
		if (!moving) return;
		walkTo = null;
		goingToGreet = null;
		lookTicks = 0;
		greeting = false;
		sneakTicks = 0;
		sprinting = false;
		wantsJump = false;
		move(self, world, 0, 0);
		look(self, 0, 0);
		apply(self);
		if (grounded) moving = false;
	}

	private void start(RemotePlayer self, ClientLevel world) {
		pos = self.position();
		yaw = self.getYRot();
		headYaw = yaw;
		// Spawned inside something, like a ring position that landed in a wall
		for (int up = 0; up < 8 && !world.noCollision(self, boxAt(pos)); up++) pos = pos.add(0, 1, 0);
	}

	private void pickWhatsNext(RemotePlayer self, Vec3 home, double radius, List<? extends Player> players) {
		walkTicks = 0;
		blockedTicks = 0;
		sprinting = random.nextInt(5) == 0;
		if (random.nextInt(6) == 0) {
			Player someone = pickSomeone(self, home, radius, players);
			if (someone != null) {
				goingToGreet = someone;
				walkTo = besideOf(someone);
				return;
			}
		}
		double angle = random.nextDouble() * Math.PI * 2, far = Math.sqrt(random.nextDouble()) * radius;
		walkTo = home.add(Math.cos(angle) * far, 0, Math.sin(angle) * far);
	}

	private Player pickSomeone(RemotePlayer self, Vec3 home, double radius, List<? extends Player> players) {
		long now = System.currentTimeMillis();
		List<Player> choices = new ArrayList<>();
		for (Player player : players) {
			if (player.getUUID().equals(self.getUUID()) || player.isDeadOrDying() || player.isSpectator()) continue;
			if (player.distanceToSqr(pos) > GREET_RANGE * GREET_RANGE) continue;
			// It wanders near home, so it doesn't follow anyone far from there
			if (Math.hypot(player.getX() - home.x, player.getZ() - home.z) > radius + LOST_DISTANCE - 1) continue;
			if (now - greeted.getOrDefault(player.getUUID(), 0L) < GREET_COOLDOWN) continue;
			choices.add(player);
		}
		return choices.isEmpty() ? null : choices.get(random.nextInt(choices.size()));
	}

	private Vec3 besideOf(Player player) {
		Vec3 away = pos.subtract(player.position());
		double distance = Math.hypot(away.x, away.z);
		if (distance < 0.01) return player.position();
		return player.position().add(away.x / distance * GREET_DISTANCE, 0, away.z / distance * GREET_DISTANCE);
	}

	private void arrive(RemotePlayer self, Consumer<Player> onGreet) {
		walkTo = null;
		sprinting = false;
		if (goingToGreet != null && blockedTicks <= MOST_BLOCKED_TICKS) {
			greeted.put(goingToGreet.getUUID(), System.currentTimeMillis());
			lookingAt = goingToGreet;
			lookTicks = GREET_TICKS;
			greeting = true;
			onGreet.accept(goingToGreet);
		} else {
			wait = 3 + random.nextInt(20);
			if (random.nextInt(3) == 0) wantsJump = true;
		}
		goingToGreet = null;
	}

	private void move(RemotePlayer self, ClientLevel world, double moveX, double moveZ) {
		if (wantsJump && grounded) fallSpeed = JUMP;
		// Don't walk off a drop or into water or lava
		if ((moveX != 0 || moveZ != 0) && unsafe(self, world, moveX, moveZ)) {
			moveX = 0;
			moveZ = 0;
			blockedTicks = MOST_BLOCKED_TICKS + 1;
		}
		AABB box = boxAt(pos);
		Vec3 wanted = new Vec3(moveX, fallSpeed, moveZ);
		Vec3 moved = Entity.collideBoundingBox(self, wanted, box, world, List.of());
		boolean hitWall = Math.abs(moved.x - moveX) > 1e-6 || Math.abs(moved.z - moveZ) > 1e-6;
		if (hitWall) {
			blockedTicks++;
			if (grounded) {
				// A slab or a carpet gets walked up, and anything taller gets jumped
				Vec3 stepped = pos.add(moveX, STEP, moveZ);
				if (world.noCollision(self, boxAt(stepped)) && world.noCollision(self, box.expandTowards(0, STEP, 0))) {
					moved = new Vec3(moveX, STEP, moveZ);
				} else fallSpeed = JUMP;
			}
		}
		grounded = fallSpeed < 0 && moved.y > fallSpeed + 1e-6;
		if (grounded || moved.y < fallSpeed - 1e-6) fallSpeed = 0;
		else fallSpeed = (fallSpeed - GRAVITY) * DRAG;
		// So a standing player presses into the ground and notices it's there
		if (grounded) fallSpeed = -GRAVITY;
		pos = pos.add(moved);
	}

	private boolean unsafe(RemotePlayer self, ClientLevel world, double moveX, double moveZ) {
		Vec3 next = pos.add(moveX, 0, moveZ);
		if (!world.getFluidState(BlockPos.containing(next)).isEmpty()) return true;
		return grounded && world.noCollision(self, boxAt(next).expandTowards(0, -3.5, 0));
	}

	private static AABB boxAt(Vec3 pos) {
		return new AABB(pos.x - HALF_WIDTH, pos.y, pos.z - HALF_WIDTH, pos.x + HALF_WIDTH, pos.y + HEIGHT, pos.z + HALF_WIDTH);
	}

	/** Turns the head toward what it wants, with the body following behind. Returns whether the head is there yet. */
	private boolean look(RemotePlayer self, double moveX, double moveZ) {
		float wantedYaw = headYaw, wantedPitch = 0;
		if (lookTicks > 0 && lookingAt != null) {
			Vec3 eyes = pos.add(0, self.getEyeHeight(), 0);
			Vec3 to = lookingAt.getEyePosition().subtract(eyes);
			wantedYaw = (float) (Mth.atan2(to.z, to.x) * Mth.RAD_TO_DEG) - 90;
			wantedPitch = (float) -(Mth.atan2(to.y, Math.hypot(to.x, to.z)) * Mth.RAD_TO_DEG);
		} else if (moveX != 0 || moveZ != 0) {
			wantedYaw = (float) (Mth.atan2(moveZ, moveX) * Mth.RAD_TO_DEG) - 90;
		} else if (!Float.isNaN(faceYaw)) {
			wantedYaw = faceYaw;
		}
		headYaw += Mth.clamp(Mth.wrapDegrees(wantedYaw - headYaw) * HEAD_EASE, -HEAD_TURN_SPEED, HEAD_TURN_SPEED);
		pitch = Mth.approach(pitch, wantedPitch, HEAD_TURN_SPEED);
		yaw = Mth.approachDegrees(yaw, headYaw, BODY_TURN_SPEED);
		float lead = Mth.wrapDegrees(headYaw - yaw);
		if (Math.abs(lead) > MOST_HEAD_LEAD) yaw = headYaw - Math.signum(lead) * MOST_HEAD_LEAD;
		return Math.abs(Mth.wrapDegrees(wantedYaw - headYaw)) < FACING_ANGLE;
	}

	private void apply(RemotePlayer self) {
		boolean sneaking = sneakTicks > 0;
		self.setShiftKeyDown(sneaking);
		self.setPose(sneaking ? Pose.CROUCHING : Pose.STANDING);
		self.setSprinting(sprinting && walkTo != null);
		self.setOnGround(grounded);
		self.getInterpolation().interpolateTo(pos, yaw, pitch);
		self.lerpHeadTo(headYaw, 3);
	}
}
