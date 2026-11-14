package dev.femboyware.manager;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.femboyware.FemboyWare;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Texting between everyone using femboyware, wherever they're playing, through the relay on
 * femboyware.xyz. Everyone says who's online every so often with a public key; messages to
 * everyone go to the whole room, and messages to one person are encrypted with a key only the two
 * of them can work out, so neither the relay nor anyone else in the room can read them.
 * <p>
 * Names aren't checked against Minecraft's servers, so someone could claim another's name; and
 * people only get messages while they're online.
 */
public final class PhoneLink {
	/** A message for the phone: who from, what, and whether it was just to you. */
	public record Incoming(String from, String text, boolean direct) {
	}

	private record Peer(String name, PublicKey key, long seen) {
	}

	private static final String ROOM = "femboyware phone v1";
	private static final long ANNOUNCE_EVERY = 30_000, FORGET_AFTER = 80_000;
	public static final int MAX_TEXT = 300;
	private static final SecureRandom RANDOM = new SecureRandom();

	private final String self;
	private final PeerLink link;
	private final KeyPair keys;
	private final String publicKey;
	private final Consumer<Incoming> onMessage;
	private final Map<String, Peer> peers = new ConcurrentHashMap<>();
	/** People who just came online, for {@link #takeJoined()}. */
	private final java.util.concurrent.ConcurrentLinkedQueue<String> joined = new java.util.concurrent.ConcurrentLinkedQueue<>();
	private long lastAnnounce;
	private boolean wasConnected;

	/** {@code onMessage} runs on a network thread. */
	public PhoneLink(String self, Consumer<Incoming> onMessage) {
		this.self = self;
		this.onMessage = onMessage;
		try {
			keys = KeyPairGenerator.getInstance("X25519").generateKeyPair();
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
		publicKey = Base64.getEncoder().encodeToString(keys.getPublic().getEncoded());
		link = new PeerLink(ROOM, this::receive);
	}

	public String self() {
		return self;
	}

	public boolean connected() {
		return link.connected();
	}

	/** Everyone else online with femboyware right now, by name. */
	public List<String> online() {
		long now = System.currentTimeMillis();
		List<String> names = new ArrayList<>();
		for (Peer peer : peers.values()) if (now - peer.seen < FORGET_AFTER) names.add(peer.name);
		names.sort(String.CASE_INSENSITIVE_ORDER);
		return names;
	}

	public boolean isOnline(String name) {
		Peer peer = peers.get(name.toLowerCase(Locale.ROOT));
		return peer != null && System.currentTimeMillis() - peer.seen < FORGET_AFTER;
	}

	/** Says you're here every so often, and straight away once connected (asking everyone else to say so too). */
	public void tick() {
		long now = System.currentTimeMillis();
		boolean connected = link.connected();
		if (connected && (!wasConnected || now - lastAnnounce > ANNOUNCE_EVERY)) announce(!wasConnected);
		wasConnected = connected;
	}

	private void announce(boolean askBack) {
		lastAnnounce = System.currentTimeMillis();
		JsonObject hi = new JsonObject();
		hi.addProperty("t", "hi");
		hi.addProperty("n", self);
		hi.addProperty("k", publicKey);
		if (askBack) hi.addProperty("r", 1);
		link.sendInOrder(hi.toString());
	}

	/** A message to everyone online. False if not connected. */
	public boolean sendAll(String text) {
		if (!link.connected()) return false;
		JsonObject message = new JsonObject();
		message.addProperty("t", "all");
		message.addProperty("n", self);
		message.addProperty("m", clean(text));
		link.sendInOrder(message.toString());
		return true;
	}

	/** A message only {@code to} can read. False if they aren't online (or it couldn't be sealed). */
	public boolean sendDirect(String to, String text) {
		Peer peer = peers.get(to.toLowerCase(Locale.ROOT));
		if (!link.connected() || peer == null || System.currentTimeMillis() - peer.seen >= FORGET_AFTER) return false;
		try {
			byte[] iv = new byte[12];
			RANDOM.nextBytes(iv);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, pairKey(peer), new GCMParameterSpec(128, iv));
			cipher.updateAAD((self + "\n" + peer.name).toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
			byte[] sealed = cipher.doFinal(clean(text).getBytes(StandardCharsets.UTF_8));
			JsonObject message = new JsonObject();
			message.addProperty("t", "dm");
			message.addProperty("n", self);
			message.addProperty("to", peer.name);
			message.addProperty("iv", Base64.getEncoder().encodeToString(iv));
			message.addProperty("c", Base64.getEncoder().encodeToString(sealed));
			link.sendInOrder(message.toString());
			return true;
		} catch (Exception e) {
			FemboyWare.LOGGER.debug("Couldn't seal a phone message", e);
			return false;
		}
	}

	/** The key two people share: from their X25519 keys, tied to both names. */
	private SecretKeySpec pairKey(Peer peer) throws Exception {
		KeyAgreement agreement = KeyAgreement.getInstance("X25519");
		agreement.init(keys.getPrivate());
		agreement.doPhase(peer.key, true);
		String a = self.toLowerCase(Locale.ROOT), b = peer.name.toLowerCase(Locale.ROOT);
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		digest.update("femboyware phone dm v1".getBytes(StandardCharsets.UTF_8));
		digest.update(agreement.generateSecret());
		digest.update((a.compareTo(b) < 0 ? a + "\n" + b : b + "\n" + a).getBytes(StandardCharsets.UTF_8));
		return new SecretKeySpec(digest.digest(), "AES");
	}

	/** One line of plain text: no colour codes, no line breaks, not too long. */
	public static String clean(String text) {
		String out = text.replace('§', ' ').replaceAll("[\\p{Cntrl}]", " ").trim();
		return out.length() > MAX_TEXT ? out.substring(0, MAX_TEXT) : out;
	}

	private static boolean validName(String name) {
		return name != null && name.matches("[A-Za-z0-9_.]{1,17}");
	}

	private void receive(String raw) {
		JsonObject message = JsonParser.parseString(raw).getAsJsonObject();
		String type = message.has("t") ? message.get("t").getAsString() : "";
		String from = message.has("n") ? message.get("n").getAsString() : null;
		if (!validName(from) || from.equalsIgnoreCase(self)) return;
		switch (type) {
			case "hi" -> {
				try {
					byte[] key = Base64.getDecoder().decode(message.get("k").getAsString());
					PublicKey publicKey = KeyFactory.getInstance("X25519").generatePublic(new X509EncodedKeySpec(key));
					long now = System.currentTimeMillis();
					Peer before = peers.put(from.toLowerCase(Locale.ROOT), new Peer(from, publicKey, now));
					if (before == null || now - before.seen >= FORGET_AFTER || !before.key.equals(publicKey)) joined.add(from);
				} catch (Exception e) {
					return;
				}
				// Someone just came online and asked who's here
				if (message.has("r")) announce(false);
			}
			case "all" -> {
				if (!message.has("m")) return;
				String text = clean(message.get("m").getAsString());
				if (!text.isEmpty()) onMessage.accept(new Incoming(from, text, false));
			}
			case "dm" -> {
				if (!message.has("to") || !message.get("to").getAsString().equalsIgnoreCase(self)) return;
				Peer peer = peers.get(from.toLowerCase(Locale.ROOT));
				if (peer == null) return;
				try {
					byte[] iv = Base64.getDecoder().decode(message.get("iv").getAsString());
					byte[] sealed = Base64.getDecoder().decode(message.get("c").getAsString());
					Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
					cipher.init(Cipher.DECRYPT_MODE, pairKey(peer), new GCMParameterSpec(128, iv));
					cipher.updateAAD((peer.name + "\n" + self).toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
					String text = clean(new String(cipher.doFinal(sealed), StandardCharsets.UTF_8));
					if (!text.isEmpty()) onMessage.accept(new Incoming(peer.name, text, true));
				} catch (Exception e) {
					FemboyWare.LOGGER.debug("Couldn't open a phone message", e);
				}
			}
			default -> {
			}
		}
	}

	/** Who came online (or restarted) since the last call, so they can be caught up. */
	public List<String> takeJoined() {
		List<String> names = new ArrayList<>();
		for (String name; (name = joined.poll()) != null; ) if (!names.contains(name)) names.add(name);
		return names;
	}

	/** Disconnects and forgets who was online. */
	public void stop() {
		link.stop();
		peers.clear();
	}
}
