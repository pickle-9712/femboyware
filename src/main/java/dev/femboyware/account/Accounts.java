package dev.femboyware.account;

import java.util.UUID;
import net.minecraft.client.Minecraft;

/**
 * The account switcher's brain: the saved accounts, and turning one into a live session. A Microsoft
 * account uses its cached Minecraft token if it's still good, otherwise its refresh token fetches a
 * new one; "Refresh" forces that by hand. All the network work is done off the main thread by the
 * screen, then {@link SessionSwitch#switchTo} is called on it.
 */
public final class Accounts {
	private static final AccountStore STORE = new AccountStore();
	private static boolean loaded;

	private Accounts() {
	}

	public static AccountStore store() {
		if (!loaded) {
			STORE.load();
			loaded = true;
		}
		return STORE;
	}

	/** Sign in with a Microsoft account from the sign-in page: poll until it's authorized, then save it. */
	public static Account fromDeviceLogin(MsAuth.MsTokens tokens) throws MsAuth.AuthException {
		MsAuth.McLogin login = MsAuth.toMinecraft(tokens.access());
		Account account = Account.microsoft(login.name(), login.uuid(), tokens.clientId(), tokens.refresh(), login.accessToken(), login.expiresAt());
		store().add(account);
		return account;
	}

	/** Import a refresh token: work out its app if needed, prove it logs in, and save the account. */
	public static Account fromRefreshToken(String clientId, String refreshToken) throws MsAuth.AuthException {
		MsAuth.MsTokens tokens = MsAuth.refresh(clientId == null || clientId.isBlank() ? null : clientId.trim(), refreshToken.trim());
		MsAuth.McLogin login = MsAuth.toMinecraft(tokens.access());
		Account account = Account.microsoft(login.name(), login.uuid(), tokens.clientId(), tokens.refresh(), login.accessToken(), login.expiresAt());
		store().add(account);
		return account;
	}

	/** Add an account from a Minecraft access token alone. It can't be refreshed, so it stops working when it expires. */
	public static Account fromAccessToken(String mcToken) throws MsAuth.AuthException {
		MsAuth.McLogin login = MsAuth.fromAccessToken(mcToken.trim());
		Account account = Account.microsoft(login.name(), login.uuid(), null, null, login.accessToken(), login.expiresAt());
		store().add(account);
		return account;
	}

	/** A session for this account, refreshing the Minecraft token from the refresh token if it's stale. */
	public static MsAuth.McLogin login(Account account) throws MsAuth.AuthException {
		if (account.offline) return new MsAuth.McLogin(account.name, account.uuid, "0", Long.MAX_VALUE);
		// Access-token-only (no refresh token): can't be renewed, so make sure it still works and say if it doesn't
		if (account.refreshToken == null) {
			if (account.mcToken == null) throw new MsAuth.AuthException(account.name + " has no token saved. Add it again.");
			try {
				MsAuth.McLogin check = MsAuth.fromAccessToken(account.mcToken);
				account.name = check.name();
				account.uuid = check.uuid();
				account.mcExpires = check.expiresAt();
				store().save();
				return check;
			} catch (MsAuth.AuthException e) {
				throw new MsAuth.AuthException(account.name + ": " + e.getMessage() + ". Add it again with a fresh token");
			}
		}
		if (account.tokenFresh()) return new MsAuth.McLogin(account.name, account.uuid, account.mcToken, account.mcExpires);
		return refresh(account);
	}

	/** Force a fresh Minecraft token for this account from its refresh token, and save it. */
	public static MsAuth.McLogin refresh(Account account) throws MsAuth.AuthException {
		if (account.offline || account.refreshToken == null) throw new MsAuth.AuthException("This account has no refresh token to refresh with");
		MsAuth.MsTokens tokens = MsAuth.refresh(account.clientId, account.refreshToken);
		MsAuth.McLogin login = MsAuth.toMinecraft(tokens.access());
		account.name = login.name();
		account.uuid = login.uuid();
		account.clientId = tokens.clientId();
		account.refreshToken = tokens.refresh();
		account.mcToken = login.accessToken();
		account.mcExpires = login.expiresAt();
		store().save();
		return login;
	}

	/** Switch the live game to this account (call on the main thread). */
	public static void switchTo(Account account, MsAuth.McLogin login) {
		account.lastUsed = System.currentTimeMillis();
		store().save();
		if (account.offline) {
			SessionSwitch.switchTo(new MsAuth.McLogin(account.name, account.uuid, "0", Long.MAX_VALUE));
		} else {
			SessionSwitch.switchTo(login);
		}
	}

	/** The account the game is on right now, matched to a saved one by UUID if we have it. */
	public static UUID currentUuid() {
		return SessionSwitch.current().getProfileId();
	}

	public static String currentName() {
		return SessionSwitch.current().getName();
	}

	/** A saved account was renamed on Mojang (via the Account screen): update its stored name. */
	public static void renamed(UUID uuid, String newName) {
		boolean changed = false;
		for (Account a : store().all()) {
			if (a.uuid != null && a.uuid.equals(uuid)) {
				a.name = newName;
				changed = true;
			}
		}
		if (changed) store().save();
	}
}
