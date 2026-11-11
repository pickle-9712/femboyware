package dev.femboyware.module.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.femboyware.manager.CommandManager;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.StringSetting;
import dev.femboyware.util.ChatUtil;
import dev.femboyware.util.LegacyText;
import dev.femboyware.util.Mc;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Rewrites the scoreboard sidebar on your screen only: values after a label (your balance, kills and
 * so on), any text (like your name), whole lines or the title. Rules match text, not line numbers, so
 * they keep working as the server updates the board.
 */
public class ScoreboardEditModule extends Module {
	private enum Kind { VALUE, REPLACE, LINE, TITLE }

	private record Rule(Kind kind, String match, String text) {
	}

	private final StringSetting rulesSetting = add(new StringSetting("Rules", "", "[]")).visibleWhen(() -> false);
	private List<Rule> rules = new ArrayList<>();
	private String parsedFrom;

	public ScoreboardEditModule() {
		super("Scoreboard Edit", "Change your balance, stats and name on the scoreboard, on your screen only. Use " + CommandManager.PREFIX + "sb", Category.LARP);
	}

	@Override
	public String getInfo() {
		return rules().isEmpty() ? null : String.valueOf(rules().size());
	}

	private List<Rule> rules() {
		if (!rulesSetting.get().equals(parsedFrom)) {
			parsedFrom = rulesSetting.get();
			List<Rule> loaded = new ArrayList<>();
			try {
				for (JsonElement element : JsonParser.parseString(parsedFrom).getAsJsonArray()) {
					JsonObject rule = element.getAsJsonObject();
					loaded.add(new Rule(Kind.valueOf(rule.get("kind").getAsString()), rule.get("match").getAsString(), rule.get("text").getAsString()));
				}
			} catch (RuntimeException ignored) {
			}
			rules = loaded;
		}
		return rules;
	}

	private void save(List<Rule> list) {
		JsonArray array = new JsonArray();
		for (Rule rule : list) {
			JsonObject object = new JsonObject();
			object.addProperty("kind", rule.kind().name());
			object.addProperty("match", rule.match());
			object.addProperty("text", rule.text());
			array.add(object);
		}
		rulesSetting.set(array.toString());
	}

	/** Called from InGameHudMixin for the sidebar title. */
	public Component title(Component title) {
		if (!isEnabled()) return title;
		for (Rule rule : rules()) {
			if (rule.kind() == Kind.TITLE) return LegacyText.parse(rule.text());
		}
		return line(title);
	}

	/** Called from InGameHudMixin for each sidebar line and score. */
	public Component line(Component line) {
		if (!isEnabled() || line == null) return line;
		List<Rule> list = rules();
		if (list.isEmpty()) return line;
		Component out = line;
		for (Rule rule : list) {
			String plain = out.getString();
			switch (rule.kind()) {
				case LINE -> {
					if (contains(plain, rule.match())) return LegacyText.parse(rule.text());
				}
				case VALUE -> {
					int at = indexOf(plain, rule.match());
					if (at >= 0) out = setValue(out, at + rule.match().length(), rule.text());
				}
				case REPLACE -> {
					if (contains(plain, rule.match())) out = replaceAll(out, rule.match(), rule.text());
				}
				default -> {
				}
			}
		}
		return out;
	}

	private static boolean contains(String text, String find) {
		return indexOf(text, find) >= 0;
	}

	private static int indexOf(String text, String find) {
		return find.isEmpty() ? -1 : text.toLowerCase(Locale.ROOT).indexOf(find.toLowerCase(Locale.ROOT));
	}

	/** The text as pieces, each with its own style, so edits can keep the colours. */
	private record Piece(String text, Style style) {
	}

	private static List<Piece> pieces(Component text) {
		List<Piece> list = new ArrayList<>();
		text.visit((style, piece) -> {
			if (!piece.isEmpty()) list.add(new Piece(piece, style));
			return Optional.empty();
		}, Style.EMPTY);
		return list;
	}

	private static Style styleAt(List<Piece> pieces, int index) {
		int seen = 0;
		for (Piece piece : pieces) {
			if (index < seen + piece.text().length()) return piece.style();
			seen += piece.text().length();
		}
		return pieces.isEmpty() ? Style.EMPTY : pieces.getLast().style();
	}

	/** Keeps everything up to {@code from} (and the gap after a label), then the new value in the colours the old one had. */
	private static Component setValue(Component line, int from, String value) {
		List<Piece> pieces = pieces(line);
		String plain = line.getString();
		int start = from;
		while (start < plain.length() && (plain.charAt(start) == ' ' || plain.charAt(start) == ':')) start++;
		Style valueStyle = styleAt(pieces, Math.min(start, Math.max(0, plain.length() - 1)));
		MutableComponent out = slice(pieces, 0, start);
		out.append(value.contains("&") ? LegacyText.parse(value) : Component.literal(value).setStyle(valueStyle));
		return out;
	}

	/** Every match swapped, each in the colours where it began. */
	private static Component replaceAll(Component line, String find, String with) {
		List<Piece> pieces = pieces(line);
		String plain = line.getString();
		String lower = plain.toLowerCase(Locale.ROOT), findLower = find.toLowerCase(Locale.ROOT);
		MutableComponent out = Component.empty();
		int cursor = 0;
		for (int at = lower.indexOf(findLower); at >= 0; at = lower.indexOf(findLower, at + find.length())) {
			out.append(slice(pieces, cursor, at));
			out.append(with.contains("&") ? LegacyText.parse(with) : Component.literal(with).setStyle(styleAt(pieces, at)));
			cursor = at + find.length();
		}
		out.append(slice(pieces, cursor, plain.length()));
		return out;
	}

	private static MutableComponent slice(List<Piece> pieces, int from, int to) {
		MutableComponent out = Component.empty();
		int seen = 0;
		for (Piece piece : pieces) {
			int start = Math.max(from, seen), end = Math.min(to, seen + piece.text().length());
			if (start < end) out.append(Component.literal(piece.text().substring(start - seen, end - seen)).setStyle(piece.style()));
			seen += piece.text().length();
		}
		return out;
	}

	public void command(String[] args) {
		String usage = "Usage: " + CommandManager.PREFIX + "sb <lines|value <label> <value>|replace <find> <with>|line <label> <text>|title <text>|list|remove <number>|clear>";
		String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
		List<Rule> list = new ArrayList<>(rules());
		switch (action) {
			case "lines" -> showLines();
			case "value", "replace", "line" -> {
				if (args.length < 4) {
					ChatUtil.info(switch (action) {
						case "value" -> "Usage: " + CommandManager.PREFIX + "sb value <label> <new value>, like " + CommandManager.PREFIX + "sb value Money $50M";
						case "replace" -> "Usage: " + CommandManager.PREFIX + "sb replace <find> <with>, like " + CommandManager.PREFIX + "sb replace YourName NewName";
						default -> "Usage: " + CommandManager.PREFIX + "sb line <label> <&-coded text>";
					});
					return;
				}
				String match = args[2].replace('_', ' ');
				String text = String.join(" ", List.of(args).subList(3, args.length));
				Kind kind = Kind.valueOf(action.toUpperCase(Locale.ROOT));
				list.removeIf(rule -> rule.kind() == kind && rule.match().equalsIgnoreCase(match));
				list.add(new Rule(kind, match, text));
				save(list);
				if (!isEnabled()) setEnabled(true);
				ChatUtil.info(switch (kind) {
					case VALUE -> "\"" + match + "\" now shows " + text;
					case REPLACE -> "\"" + match + "\" now reads " + text;
					default -> "The line with \"" + match + "\" is replaced";
				} + (match.contains(" ") ? "" : " (use _ for spaces in the label)"));
			}
			case "title" -> {
				if (args.length < 3) {
					ChatUtil.info("Usage: " + CommandManager.PREFIX + "sb title <&-coded text>");
					return;
				}
				list.removeIf(rule -> rule.kind() == Kind.TITLE);
				list.add(new Rule(Kind.TITLE, "", String.join(" ", List.of(args).subList(2, args.length))));
				save(list);
				if (!isEnabled()) setEnabled(true);
				ChatUtil.info("Title replaced");
			}
			case "list" -> {
				if (list.isEmpty()) {
					ChatUtil.info("No scoreboard edits yet");
					return;
				}
				for (int i = 0; i < list.size(); i++) {
					Rule rule = list.get(i);
					ChatUtil.info((i + 1) + ". " + rule.kind().name().toLowerCase(Locale.ROOT) + (rule.match().isEmpty() ? "" : " \"" + rule.match() + "\"") + " -> " + rule.text());
				}
			}
			case "remove", "del" -> {
				try {
					list.remove(Integer.parseInt(args[2]) - 1);
					save(list);
					ChatUtil.info("Removed; " + list.size() + " left");
				} catch (RuntimeException e) {
					ChatUtil.info("Usage: " + CommandManager.PREFIX + "sb remove <number from " + CommandManager.PREFIX + "sb list>");
				}
			}
			case "clear" -> {
				save(List.of());
				ChatUtil.info("Scoreboard back to normal");
			}
			default -> ChatUtil.info(usage);
		}
	}

	/** Prints the sidebar as the server sends it, with & codes, so you can see what to match and copy its colours. */
	private void showLines() {
		if (mc.level == null) {
			ChatUtil.info("Join a server first");
			return;
		}
		Scoreboard scoreboard = mc.level.getScoreboard();
		Objective objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
		if (objective == null) {
			ChatUtil.info("No scoreboard showing");
			return;
		}
		ChatUtil.info("Title: " + LegacyText.write(objective.getDisplayName()));
		List<PlayerScoreEntry> entries = new ArrayList<>(scoreboard.listPlayerScores(objective));
		entries.removeIf(PlayerScoreEntry::isHidden);
		entries.sort(Comparator.comparing(PlayerScoreEntry::value).reversed().thenComparing(PlayerScoreEntry::owner, String.CASE_INSENSITIVE_ORDER));
		int number = 1;
		for (PlayerScoreEntry entry : entries.subList(0, Math.min(15, entries.size()))) {
			Component name = PlayerTeam.formatNameForTeam(scoreboard.getPlayersTeam(entry.owner()), entry.ownerName());
			Mc.addChat(Component.literal("[femboyware] " + number++ + ": ").append(name).append(Component.literal("  " + LegacyText.write(name).replace("&r", ""))));
		}
	}
}
