package dev.femboyware.module.render;

import dev.femboyware.event.PacketEvent;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.ChatUtil;
import dev.femboyware.util.HiddenDebrisScanner;
import dev.femboyware.util.RenderUtil;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * DonutSMP's anti-xray hides ancient debris, but a chunk's block palette still lists it: this boxes
 * every 16x16x16 Nether section where debris is listed but hidden. Ported from the Anubis client's
 * Netherite Finder (GPL-3.0, by 4ldenz, https://github.com/4ldenz/Anubis-client), without its
 * Advanced Finder probes.
 */
public class NetheriteFinderModule extends Module {
	private static final int MAX = 512, MAX_RENDERED = 128;
	private static final int DEBRIS_ID = Block.getId(Blocks.ANCIENT_DEBRIS.defaultBlockState());
	private static final ResourceKey<Level> DONUT_NETHER = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("worlds", "smp_nether"));

	private final ColorSetting near = add(new ColorSetting("Near Color", "Colour of close sections", 0xFFFF7A00));
	private final ColorSetting far = add(new ColorSetting("Far Color", "Colour of far sections", 0xFF4DA6FF));
	private final NumberSetting fade = add(new NumberSetting("Fade Distance", "Blocks over which near turns to far", 192, 32, 512, 8));
	private final NumberSetting range = add(new NumberSetting("Range", "Furthest section to draw", 512, 64, 1024, 1));
	private final BoolSetting tracers = add(new BoolSetting("Tracers", "Lines to the nearest few", true));
	private final NumberSetting tracerCount = add(new NumberSetting("Tracer Count", "How many get a line", 3, 1, 8, 1));
	private final BoolSetting notify = add(new BoolSetting("Notify", "Say in chat when new ones are found", false));

	private record Section(int x, int y, int z) {
	}

	private final Set<Section> sections = new LinkedHashSet<>();
	private int pending;
	private BlockPos nearestPending;
	private long lastNotify;
	private Level lastLevel;

	public NetheriteFinderModule() {
		super("Netherite Finder", "Boxes Nether chunk sections hiding ancient debris (anti-xray bypass)", Category.ESP);
	}

	@Override
	protected void onEnable() {
		synchronized (sections) {
			sections.clear();
		}
		pending = 0;
	}

	private boolean nether() {
		if (mc.level == null) return false;
		ResourceKey<Level> dim = mc.level.dimension();
		return dim == Level.NETHER || DONUT_NETHER.equals(dim);
	}

	@Override
	public void onPacketReceive(PacketEvent event) {
		if (!(event.getPacket() instanceof ClientboundLevelChunkWithLightPacket chunk) || mc.level == null || !nether()) return;
		// Read the palette on the network thread (the packet is untouched), hand the results over
		//? if >=26.3 {
		/*var data = chunk.chunkData();
		int cx = chunk.x(), cz = chunk.z();
		*///?} else {
		var data = chunk.getChunkData();
		int cx = chunk.getX(), cz = chunk.getZ();
		//?}
		List<Integer> hidden = HiddenDebrisScanner.findHiddenSections(data, mc.level.getSectionsCount(), mc.level.getMinSectionY(), DEBRIS_ID);
		if (hidden.isEmpty()) return;
		mc.execute(() -> {
			for (int sy : hidden) add(new Section(cx, sy, cz));
		});
	}

	private void add(Section s) {
		synchronized (sections) {
			if (!sections.add(s)) return;
			if (sections.size() > MAX) {
				Iterator<Section> oldest = sections.iterator();
				oldest.next();
				oldest.remove();
			}
		}
		BlockPos center = new BlockPos((s.x << 4) + 8, (s.y << 4) + 8, (s.z << 4) + 8);
		if (mc.player != null && (nearestPending == null || mc.player.blockPosition().distSqr(center) < mc.player.blockPosition().distSqr(nearestPending))) nearestPending = center;
		pending++;
	}

	@Override
	public void onBlockUpdate(BlockPos pos, BlockState state) {
		// Once the debris shows for real (you dug next to it), the section's done
		if (Block.getId(state) != DEBRIS_ID) return;
		synchronized (sections) {
			sections.remove(new Section(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4));
		}
	}

	@Override
	public void onTick() {
		if (mc.level != lastLevel) {
			lastLevel = mc.level;
			synchronized (sections) {
				sections.clear();
			}
		}
		long now = System.currentTimeMillis();
		if (pending > 0 && now - lastNotify >= 2000) {
			if (notify.get() && nearestPending != null)
				ChatUtil.info(pending + " new hidden-debris section" + (pending == 1 ? "" : "s") + " found, nearest at " + ChatUtil.coords(nearestPending));
			pending = 0;
			nearestPending = null;
			lastNotify = now;
		}
	}

	@Override
	public String getInfo() {
		synchronized (sections) {
			return sections.isEmpty() ? null : Integer.toString(sections.size());
		}
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		if (mc.player == null || !nether()) return;
		Vec3 eye = mc.player.getEyePosition();
		List<AABB> boxes = new ArrayList<>();
		synchronized (sections) {
			for (Section s : sections) boxes.add(new AABB(s.x * 16.0, s.y * 16.0, s.z * 16.0, s.x * 16.0 + 16, s.y * 16.0 + 16, s.z * 16.0 + 16));
		}
		boxes.removeIf(b -> distance(b, eye) > range.get());
		boxes.sort(Comparator.comparingDouble(b -> distance(b, eye)));
		if (boxes.size() > MAX_RENDERED) boxes = boxes.subList(0, MAX_RENDERED);
		Vec3 from = RenderUtil.tracerOrigin(event);
		for (int i = 0; i < boxes.size(); i++) {
			AABB box = boxes.get(i);
			float t = (float) Math.min(1, distance(box, eye) / fade.get());
			int color = lerp(near.get(), far.get(), t);
			RenderUtil.drawBox(event, box, (color & 0xFFFFFF) | 0x22000000, color | 0xFF000000, 1.5f, true);
			if (tracers.get() && i < tracerCount.get()) RenderUtil.drawLine(event, from, box.getCenter(), color | 0xFF000000, 1.5f, true);
		}
	}

	private static double distance(AABB box, Vec3 p) {
		double dx = Math.max(0, Math.max(box.minX - p.x, p.x - box.maxX));
		double dy = Math.max(0, Math.max(box.minY - p.y, p.y - box.maxY));
		double dz = Math.max(0, Math.max(box.minZ - p.z, p.z - box.maxZ));
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	private static int lerp(int a, int b, float t) {
		int r = (int) (((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * t);
		int g = (int) (((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * t);
		int bl = (int) ((a & 255) + ((b & 255) - (a & 255)) * t);
		return r << 16 | g << 8 | bl;
	}
}
