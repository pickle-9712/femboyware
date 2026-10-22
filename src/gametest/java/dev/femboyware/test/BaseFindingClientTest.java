package dev.femboyware.test;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.render.BaseEspModule;
import dev.femboyware.module.render.GeodeEspModule;
import dev.femboyware.module.render.KelpEspModule;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;

/**
 * Geode Esp, Kelp Esp and Base Esp against things built in a superflat world: a freshly
 * generated geode must not be flagged and an overgrown one must, kelp taller than worldgen makes
 * must be flagged, and a cluster of player blocks must read as a base.
 */
public class BaseFindingClientTest implements FabricClientGameTest {
	/** Long enough for the scanners to cover their whole range at 4 chunks a tick. */
	private static final int SCAN_TICKS = 100;

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getClientWorld().waitForChunksRender();
			TestServerContext server = singleplayer.getServer();
			BlockPos player = context.computeOnClient(client -> client.player.getBlockPos());
			// Work in whole chunks so each setup lands in its own chunk
			int chunkX = (player.getX() >> 4) << 4;
			int chunkZ = (player.getZ() >> 4) << 4;

			// A real geode, floating well above the ground, two chunks north
			BlockPos geode = new BlockPos(chunkX + 8, -30, chunkZ - 32 + 8);
			server.runCommand("place feature minecraft:amethyst_geode " + geode.getX() + " " + geode.getY() + " " + geode.getZ());

			// Four tanks of 15-tall kelp two chunks west; worldgen kelp never passes 11
			int kelpX = chunkX - 32 + 1;
			for (int i = 0; i < 4; i++) {
				int x = kelpX + i * 3;
				int z = chunkZ + 1;
				server.runCommand(String.format("fill %d -60 %d %d -42 %d glass hollow", x - 1, z - 1, x + 1, z + 1));
				server.runCommand(String.format("fill %d -59 %d %d -43 %d water", x, z, x, z));
				server.runCommand(String.format("fill %d -59 %d %d -45 %d kelp_plant", x, z, x, z));
				server.runCommand(String.format("setblock %d -44 %d kelp[age=22]", x, z));
			}

			// A small base two chunks east: 2 strong blocks (3 each) and 2 weak ones (1 each)
			int baseX = chunkX + 32 + 4;
			server.runCommand(String.format("setblock %d -60 %d ender_chest", baseX, chunkZ + 4));
			server.runCommand(String.format("setblock %d -60 %d shulker_box", baseX + 1, chunkZ + 4));
			server.runCommand(String.format("setblock %d -60 %d crafting_table", baseX + 2, chunkZ + 4));
			server.runCommand(String.format("setblock %d -60 %d furnace", baseX + 3, chunkZ + 4));

			context.runOnClient(client -> {
				FemboyWare.getModules().get(GeodeEspModule.class).setEnabled(true);
				FemboyWare.getModules().get(KelpEspModule.class).setEnabled(true);
				FemboyWare.getModules().get(BaseEspModule.class).setEnabled(true);
			});
			context.waitTicks(SCAN_TICKS);

			int freshGeodes = context.computeOnClient(client -> FemboyWare.getModules().get(GeodeEspModule.class).overgrownCount());
			int kelpChunks = context.computeOnClient(client -> FemboyWare.getModules().get(KelpEspModule.class).flaggedCount());
			int bases = context.computeOnClient(client -> FemboyWare.getModules().get(BaseEspModule.class).flaggedCount());
			System.out.printf("[femboyware-test] Fresh geode flagged in %d chunks, grown kelp chunks %d, bases %d%n", freshGeodes, kelpChunks, bases);

			// Every bud grown into a cluster, as years of random ticks would leave it
			for (String bud : new String[]{"small_amethyst_bud", "medium_amethyst_bud", "large_amethyst_bud"}) {
				server.runCommand(String.format("fill %d %d %d %d %d %d amethyst_cluster replace %s",
					geode.getX() - 16, geode.getY() - 16, geode.getZ() - 16, geode.getX() + 16, geode.getY() + 16, geode.getZ() + 16, bud));
			}
			context.waitTicks(SCAN_TICKS * 3);
			int grownGeodes = context.computeOnClient(client -> FemboyWare.getModules().get(GeodeEspModule.class).overgrownCount());
			String amethyst = context.computeOnClient(client -> {
				int budding = 0, buds = 0, clusters = 0;
				for (BlockPos pos : BlockPos.iterate(geode.add(-16, -16, -16), geode.add(16, 16, 16))) {
					var state = client.world.getBlockState(pos);
					if (state.isOf(Blocks.BUDDING_AMETHYST)) budding++;
					else if (state.isOf(Blocks.AMETHYST_CLUSTER)) clusters++;
					else if (state.isOf(Blocks.SMALL_AMETHYST_BUD) || state.isOf(Blocks.MEDIUM_AMETHYST_BUD) || state.isOf(Blocks.LARGE_AMETHYST_BUD)) buds++;
				}
				return "budding " + budding + ", buds " + buds + ", clusters " + clusters;
			});
			System.out.println("[femboyware-test] Geode blocks: " + amethyst);
			System.out.printf("[femboyware-test] Overgrown geode flagged in %d chunks%n", grownGeodes);

			context.runOnClient(client -> client.player.setPitch(20));
			context.takeScreenshot("base-finding");

			if (freshGeodes != 0) throw new AssertionError("Geode Esp flagged a freshly generated geode");
			if (grownGeodes == 0) throw new AssertionError("Geode Esp missed an overgrown geode");
			if (kelpChunks == 0) throw new AssertionError("Kelp Esp missed kelp taller than worldgen makes");
			if (bases == 0) throw new AssertionError("Base Esp missed a cluster of player blocks");

			context.runOnClient(client -> {
				FemboyWare.getModules().get(GeodeEspModule.class).setEnabled(false);
				FemboyWare.getModules().get(KelpEspModule.class).setEnabled(false);
				FemboyWare.getModules().get(BaseEspModule.class).setEnabled(false);
			});
		}
	}
}
