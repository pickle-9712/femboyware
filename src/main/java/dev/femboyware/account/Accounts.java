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

	/** A session for this account, refreshing the Minecraft token from the refresh token if it's stale. */
	public static MsAuth.McLogin login(Account account) throws MsAuth.AuthException {
		if (account.offline) return new MsAuth.McLogin(account.name, account.uuid, "0", Long.MAX_VALUE);
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
}
