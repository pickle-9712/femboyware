package dev.femboyware.account;

import java.util.UUID;

/**
 * One saved account. The refresh token is the lasting credential (it fetches fresh access tokens
 * whenever they expire); the Minecraft access token is just a cache so a recent switch is instant.
 * Offline accounts have no tokens and only work on cracked servers.
 */
public final class Account {
	public String name;
	public UUID uuid;
	/** Which Microsoft app the refresh token is for, or null if unknown (it'll be worked out on first use). */
	public String clientId;
	/** The Microsoft refresh token, or null for an offline account. */
	public String refreshToken;
	/** A cached Minecraft access token and when it expires (epoch ms); refreshed when stale. */
	public String mcToken;
	public long mcExpires;
	public boolean offline;
	/** When this account was last switched to, for sorting. */
	public long lastUsed;

	public Account() {
	}

	public static Account microsoft(String name, UUID uuid, String clientId, String refreshToken, String mcToken, long mcExpires) {
		Account a = new Account();
		a.name = name;
		a.uuid = uuid;
		a.clientId = clientId;
		a.refreshToken = refreshToken;
		a.mcToken = mcToken;
		a.mcExpires = mcExpires;
		return a;
	}

	public static Account offline(String name) {
		Account a = new Account();
		a.name = name;
		a.uuid = offlineUuid(name);
		a.offline = true;
		return a;
	}

	/** The usual offline UUID a server gives a cracked name, so it stays the same each time. */
	public static UUID offlineUuid(String name) {
		return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	public boolean tokenFresh() {
		return mcToken != null && System.currentTimeMillis() < mcExpires - 60_000;
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof Account a && a.uuid != null && a.uuid.equals(uuid);
	}

	@Override
	public int hashCode() {
		return uuid == null ? 0 : uuid.hashCode();
	}
}
