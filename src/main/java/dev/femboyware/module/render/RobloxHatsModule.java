package dev.femboyware.module.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.femboyware.FemboyWare;
import dev.femboyware.manager.CommandManager;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.module.client.FakePlayerModule;
import dev.femboyware.module.effects.PlayerFxModule.ShowOn;
import dev.femboyware.module.render.RobloxAssets.Accessory;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.setting.StringSetting;
import dev.femboyware.util.ChatUtil;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * Wear any Roblox hat: give it the hat's id (or its catalog link) and it downloads the accessory
 * from Roblox, with its mesh and texture, and puts it on your head where it sits on a Roblox
 * avatar. Several at once. Separate from Cosmetics, so they go with the horns or without.
 */
public class RobloxHatsModule extends Module {
	private final StringSetting hats = add(new StringSetting("Hats", "Roblox hat ids (or catalog links) to wear, separated by commas", ""));
	private final NumberSetting size = add(new NumberSetting("Size", "How big the hats are next to your head", 1.0, 0.5, 2.0, 0.05));
	private final NumberSetting raise = add(new NumberSetting("Raise", "Moves the hats up or down, in pixels", 0, -4, 4, 0.25));
	private final EnumSetting<ShowOn> showOn = add(new EnumSetting<>("Show On", "You, you and your friends, or every player", ShowOn.YOU));
	private final BoolSetting onFakes = add(new BoolSetting("Fake Players", "Fake players wear your hats too", true));

	/** A hat being loaded or ready to draw: its triangles in head space (Minecraft model units) and its texture. */
	private static final class Hat {
		volatile float[] data;
		volatile int triangles;
		volatile Identifier texture;
		volatile String error;
	}

	private final Map<Long, Hat> loaded = new ConcurrentHashMap<>();
	private static final ExecutorService LOADER = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "femboyware roblox hats");
		thread.setDaemon(true);
		return thread;
	});
	private static final Identifier PLAIN = Identifier.fromNamespaceAndPath("femboyware", "roblox/plain");
	private static boolean plainMade;

	/** The Roblox head is 1.2 studs tall; a Minecraft head is half a block. */
	private static final float STUD = 0.5f / 1.2f;

	public RobloxHatsModule() {
		super("Roblox Hats", "Wear any Roblox hat by its id. Use " + CommandManager.PREFIX + "hat add <id>", Category.LARP);
		LivingEntityFeatureRendererRegistrationCallback.EVENT.register((type, renderer, helper, context) -> {
			if (renderer instanceof AvatarRenderer<?> player) helper.register(new Feature(asContext(player)));
		});
	}

	@SuppressWarnings("unchecked")
	private static RenderLayerParent<AvatarRenderState, PlayerModel> asContext(AvatarRenderer<?> renderer) {
		return (RenderLayerParent<AvatarRenderState, PlayerModel>) (Object) renderer;
	}

	@Override
	public String getInfo() {
		int count = ids().size();
		return count == 0 ? null : count == 1 ? "1 hat" : count + " hats";
	}

	private String idsFrom;
	private List<Long> idsParsed = List.of();

	/** The hat ids in the setting, in order. */
	private List<Long> ids() {
		String raw = hats.get();
		if (raw.equals(idsFrom)) return idsParsed;
		Set<Long> ids = new LinkedHashSet<>();
		for (String part : raw.split(",")) {
			long id = RobloxAssets.idIn(part.trim());
			if (id > 0) ids.add(id);
		}
		idsFrom = raw;
		idsParsed = List.copyOf(ids);
		return idsParsed;
	}

	@Override
	public void onTick() {
		for (long id : ids()) {
			if (!loaded.containsKey(id)) load(id);
		}
	}

	// ---------------------------------------------------------------- commands

	/** Handles {@code .hat <add|remove|list|clear> [id or link]}. */
	public void command(String[] args) {
		String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "list";
		List<Long> ids = new ArrayList<>(ids());
		switch (action) {
			case "add", "wear", "on" -> {
				long id = args.length > 2 ? RobloxAssets.idIn(args[2]) : 0;
				if (id == 0) {
					ChatUtil.info("Usage: " + CommandManager.PREFIX + "hat add <hat id or catalog link>");
					return;
				}
				if (!ids.contains(id)) ids.add(id);
				save(ids);
				if (!isEnabled()) setEnabled(true);
				Hat hat = loaded.get(id);
				ChatUtil.info(hat != null && hat.data != null ? "Wearing hat " + id : "Loading hat " + id + "...");
			}
			case "remove", "take", "off", "del" -> {
				long id = args.length > 2 ? RobloxAssets.idIn(args[2]) : 0;
				if (id == 0 || !ids.remove(id)) {
					ChatUtil.info("You're not wearing that one. " + CommandManager.PREFIX + "hat list shows what you are");
					return;
				}
				save(ids);
				ChatUtil.info("Took off hat " + id);
			}
			case "clear" -> {
				save(List.of());
				ChatUtil.info("Took off all your hats");
			}
			case "list" -> {
				if (ids.isEmpty()) {
					ChatUtil.info("No hats on. " + CommandManager.PREFIX + "hat add <id> to wear one");
					return;
				}
				for (long id : ids) {
					Hat hat = loaded.get(id);
					String state = hat == null ? "waiting" : hat.error != null ? "failed: " + hat.error : hat.data == null ? "loading" : "on";
					ChatUtil.info(id + " (" + state + ")");
				}
			}
			case "reload" -> {
				loaded.clear();
				ChatUtil.info("Reloading your hats");
			}
			default -> ChatUtil.info("Usage: " + CommandManager.PREFIX + "hat <add|remove|list|clear> [hat id or catalog link]");
		}
	}

	private void save(List<Long> ids) {
		List<String> parts = new ArrayList<>();
		for (long id : ids) parts.add(Long.toString(id));
		hats.set(String.join(", ", parts));
	}

	// ---------------------------------------------------------------- loading

	/** Downloads and builds a hat off the render thread, then uploads its texture on it. */
	private void load(long id) {
		Hat hat = new Hat();
		loaded.put(id, hat);
		LOADER.execute(() -> {
			try {
				byte[] model = RobloxAssets.download(id);
				Accessory accessory = RobloxAssets.accessory(model);
				RobloxAssets.Mesh mesh;
				if (accessory != null) {
					mesh = RobloxAssets.mesh(RobloxAssets.download(accessory.mesh()));
				} else if (new String(model, 0, Math.min(8, model.length)).startsWith("version")) {
					// Given a mesh's own id: wear it as it is, sat on the head
					mesh = RobloxAssets.mesh(model);
				} else {
					throw new IllegalStateException("that isn't a hat or accessory");
				}
				float[] data = place(mesh, accessory);
				byte[] png = accessory != null && accessory.texture() != 0 ? RobloxAssets.download(accessory.texture()) : null;
				mc.execute(() -> {
					try {
						hat.texture = png != null ? upload(id, png) : plain();
					} catch (Exception e) {
						FemboyWare.LOGGER.warn("Couldn't read the texture for Roblox hat {}", id, e);
						hat.texture = plain();
					}
					hat.triangles = mesh.triangles();
					hat.data = data;
					if (ids().contains(id)) ChatUtil.info("Wearing hat " + id);
				});
			} catch (Exception e) {
				FemboyWare.LOGGER.warn("Couldn't load Roblox hat {}", id, e);
				String why = e instanceof InterruptedException ? "interrupted" : e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
				hat.error = why;
				mc.execute(() -> ChatUtil.info("Couldn't load hat " + id + ": " + why));
			}
		});
	}

	private static Identifier upload(long id, byte[] png) throws java.io.IOException {
		NativeImage image = NativeImage.read(png);
		Identifier location = Identifier.fromNamespaceAndPath("femboyware", "roblox/hat_" + id);
		DynamicTexture texture = new DynamicTexture(() -> "femboyware roblox hat " + id, image);
		mc.getTextureManager().register(location, texture);
		texture.upload();
		return location;
	}

	/** A plain grey for hats without a texture, like an untextured Roblox part. */
	private static Identifier plain() {
		if (!plainMade) {
			NativeImage image = new NativeImage(1, 1, false);
			image.setPixel(0, 0, 0xFFA5A2A3);
			DynamicTexture texture = new DynamicTexture(() -> "femboyware roblox plain", image);
			mc.getTextureManager().register(PLAIN, texture);
			texture.upload();
			plainMade = true;
		}
		return PLAIN;
	}

	/**
	 * Puts the mesh where Roblox would on the head, in Minecraft's head model space. In Roblox the
	 * handle sits so its attachment lines up with the head's matching one: a point on the hat is,
	 * from the head, the head's attachment then the handle's attachment undone. Roblox studs become
	 * blocks at the head's size, and its axes turn round to Minecraft's model axes (y down, x to the
	 * left), with the head's middle a quarter block above its base.
	 */
	private static float[] place(RobloxAssets.Mesh mesh, Accessory accessory) {
		float[] data = mesh.data().clone();
		int corners = mesh.triangles() * 3, stride = RobloxAssets.Mesh.STRIDE;
		// A MeshPart handle stretches its mesh's bounds to its own size, centred on it
		float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE}, max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
		for (int k = 0; k < corners; k++) {
			for (int a = 0; a < 3; a++) {
				min[a] = Math.min(min[a], data[k * stride + a]);
				max[a] = Math.max(max[a], data[k * stride + a]);
			}
		}
		float[] head = headAttachment(accessory == null ? "HatAttachment" : accessory.attachment());
		for (int k = 0; k < corners; k++) {
			int o = k * stride;
			float[] p = {data[o], data[o + 1], data[o + 2]}, n = {data[o + 5], data[o + 6], data[o + 7]};
			if (accessory != null) {
				for (int a = 0; a < 3; a++) {
					if (accessory.meshPart() && accessory.size() != null) {
						float bounds = Math.max(1e-4f, accessory.initialSize() != null ? accessory.initialSize()[a] : max[a] - min[a]);
						p[a] = (p[a] - (min[a] + max[a]) / 2) * accessory.size()[a] / bounds;
					} else if (!accessory.meshPart()) {
						p[a] = p[a] * accessory.scale()[a] + accessory.offset()[a];
					}
				}
				p = accessory.frame().toLocal(p);
				n = accessory.frame().toLocalDirection(n);
			}
			data[o] = -(head[0] + p[0]);
			data[o + 1] = -(head[1] + p[1]);
			data[o + 2] = head[2] + p[2];
			data[o + 5] = -n[0];
			data[o + 6] = -n[1];
			data[o + 7] = n[2];
		}
		return data;
	}

	/** Where Roblox's head attachments are, from the head's middle, in studs. */
	private static float[] headAttachment(String name) {
		return switch (name) {
			case "FaceFrontAttachment" -> new float[]{0, 0, -0.6f};
			case "FaceCenterAttachment" -> new float[]{0, 0, 0};
			case "NeckAttachment" -> new float[]{0, -0.5f, 0};
			case "Legacy" -> new float[]{0, 0.5f, 0};
			default -> new float[]{0, 0.6f, 0};
		};
	}

	// ---------------------------------------------------------------- drawing

	// ---------------------------------------------------------------- Client Sync

	/** Someone's hats: which, how big, and how far up. */
	public record Worn(List<Long> ids, float size, float raise) {}

	/** For Client Sync: your hats, or null with none on. */
	public Worn shareWorn() {
		if (!isEnabled() || ids().isEmpty()) return null;
		return new Worn(ids(), size.get().floatValue(), raise.get().floatValue());
	}

	public boolean sharesWithFakes() {
		return onFakes.get();
	}

	/** Starts loading someone else's hats, so they're ready to draw. */
	public void ensureLoaded(List<Long> ids) {
		for (long id : ids) {
			if (!loaded.containsKey(id)) load(id);
		}
	}

	private Worn peer(AvatarRenderState state) {
		return mc.player == null || state.id == mc.player.getId() ? null
			: FemboyWare.getModules().get(dev.femboyware.module.client.ClientSyncModule.class).hatsOf(state.id);
	}

	private boolean wears(AvatarRenderState state) {
		if (mc.player != null && !state.isInvisible && peer(state) != null) return true;
		if (!isEnabled() || mc.player == null || mc.level == null || state.isInvisible) return false;
		if (state.id == mc.player.getId()) return true;
		if (onFakes.get() && FemboyWare.getModules().get(FakePlayerModule.class).isFake(state.id)) return true;
		if (showOn.is(ShowOn.YOU)) return false;
		Entity entity = mc.level.getEntity(state.id);
		if (!(entity instanceof Player player)) return false;
		return showOn.is(ShowOn.EVERYONE) || FemboyWare.getFriends().isFriend(player);
	}

	private static RenderType cutout(Identifier texture) {
		//? if >=26.1 {
		/*return RenderTypes.entityCutout(texture);
		*///?} else {
		return RenderTypes.entityCutoutNoCull(texture);
		//?}
	}

	private class Feature extends RenderLayer<AvatarRenderState, PlayerModel> {
		Feature(RenderLayerParent<AvatarRenderState, PlayerModel> context) {
			super(context);
		}

		@Override
		public void submit(PoseStack matrices, SubmitNodeCollector queue, int light, AvatarRenderState state, float headYaw, float headPitch) {
			if (!wears(state)) return;
			// Theirs, if it's someone else with femboyware; otherwise yours
			Worn peer = peer(state);
			List<Long> ids = peer != null ? peer.ids() : ids();
			if (ids.isEmpty()) return;
			float wornRaise = peer != null ? net.minecraft.util.Mth.clamp(peer.raise(), -4, 4) : raise.get().floatValue();
			float wornSize = peer != null ? net.minecraft.util.Mth.clamp(peer.size(), 0.5f, 2f) : size.get().floatValue();
			matrices.pushPose();
			getParentModel().head.translateAndRotate(matrices);
			// The head's middle, then studs to blocks
			matrices.translate(0, -0.25f - wornRaise / 16f, 0);
			float scale = STUD * wornSize;
			matrices.scale(scale, scale, scale);
			for (long id : ids) {
				Hat hat = loaded.get(id);
				if (hat == null || hat.data == null || hat.texture == null) continue;
				float[] data = hat.data;
				int triangles = hat.triangles;
				queue.submitCustomGeometry(matrices, cutout(hat.texture), (pose, consumer) -> draw(pose, consumer, data, triangles, light));
			}
			matrices.popPose();
		}
	}

	private static void draw(PoseStack.Pose pose, VertexConsumer c, float[] data, int triangles, int light) {
		int stride = RobloxAssets.Mesh.STRIDE;
		for (int t = 0; t < triangles; t++) {
			int base = t * 3 * stride;
			for (int corner = 0; corner < 4; corner++) {
				int o = base + Math.min(corner, 2) * stride;
				c.addVertex(pose, data[o], data[o + 1], data[o + 2]).setColor(0xFFFFFFFF).setUv(data[o + 3], data[o + 4])
					.setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, data[o + 5], data[o + 6], data[o + 7]);
			}
		}
	}
}
