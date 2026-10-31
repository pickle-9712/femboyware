package dev.femboyware.module.utility;

import dev.femboyware.manager.CommandManager;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.StringSetting;
import dev.femboyware.util.ChatUtil;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hides your name everywhere it's drawn, and can show every player with a default skin.
 * Ported from Meteor Client's NameProtect (https://github.com/MeteorDevelopment/meteor-client), GPL-3.0.
 */
public class NameProtectModule extends Module {
	private final BoolSetting hideName = add(new BoolSetting("Hide Name", "Replace your name in all text", true));
	private final StringSetting name = add(new StringSetting("Name", "What to show instead of your name", "femboy")).visibleWhen(hideName::get);
	private final StringSetting others = add(new StringSetting("Other Players", "Other players' names and what to show instead, like RealName=FakeName, Another=Fake2. Or use .np add <name> <fake>", ""));
	private final BoolSetting skinProtect = add(new BoolSetting("Skin Protect", "Show every player with a default skin", true));

	/** The Other Players setting as it was last read, and what that means. */
	private String parsedFrom = "";
	private Map<String, String> fakes = Map.of();
	private Pattern pattern;

	public NameProtectModule() {
		super("Name Protect", "Hides your name, other players' names and player skins", Category.MISC);
	}

	/** Reads the setting again if it's changed. Names are matched whole and ignoring case, so "Bob" leaves "Bobby" alone. */
	private void parse() {
		String setting = others.get();
		if (setting.equals(parsedFrom)) return;
		parsedFrom = setting;
		Map<String, String> found = new LinkedHashMap<>();
		for (String pair : setting.split(",")) {
			int equals = pair.indexOf('=');
			if (equals <= 0) continue;
			String real = pair.substring(0, equals).trim(), fake = pair.substring(equals + 1).trim();
			if (!real.isEmpty()) found.put(real.toLowerCase(Locale.ROOT), fake);
		}
		fakes = found;
		pattern = null;
		if (found.isEmpty()) return;
		StringJoiner names = new StringJoiner("|");
		// Longest first, so a name that starts another can't match instead of it
		found.keySet().stream().sorted(Comparator.comparingInt(String::length).reversed()).forEach(name -> names.add(Pattern.quote(name)));
		pattern = Pattern.compile("(?<![A-Za-z0-9_])(" + names + ")(?![A-Za-z0-9_])", Pattern.CASE_INSENSITIVE);
	}

	private void write(Map<String, String> pairs) {
		StringJoiner joined = new StringJoiner(", ");
		pairs.forEach((real, fake) -> joined.add(real + "=" + fake));
		others.set(joined.toString());
	}

	/** Handles {@code .np <add|remove|list|clear>}. */
	public void command(String[] args) {
		parse();
		String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "list";
		Map<String, String> pairs = new LinkedHashMap<>(fakes);
		switch (action) {
			case "add", "set" -> {
				if (args.length < 4) {
					ChatUtil.info("Usage: " + CommandManager.PREFIX + "np add <name> <fake name>");
					return;
				}
				String fake = String.join(" ", List.of(args).subList(3, args.length)).replace(",", "").replace("=", "");
				pairs.put(args[2].toLowerCase(Locale.ROOT), fake);
				write(pairs);
				// Spelled apart so this message doesn't get its own replacement
				ChatUtil.info("Now hiding that player as " + fake + (isEnabled() ? "" : " once Name Protect is on"));
			}
			case "remove", "del", "delete" -> {
				if (args.length < 3) {
					ChatUtil.info("Usage: " + CommandManager.PREFIX + "np remove <name>");
					return;
				}
				boolean had = pairs.remove(args[2].toLowerCase(Locale.ROOT)) != null;
				write(pairs);
				ChatUtil.info(had ? "No longer hiding " + args[2] : args[2] + " wasn't on the list");
			}
			case "clear" -> {
				write(Map.of());
				ChatUtil.info("Cleared the list");
			}
			case "list" -> {
				if (pairs.isEmpty()) ChatUtil.info("Nobody hidden yet. Add one with " + CommandManager.PREFIX + "np add <name> <fake name>");
				else pairs.forEach((real, fake) -> ChatUtil.info("Showing a hidden player as " + fake));
			}
			default -> ChatUtil.info("Usage: " + CommandManager.PREFIX + "np <add|remove|list|clear> [name] [fake name]");
		}
	}

	/** Called from TextVisitFactoryMixin for every string the game draws. */
	public String replaceName(String text) {
		if (!isEnabled() || text == null) return text;
		String result = text;
		if (hideName.get() && mc.getSession() != null) {
			String username = mc.getSession().getUsername();
			if (!username.isEmpty()) result = result.replace(username, name.get());
		}
		parse();
		if (pattern == null) return result;
		Matcher matcher = pattern.matcher(result);
		if (!matcher.find()) return result;
		return matcher.replaceAll(match -> Matcher.quoteReplacement(fakes.get(match.group(1).toLowerCase(Locale.ROOT))));
	}

	public boolean skinProtect() {
		return isEnabled() && skinProtect.get();
	}
}
