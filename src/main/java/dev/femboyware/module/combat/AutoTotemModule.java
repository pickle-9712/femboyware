package dev.femboyware.module.combat;

import dev.femboyware.FemboyWare;
import dev.femboyware.event.PacketEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.DamageCalculator;
import dev.femboyware.util.InventoryUtil;
import dev.femboyware.util.Timer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.util.math.Box;

/**
 * Keeps a totem in your offhand. Smart mode holds crystals instead and only falls back to a totem
 * when you're in danger: low health, a crystal nearby that could kill you, a long fall or flying.
 */
public class AutoTotemModule extends Module {
	/** Entity status the server sends when a totem of undying pops. */
	private static final byte TOTEM_POP = 35;

	public enum Mode { TOTEM, SMART }

	private final EnumSetting<Mode> mode = add(new EnumSetting<>("Mode", "Totem always holds a totem, Smart holds crystals until you're in danger", Mode.SMART));
	private final NumberSetting health = add(new NumberSetting("Health", "Hold a totem at or below this much health plus absorption", 14, 1, 36, 0.5)).visibleWhen(() -> mode.is(Mode.SMART));
	private final BoolSetting crystalCheck = add(new BoolSetting("Crystal Check", "Hold a totem while a nearby crystal could kill you", true)).visibleWhen(() -> mode.is(Mode.SMART));
	private final BoolSetting fallCheck = add(new BoolSetting("Fall Check", "Hold a totem while falling far", true)).visibleWhen(() -> mode.is(Mode.SMART));
	private final BoolSetting elytraCheck = add(new BoolSetting("Elytra Check", "Hold a totem while flying with an elytra", true)).visibleWhen(() -> mode.is(Mode.SMART));
	private final BoolSetting instantRefill = add(new BoolSetting("Instant Refill", "Replace a popped totem the moment the server says it popped, ignoring the delay", true));
	private final NumberSetting delay = add(new NumberSetting("Delay", "Milliseconds between swaps", 0, 0, 500, 10));

	private final Timer timer = new Timer();

	public AutoTotemModule() {
		super("Auto Totem", "Keeps a totem in your offhand, or crystals until you're in danger", Category.PROTECT);
	}

	@Override
	public String getInfo() {
		if (nullCheck()) return null;
		return String.valueOf(InventoryUtil.count(stack -> stack.isOf(Items.TOTEM_OF_UNDYING)));
	}

	@Override
	protected void onEnable() {
		// Both manage the offhand and would fight over it
		FemboyWare.getModules().get(OffhandModule.class).setEnabled(false);
	}

	@Override
	public void onTick() {
		if (!canSwap() || !timer.passed(delay.get())) return;
		Item wanted = desired();
		if (mc.player.getOffHandStack().isOf(wanted)) return;

		int slot = find(wanted);
		// Out of crystals: a totem is always the safe fallback
		if (slot < 0 && wanted != Items.TOTEM_OF_UNDYING && !mc.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) {
			slot = find(Items.TOTEM_OF_UNDYING);
		}
		if (slot < 0) return;
		InventoryUtil.swapWithOffhand(slot);
		timer.reset();
	}

	@Override
	public void onPacketReceive(PacketEvent event) {
		if (!instantRefill.get() || !(event.getPacket() instanceof EntityStatusS2CPacket packet) || packet.getStatus() != TOTEM_POP) return;
		mc.execute(() -> {
			if (!isEnabled() || nullCheck() || packet.getEntity(mc.world) != mc.player || !canSwap()) return;
			// The offhand still shows the used totem until the inventory update arrives, so swap regardless
			int slot = find(Items.TOTEM_OF_UNDYING);
			if (slot < 0) return;
			InventoryUtil.swapWithOffhand(slot);
			timer.reset();
		});
	}

	private boolean canSwap() {
		return mc.player.currentScreenHandler == mc.player.playerScreenHandler && !FemboyWare.getModules().get(QuiverModule.class).isBusy();
	}

	/** Prefers the main inventory, so hotbar totems stay put for Mainhand Totem. */
	private int find(Item item) {
		int slot = InventoryUtil.findMain(stack -> stack.isOf(item));
		return slot >= 0 ? slot : InventoryUtil.findInventory(stack -> stack.isOf(item));
	}

	private Item desired() {
		if (mode.is(Mode.TOTEM) || inDanger()) return Items.TOTEM_OF_UNDYING;
		return Items.END_CRYSTAL;
	}

	private boolean inDanger() {
		float hp = mc.player.getHealth() + mc.player.getAbsorptionAmount();
		if (hp <= health.get()) return true;
		if (elytraCheck.get() && mc.player.isGliding()) return true;
		if (fallCheck.get() && mc.player.fallDistance > 10 && !mc.player.isGliding()) return true;
		if (!crystalCheck.get()) return false;
		for (Entity entity : mc.world.getOtherEntities(mc.player, new Box(mc.player.getBlockPos()).expand(12))) {
			if (!(entity instanceof EndCrystalEntity crystal) || !crystal.isAlive()) continue;
			if (DamageCalculator.crystalDamage(mc.player, crystal.getEntityPos(), false, false) >= hp - 0.5f) return true;
		}
		return false;
	}
}
