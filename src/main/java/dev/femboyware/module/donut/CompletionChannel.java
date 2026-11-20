package dev.femboyware.module.donut;

import com.mojang.brigadier.suggestion.Suggestion;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket;
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket;
/** Ported from the Anubis client (GPL-3.0, by 4ldenz, https://github.com/4ldenz/Anubis-client). */
public final class CompletionChannel {
	public static final CompletionChannel INSTANCE = new CompletionChannel();
	private static final int WIRE_ID = 0;
	private static final long FORGET_MS = 15000L;
	private static final long FOREIGN_MS = 4000L;
	private static final Object FOREIGN = new Object();
	private final Object lock = new Object();
	private final LinkedList<CompletionChannel.Request> line = new LinkedList<>();
	private final Map<Integer, CompletionChannel.Request> byKey = new HashMap<>();
	private Object connection;
	private int nextKey = 1;
	private boolean sending;

	private CompletionChannel() {
	}

	/** Every packet you send, from femboyware's packet hook (always on, like in Anubis). */
	public static void onSend(Packet<?> packet) {
		INSTANCE.outgoing(packet);
	}

	/** Every packet received, on the network thread; true when it answered one of our own requests and should be dropped. */
	public static boolean onReceive(Packet<?> packet) {
		return INSTANCE.ingest(packet);
	}

	public int fire(Object owner, String partial, CompletionChannel.Form form) {
		ClientPacketListener net = Minecraft.getInstance().getConnection();
		if (net != null && owner != null && partial != null) {
			CompletionChannel.Request request;
			synchronized (this.lock) {
				this.follow(net);
				int key = this.nextKey++;
				if (this.nextKey == Integer.MAX_VALUE) {
					this.nextKey = 1;
				}

				request = new CompletionChannel.Request(key, owner, want(partial), System.currentTimeMillis(), form == CompletionChannel.Form.BOTH ? 2 : 1);
				this.line.addLast(request);
				this.byKey.put(key, request);
			}

			this.sending = true;

			try {
				if (form != CompletionChannel.Form.BARE) {
					net.send(new ServerboundCommandSuggestionPacket(0, "/" + partial));
				}

				if (form != CompletionChannel.Form.SLASH) {
					net.send(new ServerboundCommandSuggestionPacket(0, partial));
				}
			} catch (RuntimeException e) {
				synchronized (this.lock) {
					this.line.remove(request);
					this.byKey.remove(request.key);
				}

				return -1;
			} finally {
				this.sending = false;
			}

			return request.key;
		} else {
			return -1;
		}
	}

	public List<String> poll(int key) {
		synchronized (this.lock) {
			CompletionChannel.Request request = this.byKey.get(key);
			return request != null && !request.forgotten ? request.names : null;
		}
	}

	public void forget(int key) {
		synchronized (this.lock) {
			CompletionChannel.Request request = this.byKey.remove(key);
			if (request != null) {
				request.forgotten = true;
				if (!request.waiting()) {
					this.line.remove(request);
				}
			}
		}
	}

	public void prune(long now) {
		synchronized (this.lock) {
			this.expire(now);
		}
	}

	public void clear(Object owner) {
		synchronized (this.lock) {
			Iterator<CompletionChannel.Request> it = this.line.iterator();

			while (it.hasNext()) {
				CompletionChannel.Request request = it.next();
				if (request.owner == owner) {
					request.forgotten = true;
					this.byKey.remove(request.key);
					if (!request.waiting()) {
						it.remove();
					}
				}
			}
		}
	}

	private void outgoing(Packet<?> packet) {
		if (!this.sending && packet instanceof ServerboundCommandSuggestionPacket request) {
			ClientPacketListener net = Minecraft.getInstance().getConnection();
			synchronized (this.lock) {
				this.follow(net);
				this.line.addLast(new CompletionChannel.Request(0, FOREIGN, want(commandOf(request)), System.currentTimeMillis(), 1));
			}
		}
	}

	private boolean ingest(Packet<?> packet) {
		if (!(packet instanceof ClientboundCommandSuggestionsPacket reply)) {
			return false;
		} else {
			ArrayList names = new ArrayList();

			for (Suggestion suggestion : reply.toSuggestions().getList()) {
				String text = suggestion.getText();
				if (text != null && !text.isBlank()) {
					names.add(text.trim());
				}
			}

			String first = names.isEmpty() ? "" : core((String)names.get(0));
			String last = names.isEmpty() ? "" : core((String)names.get(names.size() - 1));
			synchronized (this.lock) {
				this.expire(System.currentTimeMillis());
				CompletionChannel.Request target = null;

				for (CompletionChannel.Request request : this.line) {
					if (request.waiting() && (names.isEmpty() || first.startsWith(request.want) && last.startsWith(request.want))) {
						target = request;
						break;
					}
				}

				if (target == null) {
					return false;
				}

				Iterator<CompletionChannel.Request> it = this.line.iterator();

				while (it.hasNext()) {
					CompletionChannel.Request request = it.next();
					if (request == target) {
						break;
					}

					if (request.waiting()) {
						request.answered = request.expected;
						if (request.owner == FOREIGN || request.forgotten) {
							it.remove();
						}
					}
				}

				target.answered++;
				if (names.isEmpty() || target.names != null && !target.names.isEmpty()) {
					if (target.names == null && !target.waiting()) {
						target.names = List.of();
					}
				} else {
					target.names = List.copyOf(names);
				}

				if (!target.waiting() && (target.owner == FOREIGN || target.forgotten)) {
					this.line.remove(target);
				}

				return target.owner != FOREIGN;
			}
		}
	}

	private void follow(ClientPacketListener net) {
		if (net != this.connection) {
			this.connection = net;
			this.line.clear();
			this.byKey.clear();
		}
	}

	private void expire(long now) {
		Iterator<CompletionChannel.Request> it = this.line.iterator();

		while (it.hasNext()) {
			CompletionChannel.Request request = it.next();
			long age = now - request.at;
			if (age > 15000L || request.owner == FOREIGN && age > 4000L) {
				it.remove();
				if (request.owner != FOREIGN) {
					this.byKey.remove(request.key, request);
				}
			}
		}
	}

	static String want(String partial) {
		String text = partial == null ? "" : partial.trim();
		if (text.startsWith("/")) {
			text = text.substring(1);
		}

		int space = text.lastIndexOf(32);
		text = space >= 0 ? text.substring(space + 1) : "";
		return stripDots(text).toLowerCase(Locale.ROOT);
	}

	public static String core(String raw) {
		String text = raw.replaceAll("§.", "").trim();
		int space = text.lastIndexOf(32);
		if (space >= 0) {
			text = text.substring(space + 1);
		}

		text = text.replaceAll("^[\"']|[\"']$", "");
		return stripDots(text).toLowerCase(Locale.ROOT);
	}

	private static String stripDots(String text) {
		int i = 0;

		while (i < text.length() && text.charAt(i) == '.') {
			i++;
		}

		return text.substring(i);
	}
	public enum Form {
		BOTH,
		SLASH,
		BARE;
	}
	private static final class Request {
		final int key;
		final Object owner;
		final String want;
		final long at;
		final int expected;
		int answered;
		List<String> names;
		boolean forgotten;

		Request(int key, Object owner, String want, long at, int expected) {
			this.key = key;
			this.owner = owner;
			this.want = want;
			this.at = at;
			this.expected = expected;
		}

		boolean waiting() {
			return this.answered < this.expected;
		}
	}

	private static String commandOf(ServerboundCommandSuggestionPacket packet) {
		//? if >=26.3 {
		/*return packet.command();
		*///?} else {
		return packet.getCommand();
		//?}
	}
}
