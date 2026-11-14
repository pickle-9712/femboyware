package dev.femboyware.util;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

/**
 * Small accessors for things that moved between Minecraft versions, so the modules can stay the
 * same on every version. Only this class (and a few render/mixin spots) has version conditionals.
 */
public final class Mc {
	private Mc() {
	}

	private static Minecraft mc() {
		return Minecraft.getInstance();
	}

	public static Screen screen() {
		//? if >=26.1 {
		/*return mc().gui.screen();
		*///?} else {
		return mc().screen;
		//?}
	}

	public static void setScreen(Screen screen) {
		//? if >=26.1 {
		/*mc().gui.setScreen(screen);
		*///?} else {
		mc().setScreen(screen);
		//?}
	}

	/** True when the HUD is hidden with F1. */
	public static boolean hudHidden() {
		//? if >=26.1 {
		/*return mc().gui.hud.isHidden();
		*///?} else {
		return mc().options.hideGui;
		//?}
	}

	/** Hides or shows the HUD, like F1. */
	public static void setHudHidden(boolean hidden) {
		//? if >=26.1 {
		/*if (mc().gui.hud.isHidden() != hidden) mc().gui.hud.toggle();
		*///?} else {
		mc().options.hideGui = hidden;
		//?}
	}

	public static ChatComponent chat() {
		//? if >=26.1 {
		/*return mc().gui.hud.getChat();
		*///?} else {
		return mc().gui.getChat();
		//?}
	}

	/** Adds a line to the chat, as the client (not from the server). */
	public static void addChat(net.minecraft.network.chat.Component message) {
		//? if >=26.1 {
		/*chat().addClientSystemMessage(message);
		*///?} else {
		chat().addMessage(message);
		//?}
	}

	/** A short hint over the hotbar instead of in chat. */
	public static void actionBar(String text) {
		//? if >=26.1 {
		/*mc().gui.hud.setOverlayMessage(net.minecraft.network.chat.Component.literal(text), false);
		*///?} else {
		mc().gui.setOverlayMessage(net.minecraft.network.chat.Component.literal(text), false);
		//?}
	}

	public static Camera camera() {
		//? if >=26.1 {
		/*return mc().gameRenderer.mainCamera();
		*///?} else {
		return mc().gameRenderer.getMainCamera();
		//?}
	}

	public static RenderTarget mainRenderTarget() {
		//? if >=26.1 {
		/*return mc().gameRenderer.mainRenderTarget();
		*///?} else {
		return mc().getMainRenderTarget();
		//?}
	}

	public static Identifier effectSprite(Holder<MobEffect> effect) {
		//? if >=26.1 {
		/*return net.minecraft.client.gui.Hud.getMobEffectSprite(effect);
		*///?} else {
		return net.minecraft.client.gui.Gui.getMobEffectSprite(effect);
		//?}
	}

	/** Rebuilds every chunk's geometry, for changes to how blocks are drawn. */
	public static void reloadChunks() {
		if (mc().level == null) return;
		//? if >=26.1 {
		/*mc().levelRenderer.invalidateCompiledGeometry(mc().level, mc().options, camera(), mc().getBlockColors());
		*///?} else {
		mc().levelRenderer.allChanged();
		//?}
	}

	/** The time of day in ticks (0 to 23999 is one day), as the client sees it. */
	public static long dayTime() {
		//? if >=26.1 {
		/*return mc().level.getDefaultClockTime();
		*///?} else {
		return mc().level.getDayTime();
		//?}
	}

	public static int chunkX(ChunkPos pos) {
		//? if >=26.1 {
		/*return pos.x();
		*///?} else {
		return pos.x;
		//?}
	}

	public static int chunkZ(ChunkPos pos) {
		//? if >=26.1 {
		/*return pos.z();
		*///?} else {
		return pos.z;
		//?}
	}

	public static long chunkKey(int x, int z) {
		//? if >=26.1 {
		/*return ChunkPos.pack(x, z);
		*///?} else {
		return ChunkPos.asLong(x, z);
		//?}
	}

	public static long chunkKey(ChunkPos pos) {
		return chunkKey(chunkX(pos), chunkZ(pos));
	}

	public static Vec3 center(BlockPos pos) {
		return Vec3.atCenterOf(pos);
	}

	public enum CatSound { AMBIENT, PURR, PURREOW }

	public static SoundEvent catSound(CatSound sound) {
		//? if >=26.1 {
		/*var set = net.minecraft.sounds.SoundEvents.CAT_SOUNDS.get(net.minecraft.world.entity.animal.feline.CatSoundVariants.SoundSet.CLASSIC).adultSounds();
		return switch (sound) {
			case AMBIENT -> set.ambientSound().value();
			case PURR -> set.purrSound().value();
			case PURREOW -> set.purreowSound().value();
		};
		*///?} else {
		return switch (sound) {
			case AMBIENT -> net.minecraft.sounds.SoundEvents.CAT_AMBIENT;
			case PURR -> net.minecraft.sounds.SoundEvents.CAT_PURR;
			case PURREOW -> net.minecraft.sounds.SoundEvents.CAT_PURREOW;
		};
		//?}
	}
}
