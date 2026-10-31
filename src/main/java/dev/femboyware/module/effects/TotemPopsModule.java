package dev.femboyware.module.effects;

import dev.femboyware.event.PacketEvent;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.FxRender;
import dev.femboyware.util.RenderUtil;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityStatuses;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * When a player pops a totem: rings of light burst out round them and a pop count rises over their
 * head. Counts reset when they die.
 */
public class TotemPopsModule extends FxModule {
	public enum Sound { NONE, ORB, BELL, CHIME }

	private static final int SEGMENTS = 48;

	private final NumberSetting duration = add(new NumberSetting("Duration", "Seconds the burst and count show", 1.6, 0.5, 4, 0.1));
	private final BoolSetting rings = add(new BoolSetting("Rings", "Rings of light bursting out", true));
	private final BoolSetting count = add(new BoolSetting("Count", "The pop count rising over their head", true));
	private final BoolSetting yours = add(new BoolSetting("Yours", "Show your own pops too", false));
	private final EnumSetting<Sound> sound = add(new EnumSetting<>("Sound", "Sound played on someone else's pop", Sound.NONE));
	private final Palette palette = palette(0xFFFFE066, 0xFF66FF99);

	private record Pop(int entityId, Vec3d pos, float height, int count, long time) {}

	private final Map<Integer, Integer> counts = new HashMap<>();
	private final List<Pop> pops = new ArrayList<>();

	public TotemPopsModule() {
		super("Totem Pops", "A burst and a pop count when a player pops a totem", Category.TARGETS);
	}

	@Override
	protected void onDisable() {
		counts.clear();
		pops.clear();
	}

	@Override
	public void onPacketReceive(PacketEvent event) {
		if (!(event.getPacket() instanceof EntityStatusS2CPacket packet)) return;
		byte status = packet.getStatus();
		if (status != EntityStatuses.USE_TOTEM_OF_UNDYING && status != EntityStatuses.PLAY_DEATH_SOUND_OR_ADD_PROJECTILE_HIT_PARTICLES) return;
		// Packets arrive on the network thread; world state is only safe on the main thread
		mc.execute(() -> {
			if (mc.world == null) return;
			Entity entity = packet.getEntity(mc.world);
			if (!(entity instanceof PlayerEntity player)) return;
			if (status != EntityStatuses.USE_TOTEM_OF_UNDYING) {
				counts.remove(player.getId());
				return;
			}
			int popped = counts.merge(player.getId(), 1, Integer::sum);
			if (player == mc.player && !yours.get()) return;
			pops.add(new Pop(player.getId(), player.getEntityPos(), player.getHeight(), popped, System.nanoTime()));
			SoundEvent soundEvent = switch (sound.get()) {
				case NONE -> null;
				case ORB -> SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP;
				case BELL -> SoundEvents.BLOCK_NOTE_BLOCK_BELL.value();
				case CHIME -> SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME;
			};
			if (soundEvent != null) mc.world.playSoundClient(player.getX(), player.getY(), player.getZ(), soundEvent, SoundCategory.PLAYERS, 0.8f, 1.2f, false);
		});
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		long now = System.nanoTime();
		pops.removeIf(pop -> (now - pop.time) / 1e9 > duration.get());
		if (pops.isEmpty()) return;
		float tickDelta = tickDelta();
		Vec3d cam = event.camera().pos;

		if (rings.get()) {
			MatrixStack matrices = event.matrices();
			VertexConsumer light = FxRender.quads(event, true, false);
			for (Pop pop : pops) {
				float t = (float) ((now - pop.time) / 1e9 / duration.get());
				Vec3d pos = follow(pop, tickDelta);
				matrices.push();
				matrices.translate((float) (pos.x - cam.x), (float) (pos.y - cam.y), (float) (pos.z - cam.z));
				// Three rings bursting out at the feet, waist and head, one just after another
				for (int i = 0; i < 3; i++) {
					float local = Math.max(0, Math.min(1, t * 1.6f - i * 0.15f));
					if (local <= 0 || local >= 1) continue;
					float r = 0.3f + 1.4f * (1 - (float) Math.pow(1 - local, 3));
					float alpha = 1 - local;
					float y = pop.height * (0.1f + i * 0.4f);
					float inner = r * 0.82f;
					FxRender.ring(light, matrices.peek(), inner, r, y, SEGMENTS, (around, in) -> FxRender.fade(palette.at(around), in ? 0 : alpha));
				}
				matrices.pop();
			}
		}

		if (!count.get()) return;
		for (Pop pop : pops) {
			float t = (float) ((now - pop.time) / 1e9 / duration.get());
			float fade = 1 - t * t;
			if (fade < 0.04f) continue;
			Vec3d pos = follow(pop, tickDelta).add(0, pop.height + 0.5 + t * 0.8, 0);
			String text = pop.count == 1 ? "Popped!" : "Popped x" + pop.count;
			RenderUtil.drawText(event, text, pos, FxRender.fade(FxRender.opaque(palette.at(0)), fade), 0.55f);
		}
	}

	/** Their current position if they're still around, else where they popped. */
	private static Vec3d follow(Pop pop, float tickDelta) {
		Entity entity = mc.world.getEntityById(pop.entityId);
		return entity != null ? entity.getLerpedPos(tickDelta) : pop.pos;
	}
}
