package dev.femboyware.test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.client.ClientSyncModule;
import dev.femboyware.module.larp.FbiRaidModule;
import dev.femboyware.module.larp.MansionModule;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.phys.Vec3;

public class RaidTest implements FabricClientGameTest {
	private static Object field(Object o, String name) throws Exception {
		Class<?> c = o.getClass();
		while (c != null) {
			try {
				Field f = c.getDeclaredField(name);
				f.setAccessible(true);
				return f.get(o);
			} catch (NoSuchFieldException e) {
				c = c.getSuperclass();
			}
		}
		throw new NoSuchFieldException(name);
	}

	private static void tp(ClientGameTestContext context, TestSingleplayerContext world, float x, float y, float z, float modelYaw, float pitch) {
		String[] cmd = new String[1];
		context.runOnClient(mc -> {
			MansionModule mansion = FemboyWare.getModules().get(MansionModule.class);
			Vec3 at = mansion.world(x, y, z);
			cmd[0] = String.format(java.util.Locale.ROOT, "tp @p %.3f %.3f %.3f %.1f %.1f", at.x, at.y, at.z, modelYaw + mansion.facing(), pitch);
		});
		world.getServer().runCommand(cmd[0]);
		context.waitTicks(3);
		context.runOnClient(mc -> {
			mc.player.getAbilities().flying = true;
			mc.player.onUpdateAbilities();
			mc.player.setDeltaMovement(Vec3.ZERO);
		});
	}

	private static double elapsed(ClientGameTestContext context) {
		double[] e = {-1};
		context.runOnClient(mc -> {
			try {
				Object own = field(FemboyWare.getModules().get(FbiRaidModule.class), "own");
				if (own == null) {
					e[0] = -1;
					return;
				}
				Method m = own.getClass().getDeclaredMethod("elapsed");
				m.setAccessible(true);
				e[0] = (double) m.invoke(own);
			} catch (Exception ex) {
				throw new RuntimeException(ex);
			}
		});
		return e[0];
	}

	private static double time(ClientGameTestContext context, String name) {
		double[] e = {-1};
		context.runOnClient(mc -> {
			try {
				Object own = field(FemboyWare.getModules().get(FbiRaidModule.class), "own");
				e[0] = own == null ? -1 : (double) field(own, name);
			} catch (Exception ex) {
				throw new RuntimeException(ex);
			}
		});
		return e[0];
	}

	private static void waitUntil(ClientGameTestContext context, double t) {
		for (int i = 0; i < 2000; i++) {
			double e = elapsed(context);
			if (e < 0 || e >= t) return;
			context.waitTicks(2);
		}
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getServer().runCommand("gamemode creative @a");
			world.getServer().runCommand("time set 6000");
			world.getServer().runCommand("gamerule doDaylightCycle false");
			context.waitTicks(100);
			context.runOnClient(mc -> FemboyWare.getModules().get(MansionModule.class).command(new String[]{"mansion", "spawn"}));
			context.waitTicks(20);

			// The watch room
			tp(context, world, -9.6f, 5.75f, 0.6f, 180, 18);
			context.waitTicks(30);
			context.takeScreenshot("watch_wall");
			tp(context, world, -10.4f, 5.75f, -0.9f, 180, 52);
			context.waitTicks(20);
			context.takeScreenshot("watch_case");
			tp(context, world, -9.6f, 12f, 14f, 180, 25);
			context.waitTicks(20);
			context.takeScreenshot("watch_outside");

			// The raid at dusk, from the living room looking at the front door
			world.getServer().runCommand("time set 13000");
			tp(context, world, -3, 1.2f, -1, 20, 0);
			context.waitTicks(20);
			context.runOnClient(mc -> FemboyWare.getModules().get(FbiRaidModule.class).command(new String[]{"fbi", "raid"}));
			// The raid turns up a few seconds after the plug's warning text
			for (int i = 0; i < 200 && elapsed(context) < 0; i++) context.waitTicks(5);
			System.out.println("RAID times arrive0=" + time(context, "cuffStart") + " escort=" + time(context, "escortStart") + " loaded=" + time(context, "loaded")
				+ " depart=" + time(context, "depart") + " end=" + time(context, "end"));
			waitUntil(context, 7.5);
			context.takeScreenshot("raid_1_arrive");
			waitUntil(context, 13.3);
			context.takeScreenshot("raid_2_flashbang");
			double cuff = time(context, "cuffStart");
			waitUntil(context, cuff - 1);
			context.runOnClient(mc -> {
				try {
					Object own = field(FemboyWare.getModules().get(FbiRaidModule.class), "own");
					java.util.List<?> actors = (java.util.List<?>) field(own, "actors");
					System.out.println("RAID surrounded actors=" + actors.size());
					for (Object o : actors) {
						var e = (net.minecraft.client.player.RemotePlayer) o;
						System.out.println("RAID actor id=" + e.getId() + " pos=" + e.position() + " removed=" + e.isRemoved() + " inLevel=" + (mc.level.getEntity(e.getId()) == e)
							+ " dist=" + String.format("%.1f", e.distanceTo(mc.player)) + " invisible=" + e.isInvisible() + " you=" + mc.player.position());
					}
					mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			});
			context.takeScreenshot("raid_3_surrounded");
			context.runOnClient(mc -> mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON));
			tp(context, world, -3, 1.2f, -1, 200, 10);
			context.waitTicks(10);
			context.takeScreenshot("raid_3b_behind");
			waitUntil(context, cuff + 3.6);
			context.takeScreenshot("raid_4_cuffs");
			double search = time(context, "searchStart");
			waitUntil(context, search + 6);
			context.takeScreenshot("raid_4b_search");
			tp(context, world, -9.6f, 5.75f, 1.4f, 180, 30);
			waitUntil(context, search + 11);
			context.takeScreenshot("raid_4c_watches");
			tp(context, world, -3, 1.2f, -1, 20, 0);
			double escort = time(context, "escortStart");
			waitUntil(context, escort + 2.5);
			context.runOnClient(mc -> {
				try {
					Object own = field(FemboyWare.getModules().get(FbiRaidModule.class), "own");
					var you = (net.minecraft.client.player.RemotePlayer) field(own, "you");
					System.out.println("RAID escort you=" + (you == null ? null : you.position() + " inLevel=" + (mc.level.getEntity(you.getId()) == you) + " invis=" + you.isInvisible())
						+ " camera=" + dev.femboyware.util.Mc.camera().position());
					for (Object o : (java.util.List<?>) field(own, "actors")) {
						var e = (net.minecraft.client.player.RemotePlayer) o;
						System.out.println("RAID escort actor " + e.getId() + " at " + e.position());
					}
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			});
			context.takeScreenshot("raid_5_escort");
			double loaded = time(context, "loaded");
			waitUntil(context, loaded - 1.6);
			context.takeScreenshot("raid_6_outside");
			double depart = time(context, "depart");
			waitUntil(context, depart + 2.5);
			context.takeScreenshot("raid_7_convoy");
			double end = time(context, "end");
			waitUntil(context, end + 2);
			context.takeScreenshot("raid_8_seized");
			waitUntil(context, end + 30);
			context.waitTicks(10);
			context.runOnClient(mc -> System.out.println("RAID after frozen=" + FbiRaidModule.assetsFrozen() + " info=" + FemboyWare.getModules().get(FbiRaidModule.class).getInfo()
				+ " invisible=" + mc.player.isInvisible()));
			context.takeScreenshot("raid_9_tape");

			// Someone else's raid, as Client Sync hears it, partway through
			UUID peer = UUID.randomUUID();
			for (int i = 0; i < 6; i++) {
				final int step = i;
				context.runOnClient(mc -> {
					try {
						MansionModule mansion = FemboyWare.getModules().get(MansionModule.class);
						JsonObject fb = new JsonObject();
						JsonArray o = new JsonArray();
						Vec3 origin = mansion.origin();
						for (double v : new double[]{origin.x, origin.y, origin.z, mansion.facing()}) o.add(v);
						fb.add("o", o);
						JsonArray t = new JsonArray();
						for (double v : new double[]{-3, 1.2, -1, 0}) t.add(v);
						fb.add("t", t);
						fb.addProperty("e", 20 + step * 0.5);
						fb.addProperty("s", 777);
						fb.addProperty("a", 8);
						fb.addProperty("v", 4);
						fb.addProperty("n", "Friend");
						JsonObject s = new JsonObject();
						s.addProperty("u", peer.toString());
						s.addProperty("d", mc.level.dimension().identifier().toString());
						s.addProperty("ts", System.currentTimeMillis());
						s.addProperty("i", 500);
						s.add("fb", fb);
						Method receive = ClientSyncModule.class.getDeclaredMethod("receive", String.class);
						receive.setAccessible(true);
						receive.invoke(FemboyWare.getModules().get(ClientSyncModule.class), s.toString());
					} catch (Exception e) {
						throw new RuntimeException(e);
					}
				});
				context.waitTicks(10);
			}
			tp(context, world, -3, 1.2f, 2.5f, 180, 15);
			context.waitTicks(10);
			context.runOnClient(mc -> {
				try {
					@SuppressWarnings("unchecked") Map<UUID, Object> peers = (Map<UUID, Object>) field(FemboyWare.getModules().get(FbiRaidModule.class), "peers");
					Object scene = peers.get(peer);
					System.out.println("RAID peer scene=" + (scene != null) + (scene != null ? " actors=" + ((java.util.List<?>) field(scene, "actors")).size() : ""));
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			});
			context.takeScreenshot("raid_peer");
			context.runOnClient(mc -> FemboyWare.getModules().get(FbiRaidModule.class).command(new String[]{"fbi", "release"}));
			context.waitTicks(5);
		}
	}
}
