package dev.femboyware.module.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.util.FxRender;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Bills and coins falling round emoting players. Bills tumble and flutter down slowly; coins drop
 * fast, spin, and bounce once. Both lie on the ground a few seconds, then fade. Runs on real time,
 * a step per frame, and draws in the world pass so it sorts with everything else.
 */
final class CashRain {
	enum Kind { BILL, COIN }

	private static final Identifier COIN_TEXTURE = Identifier.fromNamespaceAndPath("femboyware", "textures/larp/coin_btc.png");
	private static final int MAX = 700;
	private static final float REST = 3f, FADE = 1f, MAX_LIFE = 14f;

	private static final class Drop {
		final Kind kind;
		double x, y, z, vx, vy, vz;
		final double ground;
		float yaw, tumbleX, tumbleZ, spin;
		final float phase, scale;
		float age, restingFor = -1;
		boolean bounced;

		Drop(Kind kind, double x, double y, double z, double vx, double vy, double vz, double ground, Random random) {
			this.kind = kind;
			this.x = x;
			this.y = y;
			this.z = z;
			this.vx = vx;
			this.vy = vy;
			this.vz = vz;
			this.ground = ground;
			yaw = random.nextFloat() * 6.283f;
			tumbleX = random.nextFloat() * 6.283f;
			tumbleZ = random.nextFloat() * 6.283f;
			phase = random.nextFloat() * 100;
			scale = 0.85f + random.nextFloat() * 0.3f;
		}
	}

	private final List<Drop> drops = new ArrayList<>();
	private final Random random = new Random();
	private long lastFrame;

	void clear() {
		drops.clear();
		lastFrame = 0;
	}

	boolean isEmpty() {
		return drops.isEmpty();
	}

	/** Something falling from {@code height} above a random spot within {@code radius} of {@code center}. */
	void fall(Kind kind, Vec3 center, double radius, double height) {
		double angle = random.nextDouble() * Math.PI * 2, r = Math.sqrt(random.nextDouble()) * radius;
		double x = center.x + Math.cos(angle) * r, z = center.z + Math.sin(angle) * r;
		double vy = kind == Kind.COIN ? -1.5 - random.nextDouble() * 2 : -0.3 - random.nextDouble() * 0.3;
		add(new Drop(kind, x, center.y + height * (0.8 + random.nextDouble() * 0.4), z, 0, vy, 0, center.y, random));
	}

	/** Something thrown from {@code from}, going {@code velocity} blocks a second, landing on {@code groundY}. */
	void throwFrom(Kind kind, Vec3 from, Vec3 velocity, double groundY) {
		add(new Drop(kind, from.x, from.y, from.z, velocity.x, velocity.y, velocity.z, groundY, random));
	}

	private void add(Drop drop) {
		if (drops.size() >= MAX) drops.removeFirst();
		drops.add(drop);
	}

	/** Moves everything on by the time since the last frame. */
	private void step() {
		long now = System.nanoTime();
		float dt = lastFrame == 0 ? 0 : Math.min(0.1f, (now - lastFrame) / 1e9f);
		lastFrame = now;
		for (Iterator<Drop> it = drops.iterator(); it.hasNext(); ) {
			Drop d = it.next();
			d.age += dt;
			if (d.restingFor >= 0) {
				d.restingFor += dt;
				if (d.restingFor > REST + FADE) it.remove();
				continue;
			}
			if (d.age > MAX_LIFE) {
				it.remove();
				continue;
			}
			if (d.kind == Kind.BILL) {
				// Paper: barely any gravity past a slow drift down, swaying side to side and tumbling
				d.vy = Math.max(d.vy - 1.6 * dt, -0.9);
				d.vx *= 1 - 1.2 * dt;
				d.vz *= 1 - 1.2 * dt;
				double sway = Math.sin(d.age * 2.6 + d.phase);
				d.x += (d.vx + sway * 0.55 * Math.cos(d.yaw)) * dt;
				d.z += (d.vz + sway * 0.55 * Math.sin(d.yaw)) * dt;
				d.tumbleX += dt * (1.8f + (float) Math.sin(d.phase) * 0.8f);
				d.tumbleZ += dt * 1.1f;
			} else {
				d.vy = Math.max(d.vy - 9.8 * dt, -8);
				d.x += d.vx * dt;
				d.z += d.vz * dt;
				d.spin += dt * 9;
			}
			d.y += d.vy * dt;
			if (d.y <= d.ground + 0.02) {
				d.y = d.ground + 0.02;
				if (d.kind == Kind.COIN && !d.bounced && d.vy < -2) {
					d.bounced = true;
					d.vy = -d.vy * 0.3;
					d.vx *= 0.5;
					d.vz *= 0.5;
				} else d.restingFor = 0;
			}
		}
	}

	void render(Render3DEvent event, Identifier billTexture) {
		step();
		if (drops.isEmpty()) return;
		// One pass per texture: asking for another layer's buffer ends the one before it
		draw(event, Kind.BILL, billTexture);
		draw(event, Kind.COIN, COIN_TEXTURE);
	}

	private void draw(Render3DEvent event, Kind kind, Identifier texture) {
		VertexConsumer consumer = null;
		Vec3 cam = event.camera().pos;
		PoseStack.Pose entry = event.matrices().last();
		for (Drop d : drops) {
			if (d.kind != kind) continue;
			if (consumer == null) consumer = FxRender.textured(event, texture, false, false);
			float alpha = d.restingFor > REST ? 1 - (d.restingFor - REST) / FADE : 1;
			int color = FxRender.fade(0xFFFFFFFF, alpha);
			Quaternionf rotation = new Quaternionf().rotateY(-d.yaw);
			float halfW, halfH;
			Vector3f a, b;
			if (kind == Kind.BILL) {
				// Lying flat once it lands; tumbling while in the air
				if (d.restingFor < 0) rotation.rotateX(d.tumbleX).rotateZ((float) Math.sin(d.tumbleZ) * 0.9f);
				halfW = 0.18f * d.scale;
				halfH = 0.08f * d.scale;
				a = new Vector3f(halfW, 0, 0).rotate(rotation);
				b = new Vector3f(0, 0, halfH).rotate(rotation);
			} else {
				// A disc standing up and spinning while it falls, flat on the ground after
				if (d.restingFor < 0) rotation.rotateY(d.spin);
				else rotation.rotateX((float) Math.PI / 2);
				halfW = halfH = 0.14f * d.scale;
				a = new Vector3f(halfW, 0, 0).rotate(rotation);
				b = new Vector3f(0, halfH, 0).rotate(rotation);
			}
			float x = (float) (d.x - cam.x), y = (float) (d.y - cam.y), z = (float) (d.z - cam.z);
			FxRender.textured(consumer, entry, x - a.x - b.x, y - a.y - b.y, z - a.z - b.z, 0, 1, color);
			FxRender.textured(consumer, entry, x + a.x - b.x, y + a.y - b.y, z + a.z - b.z, 1, 1, color);
			FxRender.textured(consumer, entry, x + a.x + b.x, y + a.y + b.y, z + a.z + b.z, 1, 0, color);
			FxRender.textured(consumer, entry, x - a.x + b.x, y - a.y + b.y, z - a.z + b.z, 0, 0, color);
		}
	}
}
