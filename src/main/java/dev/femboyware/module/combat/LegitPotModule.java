package dev.femboyware.module.combat;

import dev.femboyware.FemboyWare;
import dev.femboyware.mixin.HandledScreenAccessor;
import dev.femboyware.mixin.MinecraftClientAccessor;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.InventoryUtil;
import dev.femboyware.util.KeyUtil;
import dev.femboyware.util.MouseTurner;
import dev.femboyware.util.TargetUtil;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectCategory;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.util.Random;
import java.util.Set;

/**
 * Keeps you alive like a nethpot player: when you're low on health, or strength, speed or fire
 * resistance runs out, it flicks the camera down, switches to the potion, throws it at your feet,
 * switches back and looks up again. It also eats your best food when low, puts a totem in your
 * offhand when you're about to die (and the food that was there back once you're healthy again), and restocks the hotbar with healing potions when nobody's near.
 *
 * <p>All real inputs: the camera turns by whole mouse steps, switches are number keys, throwing and
 * eating are right clicks, and items move between inventory and hotbar by opening the inventory and
 * pressing a number key (or the offhand key) over them. Legit Aura stops fighting while any of it happens.
 */
public class LegitPotModule extends Module {
	private enum Step { IDLE, OPEN, SWAP, CLOSE, DOWN, THROW, EAT, BACK }

	/** What the inventory is opened for. */
	private enum Job { FETCH, REFILL, TOTEM, FOOD_BACK }

	/** The offhand's button in a swap click, the same as pressing the offhand key over a slot. */
	private static final int OFFHAND_BUTTON = 40;

	/** Food never worth eating mid-fight: it poisons, starves or teleports you. */
	private static final Set<Item> BAD_FOOD = Set.of(Items.CHORUS_FRUIT, Items.ROTTEN_FLESH, Items.SPIDER_EYE, Items.POISONOUS_POTATO,
		Items.PUFFERFISH, Items.SUSPICIOUS_STEW, Items.CHICKEN);

	private final NumberSetting health = add(new NumberSetting("Health", "Splash a healing potion at or below this much health (20 is full)", 10, 1, 20, 1));
	private final BoolSetting healing = add(new BoolSetting("Healing", "Splash healing potions when low", true));
	private final BoolSetting strength = add(new BoolSetting("Strength", "Splash strength when it runs out", true));
	private final BoolSetting speed = add(new BoolSetting("Speed", "Splash swiftness when it runs out", true));
	private final BoolSetting fireResistance = add(new BoolSetting("Fire Resistance", "Splash fire resistance when it runs out", true));
	private final BoolSetting eat = add(new BoolSetting("Eat", "Stop fighting and eat your best food, from your offhand or hotbar, when low; golden apples first", true));
	private final NumberSetting eatHealth = add(new NumberSetting("Eat Health", "Eat at or below this much health", 14, 1, 20, 1)).visibleWhen(eat::get);
	private final NumberSetting eatSafeDistance = add(new NumberSetting("Eat Safe Distance", "Don't eat while a player this close is facing you, and stop if one turns towards you mid-bite", 6, 0, 20, 0.5)).visibleWhen(eat::get);
	private final BoolSetting totem = add(new BoolSetting("Totem", "Open your inventory and put a totem in your offhand when you're about to die", true));
	private final NumberSetting totemHealth = add(new NumberSetting("Totem Health", "Count as about to die at or below this much health", 6, 1, 20, 1)).visibleWhen(totem::get);
	private final NumberSetting swapBackHealth = add(new NumberSetting("Swap Back Health", "Put the food the totem replaced back in your offhand once you're above this much health again", 12, 1, 20, 1)).visibleWhen(totem::get);
	private final BoolSetting refill = add(new BoolSetting("Refill", "When nobody is close, open your inventory and move healing potions to the hotbar", true));
	private final NumberSetting hotbarPots = add(new NumberSetting("Hotbar Pots", "Keep this many healing potions in the hotbar", 3, 1, 9, 1)).visibleWhen(refill::get);
	private final NumberSetting safeDistance = add(new NumberSetting("Safe Distance", "Only refill while no player is closer than this", 10, 4, 32, 1)).visibleWhen(refill::get);
	private final NumberSetting flickSpeed = add(new NumberSetting("Flick Speed", "How quickly the camera turns down and back up", 16, 4, 30, 1));
	private final BoolSetting lookBack = add(new BoolSetting("Look Back", "Turn back up to where you were looking after throwing", true));
	private final BoolSetting fromInventory = add(new BoolSetting("From Inventory", "Open your inventory and move a potion to the hotbar when you need one and there's none there", true));
	private final NumberSetting clickDelay = add(new NumberSetting("Click Delay", "Ticks between each step in the inventory", 2, 1, 10, 1)).visibleWhen(() -> fromInventory.get() || refill.get() || totem.get());
	private final NumberSetting throwDelay = add(new NumberSetting("Throw Delay", "Ticks to wait after a throw before checking again, so the potion can land", 8, 4, 30, 1));
	private final NumberSetting randomDelay = add(new NumberSetting("Random Delay", "Up to this many extra ticks on every step, a different amount each time", 1, 0, 5, 1));

	private final Random random = new Random();
	private final MouseTurner mouse = new MouseTurner();
	private Step step = Step.IDLE;
	private Job job;
	private int wait;
	/** Ticks spent on the current step, so a step that can't finish gives up. */
	private int stepTicks;
	/** The potion's hotbar slot, and the inventory slot it's being fetched from. */
	private int potSlot;
	private int fetchSlot;
	private int previousSlot;
	private float previousPitch;
	/** How far down to look before throwing; a little different every time. */
	private float downPitch;
	/** Whether we're holding the use key to eat, and whether the eating has actually begun. */
	private boolean pressing;
	private boolean eating;
	/** The food being eaten. */
	private Item eatingItem;
	/** Food the totem pushed out of the offhand, to go back once we're safe again; null if none. */
	private Item pushedOut;
	private long lastFrame;
	private InventoryScreen screen;

	public LegitPotModule() {
		super("Legit Pot", "Splashes potions, eats golden apples and grabs a totem to keep you alive", Category.LEGIT);
	}

	@Override
	public String getInfo() {
		return Integer.toString(healingPots());
	}

	/** How many healing splash potions you're carrying. */
	public static int healingPots() {
		return InventoryUtil.count(stack -> splashOf(stack, StatusEffects.INSTANT_HEALTH));
	}

	/** Whether something is being thrown, eaten or moved right now; Legit Aura stops aiming and hitting meanwhile. */
	public boolean isBusy() {
		return isEnabled() && step != Step.IDLE;
	}

	/**
	 * Whether there's nothing left to do: not busy, nothing to splash or put back, and the hotbar
	 * restocked. Legit Aura only goes after far-off targets once this is true. Always true while off.
	 */
	public boolean isReady() {
		if (!isEnabled()) return true;
		if (step != Step.IDLE) return false;
		if (healing.get() && mc.player.getHealth() <= health.get() && have(StatusEffects.INSTANT_HEALTH)) return false;
		if (strength.get() && !mc.player.hasStatusEffect(StatusEffects.STRENGTH) && have(StatusEffects.STRENGTH)) return false;
		if (speed.get() && !mc.player.hasStatusEffect(StatusEffects.SPEED) && have(StatusEffects.SPEED)) return false;
		if (fireResistance.get() && !mc.player.hasStatusEffect(StatusEffects.FIRE_RESISTANCE) && have(StatusEffects.FIRE_RESISTANCE)) return false;
		return !needRefill() && !needFoodBack();
	}

	private boolean have(RegistryEntry<StatusEffect> effect) {
		return InventoryUtil.findInventory(stack -> splashOf(stack, effect)) >= 0;
	}

	@Override
	protected void onEnable() {
		step = Step.IDLE;
		wait = 0;
		lastFrame = 0;
		mouse.reset();
	}

	@Override
	protected void onDisable() {
		if (!nullCheck()) stopEating();
		step = Step.IDLE;
		screen = null;
	}

	@Override
	public void onTick() {
		if (wait > 0) {
			wait--;
			return;
		}
		stepTicks++;
		switch (step) {
			case IDLE -> start();
			case OPEN -> {
				screen = new InventoryScreen(mc.player);
				mc.setScreen(screen);
				next(Step.SWAP, clickDelay.getInt());
			}
			case SWAP -> {
				if (mc.currentScreen != screen) {
					finish(0);
					return;
				}
				swap();
			}
			case CLOSE -> {
				if (mc.currentScreen == screen) mc.player.closeHandledScreen();
				screen = null;
				finish(1);
			}
			case DOWN -> {
				if (stepTicks > 20) {
					finish(0);
					return;
				}
				if (mc.player.getPitch() < downPitch - 4) return;
				// Press the number key once the camera is down, then throw on a later tick like a hand would
				if (mc.player.getInventory().getSelectedSlot() != potSlot) {
					InventoryUtil.select(potSlot);
					next(Step.THROW, 1);
				} else next(Step.THROW, 0);
			}
			case THROW -> {
				if (!mc.player.getInventory().getStack(potSlot).isOf(Items.SPLASH_POTION) || mc.player.getInventory().getSelectedSlot() != potSlot || stepTicks > 20) {
					finish(0);
					return;
				}
				// Still lowering a shield; a right click now wouldn't throw
				if (mc.player.isUsingItem()) return;
				((MinecraftClientAccessor) mc).femboyware$rightClick();
				next(Step.BACK, 1);
			}
			case EAT -> eat();
			case BACK -> {
				if (mc.player.getInventory().getSelectedSlot() != previousSlot) InventoryUtil.select(previousSlot);
				// Legit Aura turns back onto its target on its own, so only look up when it isn't fighting
				boolean fighting = FemboyWare.getModules().get(LegitAuraModule.class).getTarget() != null;
				if (!lookBack.get() || fighting || Math.abs(mc.player.getPitch() - previousPitch) < 2 || stepTicks > 20) {
					finish(throwDelay.getInt());
				}
			}
		}
	}

	/** Starts whatever is most urgent: a totem, then healing, a golden apple, effects, and restocking last. */
	private void start() {
		// A raised shield is fine: Legit Aura lowers it as soon as we're busy
		if (mc.currentScreen != null || (mc.player.isUsingItem() && !mc.player.getActiveItem().contains(DataComponentTypes.BLOCKS_ATTACKS))) return;
		if (needTotem()) {
			openFor(Job.TOTEM);
			return;
		}
		if (needFoodBack()) {
			openFor(Job.FOOD_BACK);
			return;
		}
		if (healing.get() && mc.player.getHealth() <= health.get() && splash(StatusEffects.INSTANT_HEALTH)) return;
		if (eat.get() && mc.player.getHealth() <= eatHealth.get() && !threatened() && startEating()) return;
		if (strength.get() && !mc.player.hasStatusEffect(StatusEffects.STRENGTH) && splash(StatusEffects.STRENGTH)) return;
		if (speed.get() && !mc.player.hasStatusEffect(StatusEffects.SPEED) && splash(StatusEffects.SPEED)) return;
		if (fireResistance.get() && !mc.player.hasStatusEffect(StatusEffects.FIRE_RESISTANCE) && splash(StatusEffects.FIRE_RESISTANCE)) return;
		if (needRefill()) openFor(Job.REFILL);
	}

	// ---------------------------------------------------------------- potions

	/** Starts throwing a potion of {@code effect} from the hotbar, or fetching one from the inventory. False if there's none. */
	private boolean splash(RegistryEntry<StatusEffect> effect) {
		int slot = InventoryUtil.findHotbar(stack -> splashOf(stack, effect));
		if (slot >= 0) {
			potSlot = slot;
			remember();
			downPitch = 84 + random.nextFloat() * 6;
			mouse.reset();
			next(Step.DOWN, 0);
			return true;
		}
		if (!fromInventory.get()) return false;
		int stored = InventoryUtil.findMain(stack -> splashOf(stack, effect));
		int free = freeHotbarSlot();
		if (stored < 0 || free < 0) return false;
		fetchSlot = InventoryUtil.screenSlot(stored);
		potSlot = free;
		openFor(Job.FETCH);
		return true;
	}

	private boolean needRefill() {
		if (!refill.get() || !safe() || freeHotbarSlot() < 0) return false;
		return hotbarHealing() < hotbarPots.getInt() && InventoryUtil.findMain(LegitPotModule::healingPot) >= 0;
	}

	/** Whether no player is within Safe Distance, so there's time to stand in the inventory. */
	private boolean safe() {
		for (PlayerEntity player : mc.world.getPlayers()) {
			if (TargetUtil.isValid(player) && mc.player.distanceTo(player) < safeDistance.get()) return false;
		}
		return true;
	}

	private int hotbarHealing() {
		int count = 0;
		for (int slot = 0; slot < PlayerInventory.HOTBAR_SIZE; slot++) {
			if (healingPot(mc.player.getInventory().getStack(slot))) count++;
		}
		return count;
	}

	private static boolean healingPot(ItemStack stack) {
		return splashOf(stack, StatusEffects.INSTANT_HEALTH);
	}

	/** An empty hotbar slot to move a potion into, never the one in hand; -1 if the hotbar is full. */
	private int freeHotbarSlot() {
		int held = mc.player.getInventory().getSelectedSlot();
		for (int slot = 0; slot < PlayerInventory.HOTBAR_SIZE; slot++) {
			if (slot != held && mc.player.getInventory().getStack(slot).isEmpty()) return slot;
		}
		return -1;
	}

	/** Whether a player within Eat Safe Distance is facing us and could hit us while we eat. */
	private boolean threatened() {
		for (PlayerEntity player : mc.world.getPlayers()) {
			if (TargetUtil.isValid(player) && mc.player.distanceTo(player) < eatSafeDistance.get()
				&& TargetUtil.isFacing(player, mc.player.getEyePos())) return true;
		}
		return false;
	}

	/** Whether {@code stack} is a splash potion giving {@code effect}, with nothing harmful mixed in. */
	private static boolean splashOf(ItemStack stack, RegistryEntry<StatusEffect> effect) {
		if (!stack.isOf(Items.SPLASH_POTION)) return false;
		PotionContentsComponent contents = stack.get(DataComponentTypes.POTION_CONTENTS);
		if (contents == null) return false;
		boolean found = false;
		for (StatusEffectInstance instance : contents.getEffects()) {
			if (instance.getEffectType().value().getCategory() == StatusEffectCategory.HARMFUL) return false;
			if (instance.getEffectType().equals(effect)) found = true;
		}
		return found;
	}

	// ---------------------------------------------------------------- food

	/**
	 * Eats the best food in the offhand or hotbar. The offhand is used when its food is at least as
	 * good and the main hand has nothing that would be used first; otherwise it switches to the
	 * hotbar's best. False if there's nothing worth eating.
	 */
	private boolean startEating() {
		ItemStack main = mc.player.getMainHandStack();
		boolean offhandUsable = main.isEmpty() || main.isIn(ItemTags.SWORDS) || main.isIn(ItemTags.AXES);
		float offhandScore = offhandUsable ? foodScore(mc.player.getOffHandStack()) : 0;
		int slot = -1;
		float best = 0;
		for (int i = 0; i < PlayerInventory.HOTBAR_SIZE; i++) {
			float score = foodScore(mc.player.getInventory().getStack(i));
			if (score > best) {
				best = score;
				slot = i;
			}
		}
		boolean offhand = offhandScore > 0 && offhandScore >= best;
		if (!offhand && slot < 0) return false;
		if (offhand) slot = -1;
		eatingItem = (offhand ? mc.player.getOffHandStack() : mc.player.getInventory().getStack(slot)).getItem();
		remember();
		eating = false;
		if (slot >= 0 && slot != previousSlot) {
			InventoryUtil.select(slot);
			next(Step.EAT, 1);
		} else next(Step.EAT, 0);
		return true;
	}

	/**
	 * How good {@code stack} is to eat right now, higher is better; 0 if it can't or shouldn't be.
	 * Golden apples beat everything since they heal straight away, unless one is already healing us;
	 * other food ranks by how much hunger and saturation it gives.
	 */
	private float foodScore(ItemStack stack) {
		FoodComponent food = stack.get(DataComponentTypes.FOOD);
		if (food == null || BAD_FOOD.contains(stack.getItem()) || !mc.player.canConsume(food.canAlwaysEat())) return 0;
		if (stack.isOf(Items.ENCHANTED_GOLDEN_APPLE) || stack.isOf(Items.GOLDEN_APPLE)) {
			if (mc.player.hasStatusEffect(StatusEffects.REGENERATION)) return 0;
			return stack.isOf(Items.ENCHANTED_GOLDEN_APPLE) ? 1000 : 500;
		}
		return food.nutrition() + food.saturation();
	}

	/** Holds right click until the food is eaten, then lets go and switches back. */
	private void eat() {
		if (threatened()) {
			done();
			return;
		}
		if (mc.player.isUsingItem() && !mc.player.getActiveItem().isOf(eatingItem)) {
			// Let go of the shield before eating can start
			mc.options.useKey.setPressed(false);
			if (stepTicks > 20) done();
			return;
		}
		boolean holding = mc.player.getMainHandStack().isOf(eatingItem) || mc.player.getOffHandStack().isOf(eatingItem);
		if (mc.player.isUsingItem()) eating = true;
		else if (eating || !holding) {
			done();
			return;
		}
		if (stepTicks > 50) {
			done();
			return;
		}
		mc.options.useKey.setPressed(true);
		pressing = true;
	}

	private void done() {
		stopEating();
		next(Step.BACK, 0);
	}

	private void stopEating() {
		if (pressing) KeyUtil.sync(mc.options.useKey);
		pressing = false;
		eating = false;
	}

	// ---------------------------------------------------------------- totems

	/** About to die with no totem in either hand, and one in the inventory. Leaves it to Legit Totem when that's on. */
	private boolean needTotem() {
		if (!totem.get() || mc.player.getHealth() > totemHealth.get() || FemboyWare.isEnabled(LegitTotemModule.class)) return false;
		if (mc.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING) || mc.player.getMainHandStack().isOf(Items.TOTEM_OF_UNDYING)) return false;
		return InventoryUtil.findInventory(stack -> stack.isOf(Items.TOTEM_OF_UNDYING)) >= 0;
	}

	/** Healthy again after a totem took some food's place in the offhand, and that food is still around. */
	private boolean needFoodBack() {
		if (pushedOut == null) return false;
		if (!totem.get() || mc.player.getOffHandStack().isOf(pushedOut)) {
			pushedOut = null;
			return false;
		}
		return mc.player.getHealth() > swapBackHealth.get() && InventoryUtil.findInventory(stack -> stack.isOf(pushedOut)) >= 0;
	}

	// ---------------------------------------------------------------- inventory

	private void openFor(Job reason) {
		job = reason;
		next(Step.OPEN, 0);
	}

	/** One move in the open inventory: hover the item and press a number key, or the offhand key, over it. */
	private void swap() {
		switch (job) {
			case FETCH -> {
				hover(fetchSlot);
				InventoryUtil.click(fetchSlot, potSlot, SlotActionType.SWAP);
				next(Step.CLOSE, clickDelay.getInt());
			}
			case TOTEM -> {
				int slot = InventoryUtil.findInventory(stack -> stack.isOf(Items.TOTEM_OF_UNDYING));
				if (slot >= 0) {
					ItemStack offhand = mc.player.getOffHandStack();
					if (offhand.contains(DataComponentTypes.FOOD)) pushedOut = offhand.getItem();
					hover(InventoryUtil.screenSlot(slot));
					InventoryUtil.click(InventoryUtil.screenSlot(slot), OFFHAND_BUTTON, SlotActionType.SWAP);
				}
				next(Step.CLOSE, clickDelay.getInt());
			}
			case FOOD_BACK -> {
				// The totem goes back where the food was, ready for next time
				Item food = pushedOut;
				int slot = food == null ? -1 : InventoryUtil.findInventory(stack -> stack.isOf(food));
				if (slot >= 0) {
					hover(InventoryUtil.screenSlot(slot));
					InventoryUtil.click(InventoryUtil.screenSlot(slot), OFFHAND_BUTTON, SlotActionType.SWAP);
				}
				pushedOut = null;
				next(Step.CLOSE, clickDelay.getInt());
			}
			case REFILL -> {
				// One potion per step until the hotbar has enough, and stop as soon as someone comes close
				int stored = InventoryUtil.findMain(LegitPotModule::healingPot);
				int free = freeHotbarSlot();
				if (stored < 0 || free < 0 || hotbarHealing() >= hotbarPots.getInt() || !safe()) {
					next(Step.CLOSE, clickDelay.getInt());
					return;
				}
				hover(InventoryUtil.screenSlot(stored));
				InventoryUtil.click(InventoryUtil.screenSlot(stored), free, SlotActionType.SWAP);
				next(Step.SWAP, clickDelay.getInt());
			}
		}
	}

	/** Puts the real mouse cursor over {@code slotId}, so you can see what it grabs. */
	private void hover(int slotId) {
		if (screen == null) return;
		Slot slot = mc.player.playerScreenHandler.getSlot(slotId);
		HandledScreenAccessor accessor = (HandledScreenAccessor) screen;
		double scale = mc.getWindow().getScaleFactor();
		double x = (accessor.femboyware$getX() + slot.x + 8) * scale;
		double y = (accessor.femboyware$getY() + slot.y + 8) * scale;
		GLFW.glfwSetCursorPos(mc.getWindow().getHandle(), x, y);
	}

	// ---------------------------------------------------------------- steps

	/** Remembers the slot and where we looked, to go back to afterwards. */
	private void remember() {
		previousSlot = mc.player.getInventory().getSelectedSlot();
		previousPitch = mc.player.getPitch();
	}

	private void next(Step following, int ticks) {
		step = following;
		stepTicks = 0;
		wait = ticks + random.nextInt(randomDelay.getInt() + 1);
	}

	private void finish(int ticks) {
		step = Step.IDLE;
		stepTicks = 0;
		wait = ticks;
	}

	/** Turns the camera down for the throw and back up after it, easing in like a hand flicking the mouse. */
	@Override
	public void onFrame() {
		long now = System.nanoTime();
		float seconds = lastFrame == 0 ? 0 : Math.min(0.25f, (now - lastFrame) / 1e9f);
		lastFrame = now;
		if (seconds <= 0 || mc.currentScreen != null) return;
		float wanted;
		if (step == Step.DOWN || step == Step.THROW) wanted = downPitch;
		else if (step == Step.BACK && lookBack.get()) wanted = previousPitch;
		else return;
		float follow = 1 - (float) Math.exp(-flickSpeed.get() * seconds);
		float wobble = 0.85f + random.nextFloat() * 0.3f;
		mouse.turn(0, MathHelper.clamp(wanted - mc.player.getPitch(), -180, 180) * follow * wobble);
	}
}
