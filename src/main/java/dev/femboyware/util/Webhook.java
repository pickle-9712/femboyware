package dev.femboyware.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;

/**
 * Posts a Discord embed to a webhook URL you typed in, off the game thread. Nothing is sent
 * anywhere else, and no account details go with it beyond what each caller puts in the fields.
 * Ported from the Anubis client's webhook helpers (GPL-3.0, by 4ldenz,
 * https://github.com/4ldenz/Anubis-client).
 */
public final class Webhook {
	private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(task -> {
		Thread thread = new Thread(task, "femboyware-webhook");
		thread.setDaemon(true);
		return thread;
	});
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	private Webhook() {
	}

	/** An embed under construction. */
	public static final class Embed {
		private final JsonObject embed = new JsonObject();
		private final JsonArray fields = new JsonArray();
		private String content = "";

		public Embed(String title, String description, int color) {
			embed.addProperty("title", title);
			embed.addProperty("description", description);
			embed.addProperty("color", color);
			embed.addProperty("timestamp", Instant.now().toString());
			JsonObject footer = new JsonObject();
			footer.addProperty("text", "Sent by femboyware");
			embed.add("footer", footer);
		}

		public Embed field(String name, String value, boolean inline) {
			JsonObject field = new JsonObject();
			field.addProperty("name", name);
			field.addProperty("value", value);
			field.addProperty("inline", inline);
			fields.add(field);
			return this;
		}

		/** Text above the embed, e.g. a {@code <@id>} ping. */
		public Embed content(String text) {
			content = text == null ? "" : text;
			return this;
		}

		String json() {
			embed.add("fields", fields);
			JsonObject body = new JsonObject();
			body.addProperty("content", content);
			body.addProperty("username", "femboyware");
			JsonArray embeds = new JsonArray();
			embeds.add(embed);
			body.add("embeds", embeds);
			return body.toString();
		}
	}

	/** Whether {@code url} is an http(s) address with a host. */
	public static boolean valid(String url) {
		try {
			URI uri = new URI(url.trim());
			return uri.getHost() != null && ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()));
		} catch (Exception e) {
			return false;
		}
	}

	/** Sends it; {@code done} hears back on the game thread whether it went (may be null). */
	public static void send(String url, Embed embed, Consumer<Boolean> done) {
		if (url == null || url.isBlank() || !valid(url)) {
			if (done != null) done.accept(false);
			return;
		}
		String body = embed.json();
		WORKER.execute(() -> {
			boolean sent;
			try {
				HttpRequest request = HttpRequest.newBuilder(URI.create(url.trim())).timeout(Duration.ofSeconds(10))
					.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
				int code = HTTP.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
				sent = code >= 200 && code < 300;
			} catch (Exception e) {
				sent = false;
			}
			boolean result = sent;
			if (done != null) Minecraft.getInstance().execute(() -> done.accept(result));
		});
	}

	/** Markdown characters escaped so names show as typed. */
	public static String escape(String text) {
		StringBuilder out = new StringBuilder(text.length() + 8);
		for (char c : text.toCharArray()) {
			if ("\\*_~`|".indexOf(c) >= 0) out.append('\\');
			out.append(c);
		}
		return out.toString();
	}

	public static String coords(net.minecraft.core.BlockPos pos) {
		return "`X: " + pos.getX() + "  Y: " + pos.getY() + "  Z: " + pos.getZ() + "`";
	}
}
