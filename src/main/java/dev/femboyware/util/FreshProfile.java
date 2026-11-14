package dev.femboyware.util;

import com.mojang.authlib.GameProfile;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.properties.Property;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;

/**
 * Your own profile as Mojang has it now. The game only fetches it once, at launch, so a skin changed
 * since (or a stale fetch) leaves the menus showing the old one; this refetches it in the background,
 * at most once a minute, and getGameProfile hands out the fresh copy (see MinecraftClientMixin).
 */
public final class FreshProfile {
	private static final long EVERY = 60_000, RETRY = 60_000;
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
	private static volatile GameProfile fresh;
	private static volatile long fetchedAt;
	private static volatile boolean fetching;

	private FreshProfile() {
	}

	/** The freshest copy of {@code launched} (your profile from launch), starting a refetch if it's due. */
	public static GameProfile of(GameProfile launched) {
		if (launched == null) return null;
		// It already has its skin: nothing to fix
		if (!launched.properties().get("textures").isEmpty()) return launched;
		long now = System.currentTimeMillis();
		// Every minute once it's in; every 10 seconds while it keeps failing (no skin yet)
		long wait = fresh == null ? RETRY : EVERY;
		if (!fetching && now - fetchedAt > wait) {
			fetching = true;
			fetchedAt = now;
			Minecraft mc = Minecraft.getInstance();
			CompletableFuture.runAsync(() -> {
				try {
					var result = mc.services().sessionService().fetchProfile(launched.id(), true);
					if (result != null && result.profile() != null && !result.profile().properties().get("textures").isEmpty()) fresh = result.profile();
					else {
						GameProfile direct = direct(launched);
						if (direct != null) fresh = direct;
					}
				} catch (RuntimeException ignored) {
					// Offline or rate limited: keep what there is
				} finally {
					fetching = false;
				}
			});
		}
		GameProfile copy = fresh;
		return copy != null && copy.id().equals(launched.id()) ? copy : launched;
	}

	/** The profile straight from Mojang's public session server, for when the game's own service won't answer. */
	private static GameProfile direct(GameProfile launched) {
		return AccountSkins.profileById(launched.id());
	}
}
