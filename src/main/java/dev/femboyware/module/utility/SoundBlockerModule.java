package dev.femboyware.module.utility;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.StringSetting;
import dev.femboyware.util.ChatUtil;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Stops chosen sounds from playing. Sounds are named like {@code entity.generic.explode}; use * as
 * a wildcard, e.g. {@code block.anvil.*}. Show Names prints each sound's name as it plays, to find
 * the one to block.
 */
public class SoundBlockerModule extends Module {
	/** Show Names prints each sound at most this often. */
	private static final long NAME_REPEAT_MILLIS = 5_000;

	public enum Mode { BLOCK_LISTED, ONLY_ALLOW_LISTED }

	private final StringSetting sounds = add(new StringSetting("Sounds", "Comma separated sound names, * for wildcards, e.g. entity.generic.explode, block.anvil.*", "entity.generic.explode"));
	private final EnumSetting<Mode> mode = add(new EnumSetting<>("Mode", "Block the listed sounds, or block everything except them", Mode.BLOCK_LISTED));
	private final BoolSetting showNames = add(new BoolSetting("Show Names", "Print the name of each sound as it plays, to find ones to block", false));

	private List<Pattern> patterns = List.of();
	private String parsedFrom;
	private final Map<Identifier, Long> lastShown = new HashMap<>();
	private int blocked;

	public SoundBlockerModule() {
		super("Sound Blocker", "Turns off chosen sounds", Category.MISC);
	}

	@Override
	public String getInfo() {
		return Integer.toString(blocked);
	}

	@Override
	protected void onDisable() {
		lastShown.clear();
		blocked = 0;
	}

	/** Called from SoundSystemMixin before any sound plays. Returns true to keep it from playing. */
	public boolean shouldBlock(Identifier id) {
		if (!isEnabled() || id == null) return false;
		if (!sounds.get().equals(parsedFrom)) {
			patterns = parse(sounds.get());
			parsedFrom = sounds.get();
		}
		boolean listed = patterns.stream().anyMatch(pattern -> pattern.matcher(id.toString()).matches());
		boolean block = mode.is(Mode.BLOCK_LISTED) == listed;
		if (showNames.get()) showName(id, block);
		if (block) blocked++;
		return block;
	}

	private void showName(Identifier id, boolean block) {
		long now = System.currentTimeMillis();
		Long last = lastShown.get(id);
		if (last != null && now - last < NAME_REPEAT_MILLIS) return;
		lastShown.put(id, now);
		String name = id.getNamespace().equals(Identifier.DEFAULT_NAMESPACE) ? id.getPath() : id.toString();
		ChatUtil.info((block ? "Blocked " : "Sound ") + name);
	}

	/** Entries without a namespace mean minecraft's; * matches anything. */
	private static List<Pattern> parse(String text) {
		List<Pattern> result = new ArrayList<>();
		for (String part : text.split(",")) {
			String entry = part.trim().toLowerCase();
			if (entry.isEmpty()) continue;
			if (!entry.contains(":")) entry = Identifier.DEFAULT_NAMESPACE + ":" + entry;
			StringBuilder regex = new StringBuilder();
			for (String piece : entry.split("\\*", -1)) {
				if (!regex.isEmpty()) regex.append(".*");
				regex.append(Pattern.quote(piece));
			}
			result.add(Pattern.compile(regex.toString()));
		}
		return result;
	}
}
