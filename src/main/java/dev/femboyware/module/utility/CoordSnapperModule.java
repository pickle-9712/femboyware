package dev.femboyware.module.utility;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.KeySetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.setting.StringSetting;
import dev.femboyware.util.ChatUtil;
import dev.femboyware.util.Webhook;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * One key copies the coordinates you're looking at (up to 512 blocks) or standing on, as "X Y Z",
 * "X, Y, Z" or a /tp command, and can post them to a Discord webhook. Ported from the Anubis
 * client's Coord Snapper (GPL-3.0, by 4ldenz, https://github.com/4ldenz/Anubis-client).
 */
public class CoordSnapperModule extends Module {
	public enum Target {
		LOOKED_AT, OWN_POSITION
	}

	public enum Format {
		SPACES, COMMAS, TP_COMMAND
	}

	private final KeySetting key = add(new KeySetting("Key", "Press to copy", -1));
	private final EnumSetting<Target> target = add(new EnumSetting<>("Target", "The block you look at, or where you stand", Target.LOOKED_AT));
	private final NumberSetting range = add(new NumberSetting("Range", "How far it looks", 256, 16, 512, 1));
	private final BoolSetting fluids = add(new BoolSetting("Stop At Fluids", "Water and lava count as blocks", false));
	private final EnumSetting<Format> format = add(new EnumSetting<>("Format", "X Y Z, X, Y, Z or /tp X Y Z", Format.SPACES));
	private final BoolSetting dimension = add(new BoolSetting("Include Dimension", "Add (overworld), (nether)... after them", true));
	private final BoolSetting confirm = add(new BoolSetting("Confirm", "Say in chat what was copied", true));
	private final BoolSetting webhook = add(new BoolSetting("Webhook", "Also post them to a Discord webhook", false));
	private final StringSetting webhookUrl = add(new StringSetting("Webhook URL", "Your Discord webhook link", ""));

	public CoordSnapperModule() {
		super("Coord Snapper", "Copies the coordinates you look at or stand on with one key", Category.MISC);
	}

	@Override
	public void onKey(int pressed) {
		if (!key.is(pressed) || key.get() == -1 || mc.player == null || mc.level == null) return;
		BlockPos pos;
		if (target.is(Target.OWN_POSITION)) pos = mc.player.blockPosition();
		else {
			HitResult hit = mc.player.pick(range.get(), 1f, fluids.get());
			if (!(hit instanceof BlockHitResult block) || hit.getType() != HitResult.Type.BLOCK) {
				ChatUtil.info("Nothing copied: no loaded block within " + range.get().intValue() + " blocks of your aim");
				return;
			}
			pos = block.getBlockPos();
			if (format.is(Format.TP_COMMAND)) pos = pos.above();
		}
		String dim = mc.level.dimension().identifier().getPath().replace("the_", "");
		String text = switch (format.get()) {
			case SPACES -> pos.getX() + " " + pos.getY() + " " + pos.getZ();
			case COMMAS -> pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
			case TP_COMMAND -> "/tp " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
		};
		if (dimension.get() && !format.is(Format.TP_COMMAND)) text += " (" + dim + ")";
		mc.keyboardHandler.setClipboard(text);
		if (confirm.get()) ChatUtil.info("Copied " + text);
		if (webhook.get()) {
			if (!Webhook.valid(webhookUrl.get())) {
				ChatUtil.info("Coord Snapper: the webhook URL isn't set or isn't a link");
				return;
			}
			var server = mc.getCurrentServer();
			Webhook.Embed embed = new Webhook.Embed("📍 Coordinates", "**Coordinates:** `" + String.format(Locale.ROOT, "%d %d %d", pos.getX(), pos.getY(), pos.getZ())
				+ "`\n**Dimension:** " + dim + "\n**Server:** " + Webhook.escape(server != null && server.ip != null ? server.ip : "Singleplayer")
				+ "\n**Time:** <t:" + System.currentTimeMillis() / 1000 + ":R>", 0x3478F6);
			Webhook.send(webhookUrl.get(), embed, sent -> {
				if (!sent) ChatUtil.info("Coord Snapper: webhook failed");
				else if (confirm.get()) ChatUtil.info("Sent to webhook");
			});
		}
	}
}
