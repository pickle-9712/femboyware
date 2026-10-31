package dev.femboyware;

import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.render.PinkSkyModule;
import dev.femboyware.manager.CommandManager;
import dev.femboyware.manager.ConfigManager;
import dev.femboyware.manager.FriendManager;
import dev.femboyware.manager.RotationManager;
import dev.femboyware.manager.WaypointManager;
import dev.femboyware.module.Module;
import dev.femboyware.module.ModuleManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FemboyWare implements ClientModInitializer {
	public static final String NAME = "femboyware";
	public static final String VERSION = "1.0.0";
	public static final Logger LOGGER = LoggerFactory.getLogger(NAME);

	private static ModuleManager modules;
	private static RotationManager rotations;
	private static ConfigManager config;
	private static FriendManager friends;
	private static WaypointManager waypoints;
	private static CommandManager commands;

	@Override
	public void onInitializeClient() {
		rotations = new RotationManager();
		modules = new ModuleManager();
		friends = new FriendManager();
		friends.load();
		waypoints = new WaypointManager();
		waypoints.load();
		commands = new CommandManager();
		config = new ConfigManager();
		config.load();

		ClientTickEvents.START_CLIENT_TICK.register(client -> modules.onTick());
		HudElementRegistry.addLast(Identifier.of(NAME, "hud"), (context, tickCounter) ->
			modules.onRender2D(context, tickCounter.getTickProgress(true)));
		WorldRenderEvents.END_MAIN.register(context -> {
			// Grade the world pink first, so ESP and labels drawn after keep their own colours
			if (context.consumers() instanceof VertexConsumerProvider.Immediate immediate) immediate.draw();
			modules.get(PinkSkyModule.class).gradeWorld();
			Render3DEvent event = new Render3DEvent(context.matrices(), context.consumers(), context.worldState().cameraRenderState);
			modules.onRender3D(event);
			if (context.consumers() instanceof VertexConsumerProvider.Immediate immediate) immediate.draw();
		});
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> config.save());

		LOGGER.info("{} {} loaded", NAME, VERSION);
	}

	/** Null-safe check for mixins, which can run before the client finished initialising. */
	public static boolean isEnabled(Class<? extends Module> type) {
		return modules != null && modules.get(type).isEnabled();
	}

	public static ModuleManager getModules() {
		return modules;
	}

	public static FriendManager getFriends() {
		return friends;
	}

	public static WaypointManager getWaypoints() {
		return waypoints;
	}

	public static CommandManager getCommands() {
		return commands;
	}

	public static RotationManager getRotations() {
		return rotations;
	}

	public static ConfigManager getConfig() {
		return config;
	}
}
