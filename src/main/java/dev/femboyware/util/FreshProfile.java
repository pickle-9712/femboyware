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

	// A separate cache for the account you've switched to with the account switcher
	private static volatile GameProfile switchedProfile;
	private static volatile java.util.UUID switchedId;
	private static volatile long switchedFetchedAt;
	private static volatile boolean switchedFetching;

	/** The freshest copy of {@code launched} (your profile from launch), starting a refetch if it's due. */
	public static GameProfile of(GameProfile launched) {
		if (launched == null) return null;
		// Switched to another account: the whole game (menu skin included) should show that account
		if (dev.femboyware.account.SessionSwitch.switched()) {
			net.minecraft.client.User user = dev.femboyware.account.SessionSwitch.current();
			GameProfile p = switched(user.getProfileId(), user.getName());
			// Until its skin is fetched, at least show the right name and UUID (default skin meanwhile)
			return p != null ? p : new GameProfile(user.getProfileId(), user.getName());
		}
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

	/** The switched account's full profile (name, UUID and skin), fetched by UUID and cached; null until it's in. */
	private static GameProfile switched(java.util.UUID id, String name) {
		if (switchedId == null || !switchedId.equals(id)) {
			switchedId = id;
			switchedProfile = null;
			switchedFetchedAt = 0;
		}
		GameProfile cached = switchedProfile;
		if (cached != null && cached.id().equals(id)) return cached;
		long now = System.currentTimeMillis();
		if (!switchedFetching && now - switchedFetchedAt > RETRY) {
			switchedFetching = true;
			switchedFetchedAt = now;
			Minecraft mc = Minecraft.getInstance();
			CompletableFuture.runAsync(() -> {
				try {
					var result = mc.services().sessionService().fetchProfile(id, true);
					if (result != null && result.profile() != null && !result.profile().properties().get("textures").isEmpty()) switchedProfile = result.profile();
					else {
						GameProfile fromMojang = AccountSkins.profileById(id);
						if (fromMojang != null) switchedProfile = fromMojang;
					}
				} catch (RuntimeException ignored) {
				} finally {
					switchedFetching = false;
				}
			});
		}
		return switchedProfile != null && switchedProfile.id().equals(id) ? switchedProfile : null;
	}
}
