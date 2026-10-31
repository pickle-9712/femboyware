package dev.femboyware.manager;

import dev.femboyware.FemboyWare;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * A private line from the principal's game to the bodyguards', so orders never touch the Minecraft
 * server. The principal listens on a port and each guard connects to it directly. Orders can run
 * commands on a guard, so everything is encrypted and signed with a key derived from a shared password:
 * each side contributes a random number for the connection, messages are numbered, and anyone without
 * the password is dropped within seconds, so a recorded message can't be played back.
 */
public final class GuardLink {
	public static final int DEFAULT_PORT = 38451;
	/** Starts a guard's regular status report, as opposed to a reply meant to be read. */
	public static final String STATUS = "\u0001";
	private static final int MAX_MESSAGE = 4096;
	private static final int NONCE = 16;
	private static final int IV = 12;
	private static final int MAX_CONNECTIONS = 64;
	private static final int HANDSHAKE_TIMEOUT = 5000;
	private static final int RECONNECT_DELAY = 3000;
	private static final byte[] SALT = "femboyware guard link v1".getBytes(StandardCharsets.UTF_8);
	private static final SecureRandom RANDOM = new SecureRandom();

	private GuardLink() {
	}

	private static SecretKey derive(String password) throws GeneralSecurityException {
		SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
		byte[] key = factory.generateSecret(new PBEKeySpec(password.toCharArray(), SALT, 200_000, 256)).getEncoded();
		return new SecretKeySpec(key, "AES");
	}

	/** One connection: numbered, encrypted messages. Direction 0 is principal to guard, 1 is guard to principal. */
	private static final class Channel implements Closeable {
		private final Socket socket;
		private final DataInputStream in;
		private final DataOutputStream out;
		private final SecretKey key;
		private final byte sendDirection;
		private byte[] session;
		private long sent;
		private long received;
		String name = "guard";

		Channel(Socket socket, SecretKey key, byte sendDirection) throws IOException {
			this.socket = socket;
			this.key = key;
			this.sendDirection = sendDirection;
			socket.setTcpNoDelay(true);
			in = new DataInputStream(socket.getInputStream());
			out = new DataOutputStream(socket.getOutputStream());
		}

		/** Swaps random numbers, which tie every message to this one connection. */
		void exchangeNonces(boolean guardSide) throws IOException {
			byte[] mine = new byte[NONCE], theirs = new byte[NONCE];
			RANDOM.nextBytes(mine);
			out.write(mine);
			out.flush();
			in.readFully(theirs);
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
				out.writeInt(IV + sealed.length);
				out.write(iv);
				out.write(sealed);
				out.flush();
			} catch (GeneralSecurityException e) {
				throw new IOException(e);
			}
		}

		/** The next message; throws if the connection ends or the message wasn't made with our key. */
		String receive() throws IOException {
			int length = in.readInt();
			if (length <= IV || length > MAX_MESSAGE) throw new IOException("Bad message size");
			byte[] frame = new byte[length];
			in.readFully(frame);
			try {
				Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
				cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, frame, 0, IV));
				cipher.updateAAD(associated((byte) (1 - sendDirection)));
				ByteBuffer plain = ByteBuffer.wrap(cipher.doFinal(frame, IV, length - IV));
				long number = plain.getLong();
				if (number <= received) throw new IOException("Replayed message");
				received = number;
				byte[] text = new byte[plain.remaining()];
				plain.get(text);
				return new String(text, StandardCharsets.UTF_8);
			} catch (GeneralSecurityException e) {
				throw new WrongKeyException(e);
			}
		}

		void timeout(int millis) throws IOException {
			socket.setSoTimeout(millis);
		}

		@Override
		public void close() {
			try {
				socket.close();
			} catch (IOException ignored) {
			}
		}
	}

	private static final class WrongKeyException extends IOException {
		WrongKeyException(Throwable cause) {
			super(cause);
		}
	}

	/** The principal's end: guards connect here. */
	public static final class Server {
		private final List<Channel> guards = new CopyOnWriteArrayList<>();
		private final AtomicInteger connections = new AtomicInteger();
		private final Consumer<String> onEvent;
		private final BiConsumer<String, String> onStatus;
		private volatile ServerSocket socket;
		private volatile boolean running = true;

		/** Starts listening in the background. {@code onEvent} gets guards' replies and news, and {@code onStatus} their name and status reports, on network threads. */
		public Server(String password, int port, boolean otherComputers, Consumer<String> onEvent, BiConsumer<String, String> onStatus) {
			this.onEvent = onEvent;
			this.onStatus = onStatus;
			Thread thread = new Thread(() -> listen(password, port, otherComputers), "femboyware guard link");
			thread.setDaemon(true);
			thread.start();
		}

		private void listen(String password, int port, boolean otherComputers) {
			try (ServerSocket server = otherComputers ? new ServerSocket(port, 50) : new ServerSocket(port, 50, InetAddress.getLoopbackAddress())) {
				socket = server;
				SecretKey key = derive(password);
				if (!running) return;
				while (running) {
					Socket client = server.accept();
					if (connections.incrementAndGet() > MAX_CONNECTIONS) {
						connections.decrementAndGet();
						client.close();
						continue;
					}
					Thread thread = new Thread(() -> serve(client, key), "femboyware guard");
					thread.setDaemon(true);
					thread.start();
				}
			} catch (IOException | GeneralSecurityException e) {
				if (running) onEvent.accept("Guard link couldn't listen on port " + port + ": " + e.getMessage());
			}
		}

		private void serve(Socket client, SecretKey key) {
			Channel channel = null;
			boolean joined = false;
			try {
				channel = new Channel(client, key, (byte) 0);
				channel.timeout(HANDSHAKE_TIMEOUT);
				channel.exchangeNonces(false);
				// The guard proves it has the password by sending its name under the key
				channel.name = channel.receive();
				channel.send("ok");
				channel.timeout(0);
				guards.add(channel);
				joined = true;
				onEvent.accept(channel.name + " connected to the guard link");
				while (running) {
					String message = channel.receive();
					if (message.startsWith(STATUS)) onStatus.accept(channel.name, message.substring(STATUS.length()));
					else onEvent.accept(channel.name + ": " + message);
				}
			} catch (IOException e) {
				FemboyWare.LOGGER.debug("Guard link connection ended", e);
			} finally {
				if (channel != null) {
					guards.remove(channel);
					channel.close();
					if (joined && running) onEvent.accept(channel.name + " left the guard link");
				} else {
					try {
						client.close();
					} catch (IOException ignored) {
					}
				}
				connections.decrementAndGet();
			}
		}

		/** Sends an order to every connected guard; how many got it. */
		public int send(String order) {
			int count = 0;
			for (Channel guard : guards) {
				try {
					guard.send(order);
					count++;
				} catch (IOException e) {
					guards.remove(guard);
					guard.close();
				}
			}
			return count;
		}

		public void stop() {
			running = false;
			try {
				if (socket != null) socket.close();
			} catch (IOException ignored) {
			}
			for (Channel guard : guards) guard.close();
			guards.clear();
		}
	}

	/** A guard's end: keeps connecting to the principal until stopped. */
	public static final class Client {
		private final String host;
		private final int port;
		private final String password;
		private final String name;
		private final Consumer<String> onOrder;
		private final Consumer<String> onEvent;
		private volatile Channel channel;
		private volatile boolean running = true;

		/** Both callbacks run on a network thread. */
		public Client(String address, String password, String name, Consumer<String> onOrder, Consumer<String> onEvent) {
			int colon = address.lastIndexOf(':');
			int parsed = DEFAULT_PORT;
			if (colon > 0) {
				try {
					parsed = Integer.parseInt(address.substring(colon + 1).trim());
				} catch (NumberFormatException ignored) {
				}
			}
			this.host = (colon > 0 ? address.substring(0, colon) : address).trim();
			this.port = parsed;
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
			try {
				key = derive(password);
			} catch (GeneralSecurityException e) {
				onEvent.accept("Guard link couldn't start: " + e.getMessage());
				return;
			}
			boolean toldWrongKey = false;
			while (running) {
				Socket socket = new Socket();
				try {
					socket.connect(new InetSocketAddress(host, port), RECONNECT_DELAY);
					Channel link = new Channel(socket, key, (byte) 1);
					link.timeout(HANDSHAKE_TIMEOUT);
					link.exchangeNonces(true);
					link.send(name);
					boolean accepted = false;
					try {
						link.receive();
						accepted = true;
					} catch (IOException e) {
						if (!toldWrongKey) onEvent.accept("Guard link refused: check the Link Key matches on both ends");
						toldWrongKey = true;
					}
					if (accepted) {
						toldWrongKey = false;
						link.timeout(0);
						channel = link;
						onEvent.accept("Guard link connected");
						while (running) onOrder.accept(link.receive());
					}
				} catch (IOException e) {
					if (channel != null && running) onEvent.accept("Guard link lost, reconnecting");
				} finally {
					channel = null;
					try {
						socket.close();
					} catch (IOException ignored) {
					}
				}
				try {
					if (running) Thread.sleep(RECONNECT_DELAY);
				} catch (InterruptedException e) {
					return;
				}
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
			Channel link = channel;
			if (link != null) link.close();
		}
	}
}
