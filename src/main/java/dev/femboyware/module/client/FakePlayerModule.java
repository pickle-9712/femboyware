package dev.femboyware.module.client;

import dev.femboyware.util.Mc;
import com.mojang.authlib.GameProfile;
import dev.femboyware.FemboyWare;
import dev.femboyware.event.PacketEvent;
import dev.femboyware.gui.FormationMakerScreen;
import dev.femboyware.manager.CommandManager;
import dev.femboyware.mixin.PlayerListEntryAccessor;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.module.render.TungSahurModule;
import dev.femboyware.module.utility.NameProtectModule;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.setting.StringSetting;
import dev.femboyware.util.AccountSkins;
import dev.femboyware.util.ChatUtil;
import dev.femboyware.util.DamageCalculator;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Spawns client-side players that stand still and take damage, so combat modules can be
 * tested without a second account. Melee hits never reach the server (see the interaction
 * manager mixin) and explosions are applied from the server's explosion packets.
 */
public class FakePlayerModule extends Module {
	private static final int FIRST_ENTITY_ID = -1337;
	/** Put together to guess at real usernames once Name Protect's list runs out. Mojang confirms each one before it's used. */
	private static final String[] NAME_STARTS = {"Sleepy", "Cozy", "Happy", "Silver", "Tiny", "Minty", "Shadow", "Ice", "Pixel", "Nova", "Sky", "Luna", "Ghost", "Fire", "Blaze", "Dark", "Golden", "Lucky", "Crazy", "Super", "Red", "Blue", "Green", "Pink", "Purple", "Black", "White", "Sweet", "Wild", "Night", "Storm", "Frost", "Mad", "Big", "Little", "Epic", "Cool", "Toxic", "Angry", "Lazy"};
	private static final String[] NAME_ENDS = {"Moon", "Bear", "Panda", "Fox", "Duck", "Cat", "Wolf", "Dragon", "King", "Ninja", "Storm", "Tiger", "Angel", "Hero", "Star", "Knight", "Gamer", "Boy", "Girl", "Bunny", "Dog", "Lion", "Hawk", "Rider", "Hunter", "Wizard", "Pirate", "Shark", "Monkey", "Queen", "Slayer", "Sniper", "Ghost", "Fire", "Rose", "Sky", "Bee", "Frog", "Owl", "Pig"};
	private static final int MIN_PING = 20;
	private static final int MAX_PING = 90;
	private static final EquipmentSlot[] ARMOR_SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

	private final StringSetting names = add(new StringSetting("Names", "One fake player for each name, separated by commas. Or use " + CommandManager.PREFIX + "fp add <names>", "FakePlayer"));
	private final BoolSetting accountSkins = add(new BoolSetting("Account Skins", "Each fake player wears the real skin of the account with its name", true));
	private final NumberSetting health = add(new NumberSetting("Health", "Health the fake player spawns with", 20, 1, 20, 1));
	private final BoolSetting copyArmor = add(new BoolSetting("Copy Armor", "Give it your armor and held items", true));
	private final BoolSetting respawn = add(new BoolSetting("Respawn", "Respawn after dying, in the same spot or on the Crowd circle", true));
	private final NumberSetting respawnDelay = add(new NumberSetting("Respawn Delay", "Ticks to wait before respawning", 40, 0, 200, 1)).visibleWhen(respawn::get);
	private final BoolSetting invincible = add(new BoolSetting("Invincible", "Takes hits and flinches but never loses health", false));
	private final BoolSetting crowd = add(new BoolSetting("Crowd", "Fake players spawn in a circle, walk around inside it, jump, crouch and go up to people to say hi", false));
	private final NumberSetting wanderRadius = add(new NumberSetting("Wander Radius", "Size of the circle they spawn on and wander inside, in blocks", 6, 2, 32, 1)).visibleWhen(crowd::get);
	private final BoolSetting crowdChat = add(new BoolSetting("Crowd Chat", "They talk in your chat, which only you can see", true)).visibleWhen(crowd::get);
	private final NumberSetting crowdChatEvery = add(new NumberSetting("Crowd Chat Every", "About how many seconds between each one's messages", 30, 5, 300, 5)).visibleWhen(() -> crowd.get() && crowdChat.get());
	private final StringSetting crowdMessages = add(new StringSetting("Crowd Messages", "What they say, separated by ;. {player} is who they're with, and those lines only get used when they're with someone", "hi :3; hii :3; heyy :3; hi chat :3; hewwo :3; hi {player} :3; hey {player} :3; i see you {player} :3; uwu; owo; :3; nya~; i like your skin :3; who wants to be friends :3; im so sleepy :3; you're cute :3")).visibleWhen(() -> crowd.get() && crowdChat.get());
	private final StringSetting greetMessages = add(new StringSetting("Greet Messages", "What they say to whoever they walk up to, and when someone says hi to them, separated by ;. {player} is their name", "hi {player} :3; hii {player} :3; heyy {player} :3; hewwo {player} :3; {player} hii :3; i like your skin {player} :3; {player} wanna be friends :3; boop {player} :3")).visibleWhen(() -> crowd.get() && crowdChat.get());
	private final EnumSetting<FakeFormations.Shape> formation = add(new EnumSetting<>("Formation", "A shape they walk to and stand in, instead of wandering", FakeFormations.Shape.NONE));
	private final StringSetting customFormation = add(new StringSetting("Custom Formation", "Name of the formation to use, made with " + CommandManager.PREFIX + "fp maker", "")).visibleWhen(() -> formation.is(FakeFormations.Shape.CUSTOM));
	private final NumberSetting formationSpacing = add(new NumberSetting("Formation Spacing", "Blocks between neighbours in the formation", 2, 1, 8, 0.5)).visibleWhen(this::inFormation);
	private final BoolSetting formationFollow = add(new BoolSetting("Formation Follow", "The formation moves with you, instead of staying where you started it", false)).visibleWhen(this::inFormation);
	private final BoolSetting formationTurn = add(new BoolSetting("Formation Turn", "The formation also turns when you look around", false)).visibleWhen(() -> inFormation() && formationFollow.get());
	private final BoolSetting openMaker = add(new BoolSetting("Open Formation Maker", "Opens the screen for drawing your own formations. Or use " + CommandManager.PREFIX + "fp maker", false)).visibleWhen(this::isEnabled);
	private final StringSetting formationText = add(new StringSetting("Formation Text", "What they spell out, with / for a new line. Adds as many fake players as it takes", "hi")).visibleWhen(() -> formation.is(FakeFormations.Shape.TEXT));
	/** The formations you've made. Only saved, never shown: the maker edits them. */
	private final StringSetting savedFormations = add(new StringSetting("Saved Formations", "", "")).visibleWhen(() -> false);

	/** One fake player; the entity is null while it waits to respawn. */
	private static final class Fake {
		final String name;
		final int id;
		final GameProfile profile;
		/** Its row in the tab list, there even while it waits to respawn, like a real player's. */
		final PlayerInfo listEntry;
		RemotePlayer entity;
		Vec3 spawnPos;
		float spawnYaw;
		/** The middle of its group's circle, and its place around it. */
		Vec3 home;
		double ringAngle;
		/** Its spot in the formation, or -1 if it has none. */
		int slot = -1;
		float lastDamage;
		int invulnerableTicks;
		int deadTicks;
		/** Just spawned, and not put on the ground yet. */
		boolean landing;
		FakeWanderer wanderer = new FakeWanderer();
		long nextChat;
		/** Someone just said hi: who to answer, and in how many ticks. */
		Player replyTo;
		int replyTicks;

		Fake(String name, int id) {
			this.name = name;
			this.id = id;
			profile = new GameProfile(UUID.randomUUID(), name);
			listEntry = new PlayerInfo(profile, false);
			((PlayerListEntryAccessor) listEntry).femboyware$setLatency(ThreadLocalRandom.current().nextInt(MIN_PING, MAX_PING));
		}
	}

	private static final int MIN_REPLY_TICKS = 20;
	private static final int REPLY_TICKS_RANGE = 30;
	private static final int LISTEN_TICKS = 25;
	private static final double CHAT_NEAR = 10;
	private static final int MOST_LAND_LIFT = 8;

	private final List<Fake> fakes = new ArrayList<>();
	/** The same fake players by UUID: skins and tab rows ask about each one every frame, which adds up with a lot of them. */
	private final Map<UUID, Fake> fakesById = new HashMap<>();
	private List<String> namesParsed = List.of();
	private String namesParsedFrom;
	/** How many names the search for real accounts is working up to, and whether it should keep at it. */
	private int namesGoal;
	private volatile boolean findingNames;
	private boolean finderRunning;
	private boolean announceFound;
	private int nextId = FIRST_ENTITY_ID;
	private boolean spawned;
	private String syncedFrom = "";
	/** The running formation: where its middle is, which way it faces, and its spots. A null middle means it hasn't started. */
	private Vec3 formationCenter;
	private float formationYaw;
	private List<FakeFormations.Spot> slots = List.of();
	/** What {@link #slots} were worked out for, so they're only handed out again when it changes. */
	private String slotsFor;

	public FakePlayerModule() {
		super("Fake Player", "Spawns stationary fake players to test modules on", Category.WORLD);
	}

	@Override
	public String getInfo() {
		if (fakes.size() != 1) return fakes.isEmpty() ? null : String.valueOf(fakes.size());
		RemotePlayer entity = fakes.getFirst().entity;
		return entity == null ? null : String.format("%.1f", entity.getHealth());
	}

	@Override
	protected void onEnable() {
		// May be enabled from the config before a world exists; onTick spawns them then
		spawned = false;
		if (!nullCheck()) spawnAll();
	}

	@Override
	protected void onDisable() {
		findingNames = false;
		despawnAll();
		spawned = false;
	}

	@Override
	public void onTick() {
		if (openMaker.get()) {
			openMaker.set(false);
			Mc.setScreen(new FormationMakerScreen(this));
		}
		boolean moved = false;
		for (Fake fake : fakes) moved |= fake.entity != null && fake.entity.level() != mc.level;
		// After a world change or dimension switch, spawn new ones next to us
		if (!spawned || moved) {
			spawnAll();
			return;
		}
		// Not while a screen is open, so typing a name doesn't spawn every half of it
		if (Mc.screen() == null && !names.get().equals(syncedFrom)) sync();
		tickFormation();

		boolean removed = false;
		for (Iterator<Fake> iterator = fakes.iterator(); iterator.hasNext(); ) {
			Fake fake = iterator.next();
			if (fake.invulnerableTicks > 0) fake.invulnerableTicks--;
			if (fake.entity != null && !fake.entity.isDeadOrDying()) {
				if (fake.landing) fake.landing = !land(fake);
				Vec3 spot = formationSpot(fake);
				if (spot != null) fake.wanderer.tickFormation(fake.entity, mc.level, spot, formationYaw);
				else if (crowd.get()) tickCrowd(fake);
				else fake.wanderer.stop(fake.entity, mc.level);
				continue;
			}

			// Leave the body for the death animation, and for Tung Sahur's bonking, before removing it
			if (fake.entity != null && (fake.deadTicks < 20 || FemboyWare.getModules().get(TungSahurModule.class).holdsBody(fake.entity))) {
				fake.deadTicks = Math.min(fake.deadTicks + 1, 20);
				continue;
			}
			if (fake.entity != null) despawn(fake);
			fake.deadTicks++;
			if (!respawn.get()) {
				iterator.remove();
				fakesById.remove(fake.profile.id());
				removed = true;
			} else if (fake.deadTicks >= 20 + respawnDelay.getInt()) spawn(fake, spawnSpot(fake), fake.spawnYaw);
		}
		if (removed && fakes.isEmpty()) setEnabled(false);
	}

	/** Drops a fresh spawn onto whatever's below it, or lifts it out if it's inside something. Returns false to try again, while its chunk hasn't arrived. */
	private boolean land(Fake fake) {
		RemotePlayer entity = fake.entity;
		if (!mc.level.hasChunkAt(entity.blockPosition())) return false;
		AABB box = entity.getBoundingBox();
		for (int up = 0; up < MOST_LAND_LIFT && !mc.level.noCollision(entity, box); up++) box = box.move(0, 1, 0);
		double range = box.minY - mc.level.getMinY();
		double fall = Entity.collideBoundingBox(entity, new Vec3(0, -range, 0), box, mc.level, List.of()).y;
		// Nothing but void below: stay up, rather than falling out of the world
		double y = fall <= -range ? box.minY : box.minY + fall;
		entity.snapTo(entity.getX(), y, entity.getZ(), entity.getYRot(), entity.getXRot());
		// So it doesn't count as lost for being far below the middle of its circle
		if (y < fake.home.y) fake.home = new Vec3(fake.home.x, y, fake.home.z);
		// It picks up where it is from the entity, the next time it moves
		fake.wanderer = new FakeWanderer();
		return true;
	}

	private boolean inFormation() {
		return !formation.is(FakeFormations.Shape.NONE);
	}

	/** Starts, follows and ends the formation, and hands out new spots when the shape or the group changes. */
	private void tickFormation() {
		if (!inFormation()) {
			if (formationCenter != null) endFormation();
			return;
		}
		if (formationCenter == null || (formationFollow.get() && formationTurn.get())) formationYaw = mc.player.getYRot();
		if (formationCenter == null || formationFollow.get()) formationCenter = mc.player.position();
		String wanted = formation.get() + "/" + customFormation.get() + "/" + formationText.get() + "/" + savedFormations.get() + "/" + fakes.size();
		if (wanted.equals(slotsFor)) return;
		slotsFor = wanted;
		if (formation.is(FakeFormations.Shape.TEXT)) {
			Set<FakeFormations.Cell> letters = FakeFormations.textCells(formationText.get());
			addFakesUpTo(letters.size(), false);
			slots = FakeFormations.spots(letters, fakes.size());
		} else if (formation.is(FakeFormations.Shape.CUSTOM)) {
			Set<FakeFormations.Cell> custom = customFormations().get(customFormation.get());
			slots = custom == null ? List.of() : FakeFormations.spots(custom, fakes.size());
		} else slots = FakeFormations.spots(formation.get(), fakes.size());
		assignSlots();
	}

	/** Gives each fake player the closest spot still free, so they don't all cross over each other. Any left over carry on as they were. */
	private void assignSlots() {
		boolean[] taken = new boolean[slots.size()];
		Vec3[] spots = new Vec3[taken.length];
		for (int i = 0; i < spots.length; i++) spots[i] = worldSpot(slots.get(i));
		for (Fake fake : fakes) {
			fake.slot = -1;
			Vec3 from = fake.entity != null ? fake.entity.position() : fake.spawnPos;
			double closest = Double.MAX_VALUE;
			for (int i = 0; i < taken.length; i++) {
				double distance = taken[i] ? Double.MAX_VALUE : from.distanceToSqr(spots[i]);
				if (distance < closest) {
					closest = distance;
					fake.slot = i;
				}
			}
			if (fake.slot >= 0) taken[fake.slot] = true;
		}
	}

	/** Everyone stays where the formation left them, and wanders from there if Crowd is on. */
	private void endFormation() {
		for (Fake fake : fakes) {
			fake.slot = -1;
			fake.home = formationCenter;
			if (fake.entity != null) fake.spawnPos = fake.entity.position();
		}
		formationCenter = null;
		slots = List.of();
		slotsFor = null;
	}

	private Vec3 formationSpot(Fake fake) {
		return formationCenter != null && fake.slot >= 0 && fake.slot < slots.size() ? worldSpot(slots.get(fake.slot)) : null;
	}

	private Vec3 worldSpot(FakeFormations.Spot spot) {
		double spacing = formationSpacing.get(), yaw = Math.toRadians(formationYaw);
		double forwardX = -Math.sin(yaw), forwardZ = Math.cos(yaw);
		// Facing south (+Z), your right hand is to the west (-X)
		double rightX = -forwardZ, rightZ = forwardX;
		return formationCenter.add((spot.right() * rightX + spot.forward() * forwardX) * spacing, 0, (spot.right() * rightZ + spot.forward() * forwardZ) * spacing);
	}

	/** The maker's saved formations, by name. */
	public Map<String, Set<FakeFormations.Cell>> customFormations() {
		return FakeFormations.decode(savedFormations.get());
	}

	public void saveFormation(String name, Set<FakeFormations.Cell> cells) {
		Map<String, Set<FakeFormations.Cell>> saved = customFormations();
		saved.put(name, new LinkedHashSet<>(cells));
		savedFormations.set(FakeFormations.encode(saved));
		FemboyWare.getConfig().save();
	}

	public void deleteFormation(String name) {
		Map<String, Set<FakeFormations.Cell>> saved = customFormations();
		saved.remove(name);
		savedFormations.set(FakeFormations.encode(saved));
		if (formation.is(FakeFormations.Shape.CUSTOM) && customFormation.get().equals(name)) formation.set(FakeFormations.Shape.NONE);
		FemboyWare.getConfig().save();
	}

	/** Switches to a built-in or saved formation by name; false if there's none called that. */
	public boolean useFormation(String name) {
		FakeFormations.Shape shape = FakeFormations.builtIn(name);
		String custom = null;
		for (String saved : customFormations().keySet()) {
			if (saved.equalsIgnoreCase(name)) custom = saved;
		}
		if (custom != null) {
			customFormation.set(custom);
			shape = FakeFormations.Shape.CUSTOM;
		}
		if (shape == null) return false;
		formation.set(shape);
		moveFormationHere();
		if (!isEnabled()) setEnabled(true);
		return true;
	}

	/** Has them spell {@code text}; false if there's nothing in it that can be spelled. */
	public boolean spellText(String text) {
		int needed = FakeFormations.textCells(text).size();
		if (needed == 0) return false;
		formationText.set(text);
		formation.set(FakeFormations.Shape.TEXT);
		moveFormationHere();
		if (!isEnabled()) setEnabled(true);
		addFakesUpTo(needed, false);
		return true;
	}

	/**
	 * Adds real accounts' names, in the background, until there are {@code total}: every name is checked with Mojang first, so
	 * each fake player has an account's skin to wear. They join a few at a time as they're found.
	 */
	private void addFakesUpTo(int total, boolean announce) {
		if (parsedNames().size() >= total) return;
		namesGoal = findingNames ? Math.max(namesGoal, total) : total;
		findingNames = true;
		announceFound |= announce;
		if (finderRunning) return;
		finderRunning = true;
		List<String> free = FemboyWare.getModules().get(NameProtectModule.class).unusedFakeNames();
		Collections.shuffle(free);
		Iterator<String> listed = free.iterator();
		Iterator<String> candidates = new Iterator<>() {
			@Override
			public boolean hasNext() {
				return true;
			}

			@Override
			public String next() {
				return listed.hasNext() ? listed.next() : madeUpName();
			}
		};
		AccountSkins.findAccounts(candidates, () -> findingNames, this::addFoundNames, this::nameSearchFinished);
	}

	private static String madeUpName() {
		ThreadLocalRandom random = ThreadLocalRandom.current();
		String start = NAME_STARTS[random.nextInt(NAME_STARTS.length)], end = NAME_ENDS[random.nextInt(NAME_ENDS.length)];
		return switch (random.nextInt(4)) {
			case 0 -> start + random.nextInt(100);
			case 1 -> end + random.nextInt(100);
			default -> start + end;
		};
	}

	private void addFoundNames(List<String> found) {
		if (!findingNames) return;
		List<String> wanted = wantedNames();
		Set<String> taken = new HashSet<>();
		for (String name : wanted) taken.add(name.toLowerCase(Locale.ROOT));
		// Real players here already have their names
		taken.add(mc.getUser().getName().toLowerCase(Locale.ROOT));
		if (mc.getConnection() != null) {
			for (PlayerInfo entry : mc.getConnection().getOnlinePlayers()) taken.add(entry.getProfile().name().toLowerCase(Locale.ROOT));
		}
		for (String name : found) {
			if (wanted.size() < namesGoal && taken.add(name.toLowerCase(Locale.ROOT))) wanted.add(name);
		}
		names.set(String.join(", ", wanted));
		if (wanted.size() >= namesGoal) findingNames = false;
	}

	private void nameSearchFinished(boolean reachedGoal) {
		finderRunning = false;
		if (!reachedGoal && findingNames) ChatUtil.info("Couldn't find enough real accounts, Mojang stopped answering. Got " + parsedNames().size() + " of " + namesGoal + " fake players");
		else if (announceFound && reachedGoal && parsedNames().size() >= namesGoal) ChatUtil.info("Added them all: " + parsedNames().size() + " fake players");
		findingNames = false;
		announceFound = false;
	}

	public void stopFormation() {
		formation.set(FakeFormations.Shape.NONE);
	}

	/** Starts the formation again around you, facing the way you are. */
	public void moveFormationHere() {
		formationCenter = null;
		slotsFor = null;
	}

	public int fakeCount() {
		return fakes.size();
	}

	/** What's running, to show in the maker. */
	public String formationName() {
		if (formation.is(FakeFormations.Shape.CUSTOM)) return customFormation.get();
		return FakeFormations.displayName(formation.get());
	}

	private void tickCrowd(Fake fake) {
		fake.wanderer.tick(fake.entity, mc.level, fake.home, wanderRadius.get(), mc.level.players(), other -> {
			if (crowdChat.get()) say(fake, greetMessages.get(), other);
			// Another fake player stops to look and says hi back
			Fake them = fakeOf(other);
			if (them == null) return;
			them.wanderer.lookAt(fake.entity, LISTEN_TICKS);
			them.replyTo = fake.entity;
			them.replyTicks = MIN_REPLY_TICKS + ThreadLocalRandom.current().nextInt(REPLY_TICKS_RANGE);
		});
		if (fake.replyTo != null && --fake.replyTicks <= 0) {
			if (crowdChat.get() && !fake.replyTo.isRemoved()) say(fake, greetMessages.get(), fake.replyTo);
			fake.replyTo = null;
		}
		if (!crowdChat.get()) return;
		long now = System.currentTimeMillis();
		if (now >= fake.nextChat) {
			if (fake.nextChat != 0) say(fake, crowdMessages.get(), withWhom(fake));
			// Spread out, so they don't all talk at once
			fake.nextChat = now + (long) (crowdChatEvery.get() * (0.5 + ThreadLocalRandom.current().nextDouble()) * 1000);
		}
	}

	/** Who a fake player is talking to, or else whoever's closest; null if it's alone. */
	private Player withWhom(Fake fake) {
		Player talkingTo = fake.wanderer.talkingTo();
		if (talkingTo != null) return talkingTo;
		Player best = null;
		double bestDistance = CHAT_NEAR * CHAT_NEAR;
		for (Player player : mc.level.players()) {
			if (player == fake.entity || player.getUUID().equals(fake.profile.id())) continue;
			double distance = player.distanceToSqr(fake.entity);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = player;
			}
		}
		return best;
	}

	/** Shows a random line as a chat message from {@code fake}. Only in your chat, and never sent to the server. */
	private void say(Fake fake, String lines, Player to) {
		List<String> usable = new ArrayList<>();
		for (String line : lines.split(";")) {
			if (!line.isBlank() && (to != null || !line.contains("{player}"))) usable.add(line.trim());
		}
		if (usable.isEmpty()) return;
		String line = usable.get(ThreadLocalRandom.current().nextInt(usable.size()));
		if (to != null) line = line.replace("{player}", to.getGameProfile().name());
		Mc.addChat(Component.literal("<" + fake.name + "> " + line));
	}

	/** The Names setting: no blanks or repeats. A copy, for changing. */
	private List<String> wantedNames() {
		return new ArrayList<>(parsedNames());
	}

	private List<String> parsedNames() {
		if (names.get().equals(namesParsedFrom)) return namesParsed;
		namesParsedFrom = names.get();
		List<String> parsed = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (String part : namesParsedFrom.split(",")) {
			String trimmed = part.trim();
			if (!trimmed.isEmpty() && seen.add(trimmed.toLowerCase(Locale.ROOT))) parsed.add(trimmed);
		}
		namesParsed = List.copyOf(parsed);
		return namesParsed;
	}

	/** The names on fake players right now, which Server Name Protect leaves for them. */
	public List<String> activeNames() {
		return isEnabled() ? parsedNames() : List.of();
	}

	private static boolean containsIgnoreCase(List<String> list, String name) {
		for (String other : list) {
			if (other.equalsIgnoreCase(name)) return true;
		}
		return false;
	}

	private void spawnAll() {
		despawnAll();
		nextId = FIRST_ENTITY_ID;
		spawned = true;
		syncedFrom = names.get();
		moveFormationHere();
		spawnAround(wantedNames());
	}

	/** Brings the fake players in line with an edited Names setting, leaving the ones still listed alone. */
	private void sync() {
		syncedFrom = names.get();
		slotsFor = null;
		List<String> wanted = wantedNames();
		for (Iterator<Fake> iterator = fakes.iterator(); iterator.hasNext(); ) {
			Fake fake = iterator.next();
			if (containsIgnoreCase(wanted, fake.name)) continue;
			despawn(fake);
			iterator.remove();
			fakesById.remove(fake.profile.id());
		}
		List<String> missing = new ArrayList<>();
		for (String name : wanted) {
			boolean exists = false;
			for (Fake fake : fakes) exists |= fake.name.equalsIgnoreCase(name);
			if (!exists) missing.add(name);
		}
		spawnAround(missing);
	}

	/** One goes where you're standing, and several go in a ring around you. A crowd always goes on the circle it wanders in. */
	private void spawnAround(List<String> toSpawn) {
		Vec3 center = mc.player.position();
		float yaw = mc.player.getYRot();
		double radius = Math.max(2, toSpawn.size() * 0.5);
		for (int i = 0; i < toSpawn.size(); i++) {
			double angle = Math.PI * 2 * i / toSpawn.size();
			Fake fake = new Fake(toSpawn.get(i), nextId--);
			fake.spawnPos = toSpawn.size() == 1 ? center : center.add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
			fake.home = center;
			fake.ringAngle = angle;
			fakes.add(fake);
			fakesById.put(fake.profile.id(), fake);
			spawn(fake, spawnSpot(fake), yaw);
		}
	}

	private Vec3 spawnSpot(Fake fake) {
		Vec3 spot = formationSpot(fake);
		if (spot != null) return spot;
		if (!crowd.get()) return fake.spawnPos;
		double radius = wanderRadius.get();
		return fake.home.add(Math.cos(fake.ringAngle) * radius, 0, Math.sin(fake.ringAngle) * radius);
	}

	/** Handles {@code .fp <add|random|crowd|formation|maker|text|remove|list|clear>}. */
	public void command(String[] args) {
		String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "list";
		List<String> wanted = wantedNames();
		switch (action) {
			case "add", "spawn" -> {
				if (args.length < 3) {
					ChatUtil.info("Usage: " + CommandManager.PREFIX + "fp add <names>");
					return;
				}
				for (int i = 2; i < args.length; i++) {
					String name = args[i].replace(",", "");
					if (!name.isEmpty() && !containsIgnoreCase(wanted, name)) wanted.add(name);
				}
				names.set(String.join(", ", wanted));
				if (!isEnabled()) setEnabled(true);
				ChatUtil.info(wanted.size() + (wanted.size() == 1 ? " fake player" : " fake players"));
			}
			case "random", "rand" -> {
				boolean all = args.length > 2 && args[2].equalsIgnoreCase("all");
				List<String> free = FemboyWare.getModules().get(NameProtectModule.class).unusedFakeNames();
				free.removeIf(name -> containsIgnoreCase(wanted, name));
				int count = 1;
				if (args.length > 2) {
					try {
						count = all ? free.size() : Integer.parseInt(args[2]);
					} catch (NumberFormatException e) {
						ChatUtil.info("Usage: " + CommandManager.PREFIX + "fp random [how many|all]");
						return;
					}
				}
				if (count <= 0) {
					ChatUtil.info(all ? "Every name in Name Protect's Fake Names is taken" : "Usage: " + CommandManager.PREFIX + "fp random [how many|all]");
					return;
				}
				if (!isEnabled()) setEnabled(true);
				addFakesUpTo(wanted.size() + count, true);
				ChatUtil.info("Looking up " + count + " real " + (count == 1 ? "account" : "accounts") + "...");
			}
			case "crowd" -> {
				crowd.set(args.length > 2 ? args[2].equalsIgnoreCase("on") : !crowd.get());
				ChatUtil.info(crowd.get() ? "Fake players are hanging out" : "Fake players are standing still");
			}
			case "formation", "form" -> {
				String choice = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "list";
				switch (choice) {
					case "off", "none", "stop" -> {
						stopFormation();
						ChatUtil.info("Fake players broke formation");
					}
					case "here" -> {
						moveFormationHere();
						ChatUtil.info("Moved the formation to you");
					}
					case "list" -> {
						List<String> all = new ArrayList<>();
						for (FakeFormations.Shape shape : FakeFormations.Shape.values()) {
							if (FakeFormations.builtIn(shape.name()) != null) all.add(shape.name().toLowerCase(Locale.ROOT));
						}
						all.addAll(customFormations().keySet());
						ChatUtil.info("Formations: " + String.join(", ", all));
					}
					default -> ChatUtil.info(useFormation(args[2]) ? "Formation: " + formationName() : "No formation called \"" + args[2] + "\". Try " + CommandManager.PREFIX + "fp formation list");
				}
			}
			case "text", "spell" -> {
				String text = String.join(" ", List.of(args).subList(Math.min(2, args.length), args.length));
				ChatUtil.info(spellText(text) ? "Spelling \"" + text + "\" with " + FakeFormations.textCells(text).size() + " fake players" : "Usage: " + CommandManager.PREFIX + "fp text <words>  (letters, numbers and ! ? . , - : ')");
			}
			// The chat screen closes after sending, so open once that's done
			case "maker" -> mc.schedule(() -> Mc.setScreen(new FormationMakerScreen(this)));
			case "remove", "del", "delete" -> {
				if (args.length < 3) {
					ChatUtil.info("Usage: " + CommandManager.PREFIX + "fp remove <names>");
					return;
				}
				findingNames = false;
				for (int i = 2; i < args.length; i++) {
					String name = args[i];
					wanted.removeIf(other -> other.equalsIgnoreCase(name));
				}
				names.set(String.join(", ", wanted));
				ChatUtil.info(wanted.size() + (wanted.size() == 1 ? " fake player left" : " fake players left"));
			}
			case "clear" -> {
				findingNames = false;
				names.set("");
				ChatUtil.info("Removed all the fake players");
			}
			case "list" -> ChatUtil.info(wanted.isEmpty() ? "No fake players yet. Add some with " + CommandManager.PREFIX + "fp add <names>"
				: "Fake players (" + wanted.size() + "): " + String.join(", ", wanted));
			default -> ChatUtil.info("Usage: " + CommandManager.PREFIX + "fp <add|random|crowd|formation|maker|text|remove|list|clear> [names]");
		}
	}

	/** Called from AbstractClientPlayerEntityMixin: a fake player wears the skin of the account with its name. */
	public PlayerSkin skinFor(AbstractClientPlayer player, PlayerSkin textures) {
		if (!isEnabled() || !accountSkins.get()) return textures;
		Fake fake = fakesById.get(player.getUUID());
		PlayerSkin account = fake == null ? null : AccountSkins.get(fake.name);
		return account != null ? account : textures;
	}

	/** Called from PlayerListHudMixin: the tab list's players, plus ours. */
	public Collection<PlayerInfo> withTabEntries(Collection<PlayerInfo> entries) {
		if (!isEnabled() || fakes.isEmpty()) return entries;
		List<PlayerInfo> all = new ArrayList<>(entries);
		for (Fake fake : fakes) all.add(fake.listEntry);
		return all;
	}

	/** Called from PlayerListEntryMixin: the skin for a fake player's tab list row, or null if this isn't one. */
	public PlayerSkin tabSkin(GameProfile profile) {
		Fake fake = fakesById.get(profile.id());
		if (fake == null) return null;
		PlayerSkin account = accountSkins.get() ? AccountSkins.get(fake.name) : null;
		return account != null ? account : DefaultPlayerSkin.get(profile);
	}

	@Override
	public void onPacketReceive(PacketEvent event) {
		if (event.getPacket() instanceof ClientboundExplodePacket packet) {
			mc.execute(() -> onExplosion(packet.center(), packet.radius()));
		}
	}

	/** Called from EntityMixin: pushing against a fake player moves you on the client alone, which servers correct with a teleport. */
	public boolean ignoresPush(Entity first, Entity second) {
		return isEnabled() && (fakeOf(first) != null || fakeOf(second) != null);
	}

	/** Called from the interaction manager mixin. Returns true when the attack was handled locally. */
	public boolean onAttack(Player attacker, Entity target) {
		Fake fake = fakeOf(target);
		if (!isEnabled() || fake == null) return false;
		RemotePlayer entity = fake.entity;
		// Read the charge before resetting it, as vanilla does; resetting first made every hit uncharged
		float progress = attacker.getAttackStrengthScale(0.5f);
		attacker.resetAttackStrengthTicker();
		if (entity.isDeadOrDying()) return true;

		// Mirrors PlayerEntity#attack; enchantment damage bonuses are server-side and not included
		DamageSource source = mc.level.damageSources().playerAttack(attacker);
		float damage = (float) attacker.getAttributeValue(Attributes.ATTACK_DAMAGE) * (0.2f + progress * progress * 0.8f);
		damage += attacker.getWeaponItem().getItem().getAttackDamageBonus(entity, damage, source);
		boolean crit = progress > 0.9f && attacker.fallDistance > 0 && !attacker.onGround() && !attacker.onClimbable()
			&& !attacker.isInWater() && !attacker.hasEffect(MobEffects.BLINDNESS) && !attacker.isPassenger() && !attacker.isSprinting();
		if (crit) {
			damage *= 1.5f;
			mc.particleEngine.createTrackingEmitter(entity, ParticleTypes.CRIT);
		}

		damage = CombatRules.getDamageAfterAbsorb(entity, damage, source, entity.getArmorValue(), (float) entity.getAttributeValue(Attributes.ARMOR_TOUGHNESS));
		damage = CombatRules.getDamageAfterMagicAbsorb(damage, getProtection(entity));
		playSound(entity, crit ? SoundEvents.PLAYER_ATTACK_CRIT : progress > 0.9f ? SoundEvents.PLAYER_ATTACK_STRONG : SoundEvents.PLAYER_ATTACK_WEAK);
		damage(fake, damage, source);
		return true;
	}

	private void onExplosion(Vec3 center, float power) {
		if (!isEnabled()) return;
		for (Fake fake : fakes) {
			RemotePlayer entity = fake.entity;
			if (entity == null || entity.isDeadOrDying() || entity.level() != mc.level) continue;
			// Blast protection and armor are already applied by the calculator
			float damage = DamageCalculator.explosionDamage(entity, entity.getBoundingBox(), center, power, false, false);
			if (damage > 0) damage(fake, damage, mc.level.damageSources().explosion(null, null));
		}
	}

	private Fake fakeOf(Entity target) {
		Fake fake = fakesById.get(target.getUUID());
		return fake != null && fake.entity == target ? fake : null;
	}

	private void damage(Fake fake, float amount, DamageSource source) {
		RemotePlayer entity = fake.entity;
		// Vanilla hurt cooldown: within 10 ticks of a hit only damage above the last hit counts
		if (fake.invulnerableTicks > 10) {
			if (amount <= fake.lastDamage) return;
			float extra = amount - fake.lastDamage;
			fake.lastDamage = amount;
			amount = extra;
		} else {
			fake.lastDamage = amount;
			fake.invulnerableTicks = 20;
			entity.handleDamageEvent(source);
		}

		if (invincible.get()) {
			playSound(entity, SoundEvents.PLAYER_HURT);
			return;
		}
		float remaining = entity.getHealth() - amount;
		if (remaining > 0) {
			entity.setHealth(remaining);
			playSound(entity, SoundEvents.PLAYER_HURT);
			return;
		}
		entity.setHealth(0);
		fake.deadTicks = 0;
		playSound(entity, SoundEvents.PLAYER_DEATH);
	}

	/** Protection enchantment levels on the armor, which reduce melee damage. */
	private float getProtection(RemotePlayer entity) {
		int total = 0;
		for (EquipmentSlot slot : ARMOR_SLOTS) {
			for (Object2IntMap.Entry<Holder<Enchantment>> entry : entity.getItemBySlot(slot).getEnchantments().entrySet()) {
				if (entry.getKey().is(Enchantments.PROTECTION)) total += entry.getIntValue();
			}
		}
		return Mth.clamp(total, 0, 20);
	}

	private void spawn(Fake fake, Vec3 pos, float yaw) {
		despawn(fake);
		ClientLevel world = mc.level;
		RemotePlayer entity = new RemotePlayer(world, fake.profile);
		entity.setId(fake.id);
		entity.snapTo(pos.x, pos.y, pos.z, yaw, 0);
		entity.setYHeadRot(yaw);
		entity.setYBodyRot(yaw);
		entity.setHealth(health.getFloat());
		if (copyArmor.get()) {
			for (EquipmentSlot slot : EquipmentSlot.values()) {
				ItemStack stack = mc.player.getItemBySlot(slot);
				if (!stack.isEmpty()) entity.setItemSlot(slot, stack.copy());
			}
		}
		world.addEntity(entity);
		fake.entity = entity;
		fake.wanderer = new FakeWanderer();
		fake.spawnYaw = yaw;
		fake.lastDamage = 0;
		fake.invulnerableTicks = 0;
		fake.deadTicks = 0;
		fake.landing = true;
	}

	private void despawn(Fake fake) {
		if (fake.entity != null && mc.level != null && fake.entity.level() == mc.level) {
			mc.level.removeEntity(fake.entity.getId(), Entity.RemovalReason.DISCARDED);
		}
		fake.entity = null;
	}

	private void despawnAll() {
		for (Fake fake : fakes) despawn(fake);
		fakes.clear();
		fakesById.clear();
	}

	private void playSound(RemotePlayer entity, SoundEvent sound) {
		mc.level.playLocalSound(entity.getX(), entity.getY(), entity.getZ(), sound, SoundSource.PLAYERS, 1f, 1f, false);
	}
}
