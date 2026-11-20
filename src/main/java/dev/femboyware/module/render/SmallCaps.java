package dev.femboyware.module.render;

/** The small-caps letters servers like DonutSMP write their scoreboards in, one char for one letter. */
public final class SmallCaps {
	private static final String PLAIN = "abcdefghijklmnopqrstuvwxyz";
	private static final String SMALL = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ";

	private SmallCaps() {
	}

	/** Small caps turned into plain letters; same length, so positions line up. */
	public static String fold(String text) {
		StringBuilder out = null;
		for (int i = 0; i < text.length(); i++) {
			int at = SMALL.indexOf(text.charAt(i));
			if (at >= 0 && text.charAt(i) != 'x') {
				if (out == null) out = new StringBuilder(text);
				out.setCharAt(i, PLAIN.charAt(at));
			}
		}
		return out == null ? text : out.toString();
	}

	/** Letters written in small caps; digits and the rest unchanged. */
	public static String to(String text) {
		StringBuilder out = new StringBuilder(text.length());
		for (int i = 0; i < text.length(); i++) {
			char c = Character.toLowerCase(text.charAt(i));
			int at = PLAIN.indexOf(c);
			out.append(at >= 0 ? SMALL.charAt(at) : text.charAt(i));
		}
		return out.toString();
	}
}
