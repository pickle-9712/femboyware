package dev.femboyware.module.combat;

import dev.femboyware.FemboyWare;
import dev.femboyware.event.PacketEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.InventoryUtil;
import dev.femboyware.util.KeyUtil;
import dev.femboyware.util.Rotation;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.Vec3;

/**
 * Place a TNT minecart and it swaps to your Flame bow, draws it while aiming at the cart, and lets
 * go, so the burning arrow sets the cart off. Then your slot comes back.
 */
public class AutoCartModule extends Module {
	private static final int ROTATION_PRIORITY = 100;

	private final NumberSetting chargeTicks = add(new NumberSetting("Charge", "Ticks to draw the bow before letting go", 10, 3, 20, 1));
	private final NumberSetting window = add(new NumberSetting("Window", "Most milliseconds after placing to still count a cart as yours", 800, 100, 3000, 50));

	private enum Stage {
		IDLE,
		CHARGING,
		RESTORE
	}

	private volatile Vec3 placedAt;
	private volatile long placedTime;
	private Stage stage = Stage.IDLE;
	private int cartId = -1;
	private int previousSlot = -1;
	private int ticks;

	public AutoCartModule() {
		super("Auto Cart", "Release a drawn Flame bow into a freshly placed TNT cart", Category.ATTACK);
	}

	@Override
	protected void onDisable() {
		if (stage != Stage.IDLE && !nullCheck()) restore();
		placedAt = null;
	}

	@Override
	public void onPacketSend(PacketEvent event) {
		if (!(event.getPacket() instanceof ServerboundUseItemOnPacket use) || mc.player == null) return;
		//? if >=26.3 {
		/*net.minecraft.world.InteractionHand hand = use.hand();
		net.minecraft.world.phys.BlockHitResult hit = use.hitResult();
		*///?} else {
		net.minecraft.world.InteractionHand hand = use.getHand();
		net.minecraft.world.phys.BlockHitResult hit = use.getHitResult();
		//?}
		if (!mc.player.getItemInHand(hand).is(Items.TNT_MINECART)) return;
		placedAt = Vec3.atCenterOf(hit.getBlockPos());
		placedTime = System.currentTimeMillis();
	}

	@Override
	public void onPacketReceive(PacketEvent event) {
		if (!(event.getPacket() instanceof ClientboundAddEntityPacket add) || add.getType() != EntityType.TNT_MINECART) return;
		Vec3 at = placedAt;
		if (at == null || System.currentTimeMillis() - placedTime > window.getInt()) return;
		if (at.distanceToSqr(add.getX(), add.getY(), add.getZ()) > 4) return;
		placedAt = null;
		int id = add.getId();
		mc.execute(() -> mc.execute(() -> start(id)));
	}

	private void start(int id) {
		if (nullCheck() || stage != Stage.IDLE) return;
		int bow = InventoryUtil.findHotbar(AutoCartModule::isFlameBow);
		if (bow < 0 || !hasArrows(mc.player.getInventory().getItem(bow))) return;
		cartId = id;
		previousSlot = mc.player.getInventory().getSelectedSlot();
		InventoryUtil.select(bow);
		mc.options.keyUse.setDown(true);
		stage = Stage.CHARGING;
		ticks = 0;
	}

	@Override
	public void onPreMotion() {
		if (stage != Stage.CHARGING) return;
		Entity cart = mc.level.getEntity(cartId);
		if (cart == null) return;
		Vec3 eyes = mc.player.getEyePosition();
		Vec3 aim = cart.getBoundingBox().getCenter();
		// Lift the aim by how far the arrow drops on the way, for this much draw
		float t = Math.min(1, chargeTicks.getInt() / 20f);
		double speed = Math.max(0.3, (t * t + t * 2) / 3 * 3);
		double flight = eyes.distanceTo(aim) / speed;
		aim = aim.add(0, 0.025 * flight * flight, 0);
		FemboyWare.getRotations().request(Rotation.between(eyes, aim), ROTATION_PRIORITY, 180f, false);
	}

	@Override
	public void onTick() {
		switch (stage) {
			case IDLE -> {
			}
			case CHARGING -> {
				Entity cart = mc.level.getEntity(cartId);
				if (cart == null || !isFlameBow(mc.player.getMainHandItem())) {
					restore();
					return;
				}
				// Let go after the draw; the game releases the bow when use is no longer held
				if (++ticks > chargeTicks.getInt()) {
					KeyUtil.sync(mc.options.keyUse);
					stage = Stage.RESTORE;
					ticks = 0;
				} else {
					mc.options.keyUse.setDown(true);
				}
			}
			case RESTORE -> {
				if (++ticks >= 2) restore();
			}
		}
	}

	private void restore() {
		KeyUtil.sync(mc.options.keyUse);
		if (previousSlot >= 0) InventoryUtil.select(previousSlot);
		previousSlot = -1;
		cartId = -1;
		stage = Stage.IDLE;
	}

	private static boolean isFlameBow(ItemStack stack) {
		return stack.is(Items.BOW) && InventoryUtil.getEnchantmentLevel(stack, Enchantments.FLAME) > 0;
	}

	private boolean hasArrows(ItemStack bow) {
		if (mc.player.getAbilities().instabuild || InventoryUtil.getEnchantmentLevel(bow, Enchantments.INFINITY) > 0) return true;
		return InventoryUtil.count(stack -> stack.is(ItemTags.ARROWS)) > 0;
	}
}
