package dev.femboyware.module.utility;

import com.mojang.authlib.GameProfile;
import dev.femboyware.FemboyWare;
import dev.femboyware.manager.CommandManager;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.module.client.FakePlayerModule;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.StringSetting;
import dev.femboyware.util.AccountSkins;
import dev.femboyware.util.ChatUtil;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Pattern;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.world.entity.player.PlayerSkin;
import java.util.regex.Pattern;

/**
 * Hides your name everywhere it's drawn, and can show every player with a default skin.
 * Ported from Meteor Client's NameProtect (https://github.com/MeteorDevelopment/meteor-client), GPL-3.0.
 */
public class NameProtectModule extends Module {
	/** Early Mojang accounts and short or single word names, all checked against Mojang on 2026-10-31. */
	private static final String DEFAULT_FAKE_NAMES = "Notch, jeb_, Dinnerbone, Grumm, Marc, Herobrine, Dream, deadmau5, Steve, Alex, Bot, Xbox, cat, Dog, God, Fox, Ace, Max, Sky, Ghost, Shadow, Dragon, Wolf, Hero, King, Angel, Death, Fire, ICE, tiger, Ninja, Pixel, Storm, Nova, Luna, Zero, blaze, Creeper, Ender, Minecraft, Mojang, xlson, Carnalizer, kappische, Searge, mollstam, slicedlime, TheMogMiner";

	private final BoolSetting hideName = add(new BoolSetting("Hide Name", "Replace your name in all text", true));
	private final StringSetting name = add(new StringSetting("Name", "What to show instead of your name", "femboy")).visibleWhen(hideName::get);
	private final StringSetting others = add(new StringSetting("Other Players", "Other players' names and what to show instead, like RealName=FakeName, Another=Fake2. Or use .np add <name> <fake>", ""));
	private final BoolSetting serverNames = add(new BoolSetting("Server Name Protect", "Give everyone on the server a different random name from Fake Names", false));
	private final StringSetting fakeNames = add(new StringSetting("Fake Names", "The names Server Name Protect picks from, separated by commas", DEFAULT_FAKE_NAMES)).visibleWhen(serverNames::get);
	private final BoolSetting accountSkins = add(new BoolSetting("Account Skins", "Anyone with a fake name wears that account's real skin, or a default skin if it has none", true));
	private final BoolSetting skinProtect = add(new BoolSetting("Skin Protect", "Show every player with a default skin; Account Skins goes first for anyone with a fake name", true));

	private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");

	/** The Other Players setting as it was last read, and what that means. */
	private String parsedFrom = "";
	private Map<String, String> fakes = Map.of();
	/** Random names given out on this server, by lower case real name. Only online players hold theirs; people who left stay listed so old chat keeps hiding them, but anyone can claim their name. */
	private final Map<String, String> assigned = new HashMap<>();
	/** Names given out after the list ran out, which aren't accounts. */
	private final Set<String> madeUp = new HashSet<>();
	/** Lower case names online players are showing as, from the last time names were handed out. */
	private Set<String> heldNames = Set.of();
	private ClientPacketListener assignedOn;
	private String assignedFrom = "";
	/** Everything to replace, by lower case real name. */
	private Map<String, String> replacements = Map.of();

	public NameProtectModule() {
		super("Name Protect", "Hides your name, other players' names and player skins", Category.MISC);
	}

	/** Reads the setting again if it's changed. */
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
		publish();
	}

	/** Names set by hand go over random ones. */
	private void publish() {
		Map<String, String> all = new HashMap<>(assigned);
		all.putAll(fakes);
		replacements = all;
	}

	@Override
	public void onTick() {
		parse();
		ClientPacketListener handler = mc.getConnection();
		String from = serverNames.get() + "|" + hideName.get() + "|" + name.get() + "|" + fakeNames.get();
		if (handler != assignedOn || !from.equals(assignedFrom)) {
			assignedOn = handler;
			assignedFrom = from;
			forgetAssigned();
		}
		if (handler == null || !serverNames.get()) {
			heldNames = Set.of();
			return;
		}
		List<String> online = new ArrayList<>();
		for (PlayerInfo entry : handler.getOnlinePlayers()) {
			String real = entry.getProfile().name();
			// Servers fill out the tab list with made up entries and NPCs, which don't need names
			if (!VALID_NAME.matcher(real).matches() || entry.getProfile().id().version() == 2) continue;
			String key = real.toLowerCase(Locale.ROOT);
			if (!fakes.containsKey(key) && !(hideName.get() && real.equalsIgnoreCase(mc.getUser().getName()))) online.add(key);
		}
		assignNames(online);
	}

	/** Makes sure everyone in {@code online} has a name nobody else online is using. */
	void assignNames(List<String> online) {
		Set<String> inUse = reservedNames();
		for (String used : FemboyWare.getModules().get(FakePlayerModule.class).activeNames()) inUse.add(used.toLowerCase(Locale.ROOT));
		heldNames = inUse;
		// Everyone keeps a list name they already have. Made up names are held too, until a list name frees up
		List<String> needNames = new ArrayList<>();
		for (String key : online) {
			String current = assigned.get(key);
			// Back after someone else claimed their name
			if (current != null && !inUse.add(current.toLowerCase(Locale.ROOT))) {
				assigned.remove(key);
				current = null;
			}
			if (current == null || madeUp.contains(current)) needNames.add(key);
		}
		if (needNames.isEmpty()) return;
		List<String> free = namesFreeOf(inUse);
		Collections.shuffle(free);
		boolean changed = false;
		int number = 1;
		for (String key : needNames) {
			String current = assigned.get(key);
			String picked;
			if (!free.isEmpty()) {
				picked = free.removeLast();
				if (current != null) inUse.remove(current.toLowerCase(Locale.ROOT));
			} else if (current != null && madeUp.contains(current)) {
				continue;
			} else {
				while (inUse.contains("player" + number)) number++;
				picked = "Player" + number;
				madeUp.add(picked);
			}
			inUse.add(picked.toLowerCase(Locale.ROOT));
			assigned.put(key, picked);
			changed = true;
		}
		if (changed) publish();
	}

	/** Names set by hand and your own, which random names have to stay clear of. */
	private Set<String> reservedNames() {
		Set<String> reserved = new HashSet<>();
		for (String used : fakes.values()) reserved.add(used.toLowerCase(Locale.ROOT));
		if (hideName.get()) reserved.add(name.get().toLowerCase(Locale.ROOT));
		return reserved;
	}

	private List<String> namesFreeOf(Set<String> inUse) {
		List<String> free = new ArrayList<>();
		for (String candidate : fakeNames.get().split(",")) {
			String trimmed = candidate.trim();
			if (!trimmed.isEmpty() && !inUse.contains(trimmed.toLowerCase(Locale.ROOT))) free.add(trimmed);
		}
		return free;
	}

	/** The Fake Names nobody is using: not shown for anyone online, and not any online player's real name. */
	public List<String> unusedFakeNames() {
		Set<String> inUse = reservedNames();
		if (isEnabled()) inUse.addAll(heldNames);
		ClientPacketListener handler = mc.getConnection();
		if (handler != null) {
			for (PlayerInfo entry : handler.getOnlinePlayers()) inUse.add(entry.getProfile().name().toLowerCase(Locale.ROOT));
		}
		return namesFreeOf(inUse);
	}

	private void forgetAssigned() {
		assigned.clear();
		madeUp.clear();
		heldNames = Set.of();
		publish();
	}

	private void write(Map<String, String> pairs) {
		StringJoiner joined = new StringJoiner(", ");
		pairs.forEach((real, fake) -> joined.add(real + "=" + fake));
		others.set(joined.toString());
	}

	/** Handles {@code .np <add|remove|list|clear|shuffle>}. */
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
			case "shuffle" -> {
				forgetAssigned();
				ChatUtil.info("Everyone gets a new random name");
			}
			default -> ChatUtil.info("Usage: " + CommandManager.PREFIX + "np <add|remove|list|clear|shuffle> [name] [fake name]");
		}
	}

	/** Called from TextVisitFactoryMixin for every string the game draws. */
	public String replaceName(String text) {
		if (!isEnabled() || text == null) return text;
		parse();
		String result = replaceWords(text, replacements);
		if (hideName.get() && mc.getUser() != null) {
			String username = mc.getUser().getName();
			if (!username.isEmpty()) result = result.replace(username, name.get());
		}
		return result;
	}

	/** Swaps whole names in one pass, ignoring case, so "Bob" leaves "Bobby" alone and a fake name is never replaced again. */
	private static String replaceWords(String text, Map<String, String> words) {
		if (words.isEmpty()) return text;
		StringBuilder result = null;
		int copied = 0, length = text.length(), i = 0;
		while (i < length) {
			char c = text.charAt(i);
			// The letter after a section sign is a color code, not the start of a name
			if (c == '\u00a7') {
				i += 2;
				continue;
			}
			if (!isNameChar(c)) {
				i++;
				continue;
			}
			int end = i + 1;
			while (end < length && isNameChar(text.charAt(end))) end++;
			String fake = words.get(text.substring(i, end).toLowerCase(Locale.ROOT));
			if (fake != null) {
				if (result == null) result = new StringBuilder(length);
				result.append(text, copied, i).append(fake);
				copied = end;
			}
			i = end;
		}
		return result == null ? text : result.append(text, copied, length).toString();
	}

	private static boolean isNameChar(char c) {
		return c == '_' || (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
	}

	/** Called from PlayerListEntryMixin with the skin a player would normally have. */
	public PlayerSkin skinFor(GameProfile profile, PlayerSkin real) {
		if (!isEnabled()) return real;
		String fake = fakeNameOf(profile.name());
		if (fake != null && accountSkins.get()) {
			// Never their real skin, even for the moment the other one takes to load
			PlayerSkin account = madeUp.contains(fake) ? null : AccountSkins.get(fake);
			return account != null ? account : DefaultPlayerSkin.get(profile);
		}
		return skinProtect.get() ? DefaultPlayerSkin.get(profile) : real;
	}

	private String fakeNameOf(String real) {
		if (hideName.get() && mc.getUser() != null && real.equalsIgnoreCase(mc.getUser().getName())) return name.get();
		return replacements.get(real.toLowerCase(Locale.ROOT));
	}
}
