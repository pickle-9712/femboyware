package dev.femboyware.manager;

import dev.femboyware.FemboyWare;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * A private line from the principal's game to the bodyguards', so orders never touch the Minecraft
 * server. Both ends connect out to a relay on femboyware.xyz, so it works between any computers on
 * any networks with nothing but the same Link Key on each.
 * <p>
 * The relay only passes bytes along. It groups connections into a room named after a hash of a key
 * derived from the password, so it never learns the password, and every message is encrypted and
 * signed with that key here, before it leaves: each side contributes a random number per session,
 * messages are numbered, and anyone without the password is dropped within seconds, so a recorded
 * message can't be played back, and the relay can't read or forge orders.
 */
public final class GuardLink {
	/** Where the relay is; {@code -Dfemboyware.relay=ws://localhost:8787/link/} points it at a local test server. */
	private static final String RELAY = System.getProperty("femboyware.relay", "wss://femboyware.xyz/link/");
	/** Starts a guard's regular status report, as opposed to a reply meant to be read. */
	public static final String STATUS = "\u0001";
	private static final int MAX_MESSAGE = 4096;
	private static final int NONCE = 16;
	private static final int IV = 12;
	private static final int HANDSHAKE_TIMEOUT = 8000;
	private static final int CONNECT_TIMEOUT = 10000;
	private static final int RECONNECT_DELAY = 3000;
	/** Pings the relay this often while quiet, so the connection isn't dropped as idle. */
	private static final int KEEPALIVE = 25000;
	private static final int CLOSE_TAKEN_OVER = 4001;
	private static final byte DATA = 0, JOINED = 1, LEFT = 2;
	private static final byte[] SALT = "femboyware guard link v1".getBytes(StandardCharsets.UTF_8);
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT)).build();

	private GuardLink() {
	}

	private static SecretKey derive(String password) throws GeneralSecurityException {
		SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
		byte[] key = factory.generateSecret(new PBEKeySpec(password.toCharArray(), SALT, 200_000, 256)).getEncoded();
		return new SecretKeySpec(key, "AES");
	}

	/** The relay room for a key: a one way hash of it, so the room name gives nothing away. */
	private static String room(SecretKey key) throws GeneralSecurityException {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		digest.update("femboyware guard link room".getBytes(StandardCharsets.UTF_8));
		return HexFormat.of().formatHex(digest.digest(key.getEncoded()));
	}

	/** Something that carries whole frames each way, like one guard's share of the relay. */
	private interface Pipe extends Closeable {
		void write(byte[] frame) throws IOException;

		/** The next frame, waiting up to {@code timeout} milliseconds (0 waits forever). */
		byte[] read(int timeout) throws IOException;

		@Override
		void close();
	}

	/** One WebSocket to the relay, with whatever arrives queued up to be read. */
	private static final class Relay implements WebSocket.Listener, Closeable {
		private static final Object CLOSED = new Object();
		private final BlockingQueue<Object> incoming = new LinkedBlockingQueue<>();
		private ByteBuffer partialBinary = ByteBuffer.allocate(0);
		private final StringBuilder partialText = new StringBuilder();
		private volatile WebSocket socket;
		volatile int closeCode = -1;
		volatile String closeReason = "";

		static Relay open(String room, String role) throws IOException {
			Relay relay = new Relay();
			try {
				relay.socket = HTTP.newWebSocketBuilder()
					.connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT))
					.buildAsync(URI.create(RELAY + room + "?role=" + role), relay)
					.get(CONNECT_TIMEOUT, TimeUnit.MILLISECONDS);
				return relay;
			} catch (Exception e) {
				throw new IOException("can't reach the relay", e);
			}
		}

		@Override
		public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
			ByteBuffer joined = ByteBuffer.allocate(partialBinary.remaining() + data.remaining());
			joined.put(partialBinary).put(data).flip();
			if (last) {
				byte[] frame = new byte[joined.remaining()];
				joined.get(frame);
				incoming.add(frame);
				partialBinary = ByteBuffer.allocate(0);
			} else {
				partialBinary = joined;
			}
			webSocket.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
			partialText.append(data);
			if (last) {
				incoming.add(partialText.toString());
				partialText.setLength(0);
			}
			webSocket.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
			closeCode = statusCode;
			closeReason = reason;
			incoming.add(CLOSED);
			return null;
		}

		@Override
		public void onError(WebSocket webSocket, Throwable error) {
			FemboyWare.LOGGER.debug("Guard link relay error", error);
			incoming.add(CLOSED);
		}

		synchronized void send(byte[] frame) throws IOException {
			try {
				socket.sendBinary(ByteBuffer.wrap(frame), true).get(CONNECT_TIMEOUT, TimeUnit.MILLISECONDS);
			} catch (Exception e) {
				throw new IOException("relay send failed", e);
			}
		}

		private synchronized void ping() {
			try {
				socket.sendPing(ByteBuffer.allocate(0)).get(CONNECT_TIMEOUT, TimeUnit.MILLISECONDS);
			} catch (Exception e) {
				incoming.add(CLOSED);
			}
		}

		/** The next frame (a byte[]) or relay notice (a String), pinging while it waits. */
		Object take(int timeout) throws IOException {
			long deadline = timeout > 0 ? System.currentTimeMillis() + timeout : Long.MAX_VALUE;
			while (true) {
				long left = deadline - System.currentTimeMillis();
				if (left <= 0) throw new SocketTimeoutException("No answer in time");
				Object next;
				try {
					next = incoming.poll(Math.min(left, KEEPALIVE), TimeUnit.MILLISECONDS);
				} catch (InterruptedException e) {
					throw new IOException(e);
				}
				if (next == CLOSED) {
					incoming.add(CLOSED);
					throw new EOFException("Relay connection closed");
				}
				if (next != null) return next;
				ping();
			}
		}

		@Override
		public void close() {
			WebSocket ws = socket;
			if (ws != null) {
				ws.sendClose(WebSocket.NORMAL_CLOSURE, "").orTimeout(2, TimeUnit.SECONDS).exceptionally(e -> null);
				ws.abort();
			}
			incoming.add(CLOSED);
		}
	}

	/** One session: numbered, encrypted messages. Direction 0 is principal to guard, 1 is guard to principal. */
	private static final class Channel implements Closeable {
		private final Pipe pipe;
		private final SecretKey key;
		private final byte sendDirection;
		private byte[] session;
		private long sent;
		private long received;
		String name = "guard";

		Channel(Pipe pipe, SecretKey key, byte sendDirection) {
			this.pipe = pipe;
			this.key = key;
			this.sendDirection = sendDirection;
		}

		/** Swaps random numbers, which tie every message to this one session. */
		void exchangeNonces(boolean guardSide) throws IOException {
			byte[] mine = new byte[NONCE];
			RANDOM.nextBytes(mine);
			pipe.write(mine);
			byte[] theirs = pipe.read(HANDSHAKE_TIMEOUT);
			if (theirs.length != NONCE) throw new IOException("Bad handshake");
			session = ByteBuffer.allocate(NONCE * 2).put(guardSide ? mine : theirs).put(guardSide ? theirs : mine).array();
		}

		private byte[] associated(byte direction) {
			return ByteBuffer.allocate(session.length + 1).put(session).put(direction).array();
		}

		synchronized void send(String message) throws IOException {
			try {
				byte[] text = message.getBytes(StandardCharsets.UTF_8);
				if (text.length > MAX_MESSAGE - 64) throw new IOException("Message too long");
				byte[] iv = new byte[IV];
				RANDOM.nextBytes(iv);
				Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
				cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
				cipher.updateAAD(associated(sendDirection));
				byte[] sealed = cipher.doFinal(ByteBuffer.allocate(8 + text.length).putLong(++sent).put(text).array());
				pipe.write(ByteBuffer.allocate(IV + sealed.length).put(iv).put(sealed).array());
			} catch (GeneralSecurityException e) {
				throw new IOException(e);
			}
		}

		/** The next message; throws if the session ends or the message wasn't made with our key. */
		String receive(int timeout) throws IOException {
			byte[] frame = pipe.read(timeout);
			if (frame.length <= IV || frame.length > MAX_MESSAGE) throw new IOException("Bad message size");
			try {
				Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
				cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, frame, 0, IV));
				cipher.updateAAD(associated((byte) (1 - sendDirection)));
				ByteBuffer plain = ByteBuffer.wrap(cipher.doFinal(frame, IV, frame.length - IV));
				long number = plain.getLong();
				if (number <= received) throw new IOException("Replayed message");
				received = number;
				byte[] text = new byte[plain.remaining()];
				plain.get(text);
				return new String(text, StandardCharsets.UTF_8);
			} catch (GeneralSecurityException e) {
				throw new IOException("Message not made with our key", e);
			}
		}

		@Override
		public void close() {
			pipe.close();
		}
	}

	/** The principal's end: guards anywhere reach it through the relay. */
	public static final class Server {
		private final Map<Integer, GuardSession> sessions = new ConcurrentHashMap<>();
		private final Consumer<String> onEvent;
		private final BiConsumer<String, String> onStatus;
		private volatile Relay relay;
		private volatile boolean running = true;

		/** Starts in the background. {@code onEvent} gets guards' replies and news, and {@code onStatus} their name and status reports, on network threads. */
		public Server(String password, Consumer<String> onEvent, BiConsumer<String, String> onStatus) {
			this.onEvent = onEvent;
			this.onStatus = onStatus;
			Thread thread = new Thread(() -> run(password), "femboyware guard link");
			thread.setDaemon(true);
			thread.start();
		}

		/** One guard's share of the relay connection. */
		private final class GuardSession implements Pipe {
			private final int id;
			private final BlockingQueue<byte[]> frames = new LinkedBlockingQueue<>();
			private volatile boolean open = true;
			volatile Channel channel;

			GuardSession(int id) {
				this.id = id;
			}

			@Override
			public void write(byte[] frame) throws IOException {
				Relay current = relay;
				if (!open || current == null) throw new EOFException("Guard gone");
				current.send(ByteBuffer.allocate(5 + frame.length).put(DATA).putInt(id).put(frame).array());
			}

			@Override
			public byte[] read(int timeout) throws IOException {
				try {
					byte[] frame = timeout > 0 ? frames.poll(timeout, TimeUnit.MILLISECONDS) : frames.take();
					if (frame == null) throw new SocketTimeoutException("No answer in time");
					if (frame.length == 0 && !open) {
						frames.add(frame);
						throw new EOFException("Guard gone");
					}
					return frame;
				} catch (InterruptedException e) {
					throw new IOException(e);
				}
			}

			/** The guard went away; wakes up whoever is reading. */
			void end() {
				open = false;
				frames.add(new byte[0]);
			}

			@Override
			public void close() {
				if (open) {
					Relay current = relay;
					if (current != null) {
						try {
							current.send(ByteBuffer.allocate(5).put(LEFT).putInt(id).array());
						} catch (IOException ignored) {
						}
					}
				}
				end();
			}
		}

		private void run(String password) {
			SecretKey key;
			String room;
			try {
				key = derive(password);
				room = room(key);
			} catch (GeneralSecurityException e) {
				onEvent.accept("Guard link couldn't start: " + e.getMessage());
				return;
			}
			boolean toldUnreachable = false;
			while (running) {
				try (Relay current = Relay.open(room, "principal")) {
					relay = current;
					if (!running) return;
					toldUnreachable = false;
					onEvent.accept("Guard link ready: guards with the same Link Key can connect from anywhere");
					while (running) dispatch(current.take(0), key);
				} catch (IOException e) {
					Relay last = relay;
					if (running && last != null && last.closeCode == CLOSE_TAKEN_OVER) {
						onEvent.accept("Guard link stopped: Guard Command was turned on with this Link Key somewhere else");
						running = false;
					} else if (running && last == null && !toldUnreachable) {
						onEvent.accept("Guard link can't reach femboyware.xyz, retrying");
						toldUnreachable = true;
					} else if (running && last != null) {
						onEvent.accept("Guard link lost its connection, reconnecting");
					}
				} finally {
					relay = null;
					for (GuardSession session : sessions.values()) session.end();
					sessions.clear();
				}
				sleep(RECONNECT_DELAY);
			}
		}

		private void dispatch(Object message, SecretKey key) {
			if (!(message instanceof byte[] frame) || frame.length < 5) return;
			ByteBuffer buffer = ByteBuffer.wrap(frame);
			byte type = buffer.get();
			int id = buffer.getInt();
			switch (type) {
				case JOINED -> {
					GuardSession session = new GuardSession(id);
					GuardSession old = sessions.put(id, session);
					if (old != null) old.end();
					Thread thread = new Thread(() -> serve(session, key), "femboyware guard");
					thread.setDaemon(true);
					thread.start();
				}
				case DATA -> {
					GuardSession session = sessions.get(id);
					if (session != null) {
						byte[] payload = new byte[buffer.remaining()];
						buffer.get(payload);
						session.frames.add(payload);
					}
				}
				case LEFT -> {
					GuardSession session = sessions.remove(id);
					if (session != null) session.end();
				}
				default -> {
				}
			}
		}

		private void serve(GuardSession session, SecretKey key) {
			Channel channel = new Channel(session, key, (byte) 0);
			boolean joined = false;
			try {
				channel.exchangeNonces(false);
				// The guard proves it has the password by sending its name under the key
				channel.name = channel.receive(HANDSHAKE_TIMEOUT);
				channel.send("ok");
				session.channel = channel;
				joined = true;
				onEvent.accept(channel.name + " connected to the guard link");
				while (running) {
					String message = channel.receive(0);
					if (message.startsWith(STATUS)) onStatus.accept(channel.name, message.substring(STATUS.length()));
					else onEvent.accept(channel.name + ": " + message);
				}
			} catch (IOException e) {
				FemboyWare.LOGGER.debug("Guard link session ended", e);
			} finally {
				session.channel = null;
				sessions.remove(session.id, session);
				channel.close();
				if (joined && running) onEvent.accept(channel.name + " left the guard link");
			}
		}

		/** Sends an order to every connected guard; how many got it. */
		public int send(String order) {
			int count = 0;
			for (GuardSession session : sessions.values()) {
				Channel channel = session.channel;
				if (channel == null) continue;
				try {
					channel.send(order);
					count++;
				} catch (IOException e) {
					session.close();
				}
			}
			return count;
		}

		public void stop() {
			running = false;
			Relay current = relay;
			if (current != null) current.close();
		}
	}

	/** A guard's end: keeps connecting to the principal through the relay until stopped. */
	public static final class Client {
		private final String password;
		private final String name;
		private final Consumer<String> onOrder;
		private final Consumer<String> onEvent;
		private volatile Channel channel;
		private volatile Relay relay;
		private volatile boolean running = true;

		/** Both callbacks run on a network thread. */
		public Client(String password, String name, Consumer<String> onOrder, Consumer<String> onEvent) {
			this.password = password;
			this.name = name;
			this.onOrder = onOrder;
			this.onEvent = onEvent;
			Thread thread = new Thread(this::run, "femboyware guard link");
			thread.setDaemon(true);
			thread.start();
		}

		private void run() {
			SecretKey key;
			String room;
			try {
				key = derive(password);
				room = room(key);
			} catch (GeneralSecurityException e) {
				onEvent.accept("Guard link couldn't start: " + e.getMessage());
				return;
			}
			boolean toldUnreachable = false, toldWaiting = false, toldRefused = false;
			while (running) {
				boolean connected = false;
				try (Relay current = Relay.open(room, "guard")) {
					relay = current;
					toldUnreachable = false;
					// The relay says whether the principal is there yet
					while (running && !"ready".equals(current.take(0))) {
						if (!toldWaiting) {
							onEvent.accept("Guard link waiting for the principal: they need Guard Command on with the same Link Key");
							toldWaiting = true;
						}
					}
					if (!running) return;
					Channel link = new Channel(new Pipe() {
						@Override
						public void write(byte[] frame) throws IOException {
							current.send(frame);
						}

						@Override
						public byte[] read(int timeout) throws IOException {
							while (true) {
								if (current.take(timeout) instanceof byte[] frame) return frame;
							}
						}

						@Override
						public void close() {
							current.close();
						}
					}, key, (byte) 1);
					link.exchangeNonces(true);
					link.send(name);
					try {
						link.receive(HANDSHAKE_TIMEOUT);
					} catch (IOException e) {
						if (!toldRefused) onEvent.accept("Guard link refused: check the Link Key matches on both ends");
						toldRefused = true;
						throw e;
					}
					toldWaiting = toldRefused = false;
					channel = link;
					connected = true;
					onEvent.accept("Guard link connected");
					while (running) onOrder.accept(link.receive(0));
				} catch (IOException e) {
					if (!running) return;
					if (relay == null && !toldUnreachable) {
						onEvent.accept("Guard link can't reach femboyware.xyz, retrying");
						toldUnreachable = true;
					} else if (connected) {
						onEvent.accept("Guard link lost the principal, reconnecting");
					}
				} finally {
					channel = null;
					relay = null;
				}
				sleep(RECONNECT_DELAY);
			}
		}

		/** Answers the principal, if connected. */
		public void reply(String text) {
			Channel link = channel;
			if (link == null) return;
			try {
				link.send(text);
			} catch (IOException e) {
				link.close();
			}
		}

		public void stop() {
			running = false;
			Relay current = relay;
			if (current != null) current.close();
		}
	}

	private static void sleep(int millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException ignored) {
			Thread.currentThread().interrupt();
		}
	}
}
