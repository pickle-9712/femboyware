package dev.femboyware.module.effects;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.setting.Setting;
import dev.femboyware.util.FxRender;
import dev.femboyware.util.IsraelFlag;
import dev.femboyware.FemboyWare;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Blue and white round you: a Star of David glowing on the ground under your feet with little stars
 * circling your waist, and Israeli flags floating round your head, waving.
 */
public class IsraelAuraModule extends PlayerFxModule {
	private <T, S extends Setting<T>> S under(BoolSetting layer, S setting) {
		add(setting);
		return setting.visibleWhen(layer::get);
	}

	// ---------------------------------------------------------------- Star of David aura
	private final BoolSetting starAura = add(new BoolSetting("Star Aura", "A Star of David glowing on the ground under you, turning slowly", true));
	private final NumberSetting starSize = under(starAura, new NumberSetting("Star Size", "How big the ground star is", 1.1, 0.5, 3, 0.05));
	private final NumberSetting starSpin = under(starAura, new NumberSetting("Star Spin", "How fast it turns", 1, 0, 4, 0.05));
	private final NumberSetting orbitStars = under(starAura, new NumberSetting("Orbit Stars", "Little stars circling your waist (0 for none)", 6, 0, 12, 1));
	private final NumberSetting orbitSpeed = under(starAura, new NumberSetting("Orbit Speed", "How fast they circle", 1, 0.2, 4, 0.05));

	// ---------------------------------------------------------------- flags
	private final BoolSetting flags = add(new BoolSetting("Floating Flags", "Israeli flags floating round your head, waving", true));
	private final NumberSetting flagCount = under(flags, new NumberSetting("Flags", "How many flags", 3, 1, 8, 1));
	private final NumberSetting flagSize = under(flags, new NumberSetting("Flag Size", "How big they are", 1, 0.4, 2.5, 0.05));
	private final NumberSetting flagRadius = under(flags, new NumberSetting("Flag Radius", "How far out they float", 1.1, 0.5, 3, 0.05));
	private final NumberSetting flagSpeed = under(flags, new NumberSetting("Flag Speed", "How fast they go round you", 0.5, 0, 3, 0.05));

	private final EnumSetting<ShowOn> showOn = showOnSetting();
	private final BoolSetting othersOnly = add(new BoolSetting("Others Only", "Only on other players, never on you (Show On You then means everyone else)", false));

	public IsraelAuraModule() {
		super("Israel Aura", "A Star of David aura and Israeli flags floating round you");
	}

	@Override
	public String getInfo() {
		return starAura.get() && flags.get() ? "Star + Flags" : starAura.get() ? "Star" : flags.get() ? "Flags" : "Off";
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		if (mc.level == null) return;
		List<Player> all = who(true);
		if (all.isEmpty()) return;
		// The orbiting stars would sit in front of your eyes in first person
		List<Player> shown = who(false);
		float tickDelta = tickDelta();
		Vec3 cam = event.camera().pos;
		double t = time();

		if (flags.get()) {
			VertexConsumer solid = FxRender.solidQuads(event);
			for (Player p : all) flags(event, solid, p, cam, tickDelta, t);
		}
		VertexConsumer light = FxRender.quads(event, true, false);
		if (starAura.get()) {
			for (Player p : all) groundStar(event, light, p, cam, tickDelta, t);
			for (Player p : shown) orbitStars(event, light, p, cam, tickDelta, t);
		}
		VertexConsumer glow = FxRender.textured(event, FxRender.GLOW, true, false);
		for (Player p : all) {
			Vec3 feet = p.getPosition(tickDelta);
			if (starAura.get()) {
				float pulse = 0.8f + 0.2f * (float) Math.sin(t * 2.5);
				FxRender.glow(event, glow, feet.add(0, 0.05, 0), starSize.getFloat() * 1.5f, FxRender.fade(IsraelFlag.LIGHT_BLUE, 0.28f * pulse));
			}
			if (flags.get()) {
				// A gold ball on top of each pole
				for (int i = 0; i < flagCount.getInt(); i++) {
					FxRender.glow(event, glow, flagBase(p, feet, i, t).add(0, poleHeight() + 0.02, 0), 0.07f * flagSize.getFloat(), 0xFFFFD34A);
				}
			}
		}
		if (starAura.get()) {
			for (Player p : shown) {
				Vec3 feet = p.getPosition(tickDelta);
				int n = orbitStars.getInt();
				for (int i = 0; i < n; i++) FxRender.glow(event, glow, orbitPos(p, feet, i, n, t), 0.22f, FxRender.fade(i % 2 == 0 ? IsraelFlag.LIGHT_BLUE : 0xFFFFFFFF, 0.55f));
			}
		}
	}

	/** Who gets the aura: the usual Show On players, or with Others Only everyone but you. */
	private List<Player> who(boolean inFirstPerson) {
		if (!othersOnly.get()) return wearers(inFirstPerson);
		List<Player> others = new ArrayList<>();
		for (Player player : mc.level.players()) {
			if (player == mc.player || player.isSpectator() || player.isInvisible() || player.isRemoved()) continue;
			if (showOn.is(ShowOn.FRIENDS) && !FemboyWare.getFriends().isFriend(player)) continue;
			others.add(player);
		}
		return others;
	}

	// ---------------------------------------------------------------- the ground star

	private void groundStar(Render3DEvent event, VertexConsumer light, Player p, Vec3 cam, float tickDelta, double t) {
		Vec3 feet = p.getPosition(tickDelta);
		PoseStack matrices = event.matrices();
		matrices.pushPose();
		matrices.translate((float) (feet.x - cam.x), (float) (feet.y - cam.y) + 0.02f, (float) (feet.z - cam.z));
		matrices.mulPose(Axis.YP.rotationDegrees((float) (t * 20 * starSpin.get())));
		PoseStack.Pose pose = matrices.last();
		float r = starSize.getFloat(), pulse = 0.8f + 0.2f * (float) Math.sin(t * 2.5);
		// Two rings round it, then the star: a wide blue stroke with a thin white one down the middle
		FxRender.ring(light, pose, r * 1.1f, r * 1.15f, 0, 64, (a, inner) -> FxRender.fade(IsraelFlag.LIGHT_BLUE, 0.8f * pulse));
		FxRender.ring(light, pose, r * 1.22f, r * 1.24f, 0, 64, (a, inner) -> FxRender.fade(0xFFFFFFFF, 0.5f * pulse));
		IsraelFlag.starXZ(light, pose, r, 0, 0.11f * r, FxRender.fade(IsraelFlag.LIGHT_BLUE, 0.85f * pulse));
		IsraelFlag.starXZ(light, pose, r, 0.002f, 0.035f * r, FxRender.fade(0xFFFFFFFF, 0.9f * pulse));
		// A soft blue fill in the hexagon in the middle
		FxRender.ring(light, pose, 0, r * 0.55f, 0, 6, (a, inner) -> FxRender.fade(IsraelFlag.LIGHT_BLUE, inner ? 0.25f * pulse : 0.05f));
		matrices.popPose();
	}

	// ---------------------------------------------------------------- the orbiting stars

	private Vec3 orbitPos(Player p, Vec3 feet, int i, int n, double t) {
		double a = t * 1.2 * orbitSpeed.get() + Math.PI * 2 * i / n;
		double bob = Math.sin(t * 1.7 + i * 1.3) * 0.12;
		return feet.add(Math.cos(a) * 0.85, p.getBbHeight() * 0.55 + bob, Math.sin(a) * 0.85);
	}

	private void orbitStars(Render3DEvent event, VertexConsumer light, Player p, Vec3 cam, float tickDelta, double t) {
		Vec3 feet = p.getPosition(tickDelta);
		int n = orbitStars.getInt();
		PoseStack matrices = event.matrices();
		for (int i = 0; i < n; i++) {
			Vec3 at = orbitPos(p, feet, i, n, t);
			matrices.pushPose();
			matrices.translate((float) (at.x - cam.x), (float) (at.y - cam.y), (float) (at.z - cam.z));
			// Each one turning on its own as it goes round
			matrices.mulPose(Axis.YP.rotation((float) (t * 2 + i)));
			PoseStack.Pose pose = matrices.last();
			IsraelFlag.starXY(light, pose, 0, 0, 0.13f, 0.035f, FxRender.fade(IsraelFlag.LIGHT_BLUE, 0.95f));
			IsraelFlag.starXY(light, pose, 0, 0, 0.13f, 0.012f, FxRender.fade(0xFFFFFFFF, 0.95f));
			matrices.popPose();
		}
	}

	// ---------------------------------------------------------------- the flags

	private float poleHeight() {
		return 0.85f * flagSize.getFloat();
	}

	/** Where flag {@code i}'s pole stands: round you, a little above your head, bobbing. */
	private Vec3 flagBase(Player p, Vec3 feet, int i, double t) {
		int n = flagCount.getInt();
		double a = t * 0.6 * flagSpeed.get() + Math.PI * 2 * i / n;
		double bob = Math.sin(t * 1.5 + i * 2.1) * 0.1;
		float r = flagRadius.getFloat();
		// Your own float up over your head in first person, out of your face
		double up = p == mc.player && mc.options.getCameraType().isFirstPerson() ? p.getBbHeight() + 0.45 : p.getBbHeight() * 0.75;
		return feet.add(Math.cos(a) * r, up + bob, Math.sin(a) * r);
	}

	private void flags(Render3DEvent event, VertexConsumer solid, Player p, Vec3 cam, float tickDelta, double t) {
		Vec3 feet = p.getPosition(tickDelta);
		int n = flagCount.getInt();
		float size = flagSize.getFloat(), poleH = poleHeight();
		float fw = 0.6f * size, fh = 0.43f * size;
		PoseStack matrices = event.matrices();
		for (int i = 0; i < n; i++) {
			Vec3 base = flagBase(p, feet, i, t);
			double a = t * 0.6 * flagSpeed.get() + Math.PI * 2 * i / n;
			matrices.pushPose();
			matrices.translate((float) (base.x - cam.x), (float) (base.y - cam.y), (float) (base.z - cam.z));
			// Facing out from you, so anyone round you sees the flags, and leaning a little
			matrices.mulPose(Axis.YP.rotation((float) (Math.PI / 2 - a)));
			matrices.mulPose(Axis.ZP.rotationDegrees(8 * (float) Math.sin(t * 1.3 + i)));
			PoseStack.Pose pose = matrices.last();
			IsraelFlag.pole(solid, pose, poleH, 0.018f * size);
			IsraelFlag.cloth(solid, pose, 0.018f * size, poleH - fh - 0.02f * size, fw, fh, (float) t + i * 0.7f, 0.5f * size);
			matrices.popPose();
		}
	}
}
