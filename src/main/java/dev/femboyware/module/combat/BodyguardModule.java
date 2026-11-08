package dev.femboyware.module.combat;

import dev.femboyware.util.Mc;
import dev.femboyware.FemboyWare;
import dev.femboyware.manager.GuardLink;
import dev.femboyware.mixin.InputAccessor;
import dev.femboyware.mixin.MinecraftClientAccessor;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.module.utility.AutoMeowModule;
import dev.femboyware.module.utility.AutoMoanModule;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.setting.StringSetting;
import dev.femboyware.util.ChatUtil;
import dev.femboyware.util.InventoryUtil;
import dev.femboyware.util.MouseTurner;
import dev.femboyware.util.Pathfinder;
import dev.femboyware.util.Rotation;
import dev.femboyware.util.TargetUtil;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.StringUtil;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Guards one player, the principal, together with any number of other bodyguards, each on its own
 * computer. Fighting is Legit Aura's and staying alive is Legit Pot's; this module decides who to
 * fight, keeps everyone in formation around the principal when there's nothing to fight, and takes
 * orders the principal types in chat ({@code !formation circle}, {@code !pause} and so on), or sends
 * privately over {@link GuardLink} with {@code .g pause} when its Link Key matches Guard Command's.
 *
 * <p>Bodyguards never talk to each other. Every one of them hears the same orders in chat and sees
 * the same players in the world, and they all run the same rules on that: the guards present are
 * sorted by name and each takes the formation slot of its place in that list. So they agree on who
 * stands where without a single message passing between them, and when a guard dies or joins the
 * rest close up or make room on their own.
 */
public class BodyguardModule extends Module {
	public enum Formation { CIRCLE, FRONT, BACK, LINE, COLUMN, WEDGE, SQUARE }

	/**
	 * Neutral only fights back against whoever attacks this guard; Defend fights whoever attacks the
	 * principal or any guard; Aggressive also fights anyone who comes close.
	 */
	public enum Mode { NEUTRAL, DEFEND, AGGRESSIVE }

	/** Who guards walk up to and greet in crowd mode. */
	public enum Greet { OFF, PLAYERS, GUARDS, EVERYONE }

	/** Something fun to do instead of guarding. */
	private enum Activity { NONE, MIRROR, CROWD, FFA, ARENA }

	/** How long someone who hit the principal or a guard stays an enemy, in milliseconds. */
	private static final long GRUDGE = 30_000;
	/** Other players closer than this push our path aside. */
	private static final double PERSONAL_SPACE = 2;
	/** How wide a berth to give the principal when walking past them. */
	private static final double PRINCIPAL_SPACE = 1.8;
	/** A rally is over this close to where it's going, or after this many milliseconds if we can't get there. */
	private static final double RALLY_ARRIVED = 3.5;
	private static final long RALLY_TIMEOUT = 60_000;
	/** Worth a pearl only this far from where a rally is going, in blocks. */
	private static final double RALLY_PEARL_DISTANCE = 20;
	private static final int STATUS_TICKS = 20;
	/** Ticks of getting nowhere before looking for a way round, and between looks. */
	private static final int PATH_AFTER = 12;
	private static final int PATH_COOLDOWN = 20;
	private static final int PATH_NODES = 6000;
	/** Find a new path if where we're going has moved this far from where the old one ends. */
	private static final double PATH_STALE = 5;
	private static final int RESPAWN_TICKS = 20;
	/** How close to get to someone being greeted, how long to carry on around them, and how long to try reaching them, in ticks. */
	private static final double GREET_DISTANCE = 2.2;
	private static final int GREET_TICKS = 50;
	private static final int GREET_WALK_TICKS = 240;
	/** How far off to the side someone can be and still count as who we're looking at, in degrees, and how far away. */
	private static final double LOOKED_AT_ANGLE = 25;
	private static final double LOOKED_AT_RANGE = 16;
	/** In a free-for-all, stop running at someone this close. */
	private static final double FFA_CLOSE = 2.8;
	/** Arena line-up: close enough to our spot, how far each team's line is from the middle as a share of the radius, and the gap between teammates. */
	private static final double ARENA_SPOT_REACHED = 0.7, ARENA_LINE = 0.6, ARENA_GAP = 1.5;
	/** How many steps along a path the camera looks, so it sweeps round corners instead of snapping at each block. */
	private static final int PATH_LOOK_AHEAD = 4;
	/** The fastest the camera turns while walking somewhere, in degrees per second. */
	private static final float WALK_TURN_SPEED = 110;

	private final StringSetting principal = add(new StringSetting("Principal", "Name of the player to guard; only they can give orders", ""));
	private final StringSetting guards = add(new StringSetting("Guards", "Names of every bodyguard, yours included, separated by spaces or commas. Set it the same on every computer, or have the principal type !guards", ""));
	private final EnumSetting<Formation> formation = add(new EnumSetting<>("Formation", "How the guards stand around the principal", Formation.CIRCLE));
	private final NumberSetting radius = add(new NumberSetting("Radius", "How far from the principal the guards stand", 3, 1.5, 10, 0.5));
	private final EnumSetting<Mode> mode = add(new EnumSetting<>("Mode", "Neutral only fights back against whoever hits this guard; Defend fights whoever attacks the principal or a guard; Aggressive also fights anyone who comes close", Mode.DEFEND));
	private final NumberSetting guardRange = add(new NumberSetting("Guard Range", "In Aggressive mode, fight anyone this close to the principal", 8, 2, 32, 1)).visibleWhen(() -> mode.is(Mode.AGGRESSIVE));
	private final NumberSetting leash = add(new NumberSetting("Leash", "Never chase anyone further than this from the principal", 16, 4, 64, 1));
	private final BoolSetting usePot = add(new BoolSetting("Legit Pot", "Turn on Legit Pot too, so guards heal and buff themselves", true));
	private final BoolSetting reply = add(new BoolSetting("Reply", "One guard whispers the principal to confirm each order", true));
	private final BoolSetting pluginChat = add(new BoolSetting("Plugin Chat", "Also read orders on servers whose chat plugins reformat messages, like \"[Rank] Name: !pause\"", true));
	private final NumberSetting crowdRadius = add(new NumberSetting("Crowd Radius", "In crowd mode, how far from the middle guards wander", 8, 2, 32, 1));
	private final BoolSetting crowdChat = add(new BoolSetting("Crowd Chat", "In crowd mode, guards now and then say something in public chat", true));
	private final StringSetting crowdMessages = add(new StringSetting("Crowd Messages", "What guards say in crowd mode, separated by ;. {player} is who they're looking at, and those lines only get used when they're looking at someone", "hi :3; hii :3; heyy :3; hi chat :3; hewwo :3; hi {player} :3; hey {player} :3; i see you {player} :3; uwu; owo; :3; nya~; i like your skin :3; who wants to be friends :3; im so sleepy :3; you're cute :3")).visibleWhen(crowdChat::get);
	private final EnumSetting<Greet> crowdGreet = add(new EnumSetting<>("Crowd Greet", "Who guards walk up to, crouch and hop around, and say hi to. Guards are whoever's in the Guards setting", Greet.EVERYONE));
	private final StringSetting greetMessages = add(new StringSetting("Greet Messages", "What guards say to whoever they walk up to, separated by ;. {player} is their name", "hi {player} :3; hii {player} :3; heyy {player} :3; hewwo {player} :3; {player} hii :3; i like your skin {player} :3; {player} wanna be friends :3; boop {player} :3")).visibleWhen(() -> !crowdGreet.is(Greet.OFF));
	private final NumberSetting greetRange = add(new NumberSetting("Greet Range", "How far away someone can be and still get greeted, in blocks", 16, 4, 64, 1)).visibleWhen(() -> !crowdGreet.is(Greet.OFF));
	private final NumberSetting greetCooldown = add(new NumberSetting("Greet Cooldown", "Seconds before greeting the same person again, so nobody gets pestered", 60, 5, 600, 5)).visibleWhen(() -> !crowdGreet.is(Greet.OFF));
	private final BoolSetting guardMoan = add(new BoolSetting("Guard Moan", "Turn on Auto Moan while guarding; set its messages in its own settings", true));
	private final BoolSetting crowdMeow = add(new BoolSetting("Crowd Meow", "Turn on Auto Meow during crowd mode; set how often it meows in its own settings", true));
	private final NumberSetting crowdChatEvery = add(new NumberSetting("Crowd Chat Every", "About how many seconds between each guard's messages", 120, 30, 600, 10)).visibleWhen(crowdChat::get);
	private final BoolSetting rallyPearl = add(new BoolSetting("Rally Pearl", "When called to a far-off place, throw an ender pearl from the hotbar to get there faster", true));
	private final StringSetting prefix = add(new StringSetting("Prefix", "What orders start with", "!"));
	private final StringSetting linkKey = add(new StringSetting("Link Key", "Private orders: the same password as the principal's Guard Command module, from any computer or network. Leave empty to only take orders from chat", ""));
	private final BoolSetting lowPower = add(new BoolSetting("Low Power", "Don't draw the world (just the HUD on black), cap the frame rate and shorten the render distance, so many guards can run on one PC", true));
	private final NumberSetting lowPowerFps = add(new NumberSetting("Low Power FPS", "Frame rate cap in low power; aiming gets choppier below about 15", 15, 5, 60, 1)).visibleWhen(lowPower::get);
	private final NumberSetting viewDistance = add(new NumberSetting("View Distance", "Render distance in low power, in chunks; players further than this many chunks away can't be seen", 4, 2, 12, 1)).visibleWhen(lowPower::get);

	private final MouseTurner mouse = new MouseTurner();
	/** Players who attacked the principal or a guard, and when they stop counting as enemies. */
	private final Map<UUID, Long> enemies = new HashMap<>();
	/** Players who attacked this guard itself, the only ones fought in Neutral mode. */
	private final Map<UUID, Long> attackedMe = new HashMap<>();
	/** Set by !stop: no fighting at all, not even back, until a new order lifts it. */
	private boolean ceasefire;
	private Player target;
	/** Name of a player the principal ordered us to attack, or null. */
	private String ordered;
	private boolean paused;
	/** Where to hold formation after {@code !stay}; null while following the principal. */
	private Vec3 anchor;
	private float anchorYaw;
	private long lastReply;
	/** Where a rally is taking us, ahead of everything but staying alive; null when not rallying. */
	private Vec3 rally;
	private long rallyEnds;
	/** Whether to hold that spot on arrival, instead of going back to following the principal. */
	private boolean rallyHolds;
	/** Where to look to land a pearl on the way there; null when not about to throw one. */
	private Rotation pearlAim;
	private int statusTimer;
	private Activity activity = Activity.NONE;
	private final Random random = new Random();
	/** The way round whatever we got stuck on, the next place along it, and where it was heading. */
	private List<Vec3> path;
	private int pathIndex;
	private Vec3 pathGoal;
	private int pathCooldown;
	private boolean wasBossSwinging;
	/** Crowd mode: the middle, where we're strolling to, ticks before strolling again, and idle fidgets. */
	private Vec3 crowdCenter;
	private Vec3 wanderTo;
	private int crowdWait;
	private int crowdSneakTicks;
	private boolean crowdJump;
	/** Who we're on our way to greet, or greeting; how much longer that goes on; and when each person can next be greeted. */
	private Player greeting;
	private int greetTicks;
	private int greetWalkTicks;
	private boolean greeted;
	private final Map<UUID, Long> greetAgain = new HashMap<>();
	private long nextCrowdChat;
	/** Free-for-all: the other guards, in lower case, and ticks spent dead before respawning. */
	private final Set<String> ffaEnemies = new HashSet<>();
	private int respawnTimer;
	/** Arena: our place in the team's line, whether the round has started, and whether we've died this round. The other team is in {@link #ffaEnemies}. */
	private Vec3 arenaSpot;
	private boolean arenaFighting;
	private boolean arenaOut;
	private GuardLink.Client link;
	private String linkSettings = "";
	/** Whether the order being carried out came over the link, so its reply goes back that way too. */
	private boolean linkOrder;
	/** Which way we're walking after steering round people; null when not walking. The camera turns this way. */
	private Vec3 heading;
	/** Ticks we've barely moved while trying to walk, and ticks left of sidestepping to get unstuck. */
	private int stuckTicks;
	private int unstickTicks;
	private Vec3 lastPos;
	private long lastFrame;
	/** Settings we changed, to put back: the render distance (-1 if untouched) and pausing when the window loses focus. */
	private int savedViewDistance = -1;
	private boolean savedPauseOnLostFocus;
	private boolean changedPause;
	/** Whether we turned these on, and so should turn them off again. */
	private boolean enabledAura;
	private boolean enabledPot;
	private boolean enabledMeow;
	private boolean enabledMoan;

	public BodyguardModule() {
		super("Bodyguard", "Guards a player with Legit Aura and Legit Pot, in formation with other bodyguards, taking orders from chat", Category.LEGIT);
		ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, timestamp) -> {
			if (sender == null || !isEnabled() || mc.player == null) return;
			if (sender.name().equalsIgnoreCase(principal.get())) order(signed != null ? signed.signedContent() : message.getString());
		});
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (overlay || !isEnabled() || !pluginChat.get() || mc.player == null || principal.get().isBlank()) return;
			String text = ChatFormatting.stripFormatting(message.getString());
			if (text == null) return;
			String name = Pattern.quote(principal.get());
			// "[Rank] Name: msg", "<Name> msg", "Name » msg", or a plugin whisper "[Name -> you] msg". Anchored to the
			// start, so someone else can't fake an order by typing the principal's name into their own message
			Matcher matcher = Pattern.compile("^(?:\\[[^\\]]*\\]\\s*)*<?" + name + ">?\\s*[:>»\\-]+\\s*(.*)$|^\\[" + name + "\\s*->[^\\]]*\\]\\s*(.*)$",
				Pattern.CASE_INSENSITIVE).matcher(text);
			if (matcher.matches()) order(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
		});
		// Put the player's settings back before the game saves them on the way out
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> restoreOptions());
	}

	/** Whether the game should skip drawing the world and run at a low frame rate. */
	public static boolean lowPower() {
		if (FemboyWare.getModules() == null) return false;
		BodyguardModule module = FemboyWare.getModules().get(BodyguardModule.class);
		return module.isEnabled() && module.lowPower.get();
	}

	/** The frame rate cap in low power. */
	public int lowPowerFps() {
		return lowPowerFps.getInt();
	}

	/**
	 * Keeps the game options in step with the settings. A guard must keep going in a window that isn't
	 * focused, so the game no longer pauses when you click into another one; in low power the render
	 * distance comes down too.
	 */
	private void applyOptions() {
		if (!changedPause) {
			savedPauseOnLostFocus = mc.options.pauseOnLostFocus;
			mc.options.pauseOnLostFocus = false;
			changedPause = true;
		}
		int wanted = viewDistance.getInt();
		if (lowPower.get()) {
			if (savedViewDistance < 0) savedViewDistance = mc.options.renderDistance().get();
			if (mc.options.renderDistance().get() != wanted) {
				mc.options.renderDistance().set(wanted);
				mc.options.broadcastOptions();
			}
		} else if (savedViewDistance >= 0) {
			mc.options.renderDistance().set(savedViewDistance);
			savedViewDistance = -1;
			mc.options.broadcastOptions();
		}
	}

	private void restoreOptions() {
		if (changedPause) mc.options.pauseOnLostFocus = savedPauseOnLostFocus;
		changedPause = false;
		if (savedViewDistance >= 0) {
			mc.options.renderDistance().set(savedViewDistance);
			if (mc.getConnection() != null) mc.options.broadcastOptions();
		}
		savedViewDistance = -1;
	}

	@Override
	public String getInfo() {
		if (paused) return "Paused";
		if (rally != null) return "Rally";
		if (activity != Activity.NONE) return activityName();
		if (ceasefire) return "Ceasefire";
		return target != null ? target.getName().getString() : EnumSetting.prettify(formation.get().name());
	}

	/** Who the guards should be fighting right now, or null; Legit Aura fights exactly this while Bodyguard is on. */
	public Player getTarget() {
		return isEnabled() ? target : null;
	}

	/** Whether {@code entity} is the principal or one of the guards, whom nothing should attack. */
	public static boolean protects(Entity entity) {
		if (FemboyWare.getModules() == null || !(entity instanceof Player player)) return false;
		BodyguardModule module = FemboyWare.getModules().get(BodyguardModule.class);
		return module.isEnabled() && module.isFriendly(player.getGameProfile().name());
	}

	private boolean isFriendly(String name) {
		if ((activity == Activity.FFA || activity == Activity.ARENA) && ffaEnemies.contains(name.toLowerCase(Locale.ROOT))) return false;
		if (name.equalsIgnoreCase(principal.get())) return true;
		for (String guard : roster()) {
			if (guard.equalsIgnoreCase(name)) return true;
		}
		return false;
	}

	@Override
	protected void onEnable() {
		lastFrame = 0;
		paused = false;
		ordered = null;
		anchor = null;
		rally = null;
		pearlAim = null;
		activity = Activity.NONE;
		path = null;
		ffaEnemies.clear();
		enemies.clear();
		attackedMe.clear();
		ceasefire = false;
		mouse.reset();
		enabledAura = turnOn(LegitAuraModule.class);
		enabledPot = usePot.get() && turnOn(LegitPotModule.class);
		enabledMoan = guardMoan.get() && turnOn(AutoMoanModule.class);
		syncLink();
	}

	@Override
	protected void onDisable() {
		target = null;
		endActivity();
		restoreOptions();
		stopLink();
		if (enabledAura) FemboyWare.getModules().get(LegitAuraModule.class).setEnabled(false);
		if (enabledPot) FemboyWare.getModules().get(LegitPotModule.class).setEnabled(false);
		if (enabledMoan) FemboyWare.getModules().get(AutoMoanModule.class).setEnabled(false);
		enabledAura = enabledPot = enabledMoan = false;
	}

	/** Connects the private order link, or reconnects if its settings changed. */
	private void syncLink() {
		String wanted = linkKey.get();
		if (wanted.equals(linkSettings)) return;
		stopLink();
		if (linkKey.get().isBlank()) return;
		linkSettings = wanted;
		link = new GuardLink.Client(linkKey.get(), mc.getUser().getName(),
			text -> mc.execute(() -> linkOrder(text)), event -> mc.execute(() -> ChatUtil.info(event)));
	}

	private void stopLink() {
		if (link != null) link.stop();
		link = null;
		linkSettings = "";
	}

	/** Carries out an order from the link; a leading prefix is fine but not needed. */
	private void linkOrder(String text) {
		if (!isEnabled() || mc.player == null || mc.level == null) return;
		String body = text.trim();
		if (!prefix.get().isEmpty() && body.startsWith(prefix.get())) body = body.substring(prefix.get().length()).trim();
		linkOrder = true;
		try {
			run(body);
		} finally {
			linkOrder = false;
		}
	}

	private boolean turnOn(Class<? extends Module> type) {
		Module module = FemboyWare.getModules().get(type);
		if (module.isEnabled()) return false;
		module.setEnabled(true);
		return true;
	}

	// ---------------------------------------------------------------- who to fight

	/** Called for every entity the client sees take damage; remembers who hit the principal, a guard, or us. Nothing is remembered during a ceasefire. */
	public void onDamaged(LivingEntity victim, DamageSource source) {
		if (!isEnabled() || ceasefire || !(victim instanceof Player player) || (player != mc.player && !isFriendly(player.getGameProfile().name()))) return;
		if (source.getEntity() instanceof Player attacker && TargetUtil.isValid(attacker)) {
			long until = System.currentTimeMillis() + GRUDGE;
			enemies.put(attacker.getUUID(), until);
			if (player == mc.player) attackedMe.put(attacker.getUUID(), until);
		}
	}

	@Override
	public void onTick() {
		applyOptions();
		syncLink();
		Player boss = principal();
		long now = System.currentTimeMillis();
		enemies.values().removeIf(until -> until < now);
		attackedMe.values().removeIf(until -> until < now);
		updateRally();
		updateActivity(boss);
		if (activity == Activity.FFA) target = ffaTarget();
		else if (activity == Activity.ARENA) target = arenaFighting && !arenaOut ? ffaTarget() : null;
		else target = paused || ceasefire || boss == null || rally != null || activity == Activity.MIRROR ? null : pickTarget(boss);
		reportStatus();
	}

	/** Tells the principal's Guard HUD how we're doing, once a second, if they're on the link. */
	private void reportStatus() {
		if (link == null || ++statusTimer < STATUS_TICKS) return;
		statusTimer = 0;
		String state = paused ? "Paused" : rally != null ? "Rally"
			: activity != Activity.NONE ? activityName() : ceasefire ? "Ceasefire" : EnumSetting.prettify(mode.get().name());
		link.reply(GuardLink.STATUS + String.format(Locale.ROOT, "%.1f\t%.1f\t%.1f\t%d\t%d\t%s\t%s\t%.1f\t%.1f\t%.1f",
			mc.player.getHealth(), mc.player.getMaxHealth(), mc.player.getAbsorptionAmount(), InventoryUtil.count(stack -> stack.is(Items.TOTEM_OF_UNDYING)), LegitPotModule.healingPots(),
			state, target != null ? target.getGameProfile().name() : "", mc.player.getX(), mc.player.getY(), mc.player.getZ()));
	}

	// ---------------------------------------------------------------- mirror, crowd and free-for-all

	private void updateActivity(Player boss) {
		switch (activity) {
			case MIRROR -> {
				if (boss == null) return;
				if (boss.swinging && !wasBossSwinging) mc.player.swing(InteractionHand.MAIN_HAND);
				wasBossSwinging = boss.swinging;
			}
			case CROWD -> updateCrowd();
			case FFA, ARENA -> {
				// Back in the fight straight after dying; in the arena, sitting out the rest of the round
				if (mc.player.isDeadOrDying()) {
					rally = null;
					if (activity == Activity.ARENA && arenaFighting) arenaOut = true;
					if (++respawnTimer >= RESPAWN_TICKS) {
						respawnTimer = 0;
						mc.player.respawn();
						Mc.setScreen(null);
					}
				}
			}
			default -> {
			}
		}
	}

	/** Strolls to a random spot, stands about for a while fidgeting and looking at people, and does it again. */
	private void updateCrowd() {
		if (crowdCenter == null) return;
		crowdJump = false;
		if (crowdSneakTicks > 0) crowdSneakTicks--;
		if (crowdChat.get() && System.currentTimeMillis() >= nextCrowdChat) {
			if (nextCrowdChat != 0) sayCrowdLine();
			nextCrowdChat = System.currentTimeMillis() + (long) (crowdChatEvery.get() * (0.5 + random.nextDouble()) * 1000);
		}
		if (target != null || rally != null) {
			greeting = null;
			return;
		}
		if (greeting != null) {
			updateGreeting();
			return;
		}
		if (wanderTo != null) {
			if (Math.hypot(wanderTo.x - mc.player.getX(), wanderTo.z - mc.player.getZ()) < 1.2 || --crowdWait <= 0) {
				wanderTo = null;
				crowdWait = 60 + random.nextInt(200);
			}
			return;
		}
		if (--crowdWait <= 0) {
			// Half the time, go and say hi to someone instead of wandering
			if (random.nextBoolean() && startGreeting()) return;
			double angle = random.nextDouble() * Math.PI * 2, far = Math.sqrt(random.nextDouble()) * crowdRadius.get();
			wanderTo = crowdCenter.add(Math.cos(angle) * far, 0, Math.sin(angle) * far);
			// Give up on a spot it can't get to after a while
			crowdWait = 300;
			return;
		}
		int fidget = random.nextInt(400);
		if (fidget == 0) crowdJump = true;
		else if (fidget == 1) crowdSneakTicks = 8 + random.nextInt(12);
		else if (fidget == 2) mc.player.swing(InteractionHand.MAIN_HAND);
	}

	private String activityName() {
		if (activity == Activity.ARENA) return arenaOut ? "Out" : arenaFighting ? "Arena" : "Lining Up";
		return activity == Activity.FFA ? "FFA" : EnumSetting.prettify(activity.name());
	}

	/** Picks someone nearby to greet, if there's anyone who hasn't been greeted lately. */
	private boolean startGreeting() {
		if (crowdGreet.is(Greet.OFF)) return false;
		long now = System.currentTimeMillis();
		greetAgain.values().removeIf(when -> when < now);
		List<String> team = roster();
		List<Player> choices = new ArrayList<>();
		for (Player player : mc.level.players()) {
			if (player == mc.player || !player.isAlive() || player.isSpectator() || mc.player.distanceTo(player) > greetRange.get() || greetAgain.containsKey(player.getUUID())) continue;
			boolean guard = indexOf(team, player.getGameProfile().name()) >= 0;
			if (crowdGreet.is(Greet.EVERYONE) || crowdGreet.is(guard ? Greet.GUARDS : Greet.PLAYERS)) choices.add(player);
		}
		if (choices.isEmpty()) return false;
		greeting = choices.get(random.nextInt(choices.size()));
		greetTicks = GREET_TICKS;
		greetWalkTicks = GREET_WALK_TICKS;
		greeted = false;
		// Empty hands, if there's a free slot, so the swings are punches
		int empty = InventoryUtil.findHotbar(ItemStack::isEmpty);
		if (empty >= 0) mc.player.getInventory().setSelectedSlot(empty);
		return true;
	}

	/** Walks up to them, says hi once there, then crouches, hops and swings about beside them. Swings only wave an arm: they never hit. */
	private void updateGreeting() {
		boolean gone = !greeting.isAlive() || greeting.isRemoved() || greeting.isSpectator();
		if (!gone && !greeted && mc.player.distanceTo(greeting) > GREET_DISTANCE + 0.3) {
			gone = --greetWalkTicks <= 0;
			if (!gone) return;
		}
		if (gone || --greetTicks <= 0) {
			finishGreeting();
			return;
		}
		if (!greeted) {
			greeted = true;
			if (crowdChat.get()) sayLine(greetMessages.get(), greeting);
		}
		crowdSneakTicks = greetTicks % 6 < 3 ? 1 : 0;
		if (random.nextInt(14) == 0) crowdJump = true;
		if (random.nextInt(7) == 0) mc.player.swing(InteractionHand.MAIN_HAND);
	}

	private void finishGreeting() {
		if (greeting != null) greetAgain.put(greeting.getUUID(), System.currentTimeMillis() + (long) (greetCooldown.get() * 1000));
		greeting = null;
		crowdWait = 40 + random.nextInt(120);
	}

	/** The player we're facing, or null. */
	private Player lookingAt() {
		Player best = null;
		double bestAngle = LOOKED_AT_ANGLE;
		Vec3 look = mc.player.getViewVector(1);
		for (Player player : mc.level.players()) {
			if (player == mc.player || !player.isAlive() || player.isSpectator() || mc.player.distanceTo(player) > LOOKED_AT_RANGE) continue;
			double angle = Math.toDegrees(Math.acos(Mth.clamp(look.dot(player.getEyePosition().subtract(mc.player.getEyePosition()).normalize()), -1, 1)));
			if (angle < bestAngle) {
				bestAngle = angle;
				best = player;
			}
		}
		return best;
	}

	private void sayCrowdLine() {
		sayLine(crowdMessages.get(), lookingAt());
	}

	/** Says a random line from a ;-separated list. Lines with {player} name {@code who}, and are left out if there's nobody to name. */
	private void sayLine(String list, Player who) {
		List<String> lines = new ArrayList<>();
		for (String line : list.split(";")) {
			if (!line.isBlank() && (who != null || !line.contains("{player}"))) lines.add(line.trim());
		}
		if (lines.isEmpty()) return;
		String line = lines.get(random.nextInt(lines.size()));
		say(who == null ? line : line.replace("{player}", who.getGameProfile().name()));
	}

	/** Who to fight in a free-for-all: the nearest other guard. */
	private Player ffaTarget() {
		if (paused || rally != null || mc.player.isDeadOrDying()) return null;
		Player best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Player player : mc.level.players()) {
			if (!ffaEnemies.contains(player.getGameProfile().name().toLowerCase(Locale.ROOT)) || !TargetUtil.isValid(player)) continue;
			double distance = mc.player.distanceToSqr(player);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = player;
			}
		}
		return best;
	}

	/** Starts a free-for-all between {@code names}, or everyone in the Guards setting. */
	private void startFfa(List<String> names) {
		endActivity();
		String me = mc.player.getGameProfile().name();
		for (String name : names) {
			if (!name.equalsIgnoreCase(me) && !name.equalsIgnoreCase(principal.get())) ffaEnemies.add(name.toLowerCase(Locale.ROOT));
		}
		if (ffaEnemies.isEmpty()) return;
		activity = Activity.FFA;
		respawnTimer = 0;
		paused = false;
		ceasefire = false;
		ordered = null;
		anchor = null;
		rally = null;
		confirm("FFA!");
	}

	private void endActivity() {
		if (enabledMeow) FemboyWare.getModules().get(AutoMeowModule.class).setEnabled(false);
		enabledMeow = false;
		activity = Activity.NONE;
		ffaEnemies.clear();
		arenaSpot = null;
		arenaFighting = false;
		arenaOut = false;
		wanderTo = null;
		greeting = null;
		greetAgain.clear();
		crowdCenter = null;
		crowdSneakTicks = 0;
		crowdJump = false;
	}

	// ---------------------------------------------------------------- rally

	/** Whether to be heading for the rally point right now, rather than busy staying alive or typing. */
	private boolean rallying() {
		return rally != null && !paused && Mc.screen() == null && !FemboyWare.getModules().get(LegitPotModule.class).isBusy();
	}

	/** Drops everything to go to {@code where}; with {@code holds}, stays there afterwards. */
	private void startRally(Vec3 where, boolean holds) {
		rally = where;
		rallyHolds = holds;
		rallyEnds = System.currentTimeMillis() + RALLY_TIMEOUT;
		paused = false;
		ordered = null;
		target = null;
		if (!holds) anchor = null;
	}

	private void updateRally() {
		pearlAim = null;
		if (rally == null) return;
		Vec3 to = new Vec3(rally.x - mc.player.getX(), 0, rally.z - mc.player.getZ());
		boolean arrived = to.length() < RALLY_ARRIVED;
		if (arrived || System.currentTimeMillis() > rallyEnds) {
			if (arrived && rallyHolds) {
				anchor = rally;
				anchorYaw = mc.player.getYRot();
			}
			rally = null;
			return;
		}
		if (rallying() && rallyPearl.get() && to.length() >= RALLY_PEARL_DISTANCE) throwRallyPearl(to.length());
	}

	/** Number key first, throw on a later tick once the camera is on the arc, as a player would. */
	private void throwRallyPearl(double distance) {
		int slot = InventoryUtil.findHotbar(stack -> stack.is(Items.ENDER_PEARL));
		if (slot < 0 || mc.player.getCooldowns().isOnCooldown(mc.player.getInventory().getItem(slot))) return;
		Vec3 from = mc.player.getEyePosition().subtract(0, 0.1, 0);
		double[] shot = LegitAuraModule.solve(distance, rally.y - from.y, 1.5, 0.03, true);
		if (shot == null) return;
		pearlAim = new Rotation(Rotation.between(from, rally).yaw(), (float) shot[0]);
		if (mc.player.getInventory().getSelectedSlot() != slot) {
			mc.player.getInventory().setSelectedSlot(slot);
			return;
		}
		if (Math.abs(Mth.wrapDegrees(pearlAim.yaw() - mc.player.getYRot())) < 1.5f && Math.abs(pearlAim.pitch() - mc.player.getXRot()) < 1.5f) {
			((MinecraftClientAccessor) mc).femboyware$rightClick();
		}
	}

	/**
	 * An ordered target first, then whoever attacked us (just this guard in Neutral, anyone of us
	 * otherwise), then in Aggressive mode anyone close; nearest the principal wins.
	 */
	private Player pickTarget(Player boss) {
		Player best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Player player : mc.level.players()) {
			if (!TargetUtil.isValid(player)) continue;
			double distance = player.distanceTo(boss);
			if (distance > leash.get()) continue;
			if (ordered != null && player.getGameProfile().name().equalsIgnoreCase(ordered)) return player;
			UUID id = player.getUUID();
			boolean enemy = attackedMe.containsKey(id) || (!mode.is(Mode.NEUTRAL) && enemies.containsKey(id))
				|| (mode.is(Mode.AGGRESSIVE) && distance <= guardRange.get());
			if (enemy && distance < bestDistance) {
				bestDistance = distance;
				best = player;
			}
		}
		return best;
	}

	// ---------------------------------------------------------------- formation

	/** The principal, if they're in view. */
	private Player principal() {
		if (principal.get().isBlank()) return null;
		for (Player player : mc.level.players()) {
			if (player.getGameProfile().name().equalsIgnoreCase(principal.get()) && player.isAlive()) return player;
		}
		return null;
	}

	/** Every guard's name from the Guards setting, plus our own. */
	private List<String> roster() {
		List<String> names = new ArrayList<>();
		for (String name : guards.get().split("[,\\s]+")) {
			if (!name.isEmpty() && !name.equalsIgnoreCase(principal.get())) names.add(name);
		}
		if (mc.player != null && names.stream().noneMatch(name -> name.equalsIgnoreCase(mc.player.getGameProfile().name()))) {
			names.add(mc.player.getGameProfile().name());
		}
		return names;
	}

	/**
	 * The guards who are actually here and alive, sorted by name. Every guard works this list out the
	 * same way from what it sees, so they all agree on the order without telling each other.
	 */
	private List<String> present() {
		List<String> here = new ArrayList<>();
		for (String name : roster()) {
			for (Player player : mc.level.players()) {
				if (player.isAlive() && player.getGameProfile().name().equalsIgnoreCase(name)) {
					here.add(player.getGameProfile().name());
					break;
				}
			}
		}
		here.sort(String.CASE_INSENSITIVE_ORDER);
		return here;
	}

	/** Where we should stand: our slot in the formation, turned to face wherever the principal faces. */
	private Vec3 slot(Player boss) {
		List<String> here = present();
		int count = Math.max(1, here.size());
		int index = Math.max(0, indexOf(here, mc.player.getGameProfile().name()));
		Vec3 center = anchor != null ? anchor : boss.position();
		float yaw = anchor != null ? anchorYaw : boss.getVisualRotationYInDegrees();
		double r = radius.get();
		double spacing = Math.max(1.2, r / 2);
		double side;
		double ahead;
		switch (formation.get()) {
			case FRONT, BACK -> {
				// A half circle in front of or behind them, a guard at each end
				double angle = count == 1 ? 0 : -Math.PI / 2 + Math.PI * index / (count - 1);
				side = Math.sin(angle) * r;
				ahead = Math.cos(angle) * r * (formation.is(Formation.FRONT) ? 1 : -1);
			}
			case LINE -> {
				// Shoulder to shoulder with them, alternating left and right
				int step = index / 2 + 1;
				side = (index % 2 == 0 ? 1 : -1) * step * spacing;
				ahead = 0;
			}
			case COLUMN -> {
				side = 0;
				ahead = -(r + index * spacing);
			}
			case WEDGE -> {
				// An arrowhead ahead of them, the first guard at the point
				int step = (index + 1) / 2;
				side = index == 0 ? 0 : (index % 2 == 1 ? 1 : -1) * step * spacing;
				ahead = r - step * spacing;
			}
			case SQUARE -> {
				// Spread evenly round the edge of a square
				double along = 8.0 * index / count;
				int edge = (int) along;
				double t = along - edge;
				double[][] corners = {{-1, 1}, {1, 1}, {1, -1}, {-1, -1}};
				double[] from = corners[edge / 2 % 4];
				double[] to = corners[(edge / 2 + 1) % 4];
				double f = (edge % 2 + t) / 2;
				side = Mth.lerp(f, from[0], to[0]) * r;
				ahead = Mth.lerp(f, from[1], to[1]) * r;
			}
			default -> {
				double angle = Math.PI * 2 * index / count + Math.PI;
				side = Math.sin(angle) * r;
				ahead = Math.cos(angle) * r;
			}
		}
		double radians = Math.toRadians(yaw);
		Vec3 forward = new Vec3(-Math.sin(radians), 0, Math.cos(radians));
		Vec3 right = new Vec3(-Math.cos(radians), 0, -Math.sin(radians));
		return center.add(right.scale(side)).add(forward.scale(ahead));
	}

	private static int indexOf(List<String> names, String name) {
		for (int i = 0; i < names.size(); i++) {
			if (names.get(i).equalsIgnoreCase(name)) return i;
		}
		return -1;
	}

	/** Whether to walk to our slot now: nothing to fight, not paused, and you aren't steering yourself. */
	private boolean holdingFormation() {
		if (activity != Activity.NONE || paused || target != null || Mc.screen() != null || FemboyWare.getModules().get(LegitPotModule.class).isBusy()) return false;
		if (FemboyWare.getModules().get(LegitAuraModule.class).getTarget() != null) return false;
		Input keys = mc.player.input.keyPresses;
		return !(keys.forward() || keys.backward() || keys.left() || keys.right());
	}

	/** Walks to our slot with the movement keys: far off, faces where it's going and sprints; close by, steps there without turning. */
	@Override
	public void onInput() {
		heading = null;
		Player boss = principal();
		Vec3 goal = walkingTo(boss);
		if (goal == null) {
			stuckTicks = unstickTicks = 0;
			path = null;
			if (activity == Activity.CROWD && (crowdJump || crowdSneakTicks > 0)) {
				mc.player.input.keyPresses = new Input(false, false, false, false, crowdJump && mc.player.onGround(), crowdSneakTicks > 0, false);
			}
			return;
		}
		boolean mirroring = activity == Activity.MIRROR;
		Vec3 pos = mc.player.position();
		Vec3 to = goal.subtract(pos);
		to = new Vec3(to.x, 0, to.z);
		double distance = to.length();
		if (distance < (mirroring ? 0.25 : 0.6)) {
			stuckTicks = unstickTicks = 0;
			path = null;
			if (!mirroring) return;
		}
		Vec3 walk = updatePath(pos, goal, to);
		Vec3 dir = steer(pos, walk, boss);
		heading = dir;
		boolean forward;
		boolean back = false;
		boolean left = false;
		boolean right = false;
		boolean sprint = false;
		if (distance > 3 && path == null && !mirroring) {
			// Only run forwards once the camera has come round to where we're going
			float off = Math.abs(Mth.wrapDegrees(yawOf(dir) - mc.player.getYRot()));
			forward = off < 60;
			sprint = forward && distance > 5 && off < 25;
		} else {
			// Which of the eight key directions points closest to where we're going, relative to where we look
			float diff = Mth.wrapDegrees(yawOf(dir) - mc.player.getYRot());
			forward = Math.abs(diff) < 67.5f;
			back = Math.abs(diff) > 112.5f;
			right = diff > 22.5f && diff < 157.5f;
			left = diff < -22.5f && diff > -157.5f;
		}
		boolean jump = (mc.player.horizontalCollision && mc.player.onGround()) || mc.player.isInWater() || unstickTicks > 0 && mc.player.onGround();
		boolean sneak = false;
		if (path != null && pathIndex < path.size() && mc.player.onGround() && path.get(pathIndex).y > pos.y + 0.4) jump = true;
		// Walks a path instead of running it; it's all short turns
		if (path != null) sprint = false;
		if (mirroring) {
			sneak = boss.isShiftKeyDown();
			sprint = !sneak && (boss.isSprinting() || distance > 2);
			if (mc.player.onGround() && boss.getY() - boss.yo > 0.05) jump = true;
			if (distance < 0.25) forward = back = left = right = false;
		}
		mc.player.input.keyPresses = new Input(forward, back, left, right, jump, sneak, sprint);
		int forwardAxis = (forward ? 1 : 0) - (back ? 1 : 0);
		int strafeAxis = (left ? 1 : 0) - (right ? 1 : 0);
		Vec2 movement = new Vec2(strafeAxis, forwardAxis);
		((InputAccessor) mc.player.input).femboyware$setMovementVector(movement.lengthSquared() > 0 ? movement.normalized() : Vec2.ZERO);
	}

	/** Where to walk this tick, or null to stand still. A rally comes first, then whatever we're doing. */
	private Vec3 walkingTo(Player boss) {
		if (rally != null) return rallying() ? rally : null;
		switch (activity) {
			case MIRROR -> {
				if (boss == null || paused || Mc.screen() != null || FemboyWare.getModules().get(LegitPotModule.class).isBusy()) return null;
				// A step ahead of them, so we keep up rather than trail behind
				return slot(boss).add((boss.getX() - boss.xo) * 2, 0, (boss.getZ() - boss.zo) * 2);
			}
			case CROWD -> {
				if (paused || target != null || Mc.screen() != null || FemboyWare.getModules().get(LegitPotModule.class).isBusy()) return null;
				if (greeting != null) return !greeted && mc.player.distanceTo(greeting) > GREET_DISTANCE ? greeting.position() : null;
				return wanderTo;
			}
			case FFA -> {
				// Run up to them; once close, Legit Aura does the fighting
				if (target == null || paused || Mc.screen() != null || FemboyWare.getModules().get(LegitPotModule.class).isBusy()) return null;
				return mc.player.distanceTo(target) > FFA_CLOSE ? target.position() : null;
			}
			case ARENA -> {
				// Out of the round: stay out of the way where we respawned
				if (arenaOut || paused || Mc.screen() != null || FemboyWare.getModules().get(LegitPotModule.class).isBusy()) return null;
				if (!arenaFighting) return mc.player.position().distanceTo(arenaSpot) > ARENA_SPOT_REACHED ? arenaSpot : null;
				return target != null && mc.player.distanceTo(target) > FFA_CLOSE ? target.position() : null;
			}
			default -> {
				return boss != null && holdingFormation() ? slot(boss) : null;
			}
		}
	}

	/**
	 * Which way to head to reach {@code goal}: straight for it, until we get stuck on something; then
	 * along a path round it, one block at a time, until the path runs out and straight is fine again.
	 * Returns a flat direction, like {@code straight}.
	 */
	private Vec3 updatePath(Vec3 pos, Vec3 goal, Vec3 straight) {
		if (pathCooldown > 0) pathCooldown--;
		boolean stale = path != null && pathGoal.distanceTo(goal) > PATH_STALE;
		if (pathCooldown == 0 && (stuckTicks >= PATH_AFTER || stale)) {
			path = Pathfinder.find(mc.level, BlockPos.containing(pos.add(0, 0.2, 0)), BlockPos.containing(goal), PATH_NODES);
			pathIndex = 0;
			pathGoal = goal;
			pathCooldown = PATH_COOLDOWN;
			stuckTicks = 0;
		}
		if (path == null) return straight;
		while (pathIndex < path.size()) {
			Vec3 next = path.get(pathIndex);
			if (Math.hypot(next.x - pos.x, next.z - pos.z) < 0.55 && Math.abs(next.y - pos.y) < 1.5) pathIndex++;
			else break;
		}
		if (pathIndex >= path.size()) {
			path = null;
			return straight;
		}
		Vec3 next = path.get(pathIndex);
		Vec3 toward = new Vec3(next.x - pos.x, 0, next.z - pos.z);
		return toward.lengthSqr() < 1e-4 ? straight : toward;
	}

	/**
	 * The direction to walk in: straight at the slot, bent round anything in the way.
	 * <ul>
	 * <li>The principal: if the straight line passes too close to them, it swings out to the side the
	 * line already passes on, so we walk round them instead of through them.</li>
	 * <li>Everyone else close by pushes the path away from them, harder the closer they are.</li>
	 * <li>Someone ahead also nudges us to our right. Two guards meeting head-on both keep right, so they
	 * pass each other instead of pushing straight back and forth.</li>
	 * <li>If we've hardly moved for a second and a half anyway, we sidestep and hop for a moment.</li>
	 * </ul>
	 */
	private Vec3 steer(Vec3 pos, Vec3 to, Player boss) {
		double distance = to.length();
		Vec3 dir = to.normalize();
		// On a rally the principal might be nowhere near, or not in view at all
		if (boss != null) {
			Vec3 toBoss = new Vec3(boss.getX() - pos.x, 0, boss.getZ() - pos.z);
			double along = Mth.clamp(toBoss.dot(dir), 0, distance);
			Vec3 closest = dir.scale(along);
			double miss = closest.distanceTo(toBoss);
			if (miss < PRINCIPAL_SPACE && along > 0.3 && along < distance - 0.3) {
				Vec3 out = closest.subtract(toBoss);
				// Dead centre: pick a side from the direction we're going, so every guard decides the same way
				if (out.lengthSqr() < 1e-4) out = new Vec3(-dir.z, 0, dir.x);
				dir = dir.add(out.normalize().scale((PRINCIPAL_SPACE - miss) * 2));
			}
		}
		for (Player player : mc.level.players()) {
			if (player == mc.player || player == boss || !player.isAlive()) continue;
			Vec3 away = new Vec3(pos.x - player.getX(), 0, pos.z - player.getZ());
			double gap = away.length();
			if (gap >= PERSONAL_SPACE || gap < 1e-4) continue;
			double push = (PERSONAL_SPACE - gap) / PERSONAL_SPACE;
			dir = dir.add(away.normalize().scale(push * 1.5));
			// They're in front of us: keep right
			if (away.normalize().dot(to.normalize()) < -0.3) dir = dir.add(new Vec3(-to.z, 0, to.x).normalize().scale(push * 1.5));
		}

		// Stuck against someone or something: sidestep for half a second, always to the same side
		// Sideways only: hopping against a wall is still being stuck
		if (lastPos != null && Math.hypot(lastPos.x - pos.x, lastPos.z - pos.z) < 0.05) stuckTicks++;
		else stuckTicks = 0;
		lastPos = pos;
		if (stuckTicks > 30) {
			unstickTicks = 10;
			stuckTicks = 0;
		}
		if (unstickTicks > 0) {
			unstickTicks--;
			dir = dir.add(new Vec3(-to.z, 0, to.x).normalize().scale(1.5));
		}
		return dir.lengthSqr() < 1e-4 ? to.normalize() : dir.normalize();
	}

	/** The camera yaw that faces along {@code dir}. */
	private static float yawOf(Vec3 dir) {
		return (float) Math.toDegrees(Math.atan2(dir.z, dir.x)) - 90;
	}

	/** Turns the camera like a mouse: the way we're walking while running to the slot, then out away from the principal to watch for trouble. */
	@Override
	public void onFrame() {
		long now = System.nanoTime();
		float seconds = lastFrame == 0 ? 0 : Math.min(0.25f, (now - lastFrame) / 1e9f);
		lastFrame = now;
		if (rally == null && activity != Activity.NONE) {
			activityCamera(seconds);
			return;
		}
		if (rally != null) {
			if (seconds > 0 && rallying()) {
				if (pearlAim != null) {
					float turn = 1 - (float) Math.exp(-12 * seconds);
					mouse.turn(Mth.wrapDegrees(pearlAim.yaw() - mc.player.getYRot()) * turn, (pearlAim.pitch() - mc.player.getXRot()) * turn);
				} else walkCamera(seconds);
			}
			return;
		}
		Player boss = principal();
		if (seconds <= 0 || boss == null || !holdingFormation()) return;
		Vec3 slot = slot(boss);
		double distance = Math.sqrt(mc.player.position().distanceToSqr(slot.x, mc.player.getY(), slot.z));
		Rotation wanted;
		if (heading != null && (distance > 3 || path != null)) {
			walkCamera(seconds);
			return;
		}
		else if (distance < 1.5) wanted = new Rotation(Rotation.between(boss.position(), mc.player.position()).yaw(), 0);
		else return;
		float follow = 1 - (float) Math.exp(-6 * seconds);
		mouse.turn(Mth.wrapDegrees(wanted.yaw() - mc.player.getYRot()) * follow, (wanted.pitch() - mc.player.getXRot()) * follow);
	}

	/** Looks the way we're walking, turning smoothly and no faster than a person would. */
	private void walkCamera(float seconds) {
		if (heading == null) return;
		Vec3 look = heading;
		if (path != null && pathIndex < path.size()) {
			Vec3 ahead = path.get(Math.min(pathIndex + PATH_LOOK_AHEAD, path.size() - 1));
			Vec3 toAhead = new Vec3(ahead.x - mc.player.getX(), 0, ahead.z - mc.player.getZ());
			if (toAhead.lengthSqr() > 0.25) look = toAhead.normalize();
		}
		float most = WALK_TURN_SPEED * seconds;
		float follow = 1 - (float) Math.exp(-3 * seconds);
		float yaw = Mth.clamp(Mth.wrapDegrees(yawOf(look) - mc.player.getYRot()) * follow, -most, most);
		float pitch = Mth.clamp((5 - mc.player.getXRot()) * follow, -most, most);
		mouse.turn(yaw, pitch);
	}

	/** Mirror copies where the principal looks; crowd looks where it's going or at whoever's near. A free-for-all leaves it to Legit Aura. */
	private void activityCamera(float seconds) {
		if (seconds <= 0 || paused || target != null || Mc.screen() != null) return;
		Rotation aim = null;
		float speed = 4;
		if (activity == Activity.MIRROR) {
			Player boss = principal();
			if (boss != null) aim = new Rotation(boss.getYRot(), boss.getXRot());
			speed = 16;
		} else if (activity == Activity.ARENA) {
			// Walking to the line looks ahead; the fighting is Legit Aura's
			if (heading != null) walkCamera(seconds);
			return;
		} else if (activity == Activity.CROWD) {
			if (heading != null) {
				walkCamera(seconds);
				return;
			} else {
				Player near = greeting != null ? greeting : TargetUtil.closestAny(12);
				if (near != null) aim = Rotation.between(mc.player.getEyePosition(), near.getEyePosition());
			}
		}
		if (aim == null) return;
		float turn = 1 - (float) Math.exp(-speed * seconds);
		mouse.turn(Mth.wrapDegrees(aim.yaw() - mc.player.getYRot()) * turn, (aim.pitch() - mc.player.getXRot()) * turn);
	}

	// ---------------------------------------------------------------- orders

	/** Carries out a chat message from the principal if it's an order. */
	private void order(String message) {
		String text = message.trim();
		if (!text.startsWith(prefix.get()) || prefix.get().isEmpty()) return;
		run(text.substring(prefix.get().length()).trim());
	}

	/** Carries out one order, without its prefix. */
	private void run(String body) {
		String[] words = body.split("\\s+");
		if (words.length == 0 || words[0].isEmpty()) return;
		String command = words[0].toLowerCase(Locale.ROOT);
		switch (command) {
			case "formation", "f" -> {
				if (words.length < 2) {
					confirm("Formations: circle, front, back, line, column, wedge, square");
					return;
				}
				for (Formation shape : Formation.values()) {
					if (shape.name().startsWith(words[1].toUpperCase(Locale.ROOT))) {
						formation.set(shape);
						confirm("Formation: " + EnumSetting.prettify(shape.name()));
						return;
					}
				}
				confirm("Unknown formation " + words[1]);
			}
			case "radius", "spread" -> {
				if (words.length < 2) return;
				try {
					radius.set(Mth.clamp(Double.parseDouble(words[1]), 1.5, 10));
					confirm("Radius: " + radius.get());
				} catch (NumberFormatException ignored) {
				}
			}
			case "pause", "hold" -> {
				paused = true;
				rally = null;
				confirm("Paused");
			}
			case "resume", "go" -> {
				paused = false;
				ceasefire = false;
				confirm("Resumed");
			}
			case "attack", "kill", "target" -> {
				if (words.length < 2) return;
				ordered = words[1];
				paused = false;
				ceasefire = false;
				rally = null;
				endActivity();
				confirm("Attacking " + ordered);
			}
			case "stop", "ceasefire" -> {
				// Drop every grudge and order, and fight nobody (not even back) until told otherwise
				ordered = null;
				enemies.clear();
				attackedMe.clear();
				ceasefire = true;
				target = null;
				rally = null;
				endActivity();
				confirm("Standing down; no fighting until !resume or a new order");
			}
			case "say" -> say(body.substring(words[0].length()).trim());
			case "neutral", "n" -> setMode(Mode.NEUTRAL);
			case "mode" -> {
				if (words.length < 2) return;
				String wanted = words[1].toLowerCase(Locale.ROOT);
				setMode(wanted.startsWith("a") ? Mode.AGGRESSIVE : wanted.startsWith("n") ? Mode.NEUTRAL : Mode.DEFEND);
			}
			case "stay" -> {
				Player boss = principal();
				if (boss == null) return;
				anchor = boss.position();
				anchorYaw = boss.getVisualRotationYInDegrees();
				confirm("Holding here");
			}
			case "rally", "r" -> {
				// Over the link it says where; in chat, the principal has to be in view
				Player boss = principal();
				Vec3 where = parsePlace(words);
				if (where == null && boss != null) where = boss.position();
				if (where == null) return;
				startRally(where, false);
				confirm("Rallying");
			}
			case "goto" -> {
				Vec3 where = parsePlace(words);
				if (where == null) return;
				startRally(where, true);
				confirm("On the way");
			}
			case "mirror" -> {
				boolean off = words.length > 1 && words[1].equalsIgnoreCase("off");
				endActivity();
				if (!off) {
					activity = Activity.MIRROR;
					anchor = null;
					paused = false;
				}
				confirm(off ? "Mirror off" : "Mirroring");
			}
			case "crowd" -> {
				boolean off = words.length > 1 && words[1].equalsIgnoreCase("off");
				endActivity();
				if (!off) {
					Vec3 middle = parsePlace(words);
					Player boss = principal();
					if (middle == null) middle = boss != null ? boss.position() : mc.player.position();
					crowdCenter = middle;
					crowdWait = random.nextInt(40);
					nextCrowdChat = 0;
					activity = Activity.CROWD;
					enabledMeow = crowdMeow.get() && turnOn(AutoMeowModule.class);
					anchor = null;
					paused = false;
				}
				confirm(off ? "Crowd off" : "Hanging out");
			}
			case "ffa" -> {
				if (words.length > 1 && (words[1].equalsIgnoreCase("stop") || words[1].equalsIgnoreCase("off"))) {
					endActivity();
					confirm("FFA over");
				} else startFfa(words.length > 1 ? List.of(words[1].split(",")) : roster());
			}
			case "arena" -> arenaOrder(words);
			case "follow" -> {
				endActivity();
				anchor = null;
				rally = null;
				confirm("Following");
			}
			case "guards" -> {
				guards.set(String.join(" ", List.of(words).subList(1, words.length)));
				confirm("Guards: " + String.join(", ", roster()));
			}
			case "status" -> confirm((paused ? "Paused, " : "") + (ceasefire ? "Ceasefire, " : "") + EnumSetting.prettify(mode.get().name()) + ", "
				+ EnumSetting.prettify(formation.get().name()) + ", " + present().size() + " guards here"
				+ (target != null ? ", fighting " + target.getName().getString() : ""));
			default -> {
			}
		}
	}

	/**
	 * The arena, run by the principal's Guard Command: {@code arena setup x y z radius pink,names blue,names} lines the teams
	 * up on opposite sides of the middle, {@code arena fight} starts the round, {@code arena out name} drops someone who died
	 * from the round, and {@code arena end} finishes.
	 */
	private void arenaOrder(String[] words) {
		String step = words.length > 1 ? words[1].toLowerCase(Locale.ROOT) : "";
		switch (step) {
			case "setup" -> {
				if (words.length < 8) return;
				Vec3 middle;
				double radius;
				try {
					middle = new Vec3(Double.parseDouble(words[2]), Double.parseDouble(words[3]), Double.parseDouble(words[4]));
					radius = Double.parseDouble(words[5]);
				} catch (NumberFormatException e) {
					return;
				}
				List<String> pink = List.of(words[6].split(",")), blue = List.of(words[7].split(","));
				String me = mc.player.getGameProfile().name();
				int index = indexOf(pink, me);
				boolean onPink = index >= 0;
				if (!onPink) index = indexOf(blue, me);
				// Not playing: carry on as before
				if (index < 0) return;
				List<String> team = onPink ? pink : blue;
				endActivity();
				for (String name : onPink ? blue : pink) ffaEnemies.add(name.toLowerCase(Locale.ROOT));
				double side = (onPink ? -1 : 1) * radius * ARENA_LINE, along = (index - (team.size() - 1) / 2.0) * ARENA_GAP;
				arenaSpot = middle.add(side, 0, along);
				activity = Activity.ARENA;
				respawnTimer = 0;
				paused = false;
				ceasefire = false;
				ordered = null;
				anchor = null;
				rally = null;
				confirm("Lining up");
			}
			case "fight" -> {
				if (activity == Activity.ARENA) arenaFighting = true;
			}
			case "out" -> {
				if (activity == Activity.ARENA && words.length > 2) ffaEnemies.remove(words[2].toLowerCase(Locale.ROOT));
			}
			case "end" -> {
				if (activity != Activity.ARENA) return;
				endActivity();
				confirm("Arena over");
			}
			default -> {
			}
		}
	}

	/** The x, y and z after an order's name, or null if they aren't there. */
	private static Vec3 parsePlace(String[] words) {
		if (words.length < 4) return null;
		try {
			return new Vec3(Double.parseDouble(words[1]), Double.parseDouble(words[2]), Double.parseDouble(words[3]));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** Types {@code message} in chat exactly as written, or runs it as a command if it starts with a slash. Every guard does it, even while paused. */
	private void say(String message) {
		String clean = StringUtil.trimChatMessage(StringUtil.filterText(message));
		if (clean.isEmpty() || mc.getConnection() == null) return;
		if (clean.startsWith("/")) {
			if (clean.length() > 1) mc.getConnection().sendCommand(clean.substring(1));
		} else mc.getConnection().sendChat(clean);
	}

	/** Switches mode and lifts any ceasefire. Neutral also forgets who attacked the others, so guards drop those fights now. */
	private void setMode(Mode wanted) {
		mode.set(wanted);
		ceasefire = false;
		if (wanted == Mode.NEUTRAL) enemies.clear();
		confirm("Mode: " + EnumSetting.prettify(wanted.name()));
	}

	/**
	 * Whispers the principal to confirm an order. Only the first guard present (by name) answers,
	 * which every guard works out the same way, so the principal gets one reply, not one per guard.
	 */
	private void confirm(String text) {
		if (!reply.get() || mc.getConnection() == null) return;
		List<String> here = present();
		if (!here.isEmpty() && !here.get(0).equalsIgnoreCase(mc.player.getGameProfile().name())) return;
		long now = System.currentTimeMillis();
		if (now - lastReply < 1000) return;
		lastReply = now;
		if (linkOrder && link != null) link.reply(text);
		else mc.getConnection().sendCommand("msg " + principal.get() + " " + text);
	}
}
