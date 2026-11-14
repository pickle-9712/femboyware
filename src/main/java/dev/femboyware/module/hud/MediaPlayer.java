package dev.femboyware.module.hud;

/**
 * Something on the PC that plays music and can be read and controlled: Windows' media session, or
 * Spotify on a Mac. Every call is made on the one thread that called {@link #open()}.
 */
interface MediaPlayer {
	// Controls, for {@link #control}; on Windows they're the session's vtable slots
	int TOGGLE_PLAY_PAUSE = 20;
	int SKIP_NEXT = 16;
	int SKIP_PREVIOUS = 17;

	/** Connects; false if it can't. */
	boolean open();

	void close();

	/** The current track, or null when nothing is playing (or the player isn't open). */
	WindowsMedia.Track track();

	/** The current track's art, or null if it has none. */
	WindowsMedia.Art art();

	/** Runs one of the controls above. */
	void control(int which);

	/** Jumps to {@code seconds} into the current track. */
	void seek(double seconds);

	/** The player's own volume, 0 to 1, or -1 if it can't be read. Called after {@link #track()}. */
	default float volume() {
		return -1;
	}

	/** Sets the player's own volume, 0 to 1 (not the whole PC's). */
	default void setVolume(float volume) {
	}

	/** How often it's worth asking what's playing, in milliseconds. */
	int pollMillis();

	/** The player for this PC (its whole media session, or Spotify on a Mac), or null if there isn't one. */
	static MediaPlayer forThisPc() {
		if (WindowsMedia.supported()) return new WindowsMedia(null, 96);
		if (MacSpotify.supported()) return new MacSpotify(96);
		return null;
	}
}
