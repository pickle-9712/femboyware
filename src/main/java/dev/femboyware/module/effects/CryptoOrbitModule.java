package dev.femboyware.module.effects;

import dev.femboyware.event.Render3DEvent;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.FxRender;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/** Spinning crypto coins orbiting round you, with a sparkle trail. */
public class CryptoOrbitModule extends PlayerFxModule {
	public enum Coin {
		MIXED(null, 0),
		BITCOIN("bitcoin", 0xFFF7931A),
		ETHEREUM("ethereum", 0xFF627EEA),
		DOGECOIN("dogecoin", 0xFFC2A633),
		LITECOIN("litecoin", 0xFF345D9D),
		MONERO("monero", 0xFFFF6600),
		SOLANA("solana", 0xFF9945FF),
		FEMCOIN("femcoin", 0xFFFF77C8);

		private final Identifier texture;
		private final int color;

		Coin(String name, int color) {
			this.texture = name == null ? null : Identifier.of("femboyware", "textures/fx/coin_" + name + ".png");
			this.color = color;
		}
	}

	private static final Coin[] REAL = {Coin.BITCOIN, Coin.ETHEREUM, Coin.DOGECOIN, Coin.SOLANA, Coin.MONERO, Coin.LITECOIN, Coin.FEMCOIN};
	private static final int RIM_SEGMENTS = 20;
	private static final float THICKNESS = 0.12f;

	private final EnumSetting<Coin> coin = add(new EnumSetting<>("Coin", "Which coin orbits you; Mixed cycles through all of them", Coin.MIXED));
	private final NumberSetting count = add(new NumberSetting("Count", "How many coins", 5, 1, 14, 1));
	private final NumberSetting radius = add(new NumberSetting("Radius", "How far out they orbit", 0.95, 0.4, 3, 0.05));
	private final NumberSetting height = add(new NumberSetting("Height", "How high up you they orbit", 1, 0, 2.2, 0.05));
	private final NumberSetting speed = add(new NumberSetting("Speed", "How fast they go round; negative goes the other way", 0.8, -4, 4, 0.05));
	private final NumberSetting size = add(new NumberSetting("Size", "How big each coin is", 0.3, 0.08, 0.7, 0.01));
	private final NumberSetting spin = add(new NumberSetting("Spin", "How fast each coin spins on itself", 1.5, 0, 6, 0.1));
	private final NumberSetting tilt = add(new NumberSetting("Tilt", "Tips the orbit over, in degrees", 14, 0, 60, 1));
	private final NumberSetting bob = add(new NumberSetting("Bob", "How much they float up and down", 0.08, 0, 0.5, 0.01));
	private final BoolSetting sparkles = add(new BoolSetting("Sparkles", "A glittering trail behind each coin", true));
	private final EnumSetting<ShowOn> showOn = showOnSetting();

	public CryptoOrbitModule() {
		super("Crypto Orbit", "Crypto coins orbit round you");
	}

	@Override
	public String getInfo() {
		return coin.getDisplayName();
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		float tickDelta = tickDelta();
		double now = time();
		int n = count.getInt();
		List<Placed> coins = new ArrayList<>();
		for (PlayerEntity player : wearers(true)) {
			Vec3d feet = player.getLerpedPos(tickDelta);
			for (int i = 0; i < n; i++) {
				Coin type = coin.is(Coin.MIXED) ? REAL[i % REAL.length] : coin.get();
				coins.add(new Placed(type, feet, i, feet.add(orbit(i, n, now)), (float) (now * spin.get() * 2 + i * 0.9)));
			}
		}
		if (coins.isEmpty()) return;

		float s = size.getFloat() / 2;
		float back = -s * THICKNESS * 2;
		Vec3d cam = event.camera().pos;
		MatrixStack matrices = event.matrices();

		// Ridged edges first, then each kind of coin's faces together, since every texture is its own layer
		VertexConsumer rims = FxRender.quads(event, false, false);
		for (Placed placed : coins) {
			matrices.push();
			face(matrices, placed, cam);
			MatrixStack.Entry pose = matrices.peek();
			int dark = FxRender.darken(placed.type.color, 0.35f);
			int light = FxRender.lighten(placed.type.color, 0.2f);
			float edge = s * 0.97f;
			for (int k = 0; k < RIM_SEGMENTS; k++) {
				double a1 = Math.PI * 2 * k / RIM_SEGMENTS, a2 = Math.PI * 2 * (k + 1) / RIM_SEGMENTS;
				float x1 = (float) Math.cos(a1) * edge, y1 = (float) Math.sin(a1) * edge;
				float x2 = (float) Math.cos(a2) * edge, y2 = (float) Math.sin(a2) * edge;
				int c = k % 2 == 0 ? dark : light;
				FxRender.quad(rims, pose, x1, y1, 0, c, x2, y2, 0, c, x2, y2, back, c, x1, y1, back, c);
			}
			matrices.pop();
		}

		for (Coin type : REAL) {
			VertexConsumer faces = null;
			for (Placed placed : coins) {
				if (placed.type != type) continue;
				if (faces == null) faces = FxRender.solid(event, type.texture);
				matrices.push();
				face(matrices, placed, cam);
				MatrixStack.Entry pose = matrices.peek();
				FxRender.textured(faces, pose, -s, -s, 0, 0, 1, 0xFFFFFFFF);
				FxRender.textured(faces, pose, s, -s, 0, 1, 1, 0xFFFFFFFF);
				FxRender.textured(faces, pose, s, s, 0, 1, 0, 0xFFFFFFFF);
				FxRender.textured(faces, pose, -s, s, 0, 0, 0, 0xFFFFFFFF);
				// The back face, mirrored so it reads the right way round from behind
				FxRender.textured(faces, pose, s, -s, back, 0, 1, 0xFFFFFFFF);
				FxRender.textured(faces, pose, -s, -s, back, 1, 1, 0xFFFFFFFF);
				FxRender.textured(faces, pose, -s, s, back, 1, 0, 0xFFFFFFFF);
				FxRender.textured(faces, pose, s, s, back, 0, 0, 0xFFFFFFFF);
				matrices.pop();
			}
		}

		if (!sparkles.get()) return;
		VertexConsumer glints = FxRender.textured(event, FxRender.GLOW, true, false);
		// Space the sparkles by angle, so they trail the same distance at any speed
		double step = 0.09 / Math.max(0.3, Math.abs(speed.get()));
		for (Placed placed : coins) {
			for (int k = 1; k <= 6; k++) {
				Vec3d trail = placed.feet.add(orbit(placed.index, n, now - k * step));
				float twinkle = 0.6f + 0.4f * (float) Math.sin(now * 12 + k * 1.7 + placed.index);
				FxRender.glow(event, glints, trail, s * (0.9f - k * 0.1f), FxRender.fade(placed.type.color, (1 - k / 7f) * 0.55f * twinkle));
			}
		}
	}

	private record Placed(Coin type, Vec3d feet, int index, Vec3d pos, float spin) {}

	/** Moves {@code matrices} to the coin's centre, turned by its spin. */
	private static void face(MatrixStack matrices, Placed placed, Vec3d cam) {
		matrices.translate((float) (placed.pos.x - cam.x), (float) (placed.pos.y - cam.y), (float) (placed.pos.z - cam.z));
		matrices.multiply(RotationAxis.POSITIVE_Y.rotation(placed.spin));
	}

	/** Coin {@code i}'s place round the player at {@code now}, from their feet. */
	private Vec3d orbit(int i, int n, double now) {
		float r = radius.getFloat();
		double tiltRadians = Math.toRadians(tilt.get());
		double angle = now * speed.get() * 2 + Math.PI * 2 * i / n;
		double x = Math.cos(angle) * r, z = Math.sin(angle) * r;
		double y = height.get() + z * Math.sin(tiltRadians) + Math.sin(now * 2.2 + i * 1.3) * bob.get();
		return new Vec3d(x, y, z * Math.cos(tiltRadians));
	}
}
