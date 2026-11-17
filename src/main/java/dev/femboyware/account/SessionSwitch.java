package dev.femboyware.account;

import java.util.Optional;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;

/**
 * Switches the account the running game uses, with no restart. The game reads {@link Minecraft#getUser()}
 * for its name and UUID and for the access token it joins online servers with, so swapping what that
 * returns is enough to play as another account. When the Session Login mod is installed we hand the
 * session to it (so both mods agree and its UI updates); otherwise our own mixin returns the switched
 * user. Signed chat is turned off while switched, since the profile keys belong to the launcher account.
 */
public final class SessionSwitch {
	private static final boolean SESSION_LOGIN = FabricLoader.getInstance().isModLoaded("sessionlogin");
	/** The account we've switched to, or null to use the launcher's own. Read by the mixin each frame. */
	private static volatile User active;
	/** The launcher's original user, kept so we can switch back. */
	private static User original;

	private SessionSwitch() {
	}

	/** Whether our mixin should take over {@link Minecraft#getUser()} (only when Session Login isn't doing it). */
	public static boolean overriding() {
		return !SESSION_LOGIN && active != null;
	}

	/** The switched user for the mixin to return. */
	public static User activeUser() {
		return active;
	}

	/** The account the game is on right now (switched if we switched, else the launcher's). */
	public static User current() {
		if (SESSION_LOGIN) {
			User fromMod = sessionLoginCurrent();
			if (fromMod != null) return fromMod;
		}
		return active != null ? active : Minecraft.getInstance().getUser();
	}

	/** Build a {@code User} from a finished login. */
	public static User userFor(MsAuth.McLogin login) {
		return new User(login.name(), login.uuid(), login.accessToken(), Optional.empty(), Optional.empty());
	}

	/** Switch the live game to this account. Call on the main thread. */
	public static void switchTo(MsAuth.McLogin login) {
		User user = userFor(login);
		if (original == null) original = Minecraft.getInstance().getUser();
		if (SESSION_LOGIN && sessionLoginSetSession(user)) {
			active = null;
			return;
		}
		active = user;
	}

	/**
	 * The account the game is on was renamed on Mojang: show the new name in-game right away, without a
	 * restart, by rebuilding its session with the new name (same UUID and token). Does nothing unless the
	 * current account matches {@code uuid}.
	 */
	public static void renameCurrent(UUID uuid, String newName) {
		User base = current();
		if (base == null || !base.getProfileId().equals(uuid)) return;
		User renamed = new User(newName, base.getProfileId(), base.getAccessToken(), Optional.empty(), Optional.empty());
		if (original == null) original = Minecraft.getInstance().getUser();
		if (SESSION_LOGIN && sessionLoginSetSession(renamed)) {
			active = null;
			return;
		}
		active = renamed;
	}

	/** Go back to the account the launcher started with. */
	public static void restore() {
		if (SESSION_LOGIN) {
			sessionLoginRestore();
			active = null;
			return;
		}
		active = null;
	}

	/** Whether the game is on a switched account rather than the launcher's. */
	public static boolean switched() {
		if (SESSION_LOGIN) {
			User cur = sessionLoginCurrent(), orig = sessionLoginOriginal();
			return cur != null && orig != null && !cur.getProfileId().equals(orig.getProfileId());
		}
		return active != null;
	}

	// ---------------------------------------------------------------- Session Login bridge (reflection)

	// Resolved once, not on every call: this runs from getGameProfile, which is very hot
	private static boolean reflectionReady;
	private static java.lang.reflect.Field currentField, originalField;

	/** Look up Session Login's session fields a single time and keep them. */
	private static void prepareReflection() {
		if (reflectionReady) return;
		reflectionReady = true;
		if (!SESSION_LOGIN) return;
		try {
			Class<?> mod = Class.forName("dev.elv1n200.sessionlogin.SessionLogin");
			currentField = mod.getDeclaredField("currentSession");
			currentField.setAccessible(true);
			originalField = mod.getDeclaredField("originalSession");
			originalField.setAccessible(true);
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			currentField = null;
			originalField = null;
		}
	}

	private static User sessionLoginCurrent() {
		return read(currentField());
	}

	private static User sessionLoginOriginal() {
		return read(originalField());
	}

	private static java.lang.reflect.Field currentField() {
		prepareReflection();
		return currentField;
	}

	private static java.lang.reflect.Field originalField() {
		prepareReflection();
		return originalField;
	}

	private static User read(java.lang.reflect.Field f) {
		if (f == null) return null;
		try {
			return f.get(null) instanceof User user ? user : null;
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			return null;
		}
	}

	private static boolean sessionLoginSetSession(User user) {
		try {
			Class<?> util = Class.forName("dev.elv1n200.sessionlogin.util.SessionUtils");
			util.getMethod("setSession", User.class).invoke(null, user);
			Class.forName("dev.elv1n200.sessionlogin.SessionLogin").getField("overrideSession").setBoolean(null, true);
			return true;
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			return false;
		}
	}

	private static void sessionLoginRestore() {
		try {
			Class.forName("dev.elv1n200.sessionlogin.util.SessionUtils").getMethod("restoreSession").invoke(null);
		} catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
		}
	}
}
