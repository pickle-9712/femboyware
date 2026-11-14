package dev.femboyware.module.combat;

import dev.femboyware.event.PacketEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.NumberSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * After you place an end crystal, hits it the moment the server spawns it, instead of waiting for
 * your next click: the place-and-pop combo without Auto Crystal.
 */
public class CrystalTapModule extends Module {
	private final NumberSetting delay = add(new NumberSetting("Delay", "Ticks to wait after it appears before hitting", 0, 0, 5, 1));
	private final NumberSetting window = add(new NumberSetting("Window", "Most milliseconds after placing to still count a crystal as yours", 600, 100, 2000, 50));

	/** Where the crystal you just placed will stand (the top of the block you used it on). */
	private volatile Vec3 placedAt;
	private volatile long placedTime;
	private int pendingId = -1;
	private int pendingWait;

	public CrystalTapModule() {
		super("Crystal Tap", "Hit a crystal the moment the one you placed appears", Category.ATTACK);
	}

	@Override
	protected void onDisable() {
		placedAt = null;
		pendingId = -1;
	}

	@Override
	public void onPacketSend(PacketEvent event) {
		if (!(event.getPacket() instanceof ServerboundUseItemOnPacket use) || mc.player == null) return;
		//? if >=26.3 {
		/*InteractionHand hand = use.hand();
		BlockPos base = use.hitResult().getBlockPos();
		*///?} else {
		InteractionHand hand = use.getHand();
		BlockPos base = use.getHitResult().getBlockPos();
		//?}
		if (!mc.player.getItemInHand(hand).is(Items.END_CRYSTAL)) return;
		placedAt = Vec3.atBottomCenterOf(base.above());
		placedTime = System.currentTimeMillis();
	}

	@Override
	public void onPacketReceive(PacketEvent event) {
		if (!(event.getPacket() instanceof ClientboundAddEntityPacket add) || add.getType() != EntityType.END_CRYSTAL) return;
		Vec3 at = placedAt;
		if (at == null || System.currentTimeMillis() - placedTime > window.getInt()) return;
		if (at.distanceToSqr(add.getX(), add.getY(), add.getZ()) > 1.0) return;
		placedAt = null;
		int id = add.getId();
		// Queued twice: the game's own handling of this packet is queued just after the first, and
		// the crystal only exists once that has run
		mc.execute(() -> mc.execute(() -> {
			pendingId = id;
			pendingWait = delay.getInt();
			if (pendingWait == 0) tryHit();
		}));
	}

	@Override
	public void onTick() {
		if (pendingId < 0) return;
		if (pendingWait > 0 && --pendingWait > 0) return;
		tryHit();
	}

	private void tryHit() {
		int id = pendingId;
		pendingId = -1;
		if (nullCheck()) return;
		Entity crystal = mc.level.getEntity(id);
		if (crystal == null || crystal.isRemoved()) return;
		if (mc.player.getEyePosition().distanceTo(crystal.position()) > mc.player.entityInteractionRange() + 1) return;
		mc.gameMode.attack(mc.player, crystal);
		mc.player.swing(InteractionHand.MAIN_HAND);
	}
}
