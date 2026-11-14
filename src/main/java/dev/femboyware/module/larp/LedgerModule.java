package dev.femboyware.module.larp;

import dev.femboyware.FemboyWare;
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
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

/**
 * A fake hardware crypto wallet: coins you "hold", prices that drift, random incoming deposits that
 * pop up as wallet notifications, and a wallet app screen to show it all off. Nothing here is real or
 * leaves your game: it's all made up on your screen.
 */
public class LedgerModule extends Module {
	public enum Placement { TOP, TOP_RIGHT, BOTTOM_RIGHT }

	/** One line of the wallet's history. */
	public record Operation(Coin coin, double amount, double usd, long time, boolean received, String address) {}

	/** A real DonutSMP payment, read from chat: who, how much, and which way. */
	public record Payment(String player, double amount, boolean received, long time) {}

	/** A notification on screen: a crypto deposit, or money a player paid you. */
	private record Toast(Operation op, Payment pay, long time) {}

	private static final int TOAST_WIDTH = 176, TOAST_HEIGHT = 40, TOAST_GAP = 4;
	private static final long SLIDE = 260, SLIDE_IN = 90;

	/** How long a notification stays up, in milliseconds. */
	private long life() {
		return Math.round(popupTime.get() * 1000);
	}
	private static final int MAX_HISTORY = 60;

	private final KeySetting openKey = add(new KeySetting("Open Key", "Opens the wallet app. Or use " + CommandManager.PREFIX + "ledger", GLFW.GLFW_KEY_N));
	private final BoolSetting unlock = add(new BoolSetting("Unlock Animation", "The device asks for its PIN before the app opens", true));
	private final BoolSetting deposits = add(new BoolSetting("Deposits", "Random crypto comes in now and then, with a wallet notification", true));
	private final NumberSetting popupTime = add(new NumberSetting("Pop-up Time", "How long deposit and transaction notifications stay on screen, in seconds", 5.5, 2, 15, 0.5));
	private final NumberSetting minInterval = add(new NumberSetting("Min Interval", "Shortest wait between deposits, in seconds", 20, 3, 300, 1)).visibleWhen(deposits::get);
	private final NumberSetting maxInterval = add(new NumberSetting("Max Interval", "Longest wait between deposits, in seconds", 120, 3, 600, 1)).visibleWhen(deposits::get);
	private final NumberSetting minDeposit = add(new NumberSetting("Min Deposit", "Smallest deposit, in thousands of dollars", 2, 0.5, 100, 0.5)).visibleWhen(deposits::get);
	private final NumberSetting maxDeposit = add(new NumberSetting("Max Deposit", "Biggest deposit, in thousands of dollars", 120, 5, 1000, 5)).visibleWhen(deposits::get);
	private final EnumSetting<Placement> placement = add(new EnumSetting<>("Placement", "Where the notifications show", Placement.TOP));
	private final BoolSetting sound = add(new BoolSetting("Sound", "A chime with each notification", true));
	private final BoolSetting chat = add(new BoolSetting("Chat Message", "Also note each deposit in chat", false));
	/** Saved wallet: coin=amount pairs, the history, and prices you set yourself. Never shown. */
	private final StringSetting holdingsSaved = add(new StringSetting("Holdings", "", "BTC=4.2817,ETH=61.0923,SOL=1840.5,USDT=250000")).visibleWhen(() -> false);
	private final StringSetting historySaved = add(new StringSetting("History", "", "")).visibleWhen(() -> false);
	private final StringSetting pricesSaved = add(new StringSetting("Prices", "", "")).visibleWhen(() -> false);

	private final Map<Coin, Double> holdings = new EnumMap<>(Coin.class);
	private final Map<Coin, Double> basePrices = new EnumMap<>(Coin.class);
	/** How far each price has drifted from its base: today's change. */
	private final Map<Coin, Double> drift = new EnumMap<>(Coin.class);
	private final List<Operation> history = new ArrayList<>();
	private final List<Toast> toasts = new ArrayList<>();
	private final Random random = new Random();
	/** The saved strings the wallet was last read from; the same objects means nothing changed. */
	private String loadedHoldings, loadedHistory, loadedPrices;
	private int nextDepositTicks = -1;

	private final BoolSetting paymentAlerts = add(new BoolSetting("Payment Alerts", "A notification with their name and the amount whenever a player pays you on the server", true));
	/** Saved DonutSMP payments, newest first. Never shown. */
	private final StringSetting paymentsSaved = add(new StringSetting("Payments", "", "")).visibleWhen(() -> false);

	public LedgerModule() {
		super("Ledger", "A wallet app with your DonutSMP money and who paid you, plus a crypto wallet with deposit notifications. Use " + CommandManager.PREFIX + "ledger", Category.LARP);
		for (Coin coin : Coin.values()) drift.put(coin, (random.nextDouble() - 0.35) * 0.08);
		// System messages: payments both ways, and the answer to /bal. Returning false hides a message
		net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
			try {
				return serverMessage(message.getString(), overlay);
			} catch (RuntimeException e) {
				return true;
			}
		});
	}

	/** An amount as /pay wants it: whole dollars plain, cents only when there are some. */
	public static String plainAmount(double value) {
		double cents = Math.floor(value * 100) / 100.0;
		if (cents == Math.rint(cents)) return String.format(Locale.ROOT, "%.0f", cents);
		return String.format(Locale.ROOT, "%.2f", cents);
	}

	// ---------------------------------------------------------------- DonutSMP money

	private static final java.util.regex.Pattern PAID_YOU = java.util.regex.Pattern.compile("^(\\S{1,20}) paid you \\$ ?([0-9][0-9,]*(?:\\.[0-9]+)?\\s?[KMBTQkmbtq]?)");
	private static final java.util.regex.Pattern YOU_PAID = java.util.regex.Pattern.compile("^You paid (\\S{1,20}) \\$ ?([0-9][0-9,]*(?:\\.[0-9]+)?\\s?[KMBTQkmbtq]?)");
	/** A dollar amount anywhere: $1,234.56, $ 404K, $1.2M. */
	private static final java.util.regex.Pattern DOLLARS = java.util.regex.Pattern.compile("\\$\\s?([0-9][0-9,]*(?:\\.[0-9]+)?\\s?[KMBTQkmbtq]?)");
	/** A number with an optional K/M/B after a label, for a sidebar line like "Money 1.2M". */
	private static final java.util.regex.Pattern NUMBER = java.util.regex.Pattern.compile("([0-9][0-9,]*(?:\\.[0-9]+)?\\s?[KMBTQkmbtq]?)");
	private static final int MAX_PAYMENTS = 100;
	private static final long BALANCE_WAIT = 5000, BALANCE_EVERY = 15000;

	private final List<Payment> payments = new ArrayList<>();
	private String loadedPayments;
	/** The exact balance /bal last said, and when; -1 if it hasn't said yet this session. */
	private double exactBalance = -1;
	private long exactAt;
	/** Until when an answer to /bal we sent is expected (and hidden from chat). */
	private long awaitingBalance, lastBalanceRequest;

	/** 1,234.56, 404K, 1.2M or 2B as a number. */
	public static double parseMoney(String text) {
		String clean = text.replace(",", "").replace("$", "").replace(" ", "").toLowerCase(Locale.ROOT);
		if (clean.isEmpty()) return -1;
		double scale = switch (clean.charAt(clean.length() - 1)) {
			case 'k' -> 1e3;
			case 'm' -> 1e6;
			case 'b' -> 1e9;
			case 't' -> 1e12;
			case 'q' -> 1e15;
			default -> 1;
		};
		if (scale != 1) clean = clean.substring(0, clean.length() - 1);
		try {
			return Double.parseDouble(clean) * scale;
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	/** Reads one system message. Returns false to hide it: only the answer to a /bal the app sent. */
	private boolean serverMessage(String raw, boolean overlay) {
		String text = net.minecraft.ChatFormatting.stripFormatting(raw).trim();
		// A payment above the hotbar: the Ledger shows it straight away instead, so the server's is hidden
		boolean hide = overlay && hidesPopups();
		java.util.regex.Matcher in = PAID_YOU.matcher(text);
		if (in.find()) {
			double amount = parseMoney(in.group(2));
			if (amount > 0) paid(new Payment(in.group(1), amount, true, System.currentTimeMillis()));
			return !hide;
		}
		java.util.regex.Matcher out = YOU_PAID.matcher(text);
		if (out.find()) {
			double amount = parseMoney(out.group(2));
			if (amount > 0) paid(new Payment(out.group(1), amount, false, System.currentTimeMillis()));
			return !hide;
		}
		if (overlay) return !(hide && text.toLowerCase(Locale.ROOT).contains("paid you"));
		String lower = text.toLowerCase(Locale.ROOT);
		boolean aboutBalance = lower.contains("bal") || lower.contains("you have");
		// Player chat here comes as system messages too ("name » hi", "<name> hi", "rank|name: hi"), so it's never taken for an answer
		boolean chatLine = text.contains("»") || text.startsWith("<") || (!aboutBalance && text.matches("^\\S+: .*"));
		java.util.regex.Matcher dollars = DOLLARS.matcher(text);
		if (chatLine || !dollars.find()) return true;
		long now = System.currentTimeMillis();
		boolean asked = now < awaitingBalance;
		if (!asked && !aboutBalance) return true;
		double value = parseMoney(dollars.group(1));
		if (value < 0) return true;
		exactBalance = value;
		exactAt = now;
		awaitingBalance = 0;
		// Only the answer to the app's own quiet /bal is hidden; one you typed yourself stays in chat
		return !asked;
	}

	/** Whether the server's own payment pop-ups (above the hotbar, or a title) are hidden for the Ledger's. */
	private boolean hidesPopups() {
		return isEnabled() && paymentAlerts.get();
	}

	/** When a payment title was last hidden, so its subtitle (often the amount) goes with it. */
	private volatile long hidTitleAt;

	/** Payment titles: the Ledger's notification comes from them the moment they arrive, and they're hidden. On the network thread. */
	@Override
	public void onPacketReceive(dev.femboyware.event.PacketEvent event) {
		if (!hidesPopups()) return;
		net.minecraft.network.chat.Component shown = null;
		boolean subtitle = false;
		if (event.getPacket() instanceof net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket title) shown = title.text();
		else if (event.getPacket() instanceof net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket sub) {
			shown = sub.text();
			subtitle = true;
		}
		if (shown == null) return;
		String text = net.minecraft.ChatFormatting.stripFormatting(shown.getString()).trim();
		boolean payment = text.toLowerCase(Locale.ROOT).contains("paid you");
		if (!payment && !(subtitle && System.currentTimeMillis() - hidTitleAt < 1500)) return;
		event.cancel();
		hidTitleAt = System.currentTimeMillis();
		java.util.regex.Matcher in = PAID_YOU.matcher(text);
		if (in.find()) {
			String who = in.group(1);
			double amount = parseMoney(in.group(2));
			long at = System.currentTimeMillis();
			if (amount > 0) mc.execute(() -> paid(new Payment(who, amount, true, at)));
		}
	}

	private void paid(Payment payment) {
		loadPayments();
		// The same payment told twice (above the hotbar or as a title, then in chat) only counts once
		for (Payment p : payments) {
			if (payment.time() - p.time() > 4000) break;
			if (p.received() == payment.received() && p.player().equalsIgnoreCase(payment.player()) && Math.abs(p.amount() - payment.amount()) < 0.01) return;
		}
		payments.addFirst(payment);
		while (payments.size() > MAX_PAYMENTS) payments.removeLast();
		savePayments();
		if (!payment.received() || !isEnabled() || !paymentAlerts.get()) return;
		toasts.add(new Toast(null, payment, payment.time()));
		while (toasts.size() > 3) toasts.removeFirst();
		if (sound.get() && dev.femboyware.module.hud.NotificationsModule.on()) LarpSounds.play(LarpSounds.LEDGER_NOTIFY, 0.9f);
	}

	private void loadPayments() {
		String saved = paymentsSaved.get();
		if (saved == loadedPayments) return;
		loadedPayments = saved;
		payments.clear();
		for (String row : saved.split(";")) {
			String[] parts = row.split("\\|");
			if (parts.length != 4) continue;
			payments.add(new Payment(parts[0], parseOr(parts[1], 0), parts[2].equals("R"), (long) parseOr(parts[3], 0)));
		}
	}

	private void savePayments() {
		List<String> rows = new ArrayList<>();
		for (Payment p : payments) rows.add(p.player() + "|" + p.amount() + "|" + (p.received() ? "R" : "S") + "|" + p.time());
		paymentsSaved.set(String.join(";", rows));
		loadedPayments = paymentsSaved.get();
		FemboyWare.getConfig().save();
	}

	/** Your DonutSMP payments, newest first. */
	public List<Payment> payments() {
		loadPayments();
		return payments;
	}

	/** Quietly asks the server for your exact balance (its answer is kept out of chat); at most every 15 seconds. */
	public void requestBalance() {
		long now = System.currentTimeMillis();
		if (mc.player == null || mc.getConnection() == null || mc.getSingleplayerServer() != null) return;
		if (now - lastBalanceRequest < BALANCE_EVERY) return;
		lastBalanceRequest = now;
		awaitingBalance = now + BALANCE_WAIT;
		mc.player.connection.sendCommand("bal");
	}

	/** The sidebar's money line, as the server sends it (not as Scoreboard Edit shows it); -1 if there isn't one. */
	public double scoreboardBalance() {
		if (mc.level == null) return -1;
		var scoreboard = mc.level.getScoreboard();
		var objective = scoreboard.getDisplayObjective(net.minecraft.world.scores.DisplaySlot.SIDEBAR);
		if (objective == null) return -1;
		for (var entry : scoreboard.listPlayerScores(objective)) {
			if (entry.isHidden()) continue;
			String line = net.minecraft.ChatFormatting.stripFormatting(net.minecraft.world.scores.PlayerTeam.formatNameForTeam(
				scoreboard.getPlayersTeam(entry.owner()), entry.ownerName()).getString());
			String lower = line.toLowerCase(Locale.ROOT);
			int label = Math.max(lower.indexOf("money"), lower.indexOf("balance"));
			if (label < 0) continue;
			java.util.regex.Matcher number = NUMBER.matcher(line.substring(label));
			if (number.find()) return parseMoney(number.group(1));
		}
		return -1;
	}

	/**
	 * Your DonutSMP balance, as exactly as it's known: what /bal last said plus the payments since,
	 * unless the sidebar shows it's moved on some other way (shops, auctions), then the sidebar's.
	 * -1 if neither has said.
	 */
	public double balance() {
		// A fake balance set with /fakebal is the one the larp goes by
		double fake = dev.femboyware.module.utility.FakeEconomy.balance();
		if (fake >= 0) return fake;
		double side = scoreboardBalance();
		if (exactBalance >= 0) {
			double adjusted = exactBalance;
			for (Payment p : payments()) {
				if (p.time() <= exactAt) break;
				adjusted += p.received() ? p.amount() : -p.amount();
			}
			// The sidebar rounds (1.2M), so it only counts as different when it's well off
			if (side < 0 || Math.abs(adjusted - side) <= Math.max(1, Math.abs(side) * 0.06)) return adjusted;
			requestBalance();
		}
		return side;
	}

	/** Whether {@link #balance} is the exact figure rather than the sidebar's rounded one. */
	public boolean balanceExact() {
		double side = scoreboardBalance();
		return exactBalance >= 0 && (side < 0 || Math.abs(balance() - side) <= Math.max(1, Math.abs(side) * 0.06));
	}

	/** Someone paid you: their face, their name and the amount. */
	private void drawPaymentToast(GuiGraphics context, int x, int y, Payment pay, int a) {
		var font = mc.font;
		var info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(pay.player());
		if (info != null) {
			//? if >=26.1 {
			/*net.minecraft.client.gui.components.PlayerFaceExtractor.extractRenderState(context, info.getSkin(), x + 8, y + 10, 20);
			*///?} else {
			net.minecraft.client.gui.components.PlayerFaceRenderer.draw(context, info.getSkin(), x + 8, y + 10, 20);
			//?}
		} else {
			Glass.rounded(context, x + 8, y + 10, x + 28, y + 30, 10, withAlpha(0xFF1F3320, a));
			context.drawString(font, "$", x + 18 - font.width("$") / 2, y + 16, withAlpha(0xFF66BE54, a), false);
		}
		context.drawString(font, "Ledger  ·  now", x + 34, y + 5, withAlpha(0xFF85858C, a), false);
		context.drawString(font, font.plainSubstrByWidth(pay.player() + " paid you", TOAST_WIDTH - 42), x + 34, y + 15, withAlpha(0xFFFFFFFF, a), false);
		context.drawString(font, "+" + usd(pay.amount()), x + 34, y + 26, withAlpha(0xFF66BE54, a), false);
		String where = "DonutSMP";
		context.drawString(font, where, x + TOAST_WIDTH - 8 - font.width(where), y + 26, withAlpha(0xFFB5B5BC, a), false);
	}

	// ---------------------------------------------------------------- the wallet

	/** Reads the saved wallet the first time it's needed, and again if the config changed it. */
	private void load() {
		// Called for every number the wallet shows, so only the saved strings themselves are compared:
		// building and comparing one big string here each call froze the wallet app
		String h = holdingsSaved.get(), o = historySaved.get(), p = pricesSaved.get();
		if (h == loadedHoldings && o == loadedHistory && p == loadedPrices) return;
		loadedHoldings = h;
		loadedHistory = o;
		loadedPrices = p;
		holdings.clear();
		for (String pair : holdingsSaved.get().split(",")) {
			String[] parts = pair.split("=");
			Coin coin = parts.length == 2 ? Coin.parse(parts[0].trim()) : null;
			if (coin != null) holdings.put(coin, parseOr(parts[1], 0));
		}
		basePrices.clear();
		for (Coin coin : Coin.values()) basePrices.put(coin, coin.basePrice);
		for (String pair : pricesSaved.get().split(",")) {
			String[] parts = pair.split("=");
			Coin coin = parts.length == 2 ? Coin.parse(parts[0].trim()) : null;
			if (coin != null) basePrices.put(coin, parseOr(parts[1], coin.basePrice));
		}
		history.clear();
		for (String row : historySaved.get().split(";")) {
			String[] parts = row.split("\\|");
			if (parts.length != 6) continue;
			Coin coin = Coin.parse(parts[0]);
			if (coin == null) continue;
			history.add(new Operation(coin, parseOr(parts[1], 0), parseOr(parts[2], 0), (long) parseOr(parts[3], 0), parts[4].equals("R"), parts[5]));
		}
	}

	private static double parseOr(String text, double fallback) {
		try {
			return Double.parseDouble(text.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private void save() {
		List<String> pairs = new ArrayList<>();
		holdings.forEach((coin, amount) -> {
			if (amount > 0) pairs.add(coin.name() + "=" + amount);
		});
		holdingsSaved.set(String.join(",", pairs));
		List<String> rows = new ArrayList<>();
		for (Operation op : history) {
			rows.add(op.coin.name() + "|" + op.amount + "|" + op.usd + "|" + op.time + "|" + (op.received ? "R" : "S") + "|" + op.address);
		}
		historySaved.set(String.join(";", rows));
		List<String> prices = new ArrayList<>();
		basePrices.forEach((coin, price) -> {
			if (price != coin.basePrice) prices.add(coin.name() + "=" + price);
		});
		pricesSaved.set(String.join(",", prices));
		loadedHoldings = holdingsSaved.get();
		loadedHistory = historySaved.get();
		loadedPrices = pricesSaved.get();
		FemboyWare.getConfig().save();
	}

	public double amount(Coin coin) {
		load();
		return holdings.getOrDefault(coin, 0.0);
	}

	public double price(Coin coin) {
		load();
		return basePrices.get(coin) * (1 + drift.get(coin));
	}

	/** Today's change in a coin's price, as a fraction. */
	public double change(Coin coin) {
		return drift.get(coin);
	}

	public double value(Coin coin) {
		return amount(coin) * price(coin);
	}

	public double total() {
		double total = 0;
		for (Coin coin : Coin.values()) total += value(coin);
		return total;
	}

	/** How much the whole wallet moved today, in dollars. */
	public double totalChange() {
		double change = 0;
		for (Coin coin : Coin.values()) {
			double d = drift.get(coin);
			change += value(coin) * d / (1 + d);
		}
		return change;
	}

	/** Coins with anything in them, biggest first. */
	public List<Coin> held() {
		List<Coin> coins = new ArrayList<>();
		for (Coin coin : Coin.values()) {
			if (amount(coin) > 0) coins.add(coin);
		}
		coins.sort((a, b) -> Double.compare(value(b), value(a)));
		return coins;
	}

	/** Newest first. */
	public List<Operation> history() {
		load();
		return history;
	}

	public boolean unlockAnimation() {
		return unlock.get();
	}

	/** Adds a deposit to the wallet and shows its notification. */
	public void deposit(Coin coin, double amount) {
		load();
		holdings.merge(coin, amount, Double::sum);
		Operation op = new Operation(coin, amount, amount * price(coin), System.currentTimeMillis(), true, coin.randomAddress(random));
		history.addFirst(op);
		while (history.size() > MAX_HISTORY) history.removeLast();
		save();
		toasts.add(new Toast(op, null, op.time()));
		while (toasts.size() > 3) toasts.removeFirst();
		if (sound.get() && dev.femboyware.module.hud.NotificationsModule.on()) {
			LarpSounds.play(LarpSounds.LEDGER_NOTIFY, 0.9f);
		}
		if (chat.get() && dev.femboyware.module.hud.NotificationsModule.on()) ChatUtil.info("Ledger: received " + coin.amount(amount) + " " + coin.name() + " (" + usd(op.usd) + ")");
	}

	private void record(Operation op) {
		history.addFirst(op);
		while (history.size() > MAX_HISTORY) history.removeLast();
	}

	/** Sends {@code amount} of a coin out of the wallet. */
	public void send(Coin coin, double amount, String address) {
		load();
		amount = Math.min(amount, amount(coin));
		if (amount <= 0) return;
		holdings.put(coin, amount(coin) - amount);
		record(new Operation(coin, amount, amount * price(coin), System.currentTimeMillis(), false, address));
		save();
	}

	/** Swaps {@code amount} of one coin for its worth in another, less a small fee. Returns what came in. */
	public double swap(Coin from, Coin to, double amount) {
		load();
		amount = Math.min(amount, amount(from));
		if (amount <= 0 || from == to) return 0;
		double got = amount * price(from) / price(to) * 0.997;
		holdings.put(from, amount(from) - amount);
		holdings.merge(to, got, Double::sum);
		long now = System.currentTimeMillis();
		record(new Operation(from, amount, amount * price(from), now, false, "Swap to " + to.name()));
		record(new Operation(to, got, got * price(to), now, true, "Swap from " + from.name()));
		save();
		return got;
	}

	/** Buys a coin with dollars on the card. Returns how much came in. */
	public double buy(Coin coin, double usd) {
		load();
		double got = usd / price(coin);
		holdings.merge(coin, got, Double::sum);
		record(new Operation(coin, got, usd, System.currentTimeMillis(), true, "Card ****4821"));
		save();
		return got;
	}

	/** A random deposit worth somewhere between the min and max, in a coin you'd plausibly get. */
	public void randomDeposit() {
		Coin[] weighted = {Coin.BTC, Coin.BTC, Coin.BTC, Coin.ETH, Coin.ETH, Coin.SOL, Coin.USDT, Coin.USDT, Coin.XRP, Coin.DOGE, Coin.LTC};
		Coin coin = weighted[random.nextInt(weighted.length)];
		double low = Math.min(minDeposit.get(), maxDeposit.get()) * 1000, high = Math.max(minDeposit.get(), maxDeposit.get()) * 1000;
		// Spread evenly on a log scale, so small deposits are common and big ones are a treat
		double usd = Math.exp(Math.log(low) + random.nextDouble() * (Math.log(high) - Math.log(low)));
		double amount = usd / price(coin);
		double unit = Math.pow(10, -coin.decimals);
		deposit(coin, Math.max(unit, Math.round(amount / unit) * unit));
	}

	public static String usd(double value) {
		return String.format(Locale.US, "$%,.2f", value);
	}

	// ---------------------------------------------------------------- ticking

	@Override
	public void onTick() {
		// Prices wander a little every second, pulled back so they never run away
		// A small nudge every 10 seconds, like a calm market, not a ticker going wild
		if (mc.player.tickCount % 200 == 0) {
			for (Coin coin : Coin.values()) {
				if (coin == Coin.USDT) continue;
				double d = drift.get(coin);
				drift.put(coin, Mth.clamp(d + random.nextGaussian() * 0.0025 - d * 0.02, -0.12, 0.15));
			}
		}
		if (!deposits.get()) return;
		if (nextDepositTicks < 0) scheduleDeposit();
		if (--nextDepositTicks == 0) {
			randomDeposit();
			scheduleDeposit();
		}
	}

	private void scheduleDeposit() {
		// Any time between the min and max wait, so they never come like clockwork
		double low = Math.min(minInterval.get(), maxInterval.get()), high = Math.max(minInterval.get(), maxInterval.get());
		double seconds = low + random.nextDouble() * (high - low);
		nextDepositTicks = Math.max(20, (int) (seconds * 20));
	}

	@Override
	protected void onEnable() {
		nextDepositTicks = -1;
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
		// Taken in the raid: it's in an evidence bag until the freeze lifts
		if (FbiRaidModule.assetsFrozen()) {
			dev.femboyware.util.ChatUtil.info("Your Ledger was seized by the FBI");
			return;
		}
		Mc.setScreen(new LedgerScreen(this));
	}

	// ---------------------------------------------------------------- notifications

	@Override
	public void onRender2D(GuiGraphics context, float tickDelta) {
		if (!dev.femboyware.module.hud.NotificationsModule.on()) {
			toasts.clear();
			return;
		}
		if (toasts.isEmpty() || Mc.hudHidden()) return;
		long now = System.currentTimeMillis();
		toasts.removeIf(toast -> now - toast.time() > life());
		int screenWidth = context.guiWidth(), screenHeight = context.guiHeight();
		for (int i = 0; i < toasts.size(); i++) {
			Toast op = toasts.get(toasts.size() - 1 - i);
			long age = now - op.time();
			float shown = Math.min(Mth.clamp(age / (float) SLIDE_IN, 0, 1), Mth.clamp((life() - age) / (float) SLIDE, 0, 1));
			float eased = shown * shown * (3 - 2 * shown);
			int x, y;
			switch (placement.get()) {
				case TOP -> {
					x = (screenWidth - TOAST_WIDTH) / 2;
					y = topClearOfIsland(context, x, TOAST_WIDTH) + i * (TOAST_HEIGHT + TOAST_GAP) - Math.round((1 - eased) * (TOAST_HEIGHT + 10));
				}
				case TOP_RIGHT -> {
					x = screenWidth - TOAST_WIDTH - 6 + Math.round((1 - eased) * (TOAST_WIDTH + 10));
					y = topClearOfIsland(context, screenWidth - TOAST_WIDTH - 6, TOAST_WIDTH) + i * (TOAST_HEIGHT + TOAST_GAP);
				}
				default -> {
					x = screenWidth - TOAST_WIDTH - 6 + Math.round((1 - eased) * (TOAST_WIDTH + 10));
					y = screenHeight - 60 - TOAST_HEIGHT - i * (TOAST_HEIGHT + TOAST_GAP);
				}
			}
			drawToast(context, x, y, op, eased);
		}
	}

	/**
	 * The top edge for notifications at {@code x..x+width}: just under the Island when it's on and
	 * in the way, so it never covers them, otherwise near the top of the screen.
	 */
	/** How much room the Ledger's notifications at the top are taking, for others to stack below. */
	public int topToastsHeight() {
		if (!isEnabled() || placement.is(Placement.BOTTOM_RIGHT)) return 0;
		long now = System.currentTimeMillis();
		int count = 0;
		for (Toast toast : toasts) count += now - toast.time() <= life() ? 1 : 0;
		return count * (TOAST_HEIGHT + TOAST_GAP);
	}

	public static int topClearOfIsland(GuiGraphics context, int x, int width) {
		var island = dev.femboyware.FemboyWare.getModules().get(dev.femboyware.module.hud.IslandModule.class);
		if (island == null || !island.isEnabled() || island.width(false) <= 0) return 6;
		int left = island.left(context.guiWidth(), false), top = island.top(context.guiHeight(), false);
		boolean overlaps = x < left + island.width(false) && x + width > left && top < context.guiHeight() / 3;
		return overlaps ? top + island.height(false) + 4 : 6;
	}

	/** A wallet notification card: the coin, what came in, and what it's worth; or who paid you and how much. */
	private void drawToast(GuiGraphics context, int x, int y, Toast toast, float alpha) {
		int a = Math.round(alpha * 255);
		Glass.rounded(context, x - 1, y - 1, x + TOAST_WIDTH + 1, y + TOAST_HEIGHT + 1, 7, withAlpha(0xFF2E2E33, a));
		Glass.rounded(context, x, y, x + TOAST_WIDTH, y + TOAST_HEIGHT, 6, withAlpha(0xFF141416, a));
		if (toast.pay() != null) {
			drawPaymentToast(context, x, y, toast.pay(), a);
			return;
		}
		Operation op = toast.op();
		int icon = 20;
		context.blit(RenderPipelines.GUI_TEXTURED, op.coin().icon, x + 8, y + 12, 0, 0, icon, icon, 64, 64, 64, 64, withAlpha(0xFFFFFFFF, a));
		var font = mc.font;
		context.drawString(font, "Ledger  ·  now", x + 34, y + 5, withAlpha(0xFF85858C, a), false);
		context.drawString(font, op.coin().fullName + " received", x + 34, y + 15, withAlpha(0xFFFFFFFF, a), false);
		String amount = "+" + op.coin().amount(op.amount()) + " " + op.coin().name();
		context.drawString(font, amount, x + 34, y + 26, withAlpha(0xFF66BE54, a), false);
		String value = usd(op.usd());
		context.drawString(font, value, x + TOAST_WIDTH - 8 - font.width(value), y + 26, withAlpha(0xFFB5B5BC, a), false);
	}

	private static int withAlpha(int color, int alpha) {
		return (color & 0x00FFFFFF) | (Math.round(((color >>> 24) / 255f) * alpha) << 24);
	}

	// ---------------------------------------------------------------- commands

	/** Handles {@code .ledger [open|deposit|set|price|reset|history]}. */
	public void command(String[] args) {
		load();
		String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "open";
		switch (action) {
			case "open" -> mc.schedule(this::open);
			case "timing", "time", "every" -> {
				if (args.length < 3) {
					double low = Math.min(minInterval.get(), maxInterval.get()), high = Math.max(minInterval.get(), maxInterval.get());
					ChatUtil.info("Deposits: every " + (int) low + " to " + (int) high + "s" + (deposits.get() ? "" : " (off)") + ". Pop-ups stay up " + popupTime.get() + "s");
					ChatUtil.info("Change: " + CommandManager.PREFIX + "ledger timing <min seconds> [max seconds], or " + CommandManager.PREFIX + "ledger timing popup <seconds>");
					return;
				}
				try {
					if (args[2].equalsIgnoreCase("popup")) {
						popupTime.set(Double.parseDouble(args[3]));
						ChatUtil.info("Ledger pop-ups stay up " + popupTime.get() + "s");
					} else {
						double low = Double.parseDouble(args[2]), high = args.length > 3 ? Double.parseDouble(args[3]) : low;
						deposits.set(true);
						minInterval.set(Math.min(low, high));
						maxInterval.set(Math.max(low, high));
						ChatUtil.info("Deposits: every " + minInterval.get().intValue() + " to " + maxInterval.get().intValue() + "s");
					}
					dev.femboyware.FemboyWare.getConfig().save();
				} catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
					ChatUtil.info("Usage: " + CommandManager.PREFIX + "ledger timing <min seconds> [max seconds] | popup <seconds>");
				}
			}
			case "give", "item" -> {
				if (LarpItems.has(LarpItems.LEDGER)) {
					ChatUtil.info("You've already got your Ledger");
					return;
				}
				boolean given = LarpItems.give(LarpItems.ledger());
				if (!given) {
					ChatUtil.info("Your inventory's full");
					return;
				}
				ownsItem.set(true);
				if (!isEnabled()) setEnabled(true);
				dev.femboyware.FemboyWare.getConfig().save();
				ChatUtil.info(LarpItems.larping()
					? "Here's your Ledger, in your larp inventory: move it round like any item. Right-click it to open it"
					: "Here's your Ledger. It's a ghost item, on your side only; turn on Server Larp to move it round freely. Right-click it to open it");
			}
			case "remove", "take" -> {
				ownsItem.set(false);
				LarpItems.remove(LarpItems.LEDGER);
				dev.femboyware.FemboyWare.getConfig().save();
				ChatUtil.info("Put your Ledger away");
			}
			case "deposit", "receive", "add" -> {
				if (args.length < 3) {
					randomDeposit();
					return;
				}
				Coin coin = Coin.parse(args[2]);
				if (coin == null) {
					ChatUtil.info("Coins: " + coinList());
					return;
				}
				if (args.length < 4) {
					double usd = Math.min(minDeposit.get(), maxDeposit.get()) * 1000 + random.nextDouble() * Math.abs(maxDeposit.get() - minDeposit.get()) * 1000;
					deposit(coin, usd / price(coin));
				} else deposit(coin, parseOr(args[3], 0));
			}
			case "set" -> {
				Coin coin = args.length > 3 ? Coin.parse(args[2]) : null;
				if (coin == null) {
					ChatUtil.info("Usage: " + CommandManager.PREFIX + "ledger set <" + coinList() + "> <amount>");
					return;
				}
				holdings.put(coin, Math.max(0, parseOr(args[3], 0)));
				save();
				ChatUtil.info("Wallet now holds " + coin.amount(amount(coin)) + " " + coin.name() + " (" + usd(value(coin)) + ")");
			}
			case "price" -> {
				Coin coin = args.length > 3 ? Coin.parse(args[2]) : null;
				if (coin == null) {
					ChatUtil.info("Usage: " + CommandManager.PREFIX + "ledger price <" + coinList() + "> <dollars>");
					return;
				}
				basePrices.put(coin, Math.max(0.0001, parseOr(args[3], coin.basePrice)));
				save();
				ChatUtil.info(coin.fullName + " is now about " + usd(price(coin)));
			}
			case "history" -> {
				if (args.length > 2 && args[2].equalsIgnoreCase("clear")) {
					history.clear();
					save();
					ChatUtil.info("Cleared the wallet's history");
				} else ChatUtil.info(history.size() + " operations. Clear them with " + CommandManager.PREFIX + "ledger history clear");
			}
			case "reset" -> {
				holdingsSaved.reset();
				historySaved.reset();
				pricesSaved.reset();
				loadedHoldings = null;
				FemboyWare.getConfig().save();
				ChatUtil.info("Wallet reset to its starting coins");
			}
			case "total", "balance" -> ChatUtil.info("Wallet total: " + usd(total()));
			default -> ChatUtil.info("Usage: " + CommandManager.PREFIX + "ledger <open|give|deposit [coin] [amount]|set <coin> <amount>|price <coin> <dollars>|history [clear]|reset|total>");
		}
	}

	private static String coinList() {
		List<String> names = new ArrayList<>();
		for (Coin coin : Coin.values()) names.add(coin.name().toLowerCase(Locale.ROOT));
		return String.join("|", names);
	}
}
