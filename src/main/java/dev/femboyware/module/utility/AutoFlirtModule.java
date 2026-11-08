package dev.femboyware.module.utility;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.setting.StringSetting;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.multiplayer.PlayerInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Says cute things to one player in chat on a random timer, and answers when they talk to you :3 */
public class AutoFlirtModule extends Module {
	private final StringSetting player = add(new StringSetting("Player", "Who to flirt with", ""));
	private final StringSetting messages = add(new StringSetting("Messages", "Messages separated by ;. {player} is their name", "hi {player} :3; {player} ur so cute :3; {player} has the prettiest skin; thinking about {player} rn :3; {player} wanna be friends :3; {player} ur my favourite person on here; boop {player} :3; {player} come here i wanna show u something :3; {player} ur so pretty omg; can {player} be my duo :3; {player} hii i missed u; {player} is so cool fr; {player} u make this server better :3; hold my hand {player} :3; {player} u smell nice (in a minecraft way)"));
	private final BoolSetting whisper = add(new BoolSetting("Whisper", "Send them as private messages with /msg instead of in public chat", false));
	private final BoolSetting onlyOnline = add(new BoolSetting("Only Online", "Only flirt while they're in the tab list", true));
	private final NumberSetting minDelay = add(new NumberSetting("Min Delay", "Shortest wait between messages, in seconds", 30, 5, 600, 5));
	private final NumberSetting maxDelay = add(new NumberSetting("Max Delay", "Longest wait between messages, in seconds", 90, 5, 600, 5));
	private final BoolSetting replies = add(new BoolSetting("Replies", "Answer when they talk to you", true));
	private final BoolSetting onlyNamed = add(new BoolSetting("Only When Named", "Only answer their messages that have your name in, or that they whisper you. Off answers everything they say", true)).visibleWhen(replies::get);
	private final StringSetting nicknames = add(new StringSetting("Nicknames", "Other names they call you, separated by commas, as well as your username", "")).visibleWhen(() -> replies.get() && onlyNamed.get());
	private final StringSetting replyMessages = add(new StringSetting("Reply Messages", "Answers, separated by ;. {player} is their name", "hehe hi {player} :3; {player} stoppp ur making me blush; omg {player} talked to me :3; {player} ur so sweet; hiii {player}!!; {player} :3; awww {player}; {player} hehe; im blushing {player}; {player} u always know what to say; yes {player}? :3; {player} ur too nice to me")).visibleWhen(replies::get);
	private final NumberSetting replyDelay = add(new NumberSetting("Reply Delay", "Most seconds to wait before answering, like you're typing", 4, 1, 20, 0.5)).visibleWhen(replies::get);
	private final NumberSetting replyGap = add(new NumberSetting("Reply Gap", "Never answer more often than this many seconds, so servers don't kick for spam", 8, 2, 60, 1)).visibleWhen(replies::get);

	private final Random random = new Random();
	private long next;
	private String last, lastReply;
	/** The answer we're "typing": who to, and when to send it, or 0 if none. And when we last sent anything. */
	private String replyTo;
	private long replyAt;
	private long lastSent;

	public AutoFlirtModule() {
		super("Auto Flirt", "Says cute things to a player in chat :3", Category.MISC);
		ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, timestamp) -> {
			if (sender == null || !isEnabled() || mc.player == null) return;
			// A vanilla /msg shows up as their chat with the whisper wording around it
			boolean whispered = message.getString().toLowerCase(Locale.ROOT).contains("whispers to you");
			heard(sender.name(), signed != null ? signed.signedContent() : message.getString(), whispered);
		});
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (overlay || !isEnabled() || mc.player == null) return;
			String wanted = player.get().trim();
			String text = ChatFormatting.stripFormatting(message.getString());
			if (wanted.isEmpty() || text == null) return;
			String name = Pattern.quote(wanted);
			// Server chat plugins: "F1|[Rank] Name: msg", "<Name> msg", "Name » msg", or a whisper "[Name -> me] msg" / "Name whispers to you: msg".
			// Nothing before the name may hold a separator, so someone else can't fake it by typing their name into a message
			Matcher whispered = Pattern.compile("^\\[" + name + "\\s*->[^\\]]*\\]\\s*(.*)$|^" + name + " whispers(?: to you)?:\\s*(.*)$", Pattern.CASE_INSENSITIVE).matcher(text);
			Matcher said = Pattern.compile("^[^:>\u00bb]*?(?<![A-Za-z0-9_])<?" + name + ">?\\s*[:>\u00bb]+\\s*(.*)$", Pattern.CASE_INSENSITIVE).matcher(text);
			if (whispered.matches()) heard(wanted, whispered.group(1) != null ? whispered.group(1) : whispered.group(2), true);
			else if (said.matches()) heard(wanted, said.group(1), false);
		});
	}

	/** Someone said something; if it's them talking to us, start "typing" an answer. */
	private void heard(String sender, String text, boolean whispered) {
		if (!replies.get() || !sender.equalsIgnoreCase(player.get().trim()) || sender.equalsIgnoreCase(mc.player.getGameProfile().name())) return;
		if (replyAt != 0 || System.currentTimeMillis() - lastSent < replyGap.get() * 1000) return;
		if (onlyNamed.get() && !whispered && !namesMe(text)) return;
		replyTo = sender;
		replyAt = System.currentTimeMillis() + 1000 + (long) (random.nextDouble() * Math.max(0, replyDelay.get() - 1) * 1000);
	}

	/** Whether {@code text} has our username or one of our nicknames in it, as a word of its own. */
	private boolean namesMe(String text) {
		List<String> names = new ArrayList<>(List.of(nicknames.get().split(",")));
		names.add(mc.player.getGameProfile().name());
		for (String name : names) {
			String trimmed = name.trim();
			if (!trimmed.isEmpty() && Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(trimmed) + "(?![A-Za-z0-9_])", Pattern.CASE_INSENSITIVE).matcher(text).find()) return true;
		}
		return false;
	}

	@Override
	public String getInfo() {
		return player.get().isBlank() ? null : player.get().trim();
	}

	@Override
	protected void onEnable() {
		// Say something soon after turning it on, not a whole delay later
		next = System.currentTimeMillis() + 2000 + random.nextInt(3000);
	}

	@Override
	public void onTick() {
		long now = System.currentTimeMillis();
		if (replyAt != 0 && now >= replyAt) {
			replyAt = 0;
			String answer = pick(replyMessages.get(), replyTo, true);
			if (answer != null) send(replyTo, answer);
			// Don't flirt again straight after answering
			next = Math.max(next, now + (long) (minDelay.get() * 500));
			return;
		}
		if (now < next) return;
		String name = onlineName(player.get().trim());
		if (name == null) {
			// Check again in a bit rather than every tick
			next = now + 5000;
			return;
		}
		String message = pick(messages.get(), name, false);
		if (message != null) send(name, message);
		schedule();
	}

	/** Their name as the server has it, or null if nobody's set or they aren't on. With Only Online off, the name as typed. */
	private String onlineName(String wanted) {
		if (wanted.isEmpty()) return null;
		if (!onlyOnline.get()) return wanted;
		for (PlayerInfo entry : mc.getConnection().getOnlinePlayers()) {
			if (entry.getProfile().name().equalsIgnoreCase(wanted)) return entry.getProfile().name();
		}
		return null;
	}

	/** A random line from {@code list} with their name in, never the same one twice in a row. */
	private String pick(String list, String name, boolean reply) {
		List<String> options = new ArrayList<>();
		for (String message : list.split(";")) {
			if (!message.isBlank()) options.add(message.trim());
		}
		if (options.size() > 1) options.remove(reply ? lastReply : last);
		if (options.isEmpty()) return null;
		String chosen = options.get(random.nextInt(options.size()));
		if (reply) lastReply = chosen;
		else last = chosen;
		return chosen.replace("{player}", name);
	}

	private void send(String name, String message) {
		if (whisper.get()) mc.getConnection().sendCommand("msg " + name + " " + message);
		else if (message.startsWith("/")) mc.getConnection().sendCommand(message.substring(1));
		else mc.getConnection().sendChat(message);
		lastSent = System.currentTimeMillis();
	}

	private void schedule() {
		double min = Math.min(minDelay.get(), maxDelay.get());
		double max = Math.max(minDelay.get(), maxDelay.get());
		next = System.currentTimeMillis() + (long) ((min + random.nextDouble() * (max - min)) * 1000);
	}
}
