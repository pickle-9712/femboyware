package dev.femboyware.account;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Turns a Microsoft account into a Minecraft session, the same chain every launcher uses: Microsoft
 * token, then Xbox Live, then XSTS, then Minecraft's login_with_xbox. Two ways in are supported:
 * Microsoft's device-code page (you type a code on microsoft.com/link, so the client never sees a
 * password) and a refresh token you already have. Tokens only go to Microsoft's and Xbox's own auth
 * hosts and api.minecraftservices.com, and are never written to a log.
 */
public final class MsAuth {
	/** Minecraft for Nintendo Switch's client id: the one Microsoft lets do device-code sign-in. */
	public static final String DEVICE_CLIENT = "00000000441cc96b";
	/** The Minecraft Launcher's own client id, where most exported refresh tokens come from. */
	public static final String LAUNCHER_CLIENT = "00000000402b5328";
	/** Clients an imported refresh token is tried against when you don't say which it's from. */
	public static final String[] IMPORT_CLIENTS = {LAUNCHER_CLIENT, DEVICE_CLIENT,
		"c36a9fb6-4f2a-41ff-90bd-ae7cc92031eb", "499546d9-bbfe-4b9b-a086-eb3d75afb78f"};

	private static final String LIVE_SCOPE = "service::user.auth.xboxlive.com::MBI_SSL";
	private static final String AZURE_SCOPE = "XboxLive.signin offline_access";
	private static final String LIVE_TOKEN = "https://login.live.com/oauth20_token.srf";
	private static final String AZURE_TOKEN = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
	private static final String PROFILE = "https://api.minecraftservices.com/minecraft/profile";
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	private MsAuth() {
	}

	/** A failure with a message safe to show on screen (never a token). */
	public static final class AuthException extends Exception {
		public AuthException(String message) {
			super(message);
		}
	}

	/** Microsoft's own access and refresh tokens for one client id. */
	public record MsTokens(String clientId, String access, String refresh) {}

	/** A finished Minecraft session: what to put in a {@code User}. */
	public record McLogin(String name, UUID uuid, String accessToken, long expiresAt) {}

	static boolean isAzure(String clientId) {
		return clientId.contains("-");
	}

	// ---------------------------------------------------------------- Microsoft sign-in page

	/** The code to type on Microsoft's page, plus what's needed to poll for the result. */
	public record DeviceCode(String userCode, String deviceCode, String verificationUri, int interval, long expiresAt) {
		/** The page with the code already filled in. */
		public String link() {
			return verificationUri + "?otc=" + URLEncoder.encode(userCode, StandardCharsets.UTF_8);
		}
	}

	/** Start a sign-in: ask Microsoft for a device code to show the user. */
	public static DeviceCode deviceCode() throws AuthException {
		Map<String, String> fields = new LinkedHashMap<>();
		fields.put("client_id", DEVICE_CLIENT);
		fields.put("scope", LIVE_SCOPE);
		fields.put("response_type", "device_code");
		Reply reply = postForm("https://login.live.com/oauth20_connect.srf", fields);
		if (reply.status != 200 || reply.json == null || !reply.json.has("user_code")) {
			throw new AuthException("Microsoft wouldn't start a sign-in (" + reply.status + ")");
		}
		JsonObject o = reply.json;
		int interval = o.has("interval") ? o.get("interval").getAsInt() : 5;
		long expires = System.currentTimeMillis() + (o.has("expires_in") ? o.get("expires_in").getAsLong() : 900) * 1000;
		String uri = o.has("verification_uri") ? o.get("verification_uri").getAsString() : "https://www.microsoft.com/link";
		return new DeviceCode(o.get("user_code").getAsString(), o.get("device_code").getAsString(), uri, interval, expires);
	}

	/** Check whether the code's been entered: tokens once it has, null while it's still waiting. */
	public static MsTokens pollDevice(DeviceCode code) throws AuthException {
		Map<String, String> fields = new LinkedHashMap<>();
		fields.put("client_id", DEVICE_CLIENT);
		fields.put("device_code", code.deviceCode());
		fields.put("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
		Reply reply = postForm(LIVE_TOKEN, fields);
		if (reply.status == 200 && reply.json != null && reply.json.has("access_token")) {
			return new MsTokens(DEVICE_CLIENT, reply.json.get("access_token").getAsString(),
				reply.json.has("refresh_token") ? reply.json.get("refresh_token").getAsString() : null);
		}
		String error = reply.json != null && reply.json.has("error") ? reply.json.get("error").getAsString() : "";
		switch (error) {
			case "authorization_pending", "slow_down" -> {
				return null;
			}
			case "authorization_declined" -> throw new AuthException("Sign-in was declined");
			case "expired_token" -> throw new AuthException("The code expired before you signed in");
			default -> throw new AuthException("Sign-in failed" + (error.isEmpty() ? "" : ": " + error));
		}
	}

	// ---------------------------------------------------------------- refresh token

	/** Swap a refresh token for fresh Microsoft tokens, trying the launcher and Switch clients if none is known. */
	public static MsTokens refresh(String clientId, String refreshToken) throws AuthException {
		if (clientId != null) return refreshWith(clientId, refreshToken);
		AuthException last = new AuthException("That refresh token didn't work with any known app");
		for (String client : IMPORT_CLIENTS) {
			try {
				return refreshWith(client, refreshToken);
			} catch (AuthException e) {
				last = e;
			}
		}
		throw last;
	}

	private static MsTokens refreshWith(String clientId, String refreshToken) throws AuthException {
		Map<String, String> fields = new LinkedHashMap<>();
		fields.put("client_id", clientId);
		fields.put("refresh_token", refreshToken);
		fields.put("grant_type", "refresh_token");
		if (isAzure(clientId)) fields.put("scope", AZURE_SCOPE);
		Reply reply = postForm(isAzure(clientId) ? AZURE_TOKEN : LIVE_TOKEN, fields);
		if (reply.status == 200 && reply.json != null && reply.json.has("access_token")) {
			return new MsTokens(clientId, reply.json.get("access_token").getAsString(),
				reply.json.has("refresh_token") ? reply.json.get("refresh_token").getAsString() : refreshToken);
		}
		String error = reply.json != null && reply.json.has("error") ? reply.json.get("error").getAsString() : String.valueOf(reply.status);
		throw new AuthException("Refresh token rejected (" + error + ")");
	}

	// ---------------------------------------------------------------- Xbox and Minecraft

	/** The full chain from a Microsoft access token to a Minecraft session. */
	public static McLogin toMinecraft(String msAccessToken) throws AuthException {
		// Xbox Live: the Microsoft token becomes an Xbox token (RpsTicket shape differs for Azure apps)
		JsonObject xblProps = new JsonObject();
		xblProps.addProperty("AuthMethod", "RPS");
		xblProps.addProperty("SiteName", "user.auth.xboxlive.com");
		xblProps.addProperty("RpsTicket", (msAccessToken.startsWith("d=") ? "" : "d=") + msAccessToken);
		JsonObject xblBody = new JsonObject();
		xblBody.add("Properties", xblProps);
		xblBody.addProperty("RelyingParty", "http://auth.xboxlive.com");
		xblBody.addProperty("TokenType", "JWT");
		Reply xbl = postJson("https://user.auth.xboxlive.com/user/authenticate", xblBody.toString(), null);
		if (xbl.status != 200 || xbl.json == null || !xbl.json.has("Token")) throw new AuthException("Xbox Live rejected the login (" + xbl.status + ")");
		String xblToken = xbl.json.get("Token").getAsString();

		// XSTS: the Xbox token is authorized for Minecraft
		JsonObject xstsProps = new JsonObject();
		com.google.gson.JsonArray tokens = new com.google.gson.JsonArray();
		tokens.add(xblToken);
		xstsProps.add("UserTokens", tokens);
		xstsProps.addProperty("SandboxId", "RETAIL");
		JsonObject xstsBody = new JsonObject();
		xstsBody.add("Properties", xstsProps);
		xstsBody.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
		xstsBody.addProperty("TokenType", "JWT");
		Reply xsts = postJson("https://xsts.auth.xboxlive.com/xsts/authorize", xstsBody.toString(), null);
		if (xsts.status == 401 && xsts.json != null && xsts.json.has("XErr")) throw new AuthException(xstsReason(xsts.json.get("XErr").getAsLong()));
		if (xsts.status != 200 || xsts.json == null || !xsts.json.has("Token")) throw new AuthException("Xbox wouldn't authorize Minecraft (" + xsts.status + ")");
		String xstsToken = xsts.json.get("Token").getAsString();
		String uhs = xsts.json.getAsJsonObject("DisplayClaims").getAsJsonArray("xui").get(0).getAsJsonObject().get("uhs").getAsString();

		// Minecraft: the XSTS token becomes a Minecraft access token
		Reply mc = postJson("https://api.minecraftservices.com/authentication/login_with_xbox",
			"{\"identityToken\":\"XBL3.0 x=" + uhs + ";" + xstsToken + "\"}", null);
		if (mc.status != 200 || mc.json == null || !mc.json.has("access_token")) throw new AuthException("Minecraft login failed (" + mc.status + ")");
		String mcToken = mc.json.get("access_token").getAsString();
		long expires = System.currentTimeMillis() + (mc.json.has("expires_in") ? mc.json.get("expires_in").getAsLong() : 86400) * 1000;

		// Profile: the account's current name and UUID (also proves it owns Minecraft)
		Reply profile = get(PROFILE, mcToken);
		if (profile.status == 404) throw new AuthException("That account doesn't own Minecraft (Java)");
		if (profile.status != 200 || profile.json == null || !profile.json.has("id")) throw new AuthException("Couldn't load the Minecraft profile (" + profile.status + ")");
		return new McLogin(profile.json.get("name").getAsString(), dashUuid(profile.json.get("id").getAsString()), mcToken, expires);
	}

	private static String xstsReason(long xErr) {
		return switch ((int) (xErr - 2148916230L)) {
			case 3 -> "This Microsoft account has no Xbox account. Make one at xbox.com first";
			case 5 -> "Xbox Live isn't available in this account's country";
			case 8 -> "This account is a child and needs to be added to a Family";
			default -> "Xbox rejected the account (XErr " + xErr + ")";
		};
	}

	static UUID dashUuid(String id) {
		if (id.contains("-")) return UUID.fromString(id);
		return UUID.fromString(id.replaceFirst("(.{8})(.{4})(.{4})(.{4})(.{12})", "$1-$2-$3-$4-$5"));
	}

	private static Reply get(String url, String bearer) throws AuthException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
			.header("Authorization", "Bearer " + bearer).header("Accept", "application/json").GET().build();
		try {
			HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
			JsonObject json = null;
			try {
				json = JsonParser.parseString(response.body()).getAsJsonObject();
			} catch (RuntimeException ignored) {
			}
			return new Reply(response.statusCode(), json, response.body());
		} catch (java.io.IOException | InterruptedException e) {
			throw new AuthException("Couldn't reach Minecraft's servers");
		}
	}

	// ---------------------------------------------------------------- http helpers

	private static String form(Map<String, String> fields) {
		return fields.entrySet().stream()
			.map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
			.collect(Collectors.joining("&"));
	}

	private record Reply(int status, JsonObject json, String body) {}

	private static Reply postForm(String url, Map<String, String> fields) throws AuthException {
		return post(url, "application/x-www-form-urlencoded", form(fields), null);
	}

	private static Reply postJson(String url, String json, String bearer) throws AuthException {
		return post(url, "application/json", json, bearer);
	}

	private static Reply post(String url, String contentType, String body, String bearer) throws AuthException {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
			.header("Content-Type", contentType).header("Accept", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
		if (bearer != null) b.header("Authorization", "Bearer " + bearer);
		try {
			HttpResponse<String> response = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
			JsonObject json = null;
			try {
				json = JsonParser.parseString(response.body()).getAsJsonObject();
			} catch (RuntimeException ignored) {
			}
			return new Reply(response.statusCode(), json, response.body());
		} catch (java.io.IOException | InterruptedException e) {
			throw new AuthException("Couldn't reach the login server (no internet?)");
		}
	}
}
