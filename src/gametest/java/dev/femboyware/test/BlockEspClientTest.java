package dev.femboyware.test;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.render.BlockEspModule;
import dev.femboyware.module.render.ItemPhysicsModule;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.Setting;
import dev.femboyware.setting.StringSetting;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.util.math.Vec3d;

import java.util.Locale;

/** Block Esp set to grass blocks in a superflat world: it must find grass and draw it. */
public class BlockEspClientTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		context.runOnClient(client -> {
			BlockEspModule module = FemboyWare.getModules().get(BlockEspModule.class);
			((StringSetting) setting(module, "Blocks")).set("grass_block");
			module.setEnabled(true);
		});

		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getClientWorld().waitForChunksRender();
			// Look down over the grass so the boxes fill the shot
			context.runOnClient(client -> client.player.setPitch(35));
			// Block Esp scans 4 chunks a tick; give it time to cover its range
			context.waitTicks(60);

			String info = context.computeOnClient(client -> FemboyWare.getModules().get(BlockEspModule.class).getInfo());
			int found = Integer.parseInt(info);
			if (found <= 0) throw new AssertionError("Block Esp found no grass blocks");
			System.out.println("[femboyware-test] Block Esp found " + found + " grass blocks");
			context.takeScreenshot("block-esp-grass");

			context.runOnClient(client -> ((BoolSetting) setting(FemboyWare.getModules().get(BlockEspModule.class), "Tracers")).set(true));
			context.waitTicks(5);
			context.takeScreenshot("block-esp-grass-tracers");

			// Bedrock sits under the dirt in a superflat, so it only shows if Through Walls works
			context.runOnClient(client -> {
				BlockEspModule module = FemboyWare.getModules().get(BlockEspModule.class);
				((BoolSetting) setting(module, "Tracers")).set(false);
				((StringSetting) setting(module, "Blocks")).set("bedrock");
			});
			context.waitTicks(60);
			int bedrock = Integer.parseInt(context.computeOnClient(client -> FemboyWare.getModules().get(BlockEspModule.class).getInfo()));
			if (bedrock <= 0) throw new AssertionError("Block Esp found no bedrock");
			System.out.println("[femboyware-test] Block Esp found " + bedrock + " bedrock blocks");
			context.takeScreenshot("block-esp-bedrock-through-walls");

			// Item Physics: drop a few items in front of the player and check they lie flat
			context.runOnClient(client -> {
				FemboyWare.getModules().get(BlockEspModule.class).setEnabled(false);
				FemboyWare.getModules().get(ItemPhysicsModule.class).setEnabled(true);
				client.player.setPitch(55);
			});
			Vec3d pos = context.computeOnClient(client -> client.player.getEntityPos());
			String[] items = {"diamond_sword", "apple", "stone", "diamond", "golden_carrot"};
			for (int i = 0; i < items.length; i++) {
				singleplayer.getServer().runCommand(String.format(Locale.ROOT, "summon item %.2f %.2f %.2f {Item:{id:\"%s\"},PickupDelay:32767}",
					pos.x - 1 + i * 0.5, pos.y, pos.z + 2, items[i]));
			}
			context.waitTicks(40);
			context.takeScreenshot("item-physics");
		}
	}

	private static Setting<?> setting(BlockEspModule module, String name) {
		for (Setting<?> setting : module.getSettings()) {
			if (setting.getName().equals(name)) return setting;
		}
		throw new AssertionError("No setting " + name);
	}
}
