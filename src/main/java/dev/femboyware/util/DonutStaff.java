package dev.femboyware.util;

import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

/**
 * DonutSMP: whether you're on it, and who its staff are. The name list is from the Anubis client
 * (GPL-3.0, by 4ldenz), by way of the Meteor Anti-Vanish addon; names can go stale, so Anti Vanish
 * and Staff List let you add more.
 */
public final class DonutStaff {
	public static final List<String> DEFAULT = List.of(
		"FluffyMaster07", "archivePedro", "Munkerlich", "Frenk_Btw", "Napooo_", "auzzitech", "CryptoDaveYT", "W1zox_",
		"ZEEF69", "showered", "CaptainMoose35", "BobIsFound", "NoahvdAa", "0GSummer", "LzouzMP5", "Pastagamer08", "u_vv",
		"Owen1212055", "Splaterd", "Fallerfly", "Bloodspulse");
	public static final String DEFAULT_TEXT = String.join(", ", DEFAULT);

	private DonutStaff() {
	}

	/** Whether the server you're on is DonutSMP. */
	public static boolean onDonut() {
		ServerData server = Minecraft.getInstance().getCurrentServer();
		return server != null && server.ip != null && server.ip.toLowerCase(Locale.ROOT).contains("donutsmp");
	}

	/** Whether {@code name} is in a comma-separated list of names, any case. */
	public static boolean listed(String name, String commaList) {
		if (name == null) return false;
		for (String n : commaList.split(",")) if (n.trim().equalsIgnoreCase(name)) return true;
		return false;
	}
}
