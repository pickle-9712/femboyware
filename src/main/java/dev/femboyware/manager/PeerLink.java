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
	/**
	 * Files go over HTTP/1.1 on their own client: Java's HTTP/2 can break the connection partway
	 * through big uploads ("bad_record_mac"), which stopped videos sharing.
	 */
	private static final HttpClient FILES = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT)).version(HttpClient.Version.HTTP_1_1).build();

	/** Sends a file request, trying again a couple of times if the connection drops. */
	private static <T> java.net.http.HttpResponse<T> fileRequest(java.net.http.HttpRequest request, java.net.http.HttpResponse.BodyHandler<T> body)
		throws java.io.IOException, InterruptedException {
		java.io.IOException last = null;
		for (int attempt = 0; attempt < 4; attempt++) {
			try {
				var response = FILES.send(request, body);
				if (response.statusCode() < 500) return response;
				last = new java.io.IOException("relay said " + response.statusCode());
			} catch (java.io.IOException e) {
				last = e;
			}
			Thread.sleep(500L << attempt);
		}
		throw last;
	}

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

	/**
	 * The relay closes the connection on frames over 4096 bytes, and we don't take ones over
	 * MAX_MESSAGE + 64; this is the most that fits in one after the IV and tag.
	 */
	private static final int MAX_PLAIN = MAX_MESSAGE + 64 - IV - 16;
	/** Small messages go as plain JSON (which starts with '{'), so older versions still read them; bigger ones are deflated behind this marker. */
	private static final byte DEFLATED = 1;
	private static final int SMALL = 1000, MAX_INFLATED = 256 * 1024;

	/** Whether {@code message} fits in one frame (compressed if it needs to be); ones that don't are never sent. */
	public boolean fits(String message) {
		return pack(message) != null;
	}

	private static byte[] pack(String message) {
		byte[] text = message.getBytes(StandardCharsets.UTF_8);
		if (text.length <= SMALL) return text;
		java.util.zip.Deflater deflater = new java.util.zip.Deflater(java.util.zip.Deflater.BEST_COMPRESSION);
		try {
			deflater.setInput(text);
			deflater.finish();
			byte[] out = new byte[MAX_PLAIN];
			out[0] = DEFLATED;
			int length = 1;
			while (!deflater.finished()) {
				if (length >= out.length) return null;
				length += deflater.deflate(out, length, out.length - length);
			}
			return java.util.Arrays.copyOf(out, length);
		} finally {
			deflater.end();
		}
	}

	private static String unpack(byte[] data) {
		if (data.length == 0 || data[0] != DEFLATED) return new String(data, StandardCharsets.UTF_8);
		java.util.zip.Inflater inflater = new java.util.zip.Inflater();
		try {
			inflater.setInput(data, 1, data.length - 1);
			java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
			byte[] buffer = new byte[8192];
			while (!inflater.finished()) {
				int n = inflater.inflate(buffer);
				if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) return null;
				out.write(buffer, 0, n);
				if (out.size() > MAX_INFLATED) return null;
			}
			return out.toString(StandardCharsets.UTF_8);
		} catch (java.util.zip.DataFormatException e) {
			return null;
		} finally {
			inflater.end();
		}
	}

	private byte[] seal(String message) {
		try {
			byte[] text = pack(message);
			if (text == null) {
				FemboyWare.LOGGER.warn("Client sync message too big to send ({} bytes)", message.length());
				return null;
			}
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
			return unpack(cipher.doFinal(frame, IV, frame.length - IV));
		} catch (Exception e) {
			return null;
		}
	}

	// ---------------------------------------------------------------- files

	/** Biggest file shared through the relay, and the size of each part it's sent in. */
	public static final int MAX_FILE = 60 * 1024 * 1024, PART = 1024 * 1024;

	/** Where a file shared in this room is kept on the relay: a hash of it and the room that the relay can't undo. */
	private String blobUrl(String sha256) {
		String id = HexFormat.of().formatHex(hash("femboyware client sync blob v1|" + server + "|" + sha256));
		return RELAY.replaceFirst("^ws", "http") + room + "/blob/" + id;
	}

	/** Whether everyone here can already fetch this file. Blocks: call off the main thread. */
	public boolean hasFile(String sha256) {
		try {
			var response = fileRequest(java.net.http.HttpRequest.newBuilder(URI.create(blobUrl(sha256))).timeout(Duration.ofSeconds(20)).GET().build(),
				java.net.http.HttpResponse.BodyHandlers.discarding());
			return response.statusCode() == 200;
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * Puts a file on the relay for everyone here, encrypted like everything else, in parts. Its
	 * SHA-256 (hex) is how others ask for it. Blocks: call off the main thread.
	 */
	public void uploadFile(String sha256, byte[] data) throws java.io.IOException {
		if (data.length > MAX_FILE) throw new java.io.IOException("too big to share (" + data.length / 1_000_000 + " MB)");
		if (hasFile(sha256)) return;
		int parts = Math.max(1, (data.length + PART - 1) / PART);
		for (int i = 0; i < parts; i++) {
			byte[] sealed = sealBytes(java.util.Arrays.copyOfRange(data, i * PART, Math.min(data.length, (i + 1) * PART)));
			if (sealed == null) throw new java.io.IOException("couldn't encrypt");
			try {
				var response = fileRequest(java.net.http.HttpRequest.newBuilder(URI.create(blobUrl(sha256) + "/" + i + "?n=" + parts))
						.timeout(Duration.ofSeconds(60)).PUT(java.net.http.HttpRequest.BodyPublishers.ofByteArray(sealed)).build(),
					java.net.http.HttpResponse.BodyHandlers.ofString());
				if (response.statusCode() != 200) throw new java.io.IOException("relay said " + response.statusCode() + " " + response.body());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new java.io.IOException("interrupted");
			}
		}
	}

	/** A file someone here shared, checked against its hash; null if it isn't there (yet). Blocks: call off the main thread. */
	public byte[] downloadFile(String sha256) throws java.io.IOException {
		try {
			var count = fileRequest(java.net.http.HttpRequest.newBuilder(URI.create(blobUrl(sha256))).timeout(Duration.ofSeconds(20)).GET().build(),
				java.net.http.HttpResponse.BodyHandlers.ofString());
			if (count.statusCode() == 404) return null;
			if (count.statusCode() != 200) throw new java.io.IOException("relay said " + count.statusCode());
			int parts = Integer.parseInt(count.body().trim());
			if (parts < 1 || parts > MAX_FILE / PART + 1) throw new java.io.IOException("bad file");
			java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
			for (int i = 0; i < parts; i++) {
				var part = fileRequest(java.net.http.HttpRequest.newBuilder(URI.create(blobUrl(sha256) + "/" + i)).timeout(Duration.ofSeconds(60)).GET().build(),
					java.net.http.HttpResponse.BodyHandlers.ofByteArray());
				if (part.statusCode() != 200) throw new java.io.IOException("relay said " + part.statusCode());
				byte[] plain = openBytes(part.body());
				if (plain == null) throw new java.io.IOException("couldn't decrypt");
				out.write(plain);
				if (out.size() > MAX_FILE) throw new java.io.IOException("too big");
			}
			byte[] data = out.toByteArray();
			if (!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)).equals(sha256)) throw new java.io.IOException("doesn't match");
			return data;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new java.io.IOException("interrupted");
		} catch (java.security.NoSuchAlgorithmException | NumberFormatException e) {
			throw new java.io.IOException(e.getMessage());
		}
	}

	// ---------------------------------------------------------------- live casts

	private String liveUrl(String cast, int seq) {
		return RELAY.replaceFirst("^ws", "http") + room + "/live/" + cast + "/" + seq;
	}

	/** Sends the next segment of a live cast. Blocks: call off the main thread. */
	public void putLive(String cast, int seq, byte[] data) throws java.io.IOException {
		byte[] sealed = sealBytes(data);
		if (sealed == null) throw new java.io.IOException("couldn't encrypt");
		try {
			var response = FILES.send(java.net.http.HttpRequest.newBuilder(URI.create(liveUrl(cast, seq))).timeout(Duration.ofSeconds(10))
				.PUT(java.net.http.HttpRequest.BodyPublishers.ofByteArray(sealed)).build(), java.net.http.HttpResponse.BodyHandlers.discarding());
			if (response.statusCode() != 200) throw new java.io.IOException("relay said " + response.statusCode());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new java.io.IOException("interrupted");
		}
	}

	/**
	 * A live segment fetched: {@code data} if it came (null if it isn't there yet), the newest one
	 * the relay has, and whether the cast's gone or that segment's too old ({@code gone}).
	 */
	public record LiveSegment(byte[] data, int latest, boolean gone) {
	}

	/** Asks for one segment of a live cast; the relay waits a few seconds for it if it's due. Blocks. */
	public LiveSegment getLive(String cast, int seq) throws java.io.IOException {
		try {
			var response = FILES.send(java.net.http.HttpRequest.newBuilder(URI.create(liveUrl(cast, seq))).timeout(Duration.ofSeconds(10)).GET().build(),
				java.net.http.HttpResponse.BodyHandlers.ofByteArray());
			int latest = (int) response.headers().firstValueAsLong("X-Latest").orElse(-1);
			if (response.statusCode() == 404 || response.statusCode() == 410) return new LiveSegment(null, latest, true);
			if (response.statusCode() == 204) return new LiveSegment(null, latest, false);
			if (response.statusCode() != 200) throw new java.io.IOException("relay said " + response.statusCode());
			byte[] plain = openBytes(response.body());
			if (plain == null) throw new java.io.IOException("couldn't decrypt");
			return new LiveSegment(plain, latest, false);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new java.io.IOException("interrupted");
		}
	}

	private byte[] sealBytes(byte[] plain) {
		try {
			byte[] iv = new byte[IV];
			RANDOM.nextBytes(iv);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
			byte[] sealed = cipher.doFinal(plain);
			return ByteBuffer.allocate(IV + sealed.length).put(iv).put(sealed).array();
		} catch (Exception e) {
			return null;
		}
	}

	private byte[] openBytes(byte[] frame) {
		if (frame.length <= IV + 16) return null;
		try {
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, frame, 0, IV));
			return cipher.doFinal(frame, IV, frame.length - IV);
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
