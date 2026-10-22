package dev.femboyware.module.utility;

import dev.femboyware.mixin.MinecraftClientAccessor;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.setting.StringSetting;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.HashSet;
import java.util.Set;

/**
 * Removes the delay between right clicks.
 * Ported from Meteor Client's FastUse (https://github.com/MeteorDevelopment/meteor-client), GPL-3.0.
 */
public class FastUseModule extends Module {
	public enum Mode { ALL, SOME }

	private final EnumSetting<Mode> mode = add(new EnumSetting<>("Mode", "All items, or only the ones listed", Mode.ALL));
	private final StringSetting items = add(new StringSetting("Items", "Comma separated item ids for Some mode", "experience_bottle, snowball, egg")).visibleWhen(() -> mode.is(Mode.SOME));
	private final BoolSetting blocks = add(new BoolSetting("Blocks", "Also fast place blocks in Some mode", false)).visibleWhen(() -> mode.is(Mode.SOME));
	private final NumberSetting cooldown = add(new NumberSetting("Cooldown", "Ticks between uses, vanilla is 4", 0, 0, 4, 1));

	private Set<Item> listed = Set.of();
	private String parsedFrom;

	public FastUseModule() {
		super("Fast Use", "Uses items without the right click delay", Category.PLAYER);
	}

	@Override
	public void onTick() {
		if (!items.get().equals(parsedFrom)) {
			listed = parseItems(items.get());
			parsedFrom = items.get();
		}
		if (!applies(mc.player.getMainHandStack()) && !applies(mc.player.getOffHandStack())) return;
		MinecraftClientAccessor client = (MinecraftClientAccessor) mc;
		if (client.femboyware$getItemUseCooldown() > cooldown.getInt()) client.femboyware$setItemUseCooldown(cooldown.getInt());
	}

	private boolean applies(ItemStack stack) {
		if (stack.isEmpty()) return false;
		if (mode.is(Mode.ALL)) return true;
		return (blocks.get() && stack.getItem() instanceof BlockItem) || listed.contains(stack.getItem());
	}

	private static Set<Item> parseItems(String text) {
		Set<Item> result = new HashSet<>();
		for (String part : text.split(",")) {
			if (part.isBlank()) continue;
			Identifier id = Identifier.tryParse(part.trim().toLowerCase());
			if (id != null) Registries.ITEM.getOptionalValue(id).ifPresent(result::add);
		}
		return result;
	}
}
