package dev.femboyware.module;

import dev.femboyware.event.PacketEvent;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.setting.Setting;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * Base class for every toggleable feature. Hooks are only invoked while the module is enabled
 * and a world and player exist, so implementations can assume {@code mc.player != null}.
 */
public abstract class Module {
	protected static final MinecraftClient mc = MinecraftClient.getInstance();

	private final String name;
	private final String description;
	private final Category category;
	private final List<Setting<?>> settings = new ArrayList<>();
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

	protected <S extends Setting<?>> S add(S setting) {
		settings.add(setting);
		return setting;
	}

	public void toggle() {
		setEnabled(!enabled);
	}

	public void setEnabled(boolean enabled) {
		if (this.enabled == enabled) return;
		this.enabled = enabled;
		if (enabled) onEnable();
		else onDisable();
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
	public void onMovementInput() {
	}

	/** Called right before the local player attacks {@code target}. */
	public void onPreAttack(Entity target) {
	}

	/** Called right after the attack packet for {@code target} was sent. */
	public void onPostAttack(Entity target) {
	}

	/** Called on the netty thread. Only touch thread-safe state or schedule work with {@code mc.execute}. */
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

	public void onRender2D(DrawContext context, float tickDelta) {
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
		return mc.player == null || mc.world == null || mc.interactionManager == null;
	}
}
