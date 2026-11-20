package dev.femboyware.module;

import dev.femboyware.FemboyWare;
import dev.femboyware.event.PacketEvent;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.setting.Setting;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Base class for every toggleable feature. Hooks are only invoked while the module is enabled
 * and a world and player exist, so implementations can assume {@code mc.player != null}.
 */
public abstract class Module {
	protected static final Minecraft mc = Minecraft.getInstance();

	private final String name;
	private final String description;
	private final Category category;
	private final List<Setting<?>> settings = new ArrayList<>();
	private dev.femboyware.setting.BoolSetting grim;
	private boolean enabled;
	private int key;

	protected Module(String name, String description, Category category) {
		this(name, description, category, -1);
	}

	protected Module(String name, String description, Category category, int defaultKey) {
		this.name = name;
		this.description = description;
		this.category = category;
		this.key = defaultKey;
	}

	/**
	 * Adds this module's Grim setting: while it's on, the module acts the way Grim expects (see
	 * {@link dev.femboyware.util.Grim}). Call it where the setting should sit in the list.
	 */
	protected dev.femboyware.setting.BoolSetting addGrim(java.util.function.BooleanSupplier visible) {
		grim = add(new dev.femboyware.setting.BoolSetting("Grim", "Act the way Grim accepts: aim before placing, no look packets of its own, clicks before the movement packet, one block a tick, vanilla reach", false));
		grim.visibleWhen(visible);
		return grim;
	}

	protected dev.femboyware.setting.BoolSetting addGrim() {
		return addGrim(() -> true);
	}

	public boolean isGrim() {
		return grim != null && grim.get();
	}

	/**
	 * Whether this module's post-motion actions wait for the start of the next tick, before its
	 * movement packet, as vanilla's clicks do. Grim needs it; modules that are flagged for clicking
	 * after the movement packet (Hypixel's Watchdog does this for placements) always do it.
	 */
	public boolean actsBeforeMovement() {
		return isGrim();
	}

	protected <S extends Setting<?>> S add(S setting) {
		settings.add(setting);
		return setting;
	}

	public void toggle() {
		setEnabled(!enabled);
	}

	public void setEnabled(boolean enabled) {
		if (this.enabled == enabled) return;
		// Larp Only keeps cheats off (and remembers them for later)
		if (enabled && !dev.femboyware.module.client.LarpOnlyModule.allows(this)) return;
		this.enabled = enabled;
		try {
			if (enabled) onEnable();
			else onDisable();
		} catch (Exception e) {
			// Modules can be toggled from the main menu, before any world exists
			FemboyWare.LOGGER.error("Module {} threw while being {}", name, enabled ? "enabled" : "disabled", e);
		}
		if (FemboyWare.getModules() != null) dev.femboyware.module.hud.ModuleNotificationsModule.onToggle(this);
	}

	/** False for bots that spend money or click menus: they stay off when the game starts or a config loads. */
	public boolean persistsEnabled() {
		return true;
	}

	/** Extra text shown next to the module name in the array list. */
	public String getInfo() {
		return null;
	}

	/** Called at the start of every client tick. */
	public void onTick() {
	}

	/** Called right before the movement packet is sent; request rotations here. */
	public void onPreMotion() {
	}

	/** Called right after the movement packet has been sent with this tick's rotation. */
	public void onPostMotion() {
	}

	/** Called after vanilla computed this tick's movement input, before the player moves. */
	/** Right after the movement keys are read each tick, before anything acts on them; change {@code mc.player.input} here. */
	public void onInput() {
	}

	public void onMovementInput() {
	}

	/**
	 * Called once every frame while in a world, whether or not the world is drawn (Bodyguard's low
	 * power mode skips drawing it). Turn the camera here.
	 */
	public void onFrame() {
	}

	/** Called right before the local player attacks {@code target}. */
	public void onPreAttack(Entity target) {
	}

	/** Called right after the attack packet for {@code target} was sent. */
	public void onPostAttack(Entity target) {
	}

	/** Called on the netty thread. Only touch thread-safe state or schedule work with {@code mc.execute}. */
	/** A key was pressed in game, with no screen open; for modules with their own key settings. */
	public void onKey(int key) {
	}

	public void onPacketReceive(PacketEvent event) {
	}

	public void onPacketSend(PacketEvent event) {
	}

	/** Called on the main thread after a server block change was applied to the client world. */
	public void onBlockUpdate(BlockPos pos, BlockState state) {
	}

	/** Called when the player starts breaking a block. Return true to cancel the vanilla break. */
	public boolean onStartBreaking(BlockPos pos, Direction side) {
		return false;
	}

	public void onRender3D(Render3DEvent event) {
	}

	/** After every module's {@link #onRender3D} has been drawn: for see-through things that must go over solid ones. */
	public void onRender3DLate(Render3DEvent event) {
	}

	public void onRender2D(GuiGraphics context, float tickDelta) {
	}

	protected void onEnable() {
	}

	protected void onDisable() {
	}

	public String getName() {
		return name;
	}

	public String getDescription() {
		return description;
	}

	public Category getCategory() {
		return category;
	}

	public List<Setting<?>> getSettings() {
		return settings;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public int getKey() {
		return key;
	}

	public void setKey(int key) {
		this.key = key;
	}

	protected static boolean nullCheck() {
		return mc.player == null || mc.level == null || mc.gameMode == null;
	}
}
