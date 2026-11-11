package dev.femboyware.module.larp;

import dev.femboyware.gui.Glass;
import dev.femboyware.manager.CommandManager;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.KeySetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.setting.StringSetting;
import dev.femboyware.util.ChatUtil;
import dev.femboyware.util.Mc;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

/**
 * A fake phone: DMs that come in at random with a banner at the top of the screen, a bank app, a
 * stock trading app, a Roblox profile full of rare limiteds and the Ledger wallet. All made up and
 * only on your screen.
 */
public class PhoneModule extends Module {
	public enum Placement { TOP, TOP_RIGHT }

	/** One text: who it's with, what it says, and whether you sent it. */
	public record Message(String contact, String text, boolean mine, long time) {}

	/** A Roblox limited: its picture, name and recent average price in Robux. */
	public record Limited(String id, String name, long rap) {}

	/** A stock you hold. */
	public record Position(String ticker, String name, double shares, double basePrice) {}

	/** A line in the bank's activity. */
	public record Transaction(String label, double amount, long time) {}

	public static final List<Limited> LIMITEDS = List.of(
		new Limited("dominus_empyreus", "Dominus Empyreus", 2_480_000),
		new Limited("violet_valkyrie", "Violet Valkyrie", 1_150_000),
		new Limited("sparkle_fedora", "Red Sparkle Time Fedora", 640_000),
		new Limited("dominus_frigidus", "Dominus Frigidus", 610_000),
		new Limited("sparkle_fedora_purple", "Purple Sparkle Time Fedora", 395_000),
		new Limited("valkyrie_helm", "Valkyrie Helm", 138_000),
		new Limited("clockwork_shades", "Clockwork's Shades", 92_500),
		new Limited("fiery_horns", "Fiery Horns of the Netherworld", 71_000),
		new Limited("poisoned_horns", "Poisoned Horns of the Toxic Wasteland", 52_000),
		new Limited("frozen_horns", "Frozen Horns of the Frigid Planes", 46_500),
		new Limited("headless", "Headless Horseman", 31_000),
		new Limited("korblox", "Korblox Deathspeaker", 17_000));

	public static final List<Position> POSITIONS = List.of(
		new Position("NVDA", "NVIDIA", 4_200, 182.40),
		new Position("TSLA", "Tesla", 1_850, 412.30),
		new Position("AAPL", "Apple", 2_600, 248.10),
		new Position("MSFT", "Microsoft", 940, 512.70),
		new Position("META", "Meta", 610, 735.20),
		new Position("PLTR", "Palantir", 3_300, 178.90),
		new Position("AMZN", "Amazon", 1_100, 231.40));

	/** Who texts you, and what they say. */
	private static final Map<String, String[]> CONTACTS = new LinkedHashMap<>();
	private static final String[] REPLIES = {"bet", "say less", "on my way", "already did", "send it", "u know how i rock", "lmk", "facts", "we up", "aight", "ok bet", "hold on im in the maybach"};

	static {
		CONTACTS.put("Accountant", new String[]{"the wire cleared", "we need to talk about your taxes lol", "the 2.4M hit the account", "offshore account is set up", "your card limit got raised again", "Q3 numbers are insane", "stop buying chains for a week pls"});
		CONTACTS.put("Twin", new String[]{"bro where u at", "send pics of the new whip", "u really bought another chain??", "we outside tonight", "lend me 20k i got u friday", "u seen the new drop", "pull up"});
		CONTACTS.put("Shorty", new String[]{"wyd tonight", "the maybach looked crazy today", "come thru", "u said u were taking me shopping", "miss u", "i saw ur story"});
		CONTACTS.put("Plug", new String[]{"got that for u", "pulling up in 10", "sent the address", "new shipment of watches came in", "the AP is ready", "u still want the iced one?"});
		CONTACTS.put("Mom", new String[]{"proud of you baby", "call me when u can", "thank u for the house", "did u eat today", "your cousin says hi"});
		CONTACTS.put("Realtor", new String[]{"the penthouse is yours", "closing docs are ready to sign", "seller accepted cash", "found another one with an ocean view"});
		CONTACTS.put("Car Dealer", new String[]{"your car is ready for pickup", "we got the matte black one in", "delivery truck is on the way", "paid in full, thank you"});
	}

	private static final int TOAST_WIDTH = 200, TOAST_HEIGHT = 40;
	private static final long SLIDE = 260;

	/** How long a notification stays up, in milliseconds. */
	private long life() {
		return Math.round(popupTime.get() * 1000);
	}

	private final KeySetting openKey = add(new KeySetting("Open Key", "Opens the phone. Or use " + CommandManager.PREFIX + "phone", GLFW.GLFW_KEY_M));
	private final BoolSetting dms = add(new BoolSetting("Fake DMs", "Texts come in now and then, with a banner", true));
	private final NumberSetting popupTime = add(new NumberSetting("Pop-up Time", "How long texts, transfers and Forbes alerts stay on screen, in seconds", 4.5, 2, 15, 0.5));
	private final NumberSetting minInterval = add(new NumberSetting("Min Interval", "Shortest wait between texts, in seconds", 25, 3, 300, 1)).visibleWhen(dms::get);
	private final NumberSetting maxInterval = add(new NumberSetting("Max Interval", "Longest wait between texts, in seconds", 140, 3, 600, 1)).visibleWhen(dms::get);
	private final EnumSetting<Placement> placement = add(new EnumSetting<>("Placement", "Where the banners show", Placement.TOP));
	private final BoolSetting sound = add(new BoolSetting("Sound", "A ding with each text", true));
	private final StringSetting contactLines = add(new StringSetting("Contact Messages",
		"What your contacts text you: Name: message; message | Name: message; ...", defaultContacts()));
	private final StringSetting strangerNames = add(new StringSetting("Stranger Names", "Names of random people who text you, separated by commas", String.join(", ", STRANGERS)));
	private final StringSetting strangerLines = add(new StringSetting("Stranger Messages", "What random people text you, separated by ;", String.join("; ", STRANGER_TEXTS)));
	private final BoolSetting wires = add(new BoolSetting("Bank Transfers", "Big wire transfers land in your bank now and then, with a notification", true));
	private final NumberSetting wireMin = add(new NumberSetting("Transfer Min Interval", "Shortest wait between transfers, in seconds", 60, 5, 600, 5)).visibleWhen(wires::get);
	private final NumberSetting wireMax = add(new NumberSetting("Transfer Max Interval", "Longest wait between transfers, in seconds", 240, 5, 1200, 5)).visibleWhen(wires::get);
	private final NumberSetting wireLow = add(new NumberSetting("Transfer Min Amount", "Smallest transfer, in thousands of dollars", 50, 1, 1000, 1)).visibleWhen(wires::get);
	private final NumberSetting wireHigh = add(new NumberSetting("Transfer Max Amount", "Biggest transfer, in thousands of dollars", 2500, 10, 10000, 10)).visibleWhen(wires::get);
	private final StringSetting wireSenders = add(new StringSetting("Transfer Senders", "Who the bank transfers come from, separated by ;", String.join("; ", WIRE_SENDERS))).visibleWhen(wires::get);
	private final BoolSetting forbes = add(new BoolSetting("Forbes Alerts", "Forbes notifications about your net worth and their lists now and then", true));
	private final NumberSetting forbesMin = add(new NumberSetting("Forbes Min Interval", "Shortest wait between Forbes alerts, in seconds", 240, 5, 1800, 5)).visibleWhen(forbes::get);
	private final NumberSetting forbesMax = add(new NumberSetting("Forbes Max Interval", "Longest wait between Forbes alerts, in seconds", 720, 5, 3600, 5)).visibleWhen(forbes::get);
	private final StringSetting forbesLines = add(new StringSetting("Forbes Headlines",
		"What Forbes tells you, separated by ;. {rank} is a list position, {b} billions", String.join("; ", FORBES_LINES))).visibleWhen(forbes::get);
	private final StringSetting bankSaved = add(new StringSetting("Bank Balance", "", "3482190.55")).visibleWhen(() -> false);
	private final StringSetting robuxSaved = add(new StringSetting("Robux", "", "1247500")).visibleWhen(() -> false);

	private final Map<String, List<Message>> threads = new LinkedHashMap<>();
	private final List<Message> toasts = new ArrayList<>();
	private final List<Transaction> bankActivity = new ArrayList<>();
	private final Map<String, Double> stockDrift = new LinkedHashMap<>();
	private final Random random = new Random();
	private int nextTextTicks = -1, nextWireTicks = -1;

	/** A wire transfer's notification, or a Forbes alert when {@code headline} is set. */
	private record Wire(String from, double amount, long time, String headline) {
		Wire(String from, double amount, long time) {
			this(from, amount, time, null);
		}
	}

	private static final String[] FORBES_LINES = {
		"You've been added to the Forbes Billionaires list at #{rank}",
		"Your net worth rose ${b}B today",
		"You've been named to the Forbes 30 Under 30",
		"You just passed Bezos on the Real-Time Billionaires list",
		"You're on the cover of this month's issue",
		"You moved up to #{rank} on the World's Billionaires list",
		"Your company's valuation hit ${b}B",
		"You're now the richest person in Minecraft"};
	private int nextForbesTicks = -1;

	private final List<Wire> wireToasts = new ArrayList<>();
	private static final String[] WIRE_SENDERS = {"Offshore Holdings Ltd.", "Cayman Capital Trust", "Crypto Exchange Payout", "Royalty Distribution", "Brand Deal: Energy Drink",
		"Real Estate Closing", "Venture Fund Returns", "Swiss Private Bank", "Dividend Payment", "Ledger Withdrawal", "Tour Revenue", "NFT Sale Proceeds"};

	public PhoneModule() {
		super("Phone", "A fake phone with DMs, a bank, stocks and a Roblox profile. Use " + CommandManager.PREFIX + "phone", Category.LARP);
		long now = System.currentTimeMillis();
		// Some history so the apps aren't empty the first time
		int i = 0;
		for (Map.Entry<String, String[]> contact : CONTACTS.entrySet()) {
			List<Message> thread = new ArrayList<>();
			long t = now - (i + 1) * 3_600_000L * (1 + random.nextInt(5));
			for (int k = 0; k < 3; k++) {
				thread.add(new Message(contact.getKey(), pick(contact.getValue()), false, t));
				t += 60_000L * (2 + random.nextInt(20));
				if (random.nextBoolean()) thread.add(new Message(contact.getKey(), pick(REPLIES), true, t));
				t += 60_000L * (1 + random.nextInt(30));
			}
			threads.put(contact.getKey(), thread);
			i++;
		}
		String[][] activity = {{"Wire transfer received", "84000"}, {"Ledger withdrawal", "51296.59"}, {"Jeweler", "-48500"}, {"Penthouse rent", "-32000"},
			{"Royalties", "126400.18"}, {"Car dealership", "-310000"}, {"Interest", "9214.77"}, {"Designer store", "-12850.40"}};
		for (int k = 0; k < activity.length; k++) {
			bankActivity.add(new Transaction(activity[k][0], Double.parseDouble(activity[k][1]), now - (k + 1) * 86_400_000L / 2 - random.nextInt(3_600_000)));
		}
		for (Position p : POSITIONS) stockDrift.put(p.ticker(), (random.nextDouble() - 0.3) * 0.08);
		// Kept connected from the menus too, so texts can come in as soon as you join
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
			try {
				tickLink();
			} catch (RuntimeException e) {
				dev.femboyware.FemboyWare.LOGGER.error("Phone messages failed", e);
			}
		});
	}

	private <T> T pick(T[] options) {
		return options[random.nextInt(options.length)];
	}

	// ---------------------------------------------------------------- data for the apps

	/** Threads with the newest first. */
	public List<List<Message>> threads() {
		List<List<Message>> list = new ArrayList<>(threads.values());
		list.sort((a, b) -> Long.compare(b.getLast().time(), a.getLast().time()));
		return list;
	}

	public List<Message> thread(String contact) {
		return threads.getOrDefault(contact, List.of());
	}

	public double bankBalance() {
		try {
			return Double.parseDouble(bankSaved.get());
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	public List<Transaction> bankActivity() {
		return bankActivity;
	}

	public long robux() {
		try {
			return Long.parseLong(robuxSaved.get().trim());
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	public double stockPrice(Position p) {
		return p.basePrice() * (1 + stockDrift.get(p.ticker()));
	}

	public double stockChange(Position p) {
		return stockDrift.get(p.ticker());
	}

	public double portfolio() {
		double total = 0;
		for (Position p : POSITIONS) total += p.shares() * stockPrice(p);
		return total;
	}

	public double portfolioChange() {
		double change = 0;
		for (Position p : POSITIONS) {
			double d = stockDrift.get(p.ticker());
			change += p.shares() * stockPrice(p) * d / (1 + d);
		}
		return change;
	}

	// ---------------------------------------------------------------- ticking

	@Override
	public void onTick() {
		if (mc.player.tickCount % 200 == 0) {
			for (Position p : POSITIONS) {
				double d = stockDrift.get(p.ticker());
				stockDrift.put(p.ticker(), Mth.clamp(d + random.nextGaussian() * 0.003 - d * 0.02, -0.1, 0.14));
			}
		}
		if (wires.get()) {
			if (nextWireTicks < 0) scheduleWire();
			if (--nextWireTicks == 0) {
				randomWire();
				scheduleWire();
			}
		}
		if (forbes.get()) {
			if (nextForbesTicks < 0) scheduleForbes();
			if (--nextForbesTicks == 0) {
				randomForbes();
				scheduleForbes();
			}
		}
		if (!dms.get()) return;
		if (nextTextTicks < 0) schedule();
		if (--nextTextTicks == 0) {
			randomText();
			schedule();
		}
	}

	private void schedule() {
		double low = Math.min(minInterval.get(), maxInterval.get()), high = Math.max(minInterval.get(), maxInterval.get());
		nextTextTicks = Math.max(20, (int) ((low + random.nextDouble() * (high - low)) * 20));
	}

	@Override
	protected void onEnable() {
		nextTextTicks = -1;
		nextWireTicks = -1;
		nextForbesTicks = -1;
	}

	private void scheduleForbes() {
		double low = Math.min(forbesMin.get(), forbesMax.get()), high = Math.max(forbesMin.get(), forbesMax.get());
		nextForbesTicks = Math.max(20, (int) ((low + random.nextDouble() * (high - low)) * 20));
	}

	/** A Forbes alert from the headlines setting, with a list rank and a billions figure filled in. */
	public void randomForbes() {
		String line = pickFrom(forbesLines, ";", FORBES_LINES);
		line = line.replace("{rank}", String.valueOf(1 + random.nextInt(600)))
			.replace("{b}", String.format(java.util.Locale.ROOT, "%.1f", 0.4 + random.nextDouble() * 9.5));
		forbesAlert(line);
	}

	/** A Forbes notification pops up. */
	public void forbesAlert(String headline) {
		wireToasts.add(new Wire("Forbes", 0, System.currentTimeMillis(), headline));
		while (wireToasts.size() > 2) wireToasts.removeFirst();
		if (sound.get()) LarpSounds.play(LarpSounds.PHONE_TEXT, 1.1f, 0.8f);
	}

	private void scheduleWire() {
		double low = Math.min(wireMin.get(), wireMax.get()), high = Math.max(wireMin.get(), wireMax.get());
		nextWireTicks = Math.max(20, (int) ((low + random.nextDouble() * (high - low)) * 20));
	}

	/** A wire from somewhere rich, between the min and max, spread on a log scale so the big ones are rarer. */
	public void randomWire() {
		double low = Math.min(wireLow.get(), wireHigh.get()) * 1000, high = Math.max(wireLow.get(), wireHigh.get()) * 1000;
		double amount = Math.exp(Math.log(low) + random.nextDouble() * (Math.log(high) - Math.log(low)));
		// Round like a real wire would be: to the thousand for big ones
		amount = amount > 100_000 ? Math.round(amount / 1000) * 1000.0 : Math.round(amount * 100) / 100.0;
		wire(pickFrom(wireSenders, ";", WIRE_SENDERS), amount);
	}

	/** Money lands in the bank: the balance goes up, it shows in the activity, and a notification pops up. */
	public void wire(String from, double amount) {
		bankSaved.set(String.valueOf(bankBalance() + amount));
		dev.femboyware.FemboyWare.getConfig().save();
		bankActivity.addFirst(new Transaction("Wire from " + from, amount, System.currentTimeMillis()));
		wireToasts.add(new Wire(from, amount, System.currentTimeMillis()));
		while (wireToasts.size() > 2) wireToasts.removeFirst();
		if (sound.get()) LarpSounds.play(LarpSounds.LEDGER_NOTIFY, 0.8f, 0.9f);
	}

	@Override
	public void onKey(int key) {
		if (openKey.is(key)) open();
	}

	/** Whether you've been given the item, so it can be put back if the server's inventory wipes it. Never shown. */
	private final BoolSetting ownsItem = add(new BoolSetting("Owns Item", "", false)).visibleWhen(() -> false);

	public boolean ownsItem() {
		return ownsItem.get();
	}

	public void open() {
		Mc.setScreen(new PhoneScreen(this));
	}

	private static String defaultContacts() {
		List<String> parts = new ArrayList<>();
		CONTACTS.forEach((name, lines) -> parts.add(name + ": " + String.join("; ", lines)));
		return String.join(" | ", parts);
	}

	private String contactsFrom;
	private Map<String, String[]> contactsParsed = CONTACTS;

	/** Your contacts and their lines, from the Contact Messages setting (the built-in ones if it's empty or unreadable). */
	private Map<String, String[]> contacts() {
		String raw = contactLines.get();
		if (raw.equals(contactsFrom)) return contactsParsed;
		contactsFrom = raw;
		Map<String, String[]> parsed = new LinkedHashMap<>();
		for (String part : raw.split("\\|")) {
			int colon = part.indexOf(':');
			if (colon <= 0) continue;
			String name = part.substring(0, colon).trim();
			List<String> lines = split(part.substring(colon + 1), ";");
			if (!name.isEmpty() && !lines.isEmpty()) parsed.put(name, lines.toArray(new String[0]));
		}
		contactsParsed = parsed.isEmpty() ? CONTACTS : parsed;
		return contactsParsed;
	}

	private static List<String> split(String raw, String by) {
		List<String> out = new ArrayList<>();
		for (String piece : raw.split(java.util.regex.Pattern.quote(by))) {
			if (!piece.isBlank()) out.add(piece.trim());
		}
		return out;
	}

	/** A random entry from a setting's list, or from the built-in one when the setting's empty. */
	private String pickFrom(StringSetting setting, String by, String[] fallback) {
		List<String> list = split(setting.get(), by);
		return list.isEmpty() ? pick(fallback) : list.get(random.nextInt(list.size()));
	}

	/** People you don't have saved: fans, people from last night, randoms who got your number. */
	private static final String[] STRANGERS = {"Jayden", "Mia", "Unknown", "Destiny", "Marcus", "Ava", "Tyler", "Bella", "Kev", "Nina", "Big Mike", "Sofia"};
	private static final String[] STRANGER_TEXTS = {"yo is this the guy from the club last night?", "can i get a pic with u next time lol", "how do u get rich like that fr",
		"saw u pull up in the maybach, insane", "who did ur chain", "is ur watch real??", "u dropped this: crown", "my cousin said u got a penthouse is that true",
		"can u follow me back", "bro u famous or something", "teach me how to invest pls", "the paparazzi were all over u today lol", "who's ur barber",
		"i need a job, u hiring?", "this is ur biggest fan", "u left ur card at the restaurant", "the valet says ur car is ready", "free this weekend?",
		"just saw u on someones story", "how many cars do u have bro"};

	/** Someone texts you something they'd say: one of your contacts, or now and then a stranger or an unknown number. */
	public void randomText() {
		if (random.nextFloat() < 0.4f) {
			String name = random.nextFloat() < 0.35f
				? String.format(Locale.US, "+1 (%d) %03d-%04d", 200 + random.nextInt(780), random.nextInt(1000), random.nextInt(10000))
				: pickFrom(strangerNames, ",", STRANGERS);
			receive(name, pickFrom(strangerLines, ";", STRANGER_TEXTS));
			return;
		}
		Map<String, String[]> contacts = contacts();
		List<String> names = new ArrayList<>(contacts.keySet());
		String contact = names.get(random.nextInt(names.size()));
		receive(contact, pick(contacts.get(contact)));
	}

	// ---------------------------------------------------------------- real messages with other femboyware users

	/** The thread that goes to everyone online with femboyware. */
	public static final String EVERYONE = "Everyone";
	private final BoolSetting online = add(new BoolSetting("Online Messages",
		"Text other femboyware users from the phone: Everyone goes to all of them, or message one person (only they can read it). While this module is on", true));
	private dev.femboyware.manager.PhoneLink link;
	/** Threads with real people (and Everyone), as opposed to the made-up contacts. */
	private final java.util.Set<String> realThreads = new java.util.HashSet<>();

	private void tickLink() {
		String self = mc.getUser() == null ? null : mc.getUser().getName();
		boolean want = isEnabled() && online.get() && self != null;
		if (link != null && (!want || !link.self().equals(self))) {
			link.stop();
			link = null;
		}
		if (want && link == null) {
			link = new dev.femboyware.manager.PhoneLink(self, incoming -> mc.execute(() -> {
				String thread = incoming.direct() ? incoming.from() : EVERYONE;
				realThreads.add(thread);
				receive(thread, incoming.direct() ? incoming.text() : incoming.from() + ": " + incoming.text());
			}));
		}
		if (link != null) link.tick();
	}

	/** Whether the phone can text real people right now. */
	public boolean onlineMessages() {
		return link != null;
	}

	public boolean linkConnected() {
		return link != null && link.connected();
	}

	/** Other femboyware users online now. */
	public List<String> onlineUsers() {
		return link == null ? List.of() : link.online();
	}

	/** Whether a thread is with a real person (or Everyone) rather than a made-up contact. */
	public boolean isReal(String contact) {
		return contact.equals(EVERYONE) || realThreads.contains(contact) || (link != null && link.isOnline(contact));
	}

	/**
	 * Sends what you typed in a thread: to everyone, to a real person, or just into a made-up
	 * contact's thread. Returns why it couldn't go, or null if it went.
	 */
	public String send(String contact, String typed) {
		String text = dev.femboyware.manager.PhoneLink.clean(typed);
		if (text.isEmpty()) return null;
		if (isReal(contact)) {
			if (link == null) return "Online Messages is off";
			boolean sent = contact.equals(EVERYONE) ? link.sendAll(text) : link.sendDirect(contact, text);
			if (!sent) return link.connected() ? contact + " isn't online right now" : "Not connected to femboyware.xyz";
			realThreads.add(contact);
		}
		threads.computeIfAbsent(contact, key -> new ArrayList<>()).add(new Message(contact, text, true, System.currentTimeMillis()));
		return null;
	}

	public void receive(String contact, String text) {
		Message message = new Message(contact, text, false, System.currentTimeMillis());
		threads.computeIfAbsent(contact, key -> new ArrayList<>()).add(message);
		toasts.add(message);
		while (toasts.size() > 3) toasts.removeFirst();
		if (sound.get()) {
			LarpSounds.play(LarpSounds.PHONE_TEXT, 0.85f);
		}
	}

	/** You reply in a thread; sometimes they answer back a moment later. */
	public void reply(String contact) {
		threads.computeIfAbsent(contact, key -> new ArrayList<>()).add(new Message(contact, pick(REPLIES), true, System.currentTimeMillis()));
	}

	// ---------------------------------------------------------------- banners

	@Override
	public void onRender2D(GuiGraphics context, float tickDelta) {
		if ((toasts.isEmpty() && wireToasts.isEmpty()) || Mc.hudHidden() || Mc.screen() instanceof PhoneScreen) return;
		long now = System.currentTimeMillis();
		toasts.removeIf(m -> now - m.time() > life());
		wireToasts.removeIf(w -> now - w.time() > life());
		// Transfers first, then texts under them
		for (int i = 0; i < wireToasts.size(); i++) {
			Wire w = wireToasts.get(wireToasts.size() - 1 - i);
			long age = now - w.time();
			float shown = Math.min(Mth.clamp(age / (float) SLIDE, 0, 1), Mth.clamp((life() - age) / (float) SLIDE, 0, 1));
			float eased = shown * shown * (3 - 2 * shown);
			int x = placement.is(Placement.TOP) ? (context.guiWidth() - TOAST_WIDTH) / 2 : context.guiWidth() - TOAST_WIDTH - 6 + Math.round((1 - eased) * (TOAST_WIDTH + 10));
			int y = LedgerModule.topClearOfIsland(context, x, TOAST_WIDTH) + ledgerRoom() + i * (TOAST_HEIGHT + 4)
				- (placement.is(Placement.TOP) ? Math.round((1 - eased) * (TOAST_HEIGHT + 10)) : 0);
			drawWire(context, x, y, w, eased);
		}
		int wireRoom = wireToasts.size() * (TOAST_HEIGHT + 4);
		for (int i = 0; i < toasts.size(); i++) {
			Message m = toasts.get(toasts.size() - 1 - i);
			long age = now - m.time();
			float shown = Math.min(Mth.clamp(age / (float) SLIDE, 0, 1), Mth.clamp((life() - age) / (float) SLIDE, 0, 1));
			float eased = shown * shown * (3 - 2 * shown);
			int x, y;
			if (placement.is(Placement.TOP)) {
				x = (context.guiWidth() - TOAST_WIDTH) / 2;
				y = LedgerModule.topClearOfIsland(context, x, TOAST_WIDTH) + ledgerRoom() + wireRoom + i * (TOAST_HEIGHT + 4) - Math.round((1 - eased) * (TOAST_HEIGHT + 10));
			} else {
				x = context.guiWidth() - TOAST_WIDTH - 6 + Math.round((1 - eased) * (TOAST_WIDTH + 10));
				y = LedgerModule.topClearOfIsland(context, context.guiWidth() - TOAST_WIDTH - 6, TOAST_WIDTH) + ledgerRoom() + wireRoom + i * (TOAST_HEIGHT + 4);
			}
			drawBanner(context, x, y, m, eased);
		}
	}

	private int ledgerRoom() {
		return dev.femboyware.FemboyWare.getModules().get(LedgerModule.class).topToastsHeight();
	}

	/** A text message banner, like the ones that slide down on a phone. */
	private void drawBanner(GuiGraphics context, int x, int y, Message m, float alpha) {
		int a = Math.round(alpha * 255);
		Glass.rounded(context, x, y, x + TOAST_WIDTH, y + TOAST_HEIGHT, 10, fade(0xEC2C2C30, a));
		PhoneScreen.appIcon(context, PhoneScreen.App.MESSAGES, x + 7, y + 7, 14);
		var font = mc.font;
		context.drawString(font, "MESSAGES", x + 26, y + 9, fade(0xFF9A9AA2, a), false);
		context.drawString(font, "now", x + TOAST_WIDTH - 8 - font.width("now"), y + 9, fade(0xFF9A9AA2, a), false);
		context.drawString(font, m.contact(), x + 8, y + 19, fade(0xFFFFFFFF, a), false);
		context.drawString(font, font.plainSubstrByWidth(m.text(), TOAST_WIDTH - 16), x + 8, y + 29, fade(0xFFE4E4E8, a), false);
	}

	/** A bank notification: navy, the bank's icon, who sent it and how much. */
	private void drawWire(GuiGraphics context, int x, int y, Wire w, float alpha) {
		int a = Math.round(alpha * 255);
		if (w.headline() != null) {
			drawForbes(context, x, y, w, a);
			return;
		}
		Glass.rounded(context, x, y, x + TOAST_WIDTH, y + TOAST_HEIGHT, 10, fade(0xF00B1E3F, a));
		PhoneScreen.appIcon(context, PhoneScreen.App.BANK, x + 7, y + 7, 14);
		var font = mc.font;
		context.drawString(font, "BANK", x + 26, y + 9, fade(0xFF9FB4D8, a), false);
		context.drawString(font, "now", x + TOAST_WIDTH - 8 - font.width("now"), y + 9, fade(0xFF9FB4D8, a), false);
		String amount = "+" + LedgerModule.usd(w.amount());
		context.drawString(font, "Wire received", x + 8, y + 19, fade(0xFFFFFFFF, a), false);
		context.drawString(font, amount, x + TOAST_WIDTH - 8 - font.width(amount), y + 19, fade(0xFF5BE38A, a), false);
		context.drawString(font, font.plainSubstrByWidth("from " + w.from(), TOAST_WIDTH - 16), x + 8, y + 29, fade(0xFFD0DAEC, a), false);
	}

	/** A Forbes alert: a white card with the black masthead and the headline over two lines. */
	private void drawForbes(GuiGraphics context, int x, int y, Wire w, int a) {
		var font = mc.font;
		Glass.rounded(context, x, y, x + TOAST_WIDTH, y + TOAST_HEIGHT, 10, fade(0xF4F7F7F5, a));
		Glass.rounded(context, x + 7, y + 7, x + 21, y + 21, 4, fade(0xFF111111, a));
		context.drawString(font, "F", x + 14 - font.width("F") / 2, y + 10, fade(0xFFFFFFFF, a), false);
		context.drawString(font, "FORBES", x + 26, y + 9, fade(0xFF111111, a), false);
		context.drawString(font, "now", x + TOAST_WIDTH - 8 - font.width("now"), y + 9, fade(0xFF8A8A8A, a), false);
		List<net.minecraft.util.FormattedCharSequence> lines = font.split(net.minecraft.network.chat.Component.literal(w.headline()), TOAST_WIDTH - 16);
		for (int i = 0; i < Math.min(2, lines.size()); i++) context.drawString(font, lines.get(i), x + 8, y + 22 + i * 10, fade(0xFF1A1A1A, a), false);
	}

	private static int fade(int color, int alpha) {
		return (color & 0x00FFFFFF) | (Math.round(((color >>> 24) / 255f) * alpha) << 24);
	}

	// ---------------------------------------------------------------- commands

	/**
	 * {@code .phone timing} shows how often each notification comes and how long they stay up;
	 * {@code .phone timing <texts|bank|forbes> <min> [max]} sets a wait in seconds (one number for an
	 * exact wait), and {@code .phone timing popup <seconds>} how long they stay on screen.
	 */
	private void timing(String[] args) {
		if (args.length < 4) {
			ChatUtil.info("Texts: every " + span(minInterval, maxInterval) + (dms.get() ? "" : " (off)"));
			ChatUtil.info("Bank transfers: every " + span(wireMin, wireMax) + (wires.get() ? "" : " (off)"));
			ChatUtil.info("Forbes alerts: every " + span(forbesMin, forbesMax) + (forbes.get() ? "" : " (off)"));
			ChatUtil.info("Pop-ups stay up " + seconds(popupTime.get()));
			ChatUtil.info("Change one: " + CommandManager.PREFIX + "phone timing <texts|bank|forbes> <min seconds> [max seconds], or "
				+ CommandManager.PREFIX + "phone timing popup <seconds>");
			return;
		}
		String kind = args[2].toLowerCase(java.util.Locale.ROOT);
		double low, high;
		try {
			low = Double.parseDouble(args[3]);
			high = args.length > 4 ? Double.parseDouble(args[4]) : low;
		} catch (NumberFormatException e) {
			ChatUtil.info("Those need to be numbers of seconds");
			return;
		}
		switch (kind) {
			case "popup", "popups", "duration", "stay" -> {
				popupTime.set(low);
				ChatUtil.info("Pop-ups stay up " + seconds(popupTime.get()));
			}
			case "texts", "text", "dms", "dm", "messages" -> {
				dms.set(true);
				setSpan(minInterval, maxInterval, low, high);
				nextTextTicks = -1;
				ChatUtil.info("Texts: every " + span(minInterval, maxInterval));
			}
			case "bank", "wires", "wire", "transfers", "transfer" -> {
				wires.set(true);
				setSpan(wireMin, wireMax, low, high);
				nextWireTicks = -1;
				ChatUtil.info("Bank transfers: every " + span(wireMin, wireMax));
			}
			case "forbes" -> {
				forbes.set(true);
				setSpan(forbesMin, forbesMax, low, high);
				nextForbesTicks = -1;
				ChatUtil.info("Forbes alerts: every " + span(forbesMin, forbesMax));
			}
			default -> {
				ChatUtil.info("Which: texts, bank, forbes or popup");
				return;
			}
		}
		dev.femboyware.FemboyWare.getConfig().save();
	}

	private static void setSpan(NumberSetting min, NumberSetting max, double low, double high) {
		min.set(Math.min(low, high));
		max.set(Math.max(low, high));
	}

	private static String span(NumberSetting min, NumberSetting max) {
		double low = Math.min(min.get(), max.get()), high = Math.max(min.get(), max.get());
		return low == high ? seconds(low) : seconds(low) + " to " + seconds(high);
	}

	private static String seconds(double s) {
		if (s >= 60 && s % 60 == 0) return (int) (s / 60) + (s == 60 ? " minute" : " minutes");
		return (s == Math.floor(s) ? String.valueOf((int) s) : String.valueOf(s)) + "s";
	}

	/** Handles {@code .phone [open|give|dm|bank|robux]}. */
	public void command(String[] args) {
		String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "open";
		switch (action) {
			case "open" -> mc.schedule(this::open);
			case "give", "item" -> {
				if (LarpItems.has(LarpItems.PHONE)) {
					ChatUtil.info("You've already got your phone");
					return;
				}
				boolean given = LarpItems.give(LarpItems.phone());
				if (!given) {
					ChatUtil.info("Your inventory's full");
					return;
				}
				ownsItem.set(true);
				if (!isEnabled()) setEnabled(true);
				dev.femboyware.FemboyWare.getConfig().save();
				ChatUtil.info(LarpItems.larping()
					? "Here's your phone, in your larp inventory: move it round like any item. Right-click it to open it"
					: "Here's your phone. It's a ghost item, on your side only; turn on Server Larp to move it round freely. Right-click it to open it");
			}
			case "remove", "take" -> {
				ownsItem.set(false);
				LarpItems.remove(LarpItems.PHONE);
				dev.femboyware.FemboyWare.getConfig().save();
				ChatUtil.info("Put your phone away");
			}
			case "dm", "text" -> {
				if (args.length >= 4) receive(args[2], String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length)));
				else randomText();
			}
			case "timing", "time", "every" -> timing(args);
			case "forbes" -> {
				if (args.length > 2) forbesAlert(String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)));
				else randomForbes();
			}
			case "wire", "transfer" -> {
				if (args.length >= 3) {
					try {
						double amount = Double.parseDouble(args[2].replace(",", "").replace("$", ""));
						wire(args.length > 3 ? String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length)) : pick(WIRE_SENDERS), amount);
					} catch (NumberFormatException e) {
						ChatUtil.info("Usage: " + CommandManager.PREFIX + "phone wire [amount] [from]");
					}
				} else randomWire();
			}
			case "bank" -> {
				if (args.length < 3) {
					ChatUtil.info("Bank balance: " + LedgerModule.usd(bankBalance()));
					return;
				}
				bankSaved.set(args[2].replace(",", "").replace("$", ""));
				dev.femboyware.FemboyWare.getConfig().save();
				ChatUtil.info("Bank balance is now " + LedgerModule.usd(bankBalance()));
			}
			case "robux" -> {
				if (args.length < 3) {
					ChatUtil.info("Robux: " + robux());
					return;
				}
				robuxSaved.set(args[2].replace(",", ""));
				dev.femboyware.FemboyWare.getConfig().save();
				ChatUtil.info("Robux balance is now " + String.format(Locale.US, "%,d", robux()));
			}
			default -> ChatUtil.info("Usage: " + CommandManager.PREFIX + "phone <open|give|dm [name] [message]|wire [amount] [from]|forbes [headline]|timing|bank [amount]|robux [amount]>");
		}
	}
}
