package dev.femboyware.module.combat;

import dev.femboyware.FemboyWare;
import dev.femboyware.manager.CommandManager;
import dev.femboyware.manager.GuardLink;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.KeySetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.setting.StringSetting;
import dev.femboyware.util.ChatUtil;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turn this on on the principal's game to give bodyguards orders privately: {@code .g pause} goes
 * straight to every connected guard over {@link GuardLink}, and nothing reaches the server's chat.
 */
public class GuardCommandModule extends Module {
	private final StringSetting key = add(new StringSetting("Link Key", "Password shared with the guards' Link Key setting; use something long, since it protects commands run on the guards", ""));
	private final NumberSetting port = add(new NumberSetting("Port", "Port the guards connect to", GuardLink.DEFAULT_PORT, 1024, 65535, 1));
	private final BoolSetting otherComputers = add(new BoolSetting("Other Computers", "Accept guards on other computers, not just this one", false));
	private final KeySetting rallyKey = add(new KeySetting("Rally Key", "Press to call every guard to you", -1));
	private final KeySetting hereKey = add(new KeySetting("Send Here Key", "Press to send every guard to the block you're looking at", -1));

	/** One guard's latest report. */
	public record GuardStatus(String name, float health, float maxHealth, float absorption, int totems, int pots, String state, String target, double x, double y, double z, long received) {
		public long age() {
			return System.currentTimeMillis() - received;
		}
	}

	/** After this long without a report a guard shows as out of touch, and after {@link #STATUS_GONE} it's dropped, in milliseconds. */
	public static final long STATUS_LOST = 4_000;
	private static final long STATUS_GONE = 30_000;
	private static final double LOOK_RANGE = 128;

	private final Map<String, GuardStatus> statuses = new ConcurrentHashMap<>();
	private GuardLink.Server server;
	private String running = "";

	public GuardCommandModule() {
		super("Guard Command", "Send bodyguards orders privately with " + CommandManager.PREFIX + "g, without using chat", Category.LEGIT);
	}

	@Override
	protected void onEnable() {
		sync();
	}

	@Override
	protected void onDisable() {
		stopServer();
	}

	@Override
	public void onTick() {
		sync();
		statuses.values().removeIf(status -> status.age() > STATUS_GONE);
	}

	@Override
	public void onKey(int key) {
		if (rallyKey.is(key)) command("rally");
		else if (hereKey.is(key)) command("here");
	}

	/** The guards reporting in, sorted by name. */
	public List<GuardStatus> guards() {
		if (!isEnabled() || statuses.isEmpty()) return List.of();
		List<GuardStatus> list = new ArrayList<>(statuses.values());
		list.sort(Comparator.comparing(GuardStatus::name, String.CASE_INSENSITIVE_ORDER));
		return list;
	}

	/** Reads a guard's report: the fields in {@link BodyguardModule}'s status, separated by tabs. */
	private void noteStatus(String name, String report) {
		try {
			String[] parts = report.split("\t", -1);
			statuses.put(name, new GuardStatus(name, Float.parseFloat(parts[0]), Float.parseFloat(parts[1]), Float.parseFloat(parts[2]), Integer.parseInt(parts[3]), Integer.parseInt(parts[4]),
				parts[5], parts[6], Double.parseDouble(parts[7]), Double.parseDouble(parts[8]), Double.parseDouble(parts[9]), System.currentTimeMillis()));
		} catch (RuntimeException e) {
			FemboyWare.LOGGER.debug("Bad status from guard {}", name, e);
		}
	}

	/** Starts the link, or restarts it if the settings changed. */
	private void sync() {
		String wanted = key.get() + "|" + port.getInt() + "|" + otherComputers.get();
		if (wanted.equals(running)) return;
		stopServer();
		if (key.get().isBlank()) return;
		running = wanted;
		server = new GuardLink.Server(key.get(), port.getInt(), otherComputers.get(), event -> mc.execute(() -> ChatUtil.info(event)), this::noteStatus);
	}

	private void stopServer() {
		if (server != null) server.stop();
		server = null;
		running = "";
		statuses.clear();
	}

	/** Fills in where for orders that need it, which guards may be too far away to work out themselves; null if there's no such place. */
	private String placed(String order) {
		String lower = order.toLowerCase(Locale.ROOT);
		if (mc.player == null) return order;
		if (lower.equals("rally") || lower.equals("r")) return "rally " + coordinates(mc.player.getEntityPos());
		if (lower.equals("ffa")) {
			// Everyone connected, so guards on other computers know who's in it
			List<String> names = new ArrayList<>();
			for (GuardStatus guard : guards()) names.add(guard.name());
			return names.size() < 2 ? null : "ffa " + String.join(",", names);
		}
		if (lower.equals("crowd")) return "crowd " + coordinates(mc.player.getEntityPos());
		if (lower.equals("here")) {
			HitResult look = mc.player.raycast(LOOK_RANGE, 1, false);
			if (look.getType() != HitResult.Type.BLOCK) return null;
			return "goto " + coordinates(look.getPos());
		}
		return order;
	}

	private static String coordinates(Vec3d pos) {
		return String.format(Locale.ROOT, "%.2f %.2f %.2f", pos.x, pos.y, pos.z);
	}

	/** Handles {@code .g <order>}. */
	public void command(String order) {
		if (!isEnabled()) {
			ChatUtil.info("Turn on the Guard Command module first");
			return;
		}
		sync();
		if (server == null) {
			ChatUtil.info("Set a Link Key in Guard Command first");
			return;
		}
		if (order.isEmpty()) {
			ChatUtil.info("Usage: " + CommandManager.PREFIX + "g <order>, like " + CommandManager.PREFIX + "g pause or " + CommandManager.PREFIX + "g say /leave");
			return;
		}
		String words = placed(order);
		if (words == null) {
			ChatUtil.info(order.equalsIgnoreCase("ffa") ? "FFA needs at least 2 connected guards" : "Look at a block within " + (int) LOOK_RANGE + " blocks first");
			return;
		}
		int count = server.send(words);
		ChatUtil.info(count == 0 ? "No guards are connected" : "Sent to " + count + (count == 1 ? " guard" : " guards"));
	}
}
