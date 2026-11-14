package dev.femboyware.module.larp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.femboyware.FemboyWare;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.manager.CommandManager;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.ChatUtil;
import dev.femboyware.util.FxRender;
import dev.femboyware.util.Mc;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * An FBI raid on your mansion. A convoy of black SUVs comes up the road, red and blue lights going
 * and sirens wailing, and parks round the house. Agents in raid jackets pile out, stack at the
 * front door, breach it with a flashbang, and flood in to surround you with pistols drawn, shouting
 * at you not to move. One cuffs you and reads you your rights; two walk you out to the lead SUV
 * and put you in the back; the convoy pulls away, and everything you own is seized: cars, yacht,
 * jet, helicopter, the bank and the crypto, until you're released.
 * <p>
 * The whole scene is worked out from its start time, the mansion and where you stood, so everyone
 * with Client Sync sees exactly the same raid. Comes now and then on its own while you're home, or
 * with {@code .fbi raid}.
 */
public class FbiRaidModule extends Module {
	private final BoolSetting randomRaids = add(new BoolSetting("Random Raids", "Now and then, while you're home in your mansion, the FBI raids it", true));
	private final NumberSetting everyMinutes = add(new NumberSetting("Average Minutes", "About how many minutes at home before a random raid", 45, 5, 240, 5)).visibleWhen(randomRaids::get);
	private final NumberSetting agentCount = add(new NumberSetting("Agents", "How many agents storm the house", 10, 4, 16, 1));
	private final NumberSetting suvCount = add(new NumberSetting("SUVs", "How many black SUVs pull up", 5, 2, 8, 1));
	private final BoolSetting seize = add(new BoolSetting("Seize Assets", "They take your cars, yacht, jet and helicopter, and freeze your bank and crypto for a few minutes (or until .fbi release)", true));
	private final BoolSetting music = add(new BoolSetting("Music", "Weiland's Bitcoin plays over the raid", true));
	private final BoolSetting cinematic = add(new BoolSetting("Cinematic", "Cuts to film shots through the raid: the convoy arriving, the breach, being surrounded and cuffed", true));
	private final BoolSetting flashbang = add(new BoolSetting("Flashbang", "A flashbang goes in when they breach the door", true));
	/** Remembered across restarts: your accounts are frozen until you're released. */
	private final BoolSetting frozen = add(new BoolSetting("Assets Frozen", "Your bank and crypto are frozen after a raid", false)).visibleWhen(() -> false);
	private final NumberSetting releaseMinutes = add(new NumberSetting("Release After", "Minutes after a raid before the freeze lifts and everything they took comes back", 3, 1, 30, 1)).visibleWhen(seize::get);
	/** When the freeze lifts on its own (wall clock), or 0 when it hasn't been set yet this session. */
	private long releaseAt;

	// ---------------------------------------------------------------- the house (the mansion's own space)

	private static final float PLINTH = 1.2f, F2 = 5.7f, FRONT = 4, HX = 14, BACK = -13, ROOF = 9.4f;
	private static final float STAIR_X = -12.6f, STAIR_BOTTOM_Z = -11.8f, STAIR_TOP_Z = -4.0f;
	/** Outside the front door, just inside it, and the bottom of the front steps. */
	private static final float[] DOOR_OUT = {0, PLINTH, FRONT + 1.6f}, DOOR_IN = {0, PLINTH, FRONT - 1.2f}, STEPS_FOOT = {0, 0, 18.2f};

	// ---------------------------------------------------------------- the timeline, in seconds

	private static final double DRIVE_IN = 6.5, SUV_STAGGER = 0.7, SHOUT_OUTSIDE = 9.5, BREACH = 12.5, BANG = 13.1, ENTER_GAP = 0.32,
		RUN = 5.4, WALK = 3.0, ESCORT_SPEED = 2.6, CUFF_WALK = 1.6, CUFFS_AT = 2.2, ESCORT_AFTER_CUFFS = 2.4, LOAD = 1.2, DEPART_AFTER_LOAD = 2.5,
		DEPART_LENGTH = 12, FADE = 1.5, SEIZED_SHOWN = 7;

	private static final String[] SURNAMES = {"Miller", "Reyes", "Thompson", "Walsh", "Nguyen", "Carter", "Brooks", "Hayes", "Patel", "Murphy",
		"Diaz", "Kowalski", "Bennett", "Foster", "Grant", "Ortiz"};

	private static final PlayerSkin[] AGENT_SKINS = {skin("fbi_agent_1"), skin("fbi_agent_2"), skin("fbi_agent_3"), skin("fbi_agent_4"), skin("fbi_agent_5"), skin("fbi_agent_6")};

	private static PlayerSkin skin(String name) {
		return new PlayerSkin(new net.minecraft.core.ClientAsset.ResourceTexture(Identifier.fromNamespaceAndPath("femboyware", name),
			Identifier.fromNamespaceAndPath("femboyware", "textures/larp/" + name + ".png")), null, null, net.minecraft.world.entity.player.PlayerModelType.WIDE, true);
	}

	private static final Identifier PISTOL = Identifier.fromNamespaceAndPath("femboyware", "textures/larp/pistol.png");
	private static final int OWN_IDS = -41000, PEER_IDS = -43000;

	/** What an agent's doing on one step of their raid. */
	private static final int IDLE = 0, AIM = 1, PHOTO = 2, BAG = 3, CARRY = 4, SEATED = 5, HOLD = 6, ESCORT_LEFT = 7, ESCORT_RIGHT = 8;
	/** How long the search runs, how long each photo stop lasts, and how often a camera goes off. */
	private static final double SEARCH = 20, PHOTO_STOP = 4.2, FLASH_EVERY = 1.3;
	/** Rooms the photographers go to on the ground floor: where they stand, and what they point the camera at. */
	private static final float[][][] PHOTO_SPOTS = {
		{{-3.2f, PLINTH, -0.4f}, {-13.7f, PLINTH + 2.4f, -0.5f}},
		{{-3, PLINTH, -6.2f}, {-3, PLINTH + 1, -12}},
		{{2, PLINTH, -0.8f}, {2, PLINTH + 0.8f, -4}},
		{{8.5f, PLINTH, -3}, {11, PLINTH + 0.8f, -7}},
		{{-2.6f, PLINTH, 6.4f}, {-8.5f, PLINTH, 10}},
		{{-9, PLINTH, -3}, {-12.6f, PLINTH + 1.5f, -8}}};

	/**
	 * One step of an agent's raid: from {@code start}, walking {@code path} at {@code speed}, then
	 * standing at its end facing {@code face} (or you, if null), doing {@code activity}; or sitting in
	 * seat {@code seat} of SUV {@code suv}.
	 */
	private record Leg(double start, float[][] path, double speed, int activity, float[] face, int suv, int seat) {
		static Leg walk(double start, float[][] path, double speed, int activity, float[] face) {
			return new Leg(start, path, speed, activity, face, -1, -1);
		}

		static Leg sit(double start, int suv, int seat) {
			return new Leg(start, null, 0, SEATED, null, suv, seat);
		}

		double length() {
			return path == null ? 0 : FbiRaidModule.length(path, path.length);
		}

		/** When they get to the end of the path. */
		double arrives() {
			return speed <= 0 ? start : start + length() / speed;
		}
	}

	/**
	 * The ground a car drives on, in the mansion's space: the plinth round the house, the driveway
	 * sloping down from it to the road (its steps' nosings all sit on this line), and the road.
	 */
	private static float ground(float x, float z) {
		if (x > 6.6f && x < 13.4f && z > 16 && z < 20.2f) return PLINTH * (20.2f - z) / 4.2f;
		if (x > -18 && x < 18 && z > -16 && z <= 16) return PLINTH;
		return 0;
	}

	/** Where your Ledger sits on the desk upstairs, and how long the agent's reach for it takes. */
	static final float[] LEDGER_SPOT = {0.7f, F2 + 0.77f, -12.05f};
	private static final double LEDGER_REACH = 1.4;

	/** A reach into the watch cases: which watches come out, where the agent stands for it, and what they face. */
	private record Reach(int[] slots, float[] stand, float[] face) {
	}

	/** A reach as planned: the walk to it from the last one, when they get there, and how long the reach takes. */
	private record Grab(int[] slots, float[] stand, float[] face, float[][] approach, double walkStart, double reachStart, double length) {
	}

	/**
	 * Where a watch sits in the watch room (the same order the mansion draws them): 24 in the winder
	 * wall's cubbies, three rows of eight, then two rows of nine in each vitrine.
	 */
	static float[] slotAt(int i) {
		float u = F2 + 0.016f;
		if (i < 24) return new float[]{-10.85f + (i % 8) * 0.6f, u + 1.05f + (i / 8) * 0.5f - 0.02f, -3.84f};
		int k = i - 24, side = k / 18, row = (k % 18) / 9, col = k % 9;
		return new float[]{-12.55f + col * 0.64f, u + 0.86f + 0.035f, (side == 0 ? -2.6f : 0.6f) + 0.22f + row * 0.36f};
	}

	/**
	 * The reaches agent {@code k} of a watch team of {@code team} makes, in order: up the winder wall
	 * a column at a time from the corridor behind the first vitrine, and along the vitrines a column
	 * (two watches) at a time.
	 */
	private static List<Reach> reaches(int k, int team) {
		List<Reach> wall = new ArrayList<>(), corridor = new ArrayList<>(), walkway0 = new ArrayList<>(), walkway1 = new ArrayList<>();
		float back = -3.25f, mid0 = -1.35f, mid1 = 0.05f, u = F2;
		for (int c = 0; c < 8; c++) {
			float x = -10.85f + c * 0.6f;
			wall.add(new Reach(new int[]{c, 8 + c, 16 + c}, new float[]{x, u, back}, new float[]{x, u + 1.55f, -4}));
		}
		for (int c = 8; c >= 0; c--) {
			float x = -12.55f + c * 0.64f;
			corridor.add(new Reach(new int[]{24 + c, 33 + c}, new float[]{x, u, back}, new float[]{x, u + 0.9f, -2.2f}));
			if (c <= 4) walkway0.add(new Reach(new int[]{24 + c, 33 + c}, new float[]{x, u, mid0}, new float[]{x, u + 0.9f, -2.2f}));
		}
		for (int c = 0; c < 9; c++) {
			float x = -12.55f + c * 0.64f;
			walkway1.add(new Reach(new int[]{42 + c, 51 + c}, new float[]{x, u, mid1}, new float[]{x, u + 0.9f, 1.0f}));
		}
		List<Reach> out = new ArrayList<>();
		if (team <= 1) {
			out.addAll(wall);
			out.addAll(corridor);
			out.addAll(walkway1);
		} else if (k == 0) {
			out.addAll(wall);
			out.addAll(corridor.subList(0, 4));
		} else {
			out.addAll(walkway1);
			out.addAll(walkway0);
		}
		return out;
	}

	/** One raid, yours or someone else's, played from its start. */
	private final class Scene {
		final Vec3 origin;
		final float yaw;
		/** Where they found you, in the mansion's space, and which way you faced (model yaw). */
		final float[] target;
		final float targetYaw;
		final long seed;
		final int agents, suvs;
		final String name;
		final boolean mine;
		final UUID owner;
		final int firstId;
		/** When it started, on our clock (ms). */
		final long startMs;

		final List<float[][]> suvIn = new ArrayList<>(), suvOut = new ArrayList<>();
		final List<float[]> parked = new ArrayList<>();
		/** How many SUVs drive off with you (the lead one, and an escort); the rest stay. */
		int departing;
		final List<List<Leg>> legs = new ArrayList<>();
		final double[] agentArrive;
		final int[] agentSuv;
		final float[][] ring;
		float[][] escort;
		double lastArrive, firstArrive = Double.MAX_VALUE, cuffStart, searchStart, searchEnd, escortStart, loaded, depart, end;
		/** The watch team's bagging, each from and to when, to empty the cases as they go. */
		final List<double[]> bagging = new ArrayList<>();
		/** Each watch team agent's reaches into the cases, and when each watch goes into a bag. */
		final Map<Integer, List<Grab>> grabs = new HashMap<>();
		final double[] slotTaken = new double[MansionModule.WATCH_COUNT];
		/** When each vitrine's lid starts lifting: just before the first agent reaches into it. */
		final double[] lidAt = {Double.MAX_VALUE, Double.MAX_VALUE};
		/** The agent who goes up to the desk for your Ledger, and when it goes into their bag. */
		int ledgerAgent = -1;
		double ledgerAt = Double.MAX_VALUE;

		final List<RemotePlayer> actors = new ArrayList<>();
		RemotePlayer you;
		PlayerSkin yourSkin;
		Siren siren;
		final java.util.Set<String> said = new java.util.HashSet<>();
		/** Someone else's raid you came in on partway: what's already happened isn't said or played again. */
		boolean primed, silent;
		/** When each agent's camera last went off (ms), for the flash. */
		final Map<Integer, Long> flashes = new HashMap<>();

		Scene(Vec3 origin, float yaw, float[] target, float targetYaw, long seed, int agents, int suvs, String name, UUID owner, boolean mine, long startMs, int firstId) {
			this.origin = origin;
			this.yaw = yaw;
			this.target = target;
			this.targetYaw = targetYaw;
			this.seed = seed;
			this.agents = agents;
			this.suvs = suvs;
			this.name = name;
			this.owner = owner;
			this.mine = mine;
			this.startMs = startMs;
			this.firstId = firstId;
			this.agentArrive = new double[agents];
			this.agentSuv = new int[agents];
			this.ring = new float[agents][];
			plan();
		}

		double elapsed() {
			return (System.currentTimeMillis() - startMs) / 1000.0;
		}

		boolean upstairs() {
			return target[1] > F2 - 1;
		}

		boolean inside() {
			return target[0] > -HX && target[0] < HX && target[2] > BACK && target[2] < FRONT;
		}

		/** Works out every path and moment from the start. */
		private void plan() {
			java.util.Arrays.fill(slotTaken, Double.MAX_VALUE);
			Random random = new Random(seed);
			// The SUVs, each in its own lane so none drives through another: the lead up the driveway to right
			// outside, the escort in front of the steps, the rest out front clear of both their turning room
			departing = suvs >= 3 ? 2 : 1;
			float[] stayX = {-11.5f, 3.2f, -16f, 6.6f, -20.5f, 19f};
			for (int i = 0; i < suvs; i++) {
				float[] spot;
				if (i == 0) spot = new float[]{10, PLINTH, 9, 180};
				else if (i == 1 && departing > 1) spot = new float[]{-2.6f, 0, 20.6f, 180};
				else {
					int k = i - departing;
					float x = stayX[k % stayX.length] + (random.nextFloat() - 0.5f) * 0.6f;
					float z = 20.6f + random.nextFloat() * 1.2f + (k >= stayX.length ? 3.6f : 0);
					spot = new float[]{x, 0, z, 180};
				}
				parked.add(spot);
				suvIn.add(i == 0
					? new float[][]{{10, 0, 70}, {10, 0, 20.2f}, {10, PLINTH, 16}, {spot[0], spot[1], spot[2]}}
					: new float[][]{{spot[0], 0, 75}, {spot[0], 0, spot[2] + 6}, {spot[0], spot[1], spot[2]}});
				suvOut.add(i < departing ? departure(spot, i == 0) : new float[][]{{spot[0], spot[1], spot[2], 0}});
			}

			// Out of the SUVs and up to the house
			List<List<float[]>> approach = new ArrayList<>();
			double[] exitAt = new double[agents];
			for (int j = 0; j < agents; j++) {
				int suv = j % suvs;
				agentSuv[j] = suv;
				float[] p = parked.get(suv);
				exitAt[j] = DRIVE_IN + suv * SUV_STAGGER + 0.4 + (j / suvs) * 0.35;
				List<float[]> path = new ArrayList<>();
				path.add(carSide(p, j / suvs % 2 == 0 ? 1 : -1, 1.3f));
				if (inside() && p[1] < PLINTH / 2) {
					path.add(new float[]{STEPS_FOOT[0] + (j % 3 - 1) * 1.2f, 0, STEPS_FOOT[2]});
					path.add(new float[]{(j % 3 - 1) * 1.0f, PLINTH, 15.4f});
				}
				approach.add(path);
			}
			// The stack by the door, filled in the order they get there: the first take the spots by the door,
			// the rest line up behind them on the side they come from, so nobody runs through anyone
			float[][] stackSpot = new float[agents][];
			int[] enterRank = new int[agents];
			if (inside()) {
				Integer[] order = new Integer[agents];
				double[] reach = new double[agents];
				for (int j = 0; j < agents; j++) {
					order[j] = j;
					float[][] a = approach.get(j).toArray(new float[0][]);
					reach[j] = exitAt[j] + (length(a, a.length) + distance(a[a.length - 1], DOOR_OUT)) / RUN;
				}
				java.util.Arrays.sort(order, (x, y) -> Double.compare(reach[x], reach[y]));
				int[] rows = new int[2];
				for (int j : order) {
					List<float[]> path = approach.get(j);
					int column = path.get(path.size() - 1)[0] >= 0 ? 1 : 0;
					if (rows[column] - rows[1 - column] >= 2) column = 1 - column;
					int row = rows[column]++;
					stackSpot[j] = new float[]{(column == 1 ? 1 : -1) * 1.1f, PLINTH, DOOR_OUT[2] + 0.3f + row * 0.85f};
				}
				// In through the door front rows first, left then right
				Integer[] entering = new Integer[agents];
				for (int j = 0; j < agents; j++) entering[j] = j;
				java.util.Arrays.sort(entering, (x, y) -> {
					int byRow = Float.compare(stackSpot[x][2], stackSpot[y][2]);
					return byRow != 0 ? byRow : Float.compare(stackSpot[x][0], stackSpot[y][0]);
				});
				for (int k = 0; k < agents; k++) enterRank[entering[k]] = k;
			}
			// The ring round you: whoever comes in first goes furthest, so nobody runs through someone already in place
			if (!inside()) {
				Integer[] byExit = new Integer[agents];
				for (int j = 0; j < agents; j++) byExit[j] = j;
				java.util.Arrays.sort(byExit, (x, y) -> Double.compare(exitAt[x], exitAt[y]));
				for (int k = 0; k < agents; k++) enterRank[byExit[k]] = k;
			}
			double facing = Math.atan2(Math.cos(Math.toRadians(targetYaw)), -Math.sin(Math.toRadians(targetYaw)));
			float[][] spots = new float[agents][];
			double[] spotAngle = new double[agents];
			for (int i = 0; i < agents; i++) {
				spotAngle[i] = facing + Math.PI * 2 * i / agents + (random.nextFloat() - 0.5f) * 0.15f;
				float radius = 2.5f + (i % 2) * 0.35f;
				spots[i] = new float[]{target[0] + (float) Math.cos(spotAngle[i]) * radius, target[1], target[2] + (float) Math.sin(spotAngle[i]) * radius};
				// Not in the sofa or behind the dining chairs: the nearest spot they can actually stand
				spots[i] = RaidNav.nearestFree(spots[i], 1.5f);
			}
			float[] entry = !inside() ? DOOR_OUT : upstairs() ? new float[]{STAIR_X, F2, STAIR_TOP_Z} : DOOR_IN;
			Integer[] far = new Integer[agents];
			for (int i = 0; i < agents; i++) far[i] = i;
			java.util.Arrays.sort(far, (x, y) -> Double.compare(distance(spots[y], entry), distance(spots[x], entry)));
			int[] spotOf = new int[agents];
			for (int j = 0; j < agents; j++) {
				spotOf[j] = far[enterRank[j]];
				ring[j] = spots[spotOf[j]];
			}
			// The guards are whoever ends up in front of you, in view, guns on you the whole time
			int guards = Math.max(2, Math.min(4, agents - 4));
			Integer[] frontmost = new Integer[agents];
			for (int j = 0; j < agents; j++) frontmost[j] = j;
			java.util.Arrays.sort(frontmost, (x, y) -> Double.compare(Math.abs(Mth.wrapDegrees(Math.toDegrees(spotAngle[spotOf[x]] - facing))),
				Math.abs(Mth.wrapDegrees(Math.toDegrees(spotAngle[spotOf[y]] - facing)))));
			// Role 0 cuffs you (the guard furthest round, so the walk behind you is short), 1 walks you out with them,
			// 2 rides beside you, 3 drives the escort; everyone else (-1) searches
			int[] role = new int[agents];
			java.util.Arrays.fill(role, -1);
			role[frontmost[guards - 1]] = 0;
			role[frontmost[0]] = 1;
			for (int g = 1, next = 2; g < guards - 1; g++) role[frontmost[g]] = next++;

			// Up to the door (or straight at you, outside), then in with guns up, to a ring round you
			for (int j = 0; j < agents; j++) {
				List<Leg> mine = new ArrayList<>();
				legs.add(mine);
				List<float[]> to = new ArrayList<>(approach.get(j));
				if (inside()) {
					to.add(stackSpot[j]);
					Leg stack = Leg.walk(exitAt[j], clear(to), RUN, IDLE, DOOR_IN);
					mine.add(stack);
					List<float[]> in = new ArrayList<>();
					in.add(stackSpot[j]);
					in.add(DOOR_IN);
					if (upstairs()) {
						in.add(new float[]{STAIR_X, PLINTH, STAIR_BOTTOM_Z});
						in.add(new float[]{STAIR_X, F2, STAIR_TOP_Z});
					}
					in.add(ring[j]);
					Leg storm = Leg.walk(Math.max(stack.arrives(), BREACH + 0.4 + enterRank[j] * ENTER_GAP), clear(in), RUN, AIM, null);
					mine.add(storm);
					agentArrive[j] = storm.arrives();
				} else {
					to.add(ring[j]);
					Leg storm = Leg.walk(exitAt[j] + 0.6, clear(to), RUN, AIM, null);
					mine.add(storm);
					agentArrive[j] = storm.arrives();
				}
				lastArrive = Math.max(lastArrive, agentArrive[j]);
				firstArrive = Math.min(firstArrive, agentArrive[j]);
			}

			// A moment all round you, then the search team breaks off while you're cuffed and held at gunpoint; then you're walked out
			searchStart = lastArrive + 1.3;
			cuffStart = Math.max(lastArrive + 1.6, BREACH + 6);
			searchEnd = searchStart + SEARCH;
			escortStart = Math.max(searchEnd, cuffStart + CUFF_WALK + CUFFS_AT + 1) + 1.0;
			float[] lead = parked.get(0);
			float[] rearDoor = carPoint(lead, 1.25f, -1.35f);
			List<float[]> out = new ArrayList<>();
			out.add(target);
			if (inside()) {
				if (upstairs()) {
					out.add(new float[]{STAIR_X, F2, STAIR_TOP_Z});
					out.add(new float[]{STAIR_X, PLINTH, STAIR_BOTTOM_Z});
				}
				out.add(DOOR_IN);
				out.add(DOOR_OUT);
			}
			out.add(new float[]{rearDoor[0] - 0.6f, PLINTH, rearDoor[2] + 2.2f});
			out.add(rearDoor);
			escort = clear(out, false);
			loaded = escortStart + length(escort, escort.length) / ESCORT_SPEED + LOAD;
			depart = loaded + DEPART_AFTER_LOAD;
			end = depart + DEPART_LENGTH;

			int searchers = agents - guards;
			int watchTeam = Math.min(2, searchers);
			int staying = suvs - departing;
			int searcher = 0;
			for (int j = 0; j < agents; j++) {
				List<Leg> mine = legs.get(j);
				if (role[j] == 0) {
					// Round behind you to put the cuffs on, and holds you there
					float[] behind = offset(targetYaw, 0, -0.65f);
					float[] spot = {target[0] + behind[0], target[1], target[2] + behind[2]};
					mine.add(Leg.walk(cuffStart, new float[][]{ring[j], spot}, Math.max(0.1, distance(ring[j], spot) / CUFF_WALK), HOLD, null));
					mine.add(Leg.walk(escortStart, escort, ESCORT_SPEED, ESCORT_LEFT, null));
					mine.add(Leg.sit(loaded - LOAD * 0.5, 0, 0));
				} else if (role[j] == 1) {
					mine.add(Leg.walk(escortStart, escort, ESCORT_SPEED, ESCORT_RIGHT, null));
					mine.add(Leg.sit(loaded - LOAD * 0.5, 0, 1));
				} else if (role[j] >= 2) {
					// The other guards follow you out: one rides in the back beside you, one drives the escort SUV
					List<float[]> follow = new ArrayList<>();
					follow.add(ring[j]);
					for (int k = 1; k < escort.length - 1; k++) follow.add(escort[k]);
					boolean back = role[j] == 2;
					float[] car = back ? carPoint(lead, -1.25f, -1.35f) : departing > 1 ? carSide(parked.get(1), -1, 1.3f) : post(role[j], staying);
					follow.add(car);
					mine.add(Leg.walk(escortStart + 1.0 + (role[j] - 2) * 0.8, clear(follow), ESCORT_SPEED + 0.4, IDLE, DOOR_OUT));
					if (back) mine.add(Leg.sit(loaded, 0, 2));
					else if (departing > 1) mine.add(Leg.sit(loaded + 0.4, 1, 0));
				} else {
					int k = searcher++;
					float[] at = ring[j];
					double t = searchStart + k * 0.3;
					if (k < watchTeam) {
						// Up to the watch room to bag the collection, then out with the evidence boxes
						List<Reach> plan = reaches(k, watchTeam);
						float[] stand = plan.getFirst().stand(), face = plan.getFirst().face();
						List<float[]> up = new ArrayList<>();
						up.add(at);
						if (!upstairs()) {
							up.add(new float[]{STAIR_X, PLINTH, STAIR_BOTTOM_Z});
							up.add(new float[]{STAIR_X, F2, STAIR_TOP_Z});
						}
						up.add(stand);
						Leg climb = Leg.walk(t, clear(up), WALK, BAG, face);
						mine.add(climb);
						double bagEnd = searchEnd - 3.5;
						// Case to case: the walks between them, then each reach paced to finish about when the search does
						List<float[][]> walks = new ArrayList<>();
						double walking = 0;
						float[] from = stand;
						for (Reach r : plan) {
							float[][] path = clear(List.of(from, r.stand()), false);
							walks.add(path);
							walking += length(path, path.length) / WALK;
							from = r.stand();
						}
						double each = Mth.clamp((bagEnd - climb.arrives() - walking) / plan.size(), 0.6, 1.3);
						double tt = climb.arrives();
						List<Grab> mineGrabs = new ArrayList<>();
						for (int r = 0; r < plan.size(); r++) {
							float[][] path = walks.get(r);
							double walk = length(path, path.length) / WALK;
							Grab g = new Grab(plan.get(r).slots(), plan.get(r).stand(), plan.get(r).face(), path, tt, tt + walk, each);
							for (int slot : g.slots()) {
								slotTaken[slot] = g.reachStart() + each * 0.45;
								int v = vitrineOf(slot);
								if (v >= 0) lidAt[v] = Math.min(lidAt[v], g.reachStart() - LID_LEAD);
							}
							mineGrabs.add(g);
							tt += walk + each;
						}
						grabs.put(j, mineGrabs);
						bagging.add(new double[]{climb.arrives(), tt});
						bagEnd = tt + 0.3;
						List<float[]> down = new ArrayList<>();
						down.add(plan.getLast().stand());
						down.add(new float[]{STAIR_X, F2, STAIR_TOP_Z});
						down.add(new float[]{STAIR_X, PLINTH, STAIR_BOTTOM_Z});
						down.add(DOOR_IN);
						down.add(DOOR_OUT);
						down.add(post(k, staying));
						Leg carry = Leg.walk(bagEnd, clear(down), WALK, CARRY, DOOR_OUT);
						mine.add(carry);
						mine.add(Leg.walk(carry.arrives(), new float[][]{post(k, staying)}, 1, IDLE, DOOR_OUT));
					} else if (k == watchTeam) {
						// Upstairs to the desk for your Ledger: bagged, then out with it
						float[] stand = {0.7f, F2, -10.85f}, face = LEDGER_SPOT;
						List<float[]> up = new ArrayList<>();
						up.add(at);
						if (!upstairs()) {
							up.add(new float[]{STAIR_X, PLINTH, STAIR_BOTTOM_Z});
							up.add(new float[]{STAIR_X, F2, STAIR_TOP_Z});
						}
						up.add(stand);
						Leg climb = Leg.walk(t, clear(up), WALK, BAG, face);
						mine.add(climb);
						ledgerAgent = j;
						ledgerAt = climb.arrives() + LEDGER_REACH * 0.5;
						List<float[]> down = new ArrayList<>();
						down.add(stand);
						down.add(new float[]{STAIR_X, F2, STAIR_TOP_Z});
						down.add(new float[]{STAIR_X, PLINTH, STAIR_BOTTOM_Z});
						down.add(DOOR_IN);
						down.add(DOOR_OUT);
						down.add(post(k, staying));
						Leg leave = Leg.walk(climb.arrives() + LEDGER_REACH + 0.3, clear(down), WALK, BAG, DOOR_OUT);
						mine.add(leave);
						mine.add(Leg.walk(leave.arrives(), new float[][]{post(k, staying)}, 1, IDLE, DOOR_OUT));
					} else {
						// Round the rooms photographing everything, then out to wait by the SUVs
						int n = PHOTO_SPOTS.length, first = (k - watchTeam) * 2;
						for (int s = 0; s < 2; s++) {
							float[][] spot = PHOTO_SPOTS[(first + s) % n];
							List<float[]> path = new ArrayList<>();
							path.add(at);
							if (upstairs() && s == 0) {
								path.add(new float[]{STAIR_X, F2, STAIR_TOP_Z});
								path.add(new float[]{STAIR_X, PLINTH, STAIR_BOTTOM_Z});
							}
							boolean garage = spot[0][0] > 6, terrace = spot[0][2] > FRONT;
							if (garage) {
								path.add(new float[]{5, PLINTH, -1});
								path.add(new float[]{7, PLINTH, -1});
							}
							if (terrace) {
								path.add(DOOR_IN);
								path.add(DOOR_OUT);
							} else if (at[2] > FRONT) {
								// Back in off the terrace through the door, not the glass
								path.add(DOOR_OUT);
								path.add(DOOR_IN);
							}
							path.add(spot[0]);
							Leg go = Leg.walk(t, clear(path), WALK, PHOTO, spot[1]);
							if (go.arrives() > searchEnd) break;
							mine.add(go);
							at = spot[0];
							t = go.arrives() + PHOTO_STOP;
						}
						List<float[]> leave = new ArrayList<>();
						leave.add(at);
						if (at[2] < FRONT) {
							if (at[0] > 6) {
								leave.add(new float[]{7, PLINTH, -1});
								leave.add(new float[]{5, PLINTH, -1});
							}
							leave.add(DOOR_IN);
							leave.add(DOOR_OUT);
						}
						leave.add(post(k, staying));
						Leg go = Leg.walk(Math.max(t, searchEnd + k * 0.3), clear(leave), WALK, IDLE, DOOR_OUT);
						mine.add(go);
					}
				}
			}
		}

		/** How close a passing agent comes to you: they walk round you, not past your shoulder. */
		private static final float KEEP_CLEAR = 2.2f;

		/** A path round the furniture and walls, and round you rather than past your shoulder. */
		private float[][] clear(List<float[]> path) {
			return clear(path, true);
		}

		/**
		 * A path with turns added wherever a straight stretch of it would go through the furniture or
		 * a wall, or (with {@code avoidYou}) close by you. Stairs and steps are left as they are.
		 */
		private float[][] clear(List<float[]> path, boolean avoidYou) {
			List<float[]> out = new ArrayList<>();
			out.add(path.get(0));
			for (int i = 1; i < path.size(); i++) {
				float[] a = path.get(i - 1), b = path.get(i);
				if (Math.abs(a[1] - b[1]) < 0.3f) {
					boolean round = avoidYou && Math.abs(a[1] - target[1]) < 1.5f
						&& Math.hypot(a[0] - target[0], a[2] - target[2]) >= KEEP_CLEAR && Math.hypot(b[0] - target[0], b[2] - target[2]) >= KEEP_CLEAR;
					float[] avoid = round ? target : null;
					if (!RaidNav.clear(a, b, avoid, KEEP_CLEAR)) {
						List<float[]> turns = RaidNav.route(a, b, avoid, KEEP_CLEAR);
						if (turns != null) out.addAll(turns);
						else if (avoidYou) detour(out, a, b, 0);
					}
				} else if (avoidYou) detour(out, a, b, 0);
				out.add(b);
			}
			return out.toArray(new float[0][]);
		}

		/** Adds points between {@code a} and {@code b} (not the ends) that keep the walk out of your space. */
		private void detour(List<float[]> out, float[] a, float[] b, int depth) {
			if (depth > 3) return;
			// Only on your floor, and only stretches that start and end outside your space
			if (Math.abs(a[1] - target[1]) > 1.5f || Math.abs(b[1] - target[1]) > 1.5f) return;
			double ax = a[0] - target[0], az = a[2] - target[2], dx = b[0] - a[0], dz = b[2] - a[2];
			if (Math.hypot(ax, az) < KEEP_CLEAR || Math.hypot(b[0] - target[0], b[2] - target[2]) < KEEP_CLEAR) return;
			double len2 = dx * dx + dz * dz;
			if (len2 < 1e-4) return;
			double t = Mth.clamp(-(ax * dx + az * dz) / len2, 0, 1);
			double cx = ax + dx * t, cz = az + dz * t, d = Math.hypot(cx, cz);
			if (d >= KEEP_CLEAR) return;
			// Out from you through the nearest point; straight through you, round the side the walk ends on
			double nx, nz;
			if (d > 0.05) {
				nx = cx / d;
				nz = cz / d;
			} else {
				double len = Math.sqrt(len2);
				nx = -dz / len;
				nz = dx / len;
				if (nx * (b[0] - target[0]) + nz * (b[2] - target[2]) < 0) {
					nx = -nx;
					nz = -nz;
				}
			}
			float r = KEEP_CLEAR + 0.35f;
			float[] w = {target[0] + (float) (nx * r), a[1] + (b[1] - a[1]) * (float) t, target[2] + (float) (nz * r)};
			detour(out, a, w, depth + 1);
			out.add(w);
			detour(out, w, b, depth + 1);
		}

		/** Where searcher {@code k} waits by one of the SUVs that stay. */
		private float[] post(int k, int staying) {
			int suv = departing + Math.floorMod(k, Math.max(1, staying));
			if (suv >= suvs) suv = suvs - 1;
			int side = (k / Math.max(1, staying)) % 2 == 0 ? 1 : -1;
			float[] p = carPoint(parked.get(suv), side * 1.5f, 0.6f - (k / Math.max(1, staying * 2)) * 1.1f);
			return p;
		}

		// ---------------------------------------------------------------- where everything is at a moment

		/** A spot beside a parked car: {@code side} 1 for its right, at {@code out} blocks. */
		private float[] carSide(float[] car, int side, float out) {
			return carPoint(car, side * out, 0.3f);
		}

		/** A point in a car's own space (x right, z forward), in the mansion's. */
		private float[] carPoint(float[] car, float x, float z) {
			double rad = Math.toRadians(car[3]);
			float fx = (float) -Math.sin(rad), fz = (float) Math.cos(rad), rx = (float) -Math.cos(rad), rz = (float) -Math.sin(rad);
			return new float[]{car[0] + rx * x + fx * z, car[1], car[2] + rz * x + fz * z};
		}

		/** SUV {@code i} now: x, y, z (mansion space), model yaw. The ones that stay stay parked. */
		float[] suv(int i, double t) {
			float[] p = parked.get(i);
			double arrive = i * SUV_STAGGER;
			if (i >= departing || t < depart + i * 0.8) {
				double f = Mth.clamp((t - arrive) / DRIVE_IN, 0, 1);
				// Fast up the road, braking hard at the end
				f = 1 - Math.pow(1 - f, 2.6);
				float[][] path = suvIn.get(i);
				float[] at = along(path, f * length(path, path.length));
				float facing = f >= 1 ? p[3] : heading(path, f * length(path, path.length), p[3]);
				return onGround(at, facing);
			}
			float[][] path = suvOut.get(i);
			double d = Math.min(departed(path, t - depart - i * 0.8), length(path, path.length));
			float[] at = along(path, d);
			if (d < 0.05) return onGround(at, p[3]);
			// Facing the way it's going, or the other way while it's backing up
			return onGround(at, heading(path, d, p[3]) + (reversing(path, d) ? 180 : 0));
		}

		/**
		 * How a departing SUV leaves (points x, y, z and 1 where it's reversing): backing out (the lead
		 * back down the driveway first), reversing round so its nose swings out to one side, then
		 * forward round into its own lane and off down the road. It never leaves its lane's room, so it
		 * can't hit the others.
		 */
		private float[][] departure(float[] spot, boolean lead) {
			List<float[]> out = new ArrayList<>();
			float x = spot[0], z = spot[2];
			out.add(new float[]{x, spot[1], z, 1});
			if (lead) {
				out.add(new float[]{x, PLINTH, 16, 1});
				out.add(new float[]{x, 0, 20.2f, 1});
				z = 21.6f;
				out.add(new float[]{x, 0, z, 1});
			}
			float r = 3.5f, s = lead ? -1 : 1;
			for (int a = 3; a <= 90; a += 3) {
				double th = Math.toRadians(a);
				out.add(new float[]{x - s * r + s * r * (float) Math.cos(th), 0, z + r * (float) Math.sin(th), 1});
			}
			for (int a = 3; a <= 90; a += 3) {
				double ph = Math.toRadians(a);
				out.add(new float[]{x - s * r + s * r * (float) Math.sin(ph), 0, z + 2 * r - r * (float) Math.cos(ph), 0});
			}
			out.add(new float[]{x, 0, 140, 0});
			return out.toArray(new float[0][]);
		}

		/**
		 * An SUV at {@code at} facing {@code yaw}, sat on whatever's under its wheels: x, y, z, yaw, and
		 * how far its nose is tipped up (radians) going up or down the driveway.
		 */
		private float[] onGround(float[] at, float yaw) {
			double rad = Math.toRadians(yaw);
			float fx = (float) -Math.sin(rad), fz = (float) Math.cos(rad), axle = 1.55f;
			float front = ground(at[0] + fx * axle, at[2] + fz * axle), rear = ground(at[0] - fx * axle, at[2] - fz * axle);
			return new float[]{at[0], (front + rear) / 2, at[2], yaw, (float) Math.atan2(front - rear, axle * 2)};
		}

		/** Seats in an SUV's own space: driver, front passenger, back left, back right. */
		private final float[][] seats = {{-0.45f, 0.3f}, {0.45f, 0.3f}, {-0.45f, -1.35f}, {0.45f, -1.35f}};

		/**
		 * Agent {@code j} now: x, y, z, yaw (model), aiming (1), there (1), sitting (1), what they're
		 * doing, and when they got where they're standing (for the camera flashes); null if they're
		 * still in their SUV or gone.
		 */
		float[] agent(int j, double t) {
			List<Leg> mine = legs.get(j);
			if (mine.isEmpty() || t < mine.get(0).start - 0.01) return null;
			Leg leg = mine.get(0);
			for (Leg l : mine) if (l.start <= t) leg = l;
			if (leg.activity == SEATED) {
				float[] car = suv(leg.suv, t);
				float[] at = carPoint(car, seats[leg.seat][0], seats[leg.seat][1]);
				float lift = seats[leg.seat][1] * (float) Math.sin(car.length > 4 ? car[4] : 0);
				return new float[]{at[0], car[1] + 0.05f + lift, at[2], car[3], 0, 1, 1, SEATED, 0};
			}
			if (leg.activity == ESCORT_LEFT || leg.activity == ESCORT_RIGHT) {
				double d = Math.min((t - leg.start) * leg.speed, leg.length());
				float[] at = along(escort, d);
				float heading = heading(escort, d, targetYaw);
				float[] side = offset(heading, leg.activity == ESCORT_LEFT ? -0.55f : 0.55f, -0.25f);
				return new float[]{at[0] + side[0], at[1], at[2] + side[2], heading, 0, 1, 0, IDLE, 0};
			}
			if (leg.activity == BAG && t >= leg.arrives() && grabs.containsKey(j)) return bagging(j, t);
			if (j == ledgerAgent && leg.activity == BAG && t >= leg.arrives() && t < leg.arrives() + LEDGER_REACH) {
				// Reaching over the desk for the Ledger
				float[] at = along(leg.path, leg.length());
				float reach = (float) Math.sin(Math.PI * (t - leg.arrives()) / LEDGER_REACH);
				return new float[]{at[0], at[1], at[2], lookYaw(at, LEDGER_SPOT), 0, 1, 0, BAG, -1, reach, 0};
			}
			double total = leg.length();
			double d = Math.min((t - leg.start) * leg.speed, total);
			float[] at = along(leg.path, d);
			boolean moving = d < total - 0.01;
			float facing;
			if (leg.activity == HOLD) facing = targetYaw;
			else if (moving) facing = heading(leg.path, d, targetYaw);
			else facing = lookYaw(at, leg.face != null ? leg.face : target);
			boolean aim = leg.activity == AIM;
			return new float[]{at[0], at[1], at[2], facing, aim ? 1 : 0, 1, 0, leg.activity, moving ? -1 : (float) leg.arrives()};
		}

		/**
		 * A watch team agent at the cases: walking to the next one, or stood at it reaching in. Like
		 * {@link #agent}, plus how far the arm's reaching (0 to 1) and whether it's up at the wall.
		 */
		private float[] bagging(int j, double t) {
			List<Grab> list = grabs.get(j);
			Grab g = list.getFirst();
			for (Grab x : list) if (x.walkStart() <= t) g = x;
			if (t < g.reachStart()) {
				double d = (t - g.walkStart()) * WALK;
				float[] at = along(g.approach(), d);
				return new float[]{at[0], at[1], at[2], heading(g.approach(), d, lookYaw(g.stand(), g.face())), 0, 1, 0, BAG, -1, 0, 0};
			}
			double k = Mth.clamp((t - g.reachStart()) / g.length(), 0, 1);
			float reach = (float) Math.sin(Math.PI * k);
			return new float[]{g.stand()[0], g.stand()[1], g.stand()[2], lookYaw(g.stand(), g.face()), 0, 1, 0, BAG, -1, reach, g.slots()[0] < 24 ? 1 : 0};
		}

		/** How many of the watch collection they've bagged by now. */
		int watchesTaken(double t) {
			int n = 0;
			for (double at : slotTaken) if (at <= t) n++;
			return n;
		}

		/** How far vitrine {@code v}'s lid is lifted, 0 shut to 1 fully up. */
		float lid(int v, double t) {
			float k = (float) Mth.clamp((t - lidAt[v]) / LID_LIFT, 0, 1);
			return k * k * (3 - 2 * k);
		}

		boolean watchGone(int slot, double t) {
			return slot >= 0 && slot < slotTaken.length && slotTaken[slot] <= t;
		}

		/** You (your stand-in) once they're walking you out: x, y, z, yaw, seated (1) or walking (0); null before then. */
		float[] prisoner(double t) {
			if (t < escortStart) return null;
			double d = Math.min((t - escortStart) * ESCORT_SPEED, length(escort, escort.length));
			if (t < loaded - LOAD * 0.5) {
				float[] at = along(escort, d);
				return new float[]{at[0], at[1], at[2], heading(escort, d, targetYaw), 0};
			}
			// In the back seat, riding with the SUV
			float[] car = suv(0, t);
			float[] seat = carPoint(car, 0.45f, -1.35f);
			float lift = -1.35f * (float) Math.sin(car.length > 4 ? car[4] : 0);
			return new float[]{seat[0], car[1] + 0.05f + lift, seat[2], car[3], 1};
		}

		private float[] offset(float facing, float right, float forward) {
			double rad = Math.toRadians(facing);
			float fx = (float) -Math.sin(rad), fz = (float) Math.cos(rad), rx = (float) -Math.cos(rad), rz = (float) -Math.sin(rad);
			return new float[]{rx * right + fx * forward, 0, rz * right + fz * forward};
		}

		Vec3 world(float[] p) {
			Vector3f v = new Vector3f(p[0], p[1], p[2]).rotate(new Quaternionf().rotateY((float) Math.toRadians(-yaw)));
			return origin.add(v.x, v.y, v.z);
		}

		float worldYaw(float modelYaw) {
			return modelYaw + yaw;
		}
	}

	// ---------------------------------------------------------------- paths

	private static double length(float[][] path, int points) {
		double total = 0;
		for (int i = 1; i < Math.min(points, path.length); i++) total += distance(path[i - 1], path[i]);
		return total;
	}

	private static double distance(float[] a, float[] b) {
		float dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2];
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	/** The point {@code d} along a path. */
	private static float[] along(float[][] path, double d) {
		for (int i = 1; i < path.length; i++) {
			double seg = distance(path[i - 1], path[i]);
			if (d <= seg || i == path.length - 1) {
				float f = (float) (seg <= 0 ? 1 : Math.min(1, d / seg));
				return new float[]{Mth.lerp(f, path[i - 1][0], path[i][0]), Mth.lerp(f, path[i - 1][1], path[i][1]), Mth.lerp(f, path[i - 1][2], path[i][2])};
			}
			d -= seg;
		}
		return path[path.length - 1];
	}

	/** Which way a path heads {@code d} along it (model yaw), or {@code fallback} if it's not going anywhere. */
	private static float heading(float[][] path, double d, float fallback) {
		for (int i = 1; i < path.length; i++) {
			double seg = distance(path[i - 1], path[i]);
			if (d <= seg || i == path.length - 1) {
				float dx = path[i][0] - path[i - 1][0], dz = path[i][2] - path[i - 1][2];
				if (dx * dx + dz * dz < 1e-4) return fallback;
				return (float) Math.toDegrees(Math.atan2(-dx, dz));
			}
			d -= seg;
		}
		return fallback;
	}

	/**
	 * How far along its way out a departing SUV is {@code tau} seconds in: easing back through the
	 * reversing part and stopping at its end, pulling away forward round into its lane, then
	 * speeding up down the road.
	 */
	private static double departed(float[][] path, double tau) {
		if (tau <= 0) return 0;
		int lastRev = 0;
		for (int k = 0; k < path.length; k++) if (path[k].length > 3 && path[k][3] > 0.5f) lastRev = k;
		double back = length(path, lastRev + 1), round = length(path, path.length - 1) - back;
		double backTime = Mth.clamp(back / 3.5, 2.2, 5), roundTime = 2.2;
		if (tau < backTime) return back * smooth(tau / backTime);
		tau -= backTime;
		if (tau < roundTime) return back + round * (tau / roundTime) * (tau / roundTime);
		tau -= roundTime;
		return back + round + 2 * round / roundTime * tau + 3 * tau * tau;
	}

	/** Whether the stretch of a path {@code d} along it is driven in reverse (its end point's fourth value). */
	private static boolean reversing(float[][] path, double d) {
		for (int i = 1; i < path.length; i++) {
			double seg = distance(path[i - 1], path[i]);
			if (d <= seg || i == path.length - 1) return path[i].length > 3 && path[i][3] > 0.5f;
			d -= seg;
		}
		return false;
	}

	private static float lookYaw(float[] from, float[] to) {
		return (float) Math.toDegrees(Math.atan2(-(to[0] - from[0]), to[2] - from[2]));
	}

	// ---------------------------------------------------------------- the module

	private Scene own;
	private final Map<UUID, Scene> peers = new HashMap<>();
	/** Time at home since the last random raid, in ticks. */
	private long homeTicks;
	private boolean handHidden;

	public FbiRaidModule() {
		super("FBI Raid", "The feds raid your mansion: SUVs, agents, cuffs and a perp walk. Comes now and then while you're home, or " + CommandManager.PREFIX + "fbi raid", Category.LARP);
		LivingEntityFeatureRendererRegistrationCallback.EVENT.register((type, renderer, helper, context) -> {
			if (renderer instanceof AvatarRenderer<?> player) helper.register(new GearLayer(asContext(player)));
		});
	}

	@SuppressWarnings("unchecked")
	private static RenderLayerParent<AvatarRenderState, PlayerModel> asContext(AvatarRenderer<?> renderer) {
		return (RenderLayerParent<AvatarRenderState, PlayerModel>) (Object) renderer;
	}

	@Override
	public String getInfo() {
		if (own != null) return "Raided";
		return frozen.get() ? "Frozen" : null;
	}

	/** Whether your bank and crypto are frozen right now (the phone and Ledger show it). */
	public static boolean assetsFrozen() {
		FbiRaidModule module = FemboyWare.getModules() == null ? null : FemboyWare.getModules().get(FbiRaidModule.class);
		if (module == null) return false;
		module.checkRelease();
		return module.frozen.get();
	}

	/**
	 * Lifts the freeze once its time's up. Frozen from an earlier session with no time set, the clock
	 * starts now. Not while you're still in the middle of a raid.
	 */
	private void checkRelease() {
		if (!frozen.get() || own != null || mc.player == null) return;
		// Frozen from before a restart: the raid's over, everything's back as it was
		if (releaseAt == 0) {
			frozen.set(false);
			return;
		}
		if (System.currentTimeMillis() >= releaseAt) release();
	}

	/** The freeze lifted: your accounts open again, the tape comes down, and what they took comes back. */
	private void release() {
		frozen.set(false);
		releaseAt = 0;
		LarpSave.restore();
		Mc.addChat(net.minecraft.network.chat.Component.literal("<Lawyer> Good news. The judge lifted the freeze; your accounts are open again and they're bringing everything back."));
	}

	/** For LarpSave: while you're being raided or frozen, nothing's saved, so it all comes back as it was before. */
	public boolean holdsSaves() {
		return own != null || frozen.get();
	}

	/** Handles {@code .fbi <raid|stop|release>}. */
	public void command(String[] args) {
		String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "help";
		switch (action) {
			case "raid", "start", "now" -> {
				// The plug's text first, then a few seconds to sweat before the convoy turns up
				String problem = raidDueAt > 0 ? "They're already on their way" : cantRaid();
				if (problem != null) ChatUtil.info(problem);
				else {
					if (!isEnabled()) setEnabled(true);
					tipOff();
					raidDueAt = System.currentTimeMillis() + WARNED_LEAD_MS;
				}
			}
			case "stop", "end", "abort" -> {
				finish(false);
				ChatUtil.info("Raid called off");
			}
			case "release", "bail", "free" -> release();
			default -> ChatUtil.info("Usage: " + CommandManager.PREFIX + "fbi <raid|stop|release>");
		}
	}

	private SoundInstance song;

	/** A song over your raid: Weiland's "Bitcoin". */
	private void playSong() {
		if (!music.get()) return;
		stopSong();
		song = new net.minecraft.client.resources.sounds.SimpleSoundInstance(Identifier.fromNamespaceAndPath("femboyware", "emote.bitcoin"), SoundSource.MASTER, 0.8f, 1,
			SoundInstance.createUnseededRandom(), false, 0, SoundInstance.Attenuation.NONE, 0, 0, 0, true);
		mc.getSoundManager().play(song);
	}

	private void stopSong() {
		if (song != null) mc.getSoundManager().stop(song);
		song = null;
	}

	/** Why a raid can't start on you now, or null if it can. */
	private String cantRaid() {
		if (mc.player == null) return "Not in a world";
		if (own != null) return "You're already being raided";
		MansionModule mansion = FemboyWare.getModules().get(MansionModule.class);
		Vec3 origin = mansion.origin();
		if (!mansion.isEnabled() || origin == null) return "The FBI raids your mansion: build it first (" + CommandManager.PREFIX + "mansion spawn)";
		float[] target = toModel(origin, mansion.facing(), mc.player.position());
		if (Math.abs(target[0]) > 40 || Math.abs(target[2]) > 40) return "You need to be at your mansion";
		return null;
	}

	/** Starts a raid on your mansion now; null if it started, or why it can't. */
	private String start() {
		String problem = cantRaid();
		if (problem != null) return mc.player == null ? null : problem;
		MansionModule mansion = FemboyWare.getModules().get(MansionModule.class);
		Vec3 origin = mansion.origin();
		float[] target = toModel(origin, mansion.facing(), mc.player.position());
		if (!isEnabled()) setEnabled(true);
		// On the mansion's floor, not the ground under it (lift you up onto it if you'd sunk through)
		boolean house = target[0] > -HX && target[0] < HX && target[2] > BACK && target[2] < FRONT;
		if (house && target[1] > F2 - 1.5f) target[1] = F2;
		else if (Math.abs(target[0]) < 18 && target[2] > -16 && target[2] < 16 && target[1] < PLINTH + 1) target[1] = PLINTH;
		Vec3 floor = mansion.world(target[0], target[1], target[2]);
		if (mc.player.getY() < floor.y - 0.05) {
			mc.player.setPos(mc.player.getX(), floor.y, mc.player.getZ());
			mc.player.setDeltaMovement(Vec3.ZERO);
		}
		float targetYaw = mc.player.getYRot() - mansion.facing();
		own = new Scene(origin, mansion.facing(), target, targetYaw, System.nanoTime(), agentCount.getInt(), suvCount.getInt(),
			mc.player.getGameProfile().name(), mc.player.getUUID(), true, System.currentTimeMillis(), OWN_IDS);
		own.yourSkin = mc.player.getSkin();
		playSong();
		homeTicks = 0;
		return null;
	}

	private static float[] toModel(Vec3 origin, float yaw, Vec3 world) {
		Vector3f v = new Vector3f((float) (world.x - origin.x), (float) (world.y - origin.y), (float) (world.z - origin.z))
			.rotate(new Quaternionf().rotateY((float) Math.toRadians(yaw)));
		return new float[]{v.x, v.y, v.z};
	}

	@Override
	protected void onDisable() {
		finish(false);
	}

	@Override
	public void onTick() {
		checkRelease();
		if (own != null) {
			if (mc.level == null || own.actors.stream().anyMatch(a -> a.level() != mc.level) && !own.actors.isEmpty()) {
				finish(false);
				return;
			}
			tickScene(own);
			if (held() && mc.player != null) mc.player.setDeltaMovement(0, Math.min(0, mc.player.getDeltaMovement().y), 0);
			double t = own.elapsed();
			if (t >= own.end + FADE + SEIZED_SHOWN) finish(true);
			else if (t >= own.depart + DEPART_LENGTH - 3 && !own.said.contains("seize")) {
				own.said.add("seize");
				if (seize.get()) seizeAssets();
			}
			return;
		}
		// A tipped-off raid arrives
		if (raidDueAt > 0 && System.currentTimeMillis() >= raidDueAt) {
			raidDueAt = 0;
			start();
			return;
		}
		if (raidDueAt > 0) return;
		// Random raids, only while you're home
		if (!randomRaids.get() || mc.player == null) return;
		MansionModule mansion = FemboyWare.getModules().get(MansionModule.class);
		if (!mansion.isEnabled() || mansion.origin() == null) return;
		float[] at = toModel(mansion.origin(), mansion.facing(), mc.player.position());
		boolean home = at[0] > -HX && at[0] < HX && at[2] > BACK && at[2] < FRONT && at[1] > -1 && at[1] < ROOF;
		if (!home) return;
		homeTicks++;
		double perTick = 1.0 / (everyMinutes.get() * 60 * 20);
		if (homeTicks > 20 * 60 && Math.random() < perTick) {
			// The plug hears first: the raid comes a little after his text
			tipOff();
			raidDueAt = System.currentTimeMillis() + TIP_LEAD_MS;
		}
	}

	/** When a raid the plug warned about is due, or 0. */
	private long raidDueAt;
	private static final long TIP_LEAD_MS = 20_000;
	/** How long after the plug's text a raid you called in arrives. */
	private static final long WARNED_LEAD_MS = 6_000;

	/** The plug texts you on Telegram that the feds are coming. */
	private void tipOff() {
		PhoneModule phone = FemboyWare.getModules().get(PhoneModule.class);
		if (phone != null && phone.isEnabled()) phone.receive("Plug", "the feds are coming");
	}

	/** Moves everyone in a scene to where they are at this moment, and plays its sounds and lines. */
	private void tickScene(Scene scene) {
		double t = scene.elapsed();
		// Agents
		for (int j = 0; j < scene.agents; j++) {
			float[] a = scene.agent(j, t);
			RemotePlayer actor = actor(scene, j);
			if (a == null) {
				if (actor != null) remove(scene, actor);
				continue;
			}
			if (actor == null) actor = spawn(scene, j, a);
			place(actor, scene.world(a), scene.worldYaw(a[3]), aimPitch(scene, a));
			keepVisible(actor);
		}
		if (scene.you != null) keepVisible(scene.you);
		if (scene.mine && mc.player != null && scene.inside()) keepVisible(mc.player);
		// Your stand-in on the perp walk
		float[] p = scene.prisoner(t);
		if (p != null) {
			if (scene.you == null) {
				scene.you = new RemotePlayer(mc.level, new com.mojang.authlib.GameProfile(UUID.nameUUIDFromBytes(("femboyware raid " + scene.owner).getBytes()), scene.name));
				scene.you.setId(scene.firstId - 99);
				Vec3 at = scene.world(p);
				scene.you.snapTo(at.x, at.y, at.z, scene.worldYaw(p[3]), 0);
				mc.level.addEntity(scene.you);
				if (!scene.mine) {
					Player real = mc.level.getPlayerByUUID(scene.owner);
					if (real instanceof AbstractClientPlayer client) scene.yourSkin = client.getSkin();
					else scene.yourSkin = dev.femboyware.util.AccountSkins.get(scene.name);
				}
			}
			place(scene.you, scene.world(p), scene.worldYaw(p[3]), 8);
			// The real one stays where they were, out of sight, while their stand-in is walked out
			Player real = scene.mine ? mc.player : mc.level.getPlayerByUUID(scene.owner);
			if (real != null) real.setInvisible(true);
		}
		// The siren on the lead SUV, from when it's coming up the road until it's gone
		if (scene.siren == null && t < scene.end) {
			scene.siren = new Siren(scene);
			mc.getSoundManager().play(scene.siren);
		}
		// One-off sounds and lines, each once (joining someone's raid partway, the ones already past stay quiet)
		scene.silent = !scene.mine && !scene.primed;
		scene.primed = true;
		Vec3 door = scene.world(DOOR_OUT);
		Vec3 here = scene.world(scene.target);
		once(scene, "breach", t >= BREACH && scene.inside(), () -> sound(door, LarpSounds.FBI_BREACH, 1.4f));
		once(scene, "bang", t >= BANG && scene.inside() && flashbang.get(), () -> {
			sound(scene.world(DOOR_IN), LarpSounds.FBI_FLASHBANG, 2f);
			if (scene.mine && !scene.upstairs()) flashAt = System.currentTimeMillis();
		});
		once(scene, "down", t >= handsUpAt(scene), () -> say(scene, "HANDS UP!"));
		// The photographers' cameras going off while they're stopped in a room
		for (int j = 0; j < scene.agents; j++) {
			float[] a = scene.agent(j, t);
			if (a == null || a[7] != PHOTO || a[8] < 0) continue;
			double dwell = t - a[8] - (j * 0.37) % FLASH_EVERY;
			if (dwell < 0.4) continue;
			long shot = (long) (dwell / FLASH_EVERY);
			int id = scene.firstId - j;
			Vec3 eye = scene.world(a).add(0, 1.55, 0);
			// Every flash shows, but only the odd one is heard, and quietly: a room of cameras shouldn't be a wall of clicks
			boolean heard = shot % 3 == 0 && j % 2 == 0;
			once(scene, "f" + j + ":" + (long) (a[8] * 10) + ":" + shot, true, () -> {
				scene.flashes.put(id, System.currentTimeMillis());
				if (heard) sound(eye, LarpSounds.SHUTTER, 0.3f);
			});
		}
		once(scene, "cuffs", t >= scene.cuffStart + CUFF_WALK + CUFFS_AT, () -> {
			sound(here, LarpSounds.FBI_CUFFS, 1);
			say(scene, "YOU'RE UNDER ARREST FOR FRAUD AND EXTORTION");
		});
		once(scene, "door", t >= scene.loaded, () -> sound(scene.world(scene.suv(0, t)), LarpSounds.FBI_DOOR, 1.2f));
	}

	private void once(Scene scene, String key, boolean when, Runnable action) {
		if (when && scene.said.add(key) && !scene.silent) action.run();
	}

	/** An agent's line, heard by you, or by anyone watching close enough. */
	private void say(Scene scene, String line) {
		if (!scene.mine && (mc.player == null || mc.player.position().distanceTo(scene.world(scene.target)) > 48)) return;
		Mc.addChat(net.minecraft.network.chat.Component.literal("<FBI> " + line));
	}

	private void sound(Vec3 at, SoundEvent sound, float volume) {
		if (mc.level != null) mc.level.playLocalSound(at.x, at.y, at.z, sound, SoundSource.NEUTRAL, volume, 1, false);
	}

	/** Agents aim at your head, so their pitch looks there. */
	private float aimPitch(Scene scene, float[] a) {
		if (a[4] < 0.5f) return 0;
		double dx = scene.target[0] - a[0], dz = scene.target[2] - a[2], dy = scene.target[1] + 1.5 - (a[1] + 1.62);
		return (float) Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
	}

	private RemotePlayer actor(Scene scene, int j) {
		int id = scene.firstId - j;
		for (RemotePlayer a : scene.actors) if (a.getId() == id) return a;
		return null;
	}

	private RemotePlayer spawn(Scene scene, int j, float[] a) {
		String name = "SA " + SURNAMES[(int) ((scene.seed + j) % SURNAMES.length + SURNAMES.length) % SURNAMES.length];
		RemotePlayer actor = new RemotePlayer(mc.level, new com.mojang.authlib.GameProfile(UUID.nameUUIDFromBytes(("femboyware fbi " + scene.seed + j).getBytes()), name));
		actor.setId(scene.firstId - j);
		Vec3 at = scene.world(a);
		actor.snapTo(at.x, at.y, at.z, scene.worldYaw(a[3]), 0);
		actor.setOldPosAndRot();
		mc.level.addEntity(actor);
		scene.actors.add(actor);
		return actor;
	}

	private void remove(Scene scene, RemotePlayer actor) {
		if (mc.level != null) mc.level.removeEntity(actor.getId(), Entity.RemovalReason.DISCARDED);
		scene.actors.remove(actor);
	}

	/**
	 * For CameraMixin, every frame: everyone sat in an SUV put exactly in their seat where the car's
	 * drawn this frame, so they ride in it rather than trailing behind a tick late.
	 */
	public void placeFrame() {
		if (mc.level == null) return;
		for (Scene s : allScenes()) {
			double t = s.elapsed();
			for (int j = 0; j < s.agents; j++) {
				float[] a = s.agent(j, t);
				if (a == null || (int) a[7] != SEATED) continue;
				RemotePlayer actor = actor(s, j);
				if (actor != null) pin(actor, s.world(a), s.worldYaw(a[3]));
			}
			float[] p = s.prisoner(t);
			if (p != null && p[4] > 0.5f && s.you != null) pin(s.you, s.world(p), s.worldYaw(p[3]));
		}
	}

	private static void pin(RemotePlayer e, Vec3 at, float yaw) {
		e.setPos(at.x, at.y, at.z);
		e.xo = e.xOld = at.x;
		e.yo = e.yOld = at.y;
		e.zo = e.zOld = at.z;
		e.setYRot(yaw);
		e.yRotO = yaw;
		e.yBodyRot = e.yBodyRotO = yaw;
		e.yHeadRot = e.yHeadRotO = yaw;
	}

	private static java.lang.reflect.Method cullTimeout, cullSet;
	private static boolean cullLooked;

	/**
	 * Keeps someone in the raid drawn every frame: never frustum culled, and (with the Entity Culling
	 * mod) never hidden by its block raytraces, which lag behind the camera cuts and think the mansion's
	 * real-world terrain hides them.
	 */
	private static void keepVisible(Entity entity) {
		if (!cullLooked) {
			cullLooked = true;
			try {
				Class<?> cullable = Class.forName("dev.tr7zw.entityculling.access.Cullable");
				cullTimeout = cullable.getMethod("setTimeout");
				cullSet = cullable.getMethod("setCulled", boolean.class);
			} catch (Throwable ignored) {
				// Entity Culling isn't installed
			}
		}
		if (cullTimeout == null || !cullTimeout.getDeclaringClass().isInstance(entity)) return;
		try {
			cullSet.invoke(entity, false);
			cullTimeout.invoke(entity);
		} catch (Throwable ignored) {
			// A different Entity Culling version: nothing to do
		}
	}

	/** Moves an actor the way the game moves other players, so they walk and run rather than slide. */
	private static void place(RemotePlayer actor, Vec3 pos, float yaw, float pitch) {
		//? if >=26.3 {
		/*actor.getInterpolation().interpolateTo(net.minecraft.world.entity.PositionPath.of(pos), yaw, pitch, true);
		*///?} else {
		actor.getInterpolation().interpolateTo(pos, yaw, pitch);
		//?}
		actor.lerpHeadTo(yaw, 3);
	}

	/** Ends a raid of yours: everyone goes, you're back, and if it ran its course, your things are gone. */
	private void finish(boolean ranItsCourse) {
		if (own == null) return;
		stopSong();
		clear(own);
		if (mc.player != null) mc.player.setInvisible(false);
		showHand(true);
		own = null;
	}

	private void clear(Scene scene) {
		for (RemotePlayer a : new ArrayList<>(scene.actors)) remove(scene, a);
		if (scene.you != null && mc.level != null) mc.level.removeEntity(scene.you.getId(), Entity.RemovalReason.DISCARDED);
		scene.you = null;
		if (scene.siren != null) scene.siren.done = true;
		if (!scene.mine && mc.level != null) {
			Player real = mc.level.getPlayerByUUID(scene.owner);
			if (real != null) real.setInvisible(false);
		}
	}

	/** Everything you own, taken: cars towed, the yacht, jet and helicopter impounded, and the accounts frozen. */
	private void seizeAssets() {
		frozen.set(true);
		releaseAt = System.currentTimeMillis() + (long) (releaseMinutes.get() * 60_000);
		var modules = FemboyWare.getModules();
		modules.get(CarsModule.class).command(new String[]{"car", "clear"});
		if (modules.get(YachtModule.class).isEnabled()) modules.get(YachtModule.class).command(new String[]{"yacht", "remove"});
		if (modules.get(JetModule.class).isEnabled()) modules.get(JetModule.class).command(new String[]{"jet", "remove"});
		if (modules.get(HelicopterModule.class).isEnabled()) modules.get(HelicopterModule.class).command(new String[]{"heli", "remove"});
	}

	// ---------------------------------------------------------------- Client Sync

	/** Your raid for Client Sync to send, or null: where, who, and how far in it is. */
	public JsonObject shareState() {
		Scene scene = own;
		if (!isEnabled() || scene == null || scene.elapsed() > scene.end) return null;
		JsonObject o = new JsonObject();
		JsonArray origin = new JsonArray();
		for (double v : new double[]{scene.origin.x, scene.origin.y, scene.origin.z, scene.yaw}) origin.add(v);
		o.add("o", origin);
		JsonArray target = new JsonArray();
		for (double v : new double[]{scene.target[0], scene.target[1], scene.target[2], scene.targetYaw}) target.add(Math.round(v * 100) / 100.0);
		o.add("t", target);
		o.addProperty("e", Math.round(scene.elapsed() * 1000) / 1000.0);
		o.addProperty("s", scene.seed);
		o.addProperty("a", scene.agents);
		o.addProperty("v", scene.suvs);
		o.addProperty("n", scene.name);
		return o;
	}

	/** From Client Sync each tick: everyone's raids going on here, by whose they are, with how long ago each was heard. */
	public void syncPeers(Map<UUID, JsonObject> raids, Map<UUID, Double> secondsSince) {
		for (var it = peers.entrySet().iterator(); it.hasNext(); ) {
			var entry = it.next();
			Scene scene = entry.getValue();
			if (raids.containsKey(entry.getKey()) && scene.elapsed() <= scene.end + 1) continue;
			clear(scene);
			it.remove();
		}
		int slot = 0;
		for (var entry : raids.entrySet()) {
			slot++;
			Scene scene = peers.get(entry.getKey());
			if (scene == null) {
				scene = parse(entry.getKey(), entry.getValue(), secondsSince.getOrDefault(entry.getKey(), 0.0), PEER_IDS - slot * 120);
				if (scene == null) continue;
				peers.put(entry.getKey(), scene);
			}
			tickScene(scene);
		}
	}

	private Scene parse(UUID owner, JsonObject o, double since, int firstId) {
		try {
			JsonArray origin = o.getAsJsonArray("o"), target = o.getAsJsonArray("t");
			double e = o.get("e").getAsDouble() + Math.max(0, since);
			int agents = Mth.clamp(o.get("a").getAsInt(), 1, 16), suvs = Mth.clamp(o.get("v").getAsInt(), 1, 8);
			String name = o.has("n") ? o.get("n").getAsString() : "them";
			if (name.length() > 16) name = name.substring(0, 16);
			Vec3 at = new Vec3(origin.get(0).getAsDouble(), origin.get(1).getAsDouble(), origin.get(2).getAsDouble());
			float[] t = {target.get(0).getAsFloat(), target.get(1).getAsFloat(), target.get(2).getAsFloat()};
			if (!Double.isFinite(at.x) || Math.abs(t[0]) > 64 || Math.abs(t[2]) > 64) return null;
			return new Scene(at, origin.get(3).getAsFloat(), t, target.get(3).getAsFloat(), o.get("s").getAsLong(), agents, suvs, name, owner, false,
				System.currentTimeMillis() - (long) (e * 1000), firstId);
		} catch (RuntimeException ex) {
			return null;
		}
	}

	public void clearPeers() {
		syncPeers(Map.of(), Map.of());
	}

	// ---------------------------------------------------------------- what you see

	/** Whether this player is one of the raid's actors (agents or a stand-in), for hiding their name tags. */
	public boolean isActor(Entity entity) {
		if (entity == null) return false;
		int id = entity.getId();
		return id <= OWN_IDS && id > OWN_IDS - 200 || id <= PEER_IDS && id > PEER_IDS - 2000;
	}

	/** For the skin mixin: agents wear raid jackets, a stand-in wears the arrested player's own skin. */
	public PlayerSkin skinFor(AbstractClientPlayer player, PlayerSkin textures) {
		Scene scene = sceneOf(player);
		if (scene == null) return textures;
		if (player == scene.you) return scene.yourSkin != null ? scene.yourSkin : textures;
		return AGENT_SKINS[Math.floorMod(player.getId() + (int) scene.seed, AGENT_SKINS.length)];
	}

	/** Whether {@code id} is your stand-in being walked out in your raid, who wears what you wear. */
	public boolean isYourStandIn(int id) {
		return own != null && own.you != null && own.you.getId() == id;
	}

	private Scene sceneOf(Entity entity) {
		if (!isActor(entity)) return null;
		if (own != null && (own.actors.contains(entity) || own.you == entity)) return own;
		for (Scene scene : peers.values()) if (scene.actors.contains(entity) || scene.you == entity) return scene;
		return null;
	}

	/** Whether an agent's aiming (by render state id), and the stand-in's pose: 1 cuffed walking, 2 cuffed sitting. */
	private boolean aiming(int id) {
		Scene scene = null;
		if (own != null && id <= own.firstId && id > own.firstId - 99) scene = own;
		else for (Scene s : peers.values()) if (id <= s.firstId && id > s.firstId - 99) scene = s;
		if (scene == null) return false;
		float[] a = scene.agent(scene.firstId - id, scene.elapsed());
		return a != null && a[4] > 0.5f;
	}

	/** What an agent's doing right now (see IDLE, AIM, PHOTO...), or -1 if it isn't one. */
	private int activityOf(int id) {
		for (Scene s : allScenes()) {
			if (id <= s.firstId && id > s.firstId - 99) {
				float[] a = s.agent(s.firstId - id, s.elapsed());
				return a == null ? -1 : (int) a[7];
			}
		}
		return -1;
	}

	/** For the mansion's watch room: how many watches are gone from the cases of whoever's mansion it's drawing (null: yours). */
	public int watchesTaken(UUID owner) {
		if (owner == null) {
			if (own != null) return own.watchesTaken(own.elapsed());
			return frozen.get() ? MansionModule.WATCH_COUNT : 0;
		}
		Scene scene = peers.get(owner);
		return scene == null ? 0 : scene.watchesTaken(scene.elapsed());
	}

	/** For the mansion's desk: whether your Ledger's been taken (null owner: yours). */
	public boolean ledgerGone(UUID owner) {
		if (owner == null) {
			if (own != null) return own.elapsed() >= own.ledgerAt;
			return frozen.get();
		}
		Scene scene = peers.get(owner);
		return scene != null && scene.elapsed() >= scene.ledgerAt;
	}

	/** How long a vitrine's lid takes to lift, and how far ahead of the first reach in it starts. */
	private static final double LID_LIFT = 0.6, LID_LEAD = 0.7;

	/** Which vitrine a watch slot's in (0 or 1), or -1 for the winder wall. */
	static int vitrineOf(int slot) {
		return slot < 24 ? -1 : slot < 42 ? 0 : 1;
	}

	/** For the mansion's watch room: how far vitrine {@code v}'s lid is up, 0..1 (null owner: yours). */
	public float caseLid(UUID owner, int v) {
		if (owner == null) {
			if (own != null) return own.lid(v, own.elapsed());
			return frozen.get() ? 1 : 0;
		}
		Scene scene = peers.get(owner);
		return scene == null ? 0 : scene.lid(v, scene.elapsed());
	}

	/** For the mansion's watch room: whether watch {@code slot} has gone from its case (null owner: yours). */
	public boolean watchGone(UUID owner, int slot) {
		if (owner == null) {
			if (own != null) return own.watchGone(slot, own.elapsed());
			return frozen.get();
		}
		Scene scene = peers.get(owner);
		return scene != null && scene.watchGone(slot, scene.elapsed());
	}

	/** An agent right now (see Scene.agent), or null. */
	private float[] agentNow(int id) {
		for (Scene s : allScenes()) {
			if (id <= s.firstId && id > s.firstId - 99) return s.agent(s.firstId - id, s.elapsed());
		}
		return null;
	}

	/** When they're on you and you put your hands up. */
	private static double handsUpAt(Scene scene) {
		return Math.max(BANG + 0.5, scene.firstArrive - 1.5);
	}

	/**
	 * You, while they've got you: hands straight up once they're through the door and on you, held
	 * there at gunpoint, then pulled down behind your back as the cuffs go on.
	 */
	private static void poseYou(PlayerModel model, Scene scene) {
		double t = scene.elapsed();
		double up = handsUpAt(scene), cuffed = scene.cuffStart + CUFF_WALK + CUFFS_AT - 0.3;
		if (t < up || t >= scene.escortStart) return;
		float raise = (float) Mth.clamp((t - up) / 0.45, 0, 1), down = (float) Mth.clamp((t - cuffed) / 0.6, 0, 1);
		raise = raise * raise * (3 - 2 * raise);
		down = down * down * (3 - 2 * down);
		// Up: straight overhead, tipped a touch outward so they clear the sides of your head
		float upX = -(float) Math.PI, upZ = -0.14f;
		// Cuffed: behind the back, wrists together
		float backX = 0.55f, backY = 0.25f, backZ = 0.35f;
		float x = Mth.lerp(down, upX, backX), y = Mth.lerp(down, 0, backY), z = Mth.lerp(down, upZ, backZ);
		model.rightArm.xRot = Mth.lerp(raise, model.rightArm.xRot, x);
		model.rightArm.yRot = Mth.lerp(raise, model.rightArm.yRot, -y);
		model.rightArm.zRot = Mth.lerp(raise, model.rightArm.zRot, z);
		model.leftArm.xRot = Mth.lerp(raise, model.leftArm.xRot, x);
		model.leftArm.yRot = Mth.lerp(raise, model.leftArm.yRot, y);
		model.leftArm.zRot = Mth.lerp(raise, model.leftArm.zRot, -z);
		if (down > 0) model.head.xRot = Mth.lerp(down, model.head.xRot, 0.35f);
	}

	/** Whether an agent's sitting in an SUV. */
	private boolean agentSeated(int id) {
		for (Scene s : allScenes()) {
			if (id <= s.firstId && id > s.firstId - 99) {
				float[] a = s.agent(s.firstId - id, s.elapsed());
				return a != null && a.length > 6 && a[6] > 0.5f;
			}
		}
		return false;
	}

	private int prisonerPose(int id) {
		for (Scene s : allScenes()) {
			if (s.you != null && s.you.getId() == id) {
				float[] p = s.prisoner(s.elapsed());
				return p == null ? 0 : p[4] > 0.5f ? 2 : 1;
			}
		}
		return 0;
	}

	private List<Scene> allScenes() {
		List<Scene> all = new ArrayList<>(peers.values());
		if (own != null) all.add(own);
		return all;
	}

	/** Read by PlayerModelMixin: agents aim with both hands; the arrested walk with their hands cuffed behind them, or sit. */
	public void pose(PlayerModel model, AvatarRenderState state) {
		if (own != null && mc.player != null && state.id == mc.player.getId()) {
			poseYou(model, own);
			return;
		}
		// Someone else being raided, seen through Client Sync: their hands go up and get cuffed too
		if (mc.level != null && state.id >= 0) {
			for (Scene s : peers.values()) {
				Player owner = mc.level.getPlayerByUUID(s.owner);
				if (owner != null && owner.getId() == state.id) {
					poseYou(model, s);
					return;
				}
			}
		}
		if (state.id > OWN_IDS || state.id <= PEER_IDS - 2000) return;
		if (aiming(state.id)) {
			model.rightArm.xRot = model.head.xRot - 1.5f;
			model.rightArm.yRot = model.head.yRot - 0.12f;
			model.rightArm.zRot = 0;
			model.leftArm.xRot = model.head.xRot - 1.45f;
			model.leftArm.yRot = model.head.yRot + 0.5f;
			model.leftArm.zRot = 0;
			return;
		}
		int activity = activityOf(state.id);
		if (activity == PHOTO) {
			model.rightArm.xRot = model.head.xRot - 1.55f;
			model.rightArm.yRot = model.head.yRot - 0.35f;
			model.rightArm.zRot = 0;
			model.leftArm.xRot = model.head.xRot - 1.45f;
			model.leftArm.yRot = model.head.yRot + 0.55f;
			model.leftArm.zRot = 0;
			return;
		}
		if (activity == BAG) {
			// The evidence bag held low in the left hand, the right reaching into the case and back
			float[] a = agentNow(state.id);
			float reach = a != null && a.length > 9 ? a[9] : 0;
			boolean wall = a != null && a.length > 10 && a[10] > 0.5f;
			model.rightArm.xRot = -0.3f - reach * (wall ? 1.9f : 1.2f);
			model.rightArm.yRot = -0.05f;
			model.rightArm.zRot = 0;
			model.leftArm.xRot = -0.35f;
			model.leftArm.yRot = 0.1f;
			model.leftArm.zRot = -0.05f;
			model.head.xRot = wall ? -0.15f : 0.45f;
			return;
		}
		if (activity == CARRY || activity == HOLD) {
			float reach = activity == CARRY ? -0.95f : activity == BAG ? -0.75f : -0.6f;
			model.rightArm.xRot = reach;
			model.leftArm.xRot = reach;
			model.rightArm.yRot = activity == HOLD ? -0.2f : -0.12f;
			model.leftArm.yRot = activity == HOLD ? 0.2f : 0.12f;
			model.rightArm.zRot = 0;
			model.leftArm.zRot = 0;
			return;
		}
		if (agentSeated(state.id)) {
			model.rightLeg.xRot = -1.45f;
			model.leftLeg.xRot = -1.45f;
			model.rightLeg.yRot = 0.1f;
			model.leftLeg.yRot = -0.1f;
			model.rightArm.xRot = -0.55f;
			model.leftArm.xRot = -0.55f;
			return;
		}
		int prisoner = prisonerPose(state.id);
		if (prisoner == 0) return;
		model.rightArm.xRot = 0.55f;
		model.rightArm.yRot = -0.25f;
		model.rightArm.zRot = 0.35f;
		model.leftArm.xRot = 0.55f;
		model.leftArm.yRot = 0.25f;
		model.leftArm.zRot = -0.35f;
		model.head.xRot = 0.35f;
		if (prisoner == 2) {
			model.rightLeg.xRot = -1.45f;
			model.leftLeg.xRot = -1.45f;
			model.rightLeg.yRot = 0.1f;
			model.leftLeg.yRot = -0.1f;
		}
	}

	/** Pistols in the agents' hands while they aim, and cuffs on the arrested. */
	private class GearLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
		GearLayer(RenderLayerParent<AvatarRenderState, PlayerModel> context) {
			super(context);
		}

		@Override
		public void submit(PoseStack matrices, SubmitNodeCollector queue, int light, AvatarRenderState state, float headYaw, float headPitch) {
			if (state.id > OWN_IDS || state.id <= PEER_IDS - 2000) return;
			boolean aim = aiming(state.id);
			boolean cuffed = prisonerPose(state.id) > 0;
			int activity = activityOf(state.id);
			if (activity == PHOTO) {
				matrices.pushPose();
				getParentModel().rightArm.translateAndRotate(matrices);
				queue.submitCustomGeometry(matrices, layer(), (pose, c) -> {
					cuboid(pose, c, light, -0.1f, 0.56f, -0.22f, 0.26f, 0.72f, -0.02f, SLIDE);
					cuboid(pose, c, light, 0.0f, 0.72f, -0.18f, 0.14f, 0.82f, -0.05f, FRAME);
					cuboid(pose, c, light, 0.02f, 0.821f, -0.165f, 0.12f, 0.823f, -0.065f, BORE);
				});
				matrices.popPose();
				return;
			}
			if (activity == BAG) {
				// A clear evidence bag in the left hand, filling up with watches
				matrices.pushPose();
				getParentModel().leftArm.translateAndRotate(matrices);
				queue.submitCustomGeometry(matrices, bagLayer(), (pose, c) -> cuboid(pose, c, light, -0.04f, 0.6f, -0.16f, 0.16f, 1.08f, 0.16f, FULL));
				matrices.popPose();
				return;
			}
			if (activity == CARRY) {
				// An evidence box held out in both hands
				matrices.pushPose();
				getParentModel().rightArm.translateAndRotate(matrices);
				queue.submitCustomGeometry(matrices, layer(), (pose, c) -> cuboid(pose, c, light, -0.05f, 0.55f, -0.5f, 0.75f, 0.95f, 0.05f, BOX));
				matrices.popPose();
				return;
			}
			if (!aim && !cuffed) return;
			if (aim) {
				matrices.pushPose();
				getParentModel().rightArm.translateAndRotate(matrices);
				queue.submitCustomGeometry(matrices, layer(), (pose, c) -> drawPistol(pose, c, light));
				matrices.popPose();
			}
			if (cuffed) {
				for (boolean right : new boolean[]{true, false}) {
					matrices.pushPose();
					(right ? getParentModel().rightArm : getParentModel().leftArm).translateAndRotate(matrices);
					queue.submitCustomGeometry(matrices, layer(), (pose, c) -> cuboid(pose, c, light, -0.15f, 0.55f, -0.15f, 0.15f, 0.62f, 0.15f, METAL));
					matrices.popPose();
				}
			}
		}
	}

	private static net.minecraft.client.renderer.rendertype.RenderType layer() {
		//? if >=26.1 {
		/*return net.minecraft.client.renderer.rendertype.RenderTypes.entityCutout(PISTOL);
		*///?} else {
		return net.minecraft.client.renderer.rendertype.RenderTypes.entityCutoutNoCull(PISTOL);
		//?}
	}

	private static final Identifier EVIDENCE_BAG = Identifier.fromNamespaceAndPath("femboyware", "textures/larp/evidence_bag.png");
	private static final float[] FULL = {0, 0, 1, 1};

	private static net.minecraft.client.renderer.rendertype.RenderType bagLayer() {
		//? if >=26.1 {
		/*return net.minecraft.client.renderer.rendertype.RenderTypes.entityCutout(EVIDENCE_BAG);
		*///?} else {
		return net.minecraft.client.renderer.rendertype.RenderTypes.entityCutoutNoCull(EVIDENCE_BAG);
		//?}
	}

	private static final float[] SLIDE = {0, 0, 0.5f, 0.5f}, FRAME = {0.5f, 0, 1, 0.5f}, METAL = {0, 0.5f, 0.5f, 1}, BORE = {0.75f, 0.75f, 1, 1};
	/** A corner of the pistol texture painted cardboard brown (see textures/larp/pistol.png). */
	private static final float[] BOX = {0.5f, 0.5f, 0.75f, 0.75f};

	/**
	 * Right arm space, arm held out: +y runs forward along the arm to the hand, -z is up. A compact
	 * pistol: slide on top pointing forward, grip down into the hand, sights and the muzzle.
	 */
	private static void drawPistol(PoseStack.Pose pose, VertexConsumer c, int light) {
		cuboid(pose, c, light, -0.05f, 0.58f, -0.2f, 0.05f, 0.92f, -0.1f, SLIDE);
		cuboid(pose, c, light, -0.045f, 0.6f, -0.1f, 0.045f, 0.86f, -0.05f, FRAME);
		cuboid(pose, c, light, -0.04f, 0.6f, -0.05f, 0.04f, 0.7f, 0.1f, FRAME);
		cuboid(pose, c, light, -0.015f, 0.88f, -0.225f, 0.015f, 0.91f, -0.2f, METAL);
		cuboid(pose, c, light, -0.025f, 0.921f, -0.17f, 0.025f, 0.923f, -0.13f, BORE);
	}

	private static void cuboid(PoseStack.Pose pose, VertexConsumer c, int light, float x1, float y1, float z1, float x2, float y2, float z2, float[] uv) {
		float[][][] faces = {
			{{x1, y1, z1}, {x2, y1, z1}, {x2, y2, z1}, {x1, y2, z1}},
			{{x2, y1, z2}, {x1, y1, z2}, {x1, y2, z2}, {x2, y2, z2}},
			{{x1, y1, z2}, {x1, y1, z1}, {x1, y2, z1}, {x1, y2, z2}},
			{{x2, y1, z1}, {x2, y1, z2}, {x2, y2, z2}, {x2, y2, z1}},
			{{x1, y1, z2}, {x2, y1, z2}, {x2, y1, z1}, {x1, y1, z1}},
			{{x1, y2, z1}, {x2, y2, z1}, {x2, y2, z2}, {x1, y2, z2}}};
		float[][] normals = {{0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}};
		float[][] corners = {{uv[0], uv[1]}, {uv[2], uv[1]}, {uv[2], uv[3]}, {uv[0], uv[3]}};
		for (int f = 0; f < 6; f++) {
			for (int k = 0; k < 4; k++) {
				c.addVertex(pose, faces[f][k][0], faces[f][k][1], faces[f][k][2]).setColor(0xFFFFFFFF).setUv(corners[k][0], corners[k][1])
					.setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, normals[f][0], normals[f][1], normals[f][2]);
			}
		}
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		if (own != null) {
			drawScene(event, own);
			drawFlashes(event, own);
			drawBagging(event, own);
		}
		// The seizure notice on your front door while you're frozen
		if (frozen.get()) drawTape(event);
	}

	/** Everyone else's raids, from Client Sync's drawing (so they show with your own FBI Raid off). */
	public void drawPeers(Render3DEvent event) {
		for (Scene scene : peers.values()) {
			drawScene(event, scene);
			drawFlashes(event, scene);
			drawBagging(event, scene);
		}
	}

	/** How long a watch takes from the case into the bag. */
	private static final double BAG_FLIGHT = 0.5;

	/** Each watch on its way out of its case and into the agent's evidence bag. */
	private void drawBagging(Render3DEvent event, Scene scene) {
		double t = scene.elapsed();
		Vec3 cam = event.camera().pos;
		if (scene.ledgerAgent >= 0) {
			double since = t - scene.ledgerAt + BAG_FLIGHT * 0.6;
			float[] a = scene.agent(scene.ledgerAgent, t);
			if (since >= 0 && since <= BAG_FLIGHT && a != null) {
				double rad = Math.toRadians(a[3]);
				float fx = (float) -Math.sin(rad), fz = (float) Math.cos(rad), lx = (float) Math.cos(rad), lz = (float) Math.sin(rad);
				float[] bag = {a[0] + fx * 0.3f + lx * 0.38f, a[1] + 0.82f, a[2] + fz * 0.3f + lz * 0.38f};
				float k = (float) (since / BAG_FLIGHT), s = k * k * (3 - 2 * k);
				float x = LEDGER_SPOT[0] + (bag[0] - LEDGER_SPOT[0]) * s, z = LEDGER_SPOT[2] + (bag[2] - LEDGER_SPOT[2]) * s;
				float y = LEDGER_SPOT[1] + (bag[1] - LEDGER_SPOT[1]) * s + 0.3f * (float) Math.sin(Math.PI * k);
				Vec3 o = scene.origin;
				ShapeBuilder lb = new ShapeBuilder(FxRender.solidDetail(event), event.matrices().last(), new Quaternionf().rotateY((float) Math.toRadians(-scene.yaw)),
					(float) (o.x - cam.x), (float) (o.y - cam.y), (float) (o.z - cam.z)).soft().lit(0.9f);
				MansionModule.drawLedger(lb, x, y, z);
			}
		}
		if (scene.grabs.isEmpty()) return;
		if (cam.distanceToSqr(scene.world(new float[]{-9.5f, F2, -1})) > 30 * 30) return;
		ShapeBuilder b = null;
		for (Map.Entry<Integer, List<Grab>> e : scene.grabs.entrySet()) {
			for (Grab g : e.getValue()) {
				for (int slot : g.slots()) {
					double since = t - scene.slotTaken[slot] + BAG_FLIGHT * 0.6;
					if (since < 0 || since > BAG_FLIGHT) continue;
					float[] a = scene.agent(e.getKey(), t);
					if (a == null) continue;
					// Lifted out by the right hand, then down into the bag at the left
					double rad = Math.toRadians(a[3]);
					float fx = (float) -Math.sin(rad), fz = (float) Math.cos(rad), lx = (float) Math.cos(rad), lz = (float) Math.sin(rad);
					float[] bag = {a[0] + fx * 0.3f + lx * 0.38f, a[1] + 0.82f, a[2] + fz * 0.3f + lz * 0.38f};
					float[] from = slotAt(slot);
					float k = (float) (since / BAG_FLIGHT), s = k * k * (3 - 2 * k);
					float[] at = {from[0] + (bag[0] - from[0]) * s, from[1] + (bag[1] - from[1]) * s + (vitrineOf(slot) >= 0 ? 0.55f : 0.3f) * (float) Math.sin(Math.PI * k), from[2] + (bag[2] - from[2]) * s};
					if (b == null) {
						Vec3 o = scene.origin;
						b = new ShapeBuilder(FxRender.solidDetail(event), event.matrices().last(), new Quaternionf().rotateY((float) Math.toRadians(-scene.yaw)),
							(float) (o.x - cam.x), (float) (o.y - cam.y), (float) (o.z - cam.z)).soft().lit(0.9f);
					}
					float spin = (float) (k * Math.PI);
					float[] right = {(float) Math.cos(spin), 0, (float) Math.sin(spin)}, up = {0, 1, 0};
					Watches.draw(b, at, right, up, 0.15f, Watches.COLLECTION.get(slot % Watches.COLLECTION.size()), 600);
				}
			}
		}
	}

	private static final int[] FBI_LOOK = {0xFF0A0B0D, 0xFF1C1D21, 0xFF0A0B0D, 0xFF0A0B0D, 0xFF000000, 0, 8};

	/** The SUVs, then their lights flashing red and blue. */
	private void drawScene(Render3DEvent event, Scene scene) {
		double t = scene.elapsed();
		if (t > scene.end + 1) return;
		List<double[]> cars = new ArrayList<>();
		List<float[]> positions = new ArrayList<>();
		for (int i = 0; i < scene.suvs; i++) {
			float[] s = scene.suv(i, t);
			Vec3 at = scene.world(s);
			if (event.camera().pos.distanceToSqr(at) > 200 * 200) continue;
			cars.add(new double[]{0, at.x, at.y, at.z, scene.worldYaw(s[3]), 100, 100, s[4]});
			positions.add(new float[]{(float) at.x, (float) at.y, (float) at.z, scene.worldYaw(s[3]), i, s[4]});
		}
		if (cars.isEmpty()) return;
		FemboyWare.getModules().get(CarsModule.class).drawShared(event, cars, FBI_LOOK);
		VertexConsumer glow = FxRender.textured(event, FxRender.GLOW, true, false);
		Vec3 cam = event.camera().pos;
		long ms = System.currentTimeMillis();
		for (float[] p : positions) {
			// Each SUV on its own beat: two quick red flashes, then two blue
			long beat = (ms + (long) p[4] * 137) % 700;
			boolean red = beat < 350;
			boolean on = beat % 350 < 70 || beat % 350 > 140 && beat % 350 < 210;
			double rad = Math.toRadians(p[3]);
			float fx = (float) -Math.sin(rad), fz = (float) Math.cos(rad), rx = (float) -Math.cos(rad), rz = (float) -Math.sin(rad);
			float x = (float) (p[0] - cam.x), y = (float) (p[1] - cam.y), z = (float) (p[2] - cam.z);
			int left = red ? 0xFFFF2020 : 0xFF2050FF, right = red ? 0xFF2050FF : 0xFFFF2020;
			// Grille lights, headlight wig-wags, the visor bar inside the windscreen, mirrors, the sides, the rear deck and the bumper
			float[][] spots = {{-0.45f, 0.78f, 2.68f}, {-0.15f, 0.78f, 2.69f}, {0.15f, 0.78f, 2.69f}, {0.45f, 0.78f, 2.68f},
				{-0.72f, 0.92f, 2.7f}, {0.72f, 0.92f, 2.7f},
				{-0.55f, 1.62f, 0.95f}, {-0.2f, 1.64f, 0.93f}, {0.2f, 1.64f, 0.93f}, {0.55f, 1.62f, 0.95f},
				{-1.05f, 1.25f, 0.7f}, {1.05f, 1.25f, 0.7f}, {-1.03f, 0.75f, -0.5f}, {1.03f, 0.75f, -0.5f},
				{-0.55f, 1.55f, -2.3f}, {-0.2f, 1.58f, -2.32f}, {0.2f, 1.58f, -2.32f}, {0.55f, 1.55f, -2.3f},
				{-0.62f, 0.7f, -2.68f}, {0.62f, 0.7f, -2.68f}};
			for (int k = 0; k < spots.length; k++) {
				boolean leftSide = spots[k][0] < 0;
				int color = leftSide ? left : right;
				// Pairs on their own off-beat, so the whole car strobes rather than blinking as one
				boolean lit = k % 3 == 0 ? on : (beat + k * 53) % 350 < 120;
				if (!lit) continue;
				// Tipped with the car on the driveway
				float tilt = p[5], sy = spots[k][1] * (float) Math.cos(tilt) + spots[k][2] * (float) Math.sin(tilt);
				float sz = spots[k][2] * (float) Math.cos(tilt) - spots[k][1] * (float) Math.sin(tilt);
				float lx = x + rx * spots[k][0] + fx * sz, ly = y + sy, lz = z + rz * spots[k][0] + fz * sz;
				FxRender.billboard(event, glow, lx, ly, lz, 0.55f, 0, FxRender.fade(color, 0.85f));
				FxRender.billboard(event, glow, lx, ly, lz, 0.18f, 0, 0xF0FFFFFF);
			}
			// The glow they throw on the ground round the car
			if (on) {
				int wash = FxRender.fade(red ? 0xFFFF2020 : 0xFF2050FF, 0.25f);
				FxRender.billboard(event, glow, x, y + 0.6f, z, 4.5f, 0, wash);
			}
		}
	}

	/** The photographers' camera flashes. */
	private void drawFlashes(Render3DEvent event, Scene scene) {
		if (scene.flashes.isEmpty()) return;
		long now = System.currentTimeMillis();
		Vec3 cam = event.camera().pos;
		VertexConsumer glow = FxRender.textured(event, FxRender.GLOW, true, false);
		for (RemotePlayer actor : scene.actors) {
			Long at = scene.flashes.get(actor.getId());
			if (at == null || now - at > 160) continue;
			float strength = 1 - (now - at) / 160f;
			Vec3 p = actor.getEyePosition().add(actor.getViewVector(1).scale(0.6));
			float x = (float) (p.x - cam.x), y = (float) (p.y - cam.y), z = (float) (p.z - cam.z);
			FxRender.billboard(event, glow, x, y, z, 0.6f, 0, FxRender.fade(0xFFDDEEFF, 0.6f * strength));
			FxRender.billboard(event, glow, x, y, z, 0.22f, 0, FxRender.fade(0xFFFFFFFF, strength));
		}
	}

	/** Police tape across your front door and a seizure notice, while your assets are frozen. */
	private void drawTape(Render3DEvent event) {
		MansionModule mansion = FemboyWare.getModules().get(MansionModule.class);
		if (!mansion.isEnabled() || mansion.origin() == null) return;
		Vec3 o = mansion.origin();
		Vec3 cam = event.camera().pos;
		if (cam.distanceToSqr(o) > 120 * 120) return;
		float ox = (float) (o.x - cam.x), oy = (float) (o.y - cam.y), oz = (float) (o.z - cam.z);
		Quaternionf turn = new Quaternionf().rotateY((float) Math.toRadians(-mansion.facing()));
		ShapeBuilder b = new ShapeBuilder(FxRender.solidQuads(event), event.matrices().last(), turn, ox, oy, oz).lit(0.5f);
		float z = FRONT + 0.2f;
		// Two strips of tape in an X across the doorway, black and yellow banded
		for (int side = -1; side <= 1; side += 2) {
			for (int k = 0; k < 10; k++) {
				float f1 = k / 10f, f2 = (k + 1) / 10f;
				float x1 = side * (1.4f - 2.8f * f1), x2 = side * (1.4f - 2.8f * f2), y1 = PLINTH + 0.4f + 2.2f * f1, y2 = PLINTH + 0.4f + 2.2f * f2;
				int color = k % 2 == 0 ? 0xFFF2C418 : 0xFF141414;
				b.quad(new float[]{x1, y1 - 0.08f, z + side * 0.01f}, new float[]{x2, y2 - 0.08f, z + side * 0.01f},
					new float[]{x2, y2 + 0.08f, z + side * 0.01f}, new float[]{x1, y1 + 0.08f, z + side * 0.01f}, color);
			}
		}
		// The notice: a white sheet with a dark header band
		b.box(-0.35f, PLINTH + 1.3f, z + 0.03f, 0.35f, PLINTH + 2.15f, z + 0.035f, 0xFFF4F4F0);
		b.box(-0.33f, PLINTH + 1.95f, z + 0.035f, 0.33f, PLINTH + 2.1f, z + 0.04f, 0xFF1B2440);
		for (float y = PLINTH + 1.4f; y < PLINTH + 1.9f; y += 0.08f) b.box(-0.28f, y, z + 0.035f, 0.28f, y + 0.025f, z + 0.04f, 0xFF8A8A8A);
	}

	// ---------------------------------------------------------------- your view of your own raid

	private long flashAt;

	/** For CameraMixin: the raid's camera, cutting between shots like a film; null while it's your own view. */
	public CinematicCameraModule.View view(float tickProgress) {
		Scene scene = own;
		CinematicCameraModule.View view = scene == null || mc.level == null ? null : shot(scene, scene.elapsed());
		showHand(view == null);
		return view;
	}

	/**
	 * The shot at {@code t}: the convoy pulling up, the stack at the door before the breach, a slow
	 * circle round you once you're surrounded, the cuffs going on, the watch team bagging the
	 * collection, the perp walk (behind you inside, in front of you outside), the convoy leaving and a
	 * crane up as it goes. Between them, you see it yourself.
	 */
	private CinematicCameraModule.View shot(Scene scene, double t) {
		boolean films = cinematic.get();
		boolean inside = scene.inside();
		float[] you = scene.target;
		// The convoy coming up the road and pulling up out front, the camera drifting in
		if (films && t >= 0.8 && t < (inside ? SHOUT_OUTSIDE + 1.2 : DRIVE_IN + 2.2)) {
			double k = Mth.clamp((t - 0.8) / 9.0, 0, 1);
			float[] pos = lerp(new float[]{-19, 2.2f, 31}, new float[]{-14, 3.2f, 24}, smooth(k));
			float[] lead = scene.suv(0, t);
			float[] look = t < DRIVE_IN ? new float[]{lead[0], lead[1] + 1, lead[2]}
				: lerp(new float[]{lead[0], lead[1] + 1, lead[2]}, new float[]{0, PLINTH + 1.6f, FRONT + 1}, smooth(Mth.clamp((t - DRIVE_IN) / 2.5, 0, 1)));
			return look(scene, pos, look);
		}
		// The stack on the door: low, from the side of the steps, pushing in; then back to your own eyes for the breach
		if (films && inside && t >= SHOUT_OUTSIDE + 1.2 && t < BREACH - 0.6) {
			double k = Mth.clamp((t - SHOUT_OUTSIDE - 1.2) / (BREACH - 0.6 - SHOUT_OUTSIDE - 1.2), 0, 1);
			float[] pos = lerp(new float[]{4.2f, PLINTH + 0.45f, FRONT + 6.2f}, new float[]{3.0f, PLINTH + 0.6f, FRONT + 4.4f}, smooth(k));
			return look(scene, pos, new float[]{0, PLINTH + 1.3f, FRONT + 0.6f});
		}
		// In the flash: cut to them flooding in through the door, the camera high in the corner, following them to you
		if (films && inside && t >= BANG + FLOOD_CUT && t < scene.lastArrive + 0.3) {
			double k = Mth.clamp((t - BANG - FLOOD_CUT) / Math.max(1, scene.lastArrive + 0.3 - BANG - FLOOD_CUT), 0, 1);
			float[] goal = scene.upstairs() ? new float[]{STAIR_X, PLINTH, STAIR_BOTTOM_Z} : you;
			float side = goal[0] <= 0 ? 1 : -1;
			float[] pos = keepInGround(new float[]{(DOOR_IN[0] + goal[0]) / 2 + side * 4.5f, PLINTH + 3.0f, (DOOR_IN[2] + goal[2]) / 2 - 1.5f});
			float[] look = lerp(new float[]{DOOR_IN[0], PLINTH + 1.2f, DOOR_IN[2]}, new float[]{goal[0], PLINTH + 1.0f, goal[2]}, smooth(k));
			return look(scene, pos, look);
		}
		// Surrounded: a slow half circle round you, guns on you from every side
		double ringFrom = scene.lastArrive + 0.3;
		if (films && t >= ringFrom && t < ringFrom + 4.5) {
			double k = (t - ringFrom) / 4.5;
			double a = Math.toRadians(scene.targetYaw + 200 + 140 * smooth(k));
			float r = 3.4f;
			float[] pos = {you[0] - (float) Math.sin(a) * r, you[1] + 2.3f - 0.5f * (float) k, you[2] + (float) Math.cos(a) * r};
			return look(scene, keepIn(scene, pos), new float[]{you[0], you[1] + 1.1f, you[2]});
		}
		// The cuffs: close and low in front of you as they go on
		double cuffs = scene.cuffStart + CUFF_WALK + CUFFS_AT;
		if (films && t >= cuffs - 1.3 && t < cuffs + 1.8) {
			double rad = Math.toRadians(scene.targetYaw);
			float fx = (float) -Math.sin(rad), fz = (float) Math.cos(rad), rx = -fz, rz = fx;
			// From your side, so the agent cuffing you behind and you are both in it
			float[] pos = {you[0] + fx * 1.1f + rx * 2.5f, you[1] + 1.3f, you[2] + fz * 1.1f + rz * 2.5f};
			return look(scene, keepIn(scene, pos), new float[]{you[0], you[1] + 1.05f, you[2]});
		}
		// The watch room being emptied into evidence bags
		if (films && !scene.bagging.isEmpty()) {
			double bagFrom = scene.bagging.get(0)[0] + 2.5;
			if (t >= bagFrom && t < bagFrom + 4 && t < scene.escortStart - 0.5) {
				double k = (t - bagFrom) / 4;
				// From the end of the room, high, down the walkway and the corridor behind the first case
				float[] pos = lerp(new float[]{-6.3f, F2 + 2.2f, -1.4f}, new float[]{-6.6f, F2 + 1.9f, -1.6f}, smooth(k));
				return look(scene, pos, new float[]{-10.5f, F2 + 0.8f, -1.6f});
			}
		}
		if (t < scene.escortStart) return null;
		// The perp walk
		float[] p = scene.prisoner(t);
		if (t < scene.loaded && p != null) {
			float[] chest = {p[0], p[1] + 1.1f, p[2]};
			double rad = Math.toRadians(p[3]);
			float fx = (float) -Math.sin(rad), fz = (float) Math.cos(rad), rx = -fz, rz = fx;
			float[] pos;
			if (p[2] < FRONT - 0.6f && p[0] > -HX && p[0] < HX && p[2] > BACK) {
				// Inside: following from behind and to one side, like a news crew
				pos = keepIn(scene, new float[]{chest[0] - fx * 3.2f + rx * 1.3f, chest[1] + 0.6f, chest[2] - fz * 3.2f + rz * 1.3f});
			} else if (scene.inside() && p[2] < FRONT + 3.5f) {
				// Out through the door: low on the steps, looking up at you coming out
				pos = new float[]{2.6f, PLINTH + 0.35f, FRONT + 5.2f};
			} else {
				// Down to the car: in front of you walking backwards, the agents either side
				pos = new float[]{chest[0] + fx * 3.0f + rx * 1.0f, chest[1] + 0.35f, chest[2] + fz * 3.0f + rz * 1.0f};
			}
			return look(scene, pos, chest);
		}
		float[] lead = scene.suv(0, t);
		float[] car = {lead[0], lead[1] + 1, lead[2]};
		if (t < scene.depart + 2.5) {
			// From the road, watching them load you in and pull away
			return look(scene, new float[]{18.5f, 2.6f, 34}, car);
		}
		// A crane shot rising over the road as the convoy goes
		double k = Mth.clamp((t - scene.depart - 2.5) / 5.5, 0, 1);
		return look(scene, new float[]{14, 2.4f + 9 * (float) smooth(k), 34 + 6 * (float) k}, car);
	}

	/** A shot from {@code pos} at {@code at}, both in the mansion's space. */
	private CinematicCameraModule.View look(Scene scene, float[] pos, float[] at) {
		Vec3 from = scene.world(pos), to = scene.world(at);
		Vec3 d = to.subtract(from);
		float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
		float pitch = (float) Math.toDegrees(-Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
		return new CinematicCameraModule.View(from, yaw, pitch);
	}

	/** Keeps a camera inside the room you're in (between the walls, floor and ceiling of your floor), when you're inside. */
	private static float[] keepIn(Scene scene, float[] p) {
		if (!scene.inside()) return p;
		boolean up = scene.upstairs();
		float floor = up ? F2 : PLINTH, ceiling = up ? ROOF - 0.15f : F2 - 0.45f;
		float maxX = scene.target[0] < 6 ? 6 - 0.45f : HX - 0.45f, minX = scene.target[0] < 6 ? -HX + 0.45f : 6 + 0.45f;
		return new float[]{Mth.clamp(p[0], minX, maxX), Mth.clamp(p[1], floor + 0.4f, ceiling), Mth.clamp(p[2], BACK + 0.45f, FRONT - 0.45f)};
	}

	/** Inside the ground floor, clear of its walls, floor and ceiling. */
	private static float[] keepInGround(float[] p) {
		return new float[]{Mth.clamp(p[0], -HX + 0.45f, 6 - 0.45f), Mth.clamp(p[1], PLINTH + 0.4f, F2 - 0.45f), Mth.clamp(p[2], BACK + 0.45f, FRONT - 0.45f)};
	}

	/** How long after the flashbang goes off the film cuts to them flooding in. */
	private static final double FLOOD_CUT = 1.8;

	private static float[] lerp(float[] a, float[] b, double k) {
		return new float[]{(float) (a[0] + (b[0] - a[0]) * k), (float) (a[1] + (b[1] - a[1]) * k), (float) (a[2] + (b[2] - a[2]) * k)};
	}

	private static double smooth(double k) {
		return k * k * (3 - 2 * k);
	}

	/** For the hand mixin and the HUD: whether your own raid's camera has taken over. */
	public boolean filming() {
		Scene scene = own;
		return scene != null && mc.level != null && shot(scene, scene.elapsed()) != null;
	}

	private void showHand(boolean show) {
		handHidden = !show;
	}

	public boolean hidesHand() {
		return handHidden && own != null;
	}

	/** For the HUD: from the first shot (or the breach) to the end, it's a film, so none of the game's or femboyware's HUD shows. */
	public boolean hidesHud() {
		return held();
	}

	/** Whether you're being held: from the breach until the raid's over. You can look round, not move. */
	private boolean held() {
		return own != null && (own.elapsed() >= BREACH || filming());
	}

	/** The seized card's up: nothing else of femboyware's is drawn over it. */
	public boolean seizedScreenShowing() {
		return own != null && own.elapsed() >= own.end;
	}

	/** Holds you still from the breach on: they've got you surrounded. */
	@Override
	public void onMovementInput() {
		if (!held()) return;
		mc.player.input.keyPresses = Input.EMPTY;
		mc.player.xxa = 0;
		mc.player.zza = 0;
		mc.player.setJumping(false);
	}

	@Override
	public void onRender2D(GuiGraphics context, float tickDelta) {
		int w = context.guiWidth(), h = context.guiHeight();
		long sinceFlash = System.currentTimeMillis() - flashAt;
		if (sinceFlash < 3500) {
			// The flashbang: blinding white, fading out slowly; quicker once the film's cut away from your eyes, like a whiteout
			// Blinding white in your own eyes; once the film's cut to them flooding in, the white clears off it quickly
			float a = sinceFlash < 400 ? 1 : 1 - (sinceFlash - 400) / 3100f;
			long sinceCut = sinceFlash - (long) (FLOOD_CUT * 1000);
			if (sinceCut > 0 && filming()) a *= Math.max(0, 1 - sinceCut / 500f);
			context.fill(0, 0, w, h, (int) (Mth.clamp(a, 0, 1) * 255) << 24 | 0xFFFFFF);
		}
		Scene scene = own;
		if (scene == null) return;
		double t = scene.elapsed();
		if (filming()) {
			// Letterbox bars while the camera films (they cover the hotbar too)
			int bar = (int) (h * 0.13f);
			context.fill(0, 0, w, bar, 0xFF000000);
			context.fill(0, h - bar, w, h, 0xFF000000);
		}
		double fadeFrom = scene.end - FADE;
		if (t >= fadeFrom) {
			float a = (float) Mth.clamp((t - fadeFrom) / FADE, 0, 1);
			context.fill(0, 0, w, h, (int) (a * 255) << 24);
		}
		if (t >= scene.end) seizedScreen(context, w, h, (float) Mth.clamp((t - scene.end) / 0.8, 0, 1));
	}

	/** The end card: what was taken, by whom. */
	private void seizedScreen(GuiGraphics context, int w, int h, float alpha) {
		int a = (int) (alpha * 255) << 24;
		context.fill(0, 0, w, h, 0xFF000000);
		var font = mc.font;
		var modules = FemboyWare.getModules();
		double crypto = modules.get(LedgerModule.class).total();
		double bank = modules.get(PhoneModule.class).bankBalance() + modules.get(PhoneModule.class).portfolio();
		String[] lines = {
			"by the Federal Bureau of Investigation, under a seizure warrant",
			"issued by the United States District Court",
			"",
			"Ledger hardware wallet and seed phrase     " + LedgerModule.usd(crypto),
			"Bank and brokerage accounts     " + LedgerModule.usd(bank),
			"Watch collection (" + MansionModule.WATCH_COUNT + " pieces)",
			"Real estate, vehicles, yacht and aircraft"};
		String title = "ASSETS SEIZED";
		context.pose().pushMatrix();
		context.pose().translate(w / 2f, h * 0.24f);
		context.pose().scale(3, 3);
		context.drawString(font, title, -font.width(title) / 2, 0, a | 0xE23B3B, false);
		context.pose().popMatrix();
		int y = (int) (h * 0.24f) + 40;
		for (String line : lines) {
			context.drawString(font, line, (w - font.width(line)) / 2, y, a | 0xF0F0F0, false);
			y += 13;
		}
	}

	/** The siren on the lead SUV, following it about. */
	private final class Siren extends AbstractTickableSoundInstance {
		private final Scene scene;
		private boolean done;

		Siren(Scene scene) {
			super(LarpSounds.FBI_SIREN, SoundSource.NEUTRAL, RandomSource.create());
			this.scene = scene;
			this.looping = true;
			this.delay = 0;
			this.attenuation = SoundInstance.Attenuation.LINEAR;
			this.volume = 1.0f;
			move();
		}

		private void move() {
			Vec3 at = scene.world(scene.suv(0, scene.elapsed()));
			x = at.x;
			y = at.y + 1;
			z = at.z;
		}

		@Override
		public boolean canStartSilent() {
			return true;
		}

		@Override
		public void tick() {
			double t = scene.elapsed();
			if (done || t > scene.end) {
				stop();
				return;
			}
			move();
			// They kill the sirens once they're parked, lights still going, and run them again leaving
			double parked = DRIVE_IN + 1.5;
			if (t > parked && t < scene.depart) volume = Math.max(0.0f, volume - 0.05f);
			else volume = Math.min(1.0f, volume + 0.05f);
		}
	}
}
