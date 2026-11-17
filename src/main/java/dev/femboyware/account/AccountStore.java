package dev.femboyware.account;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import net.fabricmc.loader.api.FabricLoader;

/**
 * The saved accounts, kept in {@code femboyware/accounts.dat}. The file is encrypted with AES-GCM
 * under a random key stored next to it in {@code accounts.key}, so the accounts file alone gives
 * nothing away; both files stay on this PC and nothing is ever uploaded. This guards against a
 * stray copy of the accounts file, not against someone who already has full run of your machine.
 */
public final class AccountStore {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Type LIST = new TypeToken<List<Account>>() {}.getType();
	private static final SecureRandom RANDOM = new SecureRandom();

	private final Path dir = FabricLoader.getInstance().getGameDir().resolve("femboyware");
	private final Path file = dir.resolve("accounts.dat");
	private final Path keyFile = dir.resolve("accounts.key");
	private final List<Account> accounts = new ArrayList<>();

	public List<Account> all() {
		return accounts;
	}

	public void add(Account account) {
		accounts.removeIf(a -> a.uuid.equals(account.uuid));
		accounts.add(account);
		save();
	}

	public void remove(Account account) {
		accounts.remove(account);
		save();
	}

	public List<Account> sorted() {
		List<Account> copy = new ArrayList<>(accounts);
		copy.sort(Comparator.comparingLong((Account a) -> a.lastUsed).reversed());
		return copy;
	}

	// ---------------------------------------------------------------- disk

	public void load() {
		accounts.clear();
		if (!Files.exists(file)) return;
		try {
			byte[] plain = decrypt(Files.readAllBytes(file));
			List<Account> loaded = GSON.fromJson(new String(plain, StandardCharsets.UTF_8), LIST);
			if (loaded != null) accounts.addAll(loaded);
		} catch (Exception e) {
			dev.femboyware.FemboyWare.LOGGER.warn("Couldn't read saved accounts: {}", e.getMessage());
		}
	}

	public void save() {
		try {
			Files.createDirectories(dir);
			byte[] plain = GSON.toJson(accounts, LIST).getBytes(StandardCharsets.UTF_8);
			Files.write(file, encrypt(plain));
		} catch (Exception e) {
			dev.femboyware.FemboyWare.LOGGER.warn("Couldn't save accounts: {}", e.getMessage());
		}
	}

	// ---------------------------------------------------------------- encryption

	private byte[] key() throws Exception {
		if (Files.exists(keyFile)) {
			byte[] k = Files.readAllBytes(keyFile);
			if (k.length == 32) return k;
		}
		byte[] k = new byte[32];
		RANDOM.nextBytes(k);
		Files.createDirectories(dir);
		Files.write(keyFile, k);
		return k;
	}

	private byte[] encrypt(byte[] plain) throws Exception {
		byte[] iv = new byte[12];
		RANDOM.nextBytes(iv);
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(128, iv));
		byte[] body = cipher.doFinal(plain);
		byte[] out = new byte[iv.length + body.length];
		System.arraycopy(iv, 0, out, 0, iv.length);
		System.arraycopy(body, 0, out, iv.length, body.length);
		return out;
	}

	private byte[] decrypt(byte[] data) throws Exception {
		byte[] iv = new byte[12];
		System.arraycopy(data, 0, iv, 0, 12);
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(128, iv));
		return cipher.doFinal(data, 12, data.length - 12);
	}
}
