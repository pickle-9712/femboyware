package dev.femboyware.manager;

import dev.femboyware.FemboyWare;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * A line to every other femboyware user on the same Minecraft server, through the relay on
 * femboyware.xyz: what one sends, all the others get. The room is a hash of the server address,
 * and every message is encrypted with a key made from the address too, so the relay can't tell
 * which server it is or read anything. Anyone who knows the address can join, which is the point:
 * it only carries what's drawn for everyone to see anyway.
 */
public final class PeerLink {
	private static final String RELAY = System.getProperty("femboyware.mesh", "wss://femboyware.xyz/mesh/");
	private static final int MAX_MESSAGE = 4000, IV = 12, CONNECT_TIMEOUT = 10000, RECONNECT_DELAY = 5000, KEEPALIVE = 25000;
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT)).build();

	private final String server;
	private final SecretKeySpec key;
	private final String room;
	private final Consumer<String> onMessage;
	private volatile WebSocket socket;
	private volatile boolean running = true;
	private volatile boolean sending;

	/** Connects in the background, and keeps reconnecting until {@link #stop}. {@code onMessage} runs on a network thread. */
	public PeerLink(String server, Consumer<String> onMessage) {
		this.server = server;
		this.onMessage = onMessage;
		byte[] secret = hash("femboyware client sync key v1|" + server);
		this.key = new SecretKeySpec(secret, "AES");
		this.room = HexFormat.of().formatHex(hash("femboyware client sync room v1|" + server));
		Thread thread = new Thread(this::run, "femboyware client sync");
		thread.setDaemon(true);
		thread.start();
	}

	public String server() {
		return server;
	}

	public boolean connected() {
		return socket != null;
	}

	private static byte[] hash(String text) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private void run() {
		while (running) {
			Object closed = new Object();
			java.util.concurrent.CompletableFuture<Object> done = new java.util.concurrent.CompletableFuture<>();
			try {
				WebSocket ws = HTTP.newWebSocketBuilder()
					.connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT))
					.buildAsync(URI.create(RELAY + room), new Listener(done, closed))
					.get(CONNECT_TIMEOUT, TimeUnit.MILLISECONDS);
				socket = ws;
				while (running) {
					try {
						done.get(KEEPALIVE, TimeUnit.MILLISECONDS);
						break;
					} catch (java.util.concurrent.TimeoutException quiet) {
						ws.sendPing(ByteBuffer.allocate(0));
					}
				}
				ws.abort();
			} catch (Exception e) {
				FemboyWare.LOGGER.debug("Client sync connection failed", e);
			} finally {
				socket = null;
			}
			if (!running) return;
			try {
				Thread.sleep(RECONNECT_DELAY);
			} catch (InterruptedException e) {
				return;
			}
		}
	}

	private final class Listener implements WebSocket.Listener {
		private final java.util.concurrent.CompletableFuture<Object> done;
		private final Object closed;
		private ByteBuffer partial = ByteBuffer.allocate(0);

		Listener(java.util.concurrent.CompletableFuture<Object> done, Object closed) {
			this.done = done;
			this.closed = closed;
		}

		@Override
		public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
			ByteBuffer joined = ByteBuffer.allocate(partial.remaining() + data.remaining());
			joined.put(partial).put(data).flip();
			if (last) {
				byte[] frame = new byte[joined.remaining()];
				joined.get(frame);
				partial = ByteBuffer.allocate(0);
				String message = open(frame);
				if (message != null) {
					try {
						onMessage.accept(message);
					} catch (RuntimeException e) {
						FemboyWare.LOGGER.debug("Client sync message failed", e);
					}
				}
			} else if (joined.remaining() <= MAX_MESSAGE + 64) {
				partial = joined;
			}
			webSocket.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
			done.complete(closed);
			return null;
		}

		@Override
		public void onError(WebSocket webSocket, Throwable error) {
			done.complete(closed);
		}
	}

	/** Sends to everyone else in the room, if connected; dropped if the last send hasn't finished yet. */
	public void send(String message) {
		WebSocket ws = socket;
		if (ws == null || sending) return;
		byte[] frame = seal(message);
		if (frame == null) return;
		sending = true;
		ws.sendBinary(ByteBuffer.wrap(frame), true).whenComplete((result, error) -> sending = false);
	}

	/** Sends after whatever's still going out, never dropping it, for things like texts; lost only if not connected. */
	private java.util.concurrent.CompletableFuture<?> queue = java.util.concurrent.CompletableFuture.completedFuture(null);

	public synchronized void sendInOrder(String message) {
		WebSocket ws = socket;
		if (ws == null) return;
		byte[] frame = seal(message);
		if (frame == null) return;
		queue = queue.handle((result, error) -> null).thenCompose(ignored -> ws.sendBinary(ByteBuffer.wrap(frame), true));
	}

	private byte[] seal(String message) {
		try {
			byte[] text = message.getBytes(StandardCharsets.UTF_8);
			if (text.length > MAX_MESSAGE) return null;
			byte[] iv = new byte[IV];
			RANDOM.nextBytes(iv);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
			byte[] sealed = cipher.doFinal(text);
			return ByteBuffer.allocate(IV + sealed.length).put(iv).put(sealed).array();
		} catch (Exception e) {
			return null;
		}
	}

	private String open(byte[] frame) {
		if (frame.length <= IV + 16 || frame.length > MAX_MESSAGE + 64) return null;
		try {
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, frame, 0, IV));
			return new String(cipher.doFinal(frame, IV, frame.length - IV), StandardCharsets.UTF_8);
		} catch (Exception e) {
			return null;
		}
	}

	public void stop() {
		running = false;
		WebSocket ws = socket;
		if (ws != null) {
			ws.sendClose(WebSocket.NORMAL_CLOSURE, "").orTimeout(2, TimeUnit.SECONDS).exceptionally(e -> null);
			ws.abort();
		}
	}
}
