package dev.femboyware.test;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.Module;
import dev.femboyware.module.client.FakePlayerModule;
import dev.femboyware.module.combat.AutoCrystalModule;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.Setting;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Locale;

/**
 * Auto Crystal's Base Place against a fake player standing on plain grass, where there is no
 * obsidian: it must place obsidian next to the target and crystal it.
 */
public class AutoCrystalBaseClientTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getClientWorld().waitForChunksRender();
			// Blast protection keeps our own crystals from counting as too dangerous to place
			for (String slot : new String[]{"head", "chest", "legs", "feet"}) {
				String item = switch (slot) {
					case "head" -> "netherite_helmet";
					case "chest" -> "netherite_chestplate";
					case "legs" -> "netherite_leggings";
					default -> "netherite_boots";
				};
				singleplayer.getServer().runCommand("item replace entity @a armor." + slot + " with " + item + "[enchantments={blast_protection:4}]");
			}
			singleplayer.getServer().runCommand("give @a obsidian 64");
			singleplayer.getServer().runCommand("give @a end_crystal 64");
			context.waitTicks(10);

			// The fake player spawns where we stand; then we step back four blocks
			Vec3d spot = context.computeOnClient(client -> {
				FakePlayerModule fake = FemboyWare.getModules().get(FakePlayerModule.class);
				((BoolSetting) setting(fake, "Invincible")).set(true);
				// Unarmoured, so a base can actually reach Min Damage
				((BoolSetting) setting(fake, "Copy Armor")).set(false);
				fake.setEnabled(true);
				return client.player.getEntityPos();
			});
			singleplayer.getServer().runCommand(String.format(Locale.ROOT, "tp @a %.2f %.2f %.2f 0 25", spot.x, spot.y, spot.z - 4));
			context.waitTicks(10);

			int before = obsidianAround(context, spot);
			context.runOnClient(client -> {
				AutoCrystalModule crystal = FemboyWare.getModules().get(AutoCrystalModule.class);
				((BoolSetting) setting(crystal, "Base Place")).set(true);
				crystal.setEnabled(true);
			});
			context.waitTicks(6);
			context.takeScreenshot("auto-crystal-base-place");
			context.waitTicks(40);

			int after = obsidianAround(context, spot);
			String info = context.computeOnClient(client -> FemboyWare.getModules().get(AutoCrystalModule.class).getInfo());
			System.out.println("[femboyware-test] Base Place: obsidian near target " + before + " -> " + after + ", Auto Crystal: " + info);
			int crystalsUsed = 64 - context.computeOnClient(client -> client.player.getInventory().count(Items.END_CRYSTAL));
			System.out.println("[femboyware-test] Base Place: crystals used " + crystalsUsed);
			if (after <= before) throw new AssertionError("Auto Crystal placed no obsidian base");
			if (crystalsUsed <= 0) throw new AssertionError("Auto Crystal placed a base but never crystalled it");
			context.takeScreenshot("auto-crystal-base-place-after");

			context.runOnClient(client -> {
				FemboyWare.getModules().get(AutoCrystalModule.class).setEnabled(false);
				FemboyWare.getModules().get(FakePlayerModule.class).setEnabled(false);
			});
		}
	}

	private static int obsidianAround(ClientGameTestContext context, Vec3d center) {
		return context.computeOnClient(client -> {
			int count = 0;
			BlockPos feet = BlockPos.ofFloored(center);
			for (BlockPos pos : BlockPos.iterate(feet.add(-3, -2, -3), feet.add(3, 1, 3))) {
				if (client.world.getBlockState(pos).isOf(Blocks.OBSIDIAN)) count++;
			}
			return count;
		});
	}

	private static Setting<?> setting(Module module, String name) {
		for (Setting<?> setting : module.getSettings()) {
			if (setting.getName().equals(name)) return setting;
		}
		throw new AssertionError("No setting " + name);
	}
}
