package dev.femboyware.module;

import dev.femboyware.FemboyWare;
import dev.femboyware.event.PacketEvent;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.client.ClickGuiModule;
import dev.femboyware.module.client.FakePlayerModule;
import dev.femboyware.module.client.HudModule;
import dev.femboyware.module.client.MoveFixModule;
import dev.femboyware.module.combat.AuraModule;
import dev.femboyware.module.combat.AutoBasePlaceModule;
import dev.femboyware.module.combat.AutoCrystalModule;
import dev.femboyware.module.combat.AutoDripstoneModule;
import dev.femboyware.module.combat.AutoGappleModule;
import dev.femboyware.module.combat.AutoMineModule;
import dev.femboyware.module.combat.AutoNetheriteScrapModule;
import dev.femboyware.module.combat.AutoSweetBerriesModule;
import dev.femboyware.module.combat.AutoTotemModule;
import dev.femboyware.module.combat.AutoWebModule;
import dev.femboyware.module.combat.BlockerModule;
import dev.femboyware.module.combat.BreachSwapModule;
import dev.femboyware.module.combat.BurrowModule;
import dev.femboyware.module.combat.CriticalsModule;
import dev.femboyware.module.combat.HoleFillerModule;
import dev.femboyware.module.combat.MaceKillerModule;
import dev.femboyware.module.combat.MainhandTotemModule;
import dev.femboyware.module.combat.MoreKnockbackModule;
import dev.femboyware.module.combat.OffhandModule;
import dev.femboyware.module.combat.QuiverModule;
import dev.femboyware.module.combat.SelfTrapModule;
import dev.femboyware.module.combat.SurroundModule;
import dev.femboyware.module.combat.TrapModule;
import dev.femboyware.module.movement.AirJumpModule;
import dev.femboyware.module.movement.AntiKnockBackModule;
import dev.femboyware.module.movement.AntiVoidModule;
import dev.femboyware.module.movement.AntiWebModule;
import dev.femboyware.module.movement.AutoWalkModule;
import dev.femboyware.module.movement.AvoidModule;
import dev.femboyware.module.movement.BlinkModule;
import dev.femboyware.module.movement.BoatFlyModule;
import dev.femboyware.module.movement.ElytraBoostModule;
import dev.femboyware.module.movement.ElytraFreezeModule;
import dev.femboyware.module.movement.ElytraPlusModule;
import dev.femboyware.module.movement.ElytraRecastModule;
import dev.femboyware.module.movement.ElytraTargetModule;
import dev.femboyware.module.movement.EntitySpeedModule;
import dev.femboyware.module.movement.FastClimbModule;
import dev.femboyware.module.movement.FlightModule;
import dev.femboyware.module.movement.GuiMoveModule;
import dev.femboyware.module.movement.HighJumpModule;
import dev.femboyware.module.movement.HoleAnchorModule;
import dev.femboyware.module.movement.InfiniteElytraModule;
import dev.femboyware.module.movement.JesusModule;
import dev.femboyware.module.movement.LegitStrafeModule;
import dev.femboyware.module.movement.LevitationControlModule;
import dev.femboyware.module.movement.LongJumpModule;
import dev.femboyware.module.movement.MaceElytraTargetModule;
import dev.femboyware.module.movement.NoFallModule;
import dev.femboyware.module.movement.NoPushModule;
import dev.femboyware.module.movement.NoSlowModule;
import dev.femboyware.module.movement.ParkourModule;
import dev.femboyware.module.movement.PhaseModule;
import dev.femboyware.module.movement.ReverseStepModule;
import dev.femboyware.module.movement.SafeWalkModule;
import dev.femboyware.module.movement.ScaffoldModule;
import dev.femboyware.module.movement.SneakModule;
import dev.femboyware.module.movement.SpeedModule;
import dev.femboyware.module.movement.SpiderModule;
import dev.femboyware.module.movement.SprintModule;
import dev.femboyware.module.movement.StepModule;
import dev.femboyware.module.movement.TimerModule;
import dev.femboyware.module.movement.TridentBoostModule;
import dev.femboyware.module.movement.WaterSpeedModule;
import dev.femboyware.module.movement.WindHopModule;
import dev.femboyware.module.render.AspectModule;
import dev.femboyware.module.render.BaseEspModule;
import dev.femboyware.module.render.BetterTabModule;
import dev.femboyware.module.render.BlockEspModule;
import dev.femboyware.module.render.BreadcrumbsModule;
import dev.femboyware.module.render.CrystalChamsModule;
import dev.femboyware.module.render.DamageParticlesModule;
import dev.femboyware.module.render.DamageTintModule;
import dev.femboyware.module.render.DismembermentModule;
import dev.femboyware.module.render.EspModule;
import dev.femboyware.module.render.FreeLookModule;
import dev.femboyware.module.render.FullBrightModule;
import dev.femboyware.module.render.GeodeEspModule;
import dev.femboyware.module.render.HandChamsModule;
import dev.femboyware.module.render.HitFxModule;
import dev.femboyware.module.render.HoleEspModule;
import dev.femboyware.module.render.ItemPhysicsModule;
import dev.femboyware.module.render.KelpEspModule;
import dev.femboyware.module.render.LogoutSpotsModule;
import dev.femboyware.module.render.NametagsModule;
import dev.femboyware.module.render.NoCameraClipModule;
import dev.femboyware.module.render.ParticlesModule;
import dev.femboyware.module.render.PopChamsModule;
import dev.femboyware.module.render.StorageEspModule;
import dev.femboyware.module.render.SwingAnimationsModule;
import dev.femboyware.module.render.ThrowPetardModule;
import dev.femboyware.module.render.TimeChangerModule;
import dev.femboyware.module.render.TotemAnimationModule;
import dev.femboyware.module.render.TracersModule;
import dev.femboyware.module.render.TrajectoriesModule;
import dev.femboyware.module.render.ViewModelModule;
import dev.femboyware.module.render.WorldRendererModule;
import dev.femboyware.module.utility.AirPlaceModule;
import dev.femboyware.module.utility.AntiAfkModule;
import dev.femboyware.module.utility.AutoArmorModule;
import dev.femboyware.module.utility.AutoBoneMealModule;
import dev.femboyware.module.utility.AutoClickerModule;
import dev.femboyware.module.utility.AutoEatModule;
import dev.femboyware.module.utility.AutoFishModule;
import dev.femboyware.module.utility.AutoLandModule;
import dev.femboyware.module.utility.AutoMendModule;
import dev.femboyware.module.utility.AutoReconnectModule;
import dev.femboyware.module.utility.AutoReplenishModule;
import dev.femboyware.module.utility.AutoRespawnModule;
import dev.femboyware.module.utility.AutoShearModule;
import dev.femboyware.module.utility.AutoSignModule;
import dev.femboyware.module.utility.AutoToolModule;
import dev.femboyware.module.utility.FastUseModule;
import dev.femboyware.module.utility.FreeCamModule;
import dev.femboyware.module.utility.FtHelperModule;
import dev.femboyware.module.utility.LiquidInteractModule;
import dev.femboyware.module.utility.MiddleClickExtraModule;
import dev.femboyware.module.utility.NameProtectModule;
import dev.femboyware.module.utility.NoMiningTraceModule;
import dev.femboyware.module.utility.NoRenderModule;
import dev.femboyware.module.utility.NoRotateModule;
import dev.femboyware.module.utility.NukerModule;
import dev.femboyware.module.utility.PacketMineModule;
import dev.femboyware.module.utility.PearlChaserModule;
import dev.femboyware.module.utility.PotionSaverModule;
import dev.femboyware.module.utility.ReachModule;
import dev.femboyware.module.utility.SoilRipperModule;
import dev.femboyware.module.utility.SpeedMineModule;
import dev.femboyware.module.utility.TpsSyncModule;
import dev.femboyware.module.utility.ZoomModule;
import net.minecraft.block.BlockState;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class ModuleManager {
	private final List<Module> modules = new ArrayList<>();
	private final Map<Class<? extends Module>, Module> byType = new HashMap<>();

	public ModuleManager() {
		// Pvp: Attack
		register(new AuraModule());
		register(new AutoBasePlaceModule());
		register(new AutoCrystalModule());
		register(new AutoDripstoneModule());
		register(new AutoMineModule());
		register(new AutoNetheriteScrapModule());
		register(new AutoSweetBerriesModule());
		register(new AutoWebModule());
		register(new BreachSwapModule());
		register(new CriticalsModule());
		register(new HoleFillerModule());
		register(new MaceKillerModule());
		register(new MoreKnockbackModule());
		register(new TrapModule());
		// Pvp: Protect
		register(new AutoGappleModule());
		register(new AutoTotemModule());
		register(new BlockerModule());
		register(new BurrowModule());
		register(new MainhandTotemModule());
		register(new OffhandModule());
		register(new QuiverModule());
		register(new SelfTrapModule());
		register(new SurroundModule());
		// Movement: Basic
		register(new FastClimbModule());
		register(new ParkourModule());
		register(new SneakModule());
		register(new AutoWalkModule());
		register(new SafeWalkModule());
		register(new AntiKnockBackModule());
		register(new AvoidModule());
		register(new ElytraRecastModule());
		register(new GuiMoveModule());
		register(new HoleAnchorModule());
		register(new InfiniteElytraModule());
		register(new LegitStrafeModule());
		register(new MoveFixModule());
		register(new NoPushModule());
		register(new SprintModule());
		register(new WindHopModule());
		// Movement: Rage
		register(new LongJumpModule());
		register(new ElytraBoostModule());
		register(new HighJumpModule());
		register(new AirJumpModule());
		register(new JesusModule());
		register(new AntiVoidModule());
		register(new AntiWebModule());
		register(new BlinkModule());
		register(new BoatFlyModule());
		register(new ElytraFreezeModule());
		register(new ElytraPlusModule());
		register(new ElytraTargetModule());
		register(new EntitySpeedModule());
		register(new FlightModule());
		register(new LevitationControlModule());
		register(new MaceElytraTargetModule());
		register(new NoFallModule());
		register(new NoSlowModule());
		register(new PhaseModule());
		register(new ReverseStepModule());
		register(new ScaffoldModule());
		register(new SpeedModule());
		register(new SpiderModule());
		register(new StepModule());
		register(new TimerModule());
		register(new TridentBoostModule());
		register(new WaterSpeedModule());
		// Visual: Cosmetic
		register(new ItemPhysicsModule());
		register(new TimeChangerModule());
		register(new BetterTabModule());
		register(new BreadcrumbsModule());
		register(new AspectModule());
		register(new CrystalChamsModule());
		register(new DamageParticlesModule());
		register(new DamageTintModule());
		register(new DismembermentModule());
		register(new FreeLookModule());
		register(new FullBrightModule());
		register(new HandChamsModule());
		register(new HitFxModule());
		register(new NoCameraClipModule());
		register(new ParticlesModule());
		register(new PopChamsModule());
		register(new SwingAnimationsModule());
		register(new ThrowPetardModule());
		register(new TotemAnimationModule());
		register(new TrajectoriesModule());
		register(new ViewModelModule());
		register(new WorldRendererModule());
		// Visual: Esp
		register(new BaseEspModule());
		register(new GeodeEspModule());
		register(new KelpEspModule());
		register(new BlockEspModule());
		register(new EspModule());
		register(new HoleEspModule());
		register(new LogoutSpotsModule());
		register(new NametagsModule());
		register(new StorageEspModule());
		register(new TracersModule());
		// Utility: World
		register(new AutoBoneMealModule());
		register(new AutoLandModule());
		register(new AutoShearModule());
		register(new AutoSignModule());
		register(new FakePlayerModule());
		register(new FreeCamModule());
		register(new FtHelperModule());
		register(new NoRenderModule());
		register(new NukerModule());
		register(new PacketMineModule());
		register(new PearlChaserModule());
		register(new SoilRipperModule());
		register(new SpeedMineModule());
		register(new TpsSyncModule());
		register(new ZoomModule());
		// Utility: Auto / Equip
		register(new AutoArmorModule());
		register(new AutoEatModule());
		register(new AutoRespawnModule());
		register(new AutoToolModule());
		register(new AirPlaceModule());
		register(new LiquidInteractModule());
		register(new NoMiningTraceModule());
		register(new PotionSaverModule());
		register(new AutoClickerModule());
		register(new AutoFishModule());
		register(new AutoMendModule());
		register(new AutoReplenishModule());
		register(new MiddleClickExtraModule());
		register(new NoRotateModule());
		register(new ReachModule());
		register(new AntiAfkModule());
		register(new FastUseModule());
		// Utility: Misc
		register(new AutoReconnectModule());
		register(new NameProtectModule());
		register(new ClickGuiModule());
		register(new HudModule());
	}

	private void register(Module module) {
		modules.add(module);
		byType.put(module.getClass(), module);
	}

	public List<Module> getModules() {
		return Collections.unmodifiableList(modules);
	}

	/** Modules in one tab, alphabetically. */
	public List<Module> getModules(Category category) {
		return modules.stream().filter(module -> module.getCategory() == category)
			.sorted(Comparator.comparing(Module::getName, String.CASE_INSENSITIVE_ORDER)).toList();
	}

	/** Map lookup, so mixins on hot paths can afford to call it. */
	@SuppressWarnings("unchecked")
	public <T extends Module> T get(Class<T> type) {
		Module module = byType.get(type);
		if (module == null) throw new IllegalArgumentException("Unknown module " + type.getName());
		return (T) module;
	}

	public Module get(String name) {
		for (Module module : modules) {
			if (module.getName().equalsIgnoreCase(name)) return module;
		}
		return null;
	}

	public void onKey(int key) {
		for (Module module : modules) {
			if (module.getKey() == key) module.toggle();
		}
	}

	public void onTick() {
		dispatch(Module::onTick);
	}

	public void onPreMotion() {
		dispatch(Module::onPreMotion);
	}

	public void onPostMotion() {
		dispatch(Module::onPostMotion);
	}

	public void onMovementInput() {
		dispatch(Module::onMovementInput);
	}

	public void onPreAttack(Entity target) {
		dispatch(module -> module.onPreAttack(target));
	}

	public void onPostAttack(Entity target) {
		dispatch(module -> module.onPostAttack(target));
	}

	public void onBlockUpdate(BlockPos pos, BlockState state) {
		dispatch(module -> module.onBlockUpdate(pos, state));
	}

	/** Every enabled module sees the break, even after one has cancelled it. */
	public boolean onStartBreaking(BlockPos pos, Direction side) {
		boolean[] cancelled = {false};
		dispatch(module -> {
			if (module.onStartBreaking(pos, side)) cancelled[0] = true;
		});
		return cancelled[0];
	}

	public void onRender3D(Render3DEvent event) {
		dispatch(module -> module.onRender3D(event));
	}

	public void onRender2D(DrawContext context, float tickDelta) {
		dispatch(module -> module.onRender2D(context, tickDelta));
	}

	public boolean onPacketReceive(PacketEvent event) {
		dispatch(module -> module.onPacketReceive(event));
		return event.isCancelled();
	}

	public boolean onPacketSend(PacketEvent event) {
		dispatch(module -> module.onPacketSend(event));
		return event.isCancelled();
	}

	private void dispatch(Consumer<Module> action) {
		if (Module.nullCheck()) return;
		for (Module module : modules) {
			if (!module.isEnabled()) continue;
			try {
				action.accept(module);
			} catch (Exception e) {
				// A broken module must never take the game down with it
				FemboyWare.LOGGER.error("Module {} threw an exception", module.getName(), e);
			}
		}
	}
}
