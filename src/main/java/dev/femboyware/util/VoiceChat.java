package dev.femboyware.util;

import java.lang.reflect.Method;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Talks to Simple Voice Chat, if it's installed, without depending on it: makes and joins private
 * groups (so two phones on a call hear each other anywhere on the server), leaves them, and reads
 * and sets mute and deafen. Everything is looked up by name, so a missing or different version of
 * the mod just makes {@link #available()} false instead of crashing.
 */
public final class VoiceChat {
	private static final String NET = "de.maxhenkel.voicechat.net.";
	private static Boolean installed;
	private static Method send, playerStates;

	private VoiceChat() {
	}

	/** Whether Simple Voice Chat is installed and its classes are where we expect. */
	public static boolean available() {
		if (installed == null) {
			installed = false;
			try {
				if (FabricLoader.getInstance().isModLoaded("voicechat")) {
					Class<?> packet = Class.forName(NET + "Packet");
					send = Class.forName(NET + "ClientServerNetManager").getMethod("sendToServer", packet);
					playerStates = Class.forName("de.maxhenkel.voicechat.voice.client.ClientManager").getMethod("getPlayerStateManager");
					installed = true;
				}
			} catch (ReflectiveOperationException | LinkageError e) {
				installed = false;
			}
		}
		return installed;
	}

	private static Object states() throws ReflectiveOperationException {
		return playerStates.invoke(null);
	}

	/** Whether voice chat's connected to the server right now (the server has it too, and it's on). */
	public static boolean connected() {
		if (!available()) return false;
		try {
			Object states = states();
			return !(boolean) states.getClass().getMethod("isDisconnected").invoke(states);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return false;
		}
	}

	/**
	 * Makes a password-protected group called {@code name} and puts you in it. Normal groups still
	 * hear people nearby; you hear the group wherever they are.
	 */
	public static boolean createGroup(String name, String password) {
		if (!available()) return false;
		try {
			Class<?> type = Class.forName("de.maxhenkel.voicechat.api.Group$Type");
			Object normal = type.getField("NORMAL").get(null);
			Object packet = Class.forName(NET + "CreateGroupPacket").getConstructor(String.class, String.class, type).newInstance(name, password, normal);
			send.invoke(null, packet);
			return true;
		} catch (ReflectiveOperationException | RuntimeException e) {
			return false;
		}
	}

	public static boolean joinGroup(UUID group, String password) {
		if (!available()) return false;
		try {
			send.invoke(null, Class.forName(NET + "JoinGroupPacket").getConstructor(UUID.class, String.class).newInstance(group, password));
			return true;
		} catch (ReflectiveOperationException | RuntimeException e) {
			return false;
		}
	}

	public static void leaveGroup() {
		if (!available()) return;
		try {
			send.invoke(null, Class.forName(NET + "LeaveGroupPacket").getConstructor().newInstance());
		} catch (ReflectiveOperationException | RuntimeException ignored) {
		}
	}

	/** The group you're in, or null. */
	public static UUID group() {
		if (!available()) return null;
		try {
			Object states = states();
			return (UUID) states.getClass().getMethod("getGroupID").invoke(states);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	public static boolean muted() {
		return flag("isMuted");
	}

	public static void setMuted(boolean muted) {
		set("setMuted", muted);
	}

	/** Deafened: you hear no one (Simple Voice Chat calls it disabled). */
	public static boolean deafened() {
		return flag("isDisabled");
	}

	public static void setDeafened(boolean deafened) {
		set("setDisabled", deafened);
	}

	private static boolean flag(String getter) {
		if (!available()) return false;
		try {
			Object states = states();
			return (boolean) states.getClass().getMethod(getter).invoke(states);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return false;
		}
	}

	private static void set(String setter, boolean value) {
		if (!available()) return;
		try {
			Object states = states();
			states.getClass().getMethod(setter, boolean.class).invoke(states, value);
		} catch (ReflectiveOperationException | RuntimeException ignored) {
		}
	}
}
