package dev.femboyware.module.utility;

import dev.femboyware.mixin.ChatHudAccessor;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import net.minecraft.client.gui.hud.ChatHudLine;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Formatting;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Nicer chat: a timestamp on every line, repeated messages folded into one with a count, a
 * highlight and a soft ding when someone says your name, and a much longer history.
 */
public class ChatPlusModule extends Module {
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

	private final BoolSetting timestamps = add(new BoolSetting("Timestamps", "Show the time each message arrived", true));
	private final BoolSetting antiSpam = add(new BoolSetting("Anti Spam", "Fold repeated messages into one line with a count", true));
	private final BoolSetting highlight = add(new BoolSetting("Highlight Name", "Mark messages that mention you", true));
	private final ColorSetting highlightColor = add(new ColorSetting("Highlight Color", "Colour of the mention marker", 0xFFFF77C8)).visibleWhen(highlight::get);
	private final BoolSetting ding = add(new BoolSetting("Ding", "Play a soft sound when you're mentioned", true)).visibleWhen(highlight::get);
	private final BoolSetting longHistory = add(new BoolSetting("Long History", "Keep 1000 lines instead of 100", true));

	/** The last message as it arrived, without our additions, and how many times in a row it came. */
	private String lastRaw = "";
	private int repeats = 1;

	public ChatPlusModule() {
		super("Chat Plus", "Timestamps, anti spam, mentions and longer chat history", Category.MISC);
		setEnabled(true);
	}

	/** How many lines chat keeps; read by ChatHudMixin. */
	public int historyLimit() {
		return isEnabled() && longHistory.get() ? 1000 : 100;
	}

	/** Called by ChatHudMixin with each message as it's added; returns the message to show. */
	public Text process(Text message, ChatHudAccessor chat) {
		if (!isEnabled()) return message;
		String raw = message.getString();

		if (antiSpam.get() && raw.equals(lastRaw) && !raw.isBlank()) {
			// Take the previous copy off and show this one with the count instead
			List<ChatHudLine> lines = chat.femboyware$getMessages();
			if (!lines.isEmpty()) {
				lines.removeFirst();
				chat.femboyware$refresh();
			}
			repeats++;
		} else {
			repeats = 1;
		}
		lastRaw = raw;

		MutableText shown = Text.empty();
		if (timestamps.get()) shown.append(Text.literal("[" + LocalTime.now().format(TIME) + "] ").formatted(Formatting.GRAY));
		if (highlight.get() && mentionsMe(raw)) {
			shown.append(Text.literal("» ").setStyle(Style.EMPTY.withColor(TextColor.fromRgb(highlightColor.get() & 0xFFFFFF)).withBold(true)));
			if (ding.get() && repeats == 1) mc.getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 1.6f, 0.5f));
		}
		shown.append(message);
		if (repeats > 1) shown.append(Text.literal(" (x" + repeats + ")").formatted(Formatting.GRAY));
		return shown;
	}

	/** Whether someone else's message has our name in it; our own chat lines don't count. */
	private boolean mentionsMe(String raw) {
		if (mc.player == null) return false;
		String name = mc.player.getGameProfile().name().toLowerCase(Locale.ROOT);
		String text = raw.toLowerCase(Locale.ROOT);
		if (text.startsWith("<" + name + ">") || text.startsWith("[femboyware]")) return false;
		return text.contains(name);
	}
}
