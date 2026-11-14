package dev.femboyware.module;

import dev.femboyware.FemboyWare;
import dev.femboyware.event.PacketEvent;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.gui.UiScale;
import dev.femboyware.module.client.ClickGuiModule;
import dev.femboyware.module.client.FakePlayerModule;
import dev.femboyware.module.client.MoveFixModule;
import dev.femboyware.module.combat.AuraModule;
import dev.femboyware.module.combat.AutoAnchorModule;
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
import dev.femboyware.module.combat.LegitAnchorModule;
import dev.femboyware.module.combat.BodyguardModule;
import dev.femboyware.module.combat.LegitAuraModule;
import dev.femboyware.module.combat.LegitCrystalModule;
import dev.femboyware.module.combat.LegitPotModule;
import dev.femboyware.module.combat.LegitTotemModule;
import dev.femboyware.module.combat.MaceKillerModule;
import dev.femboyware.module.combat.MainhandTotemModule;
import dev.femboyware.module.combat.MoreKnockbackModule;
import dev.femboyware.module.combat.OffhandModule;
import dev.femboyware.module.combat.QuiverModule;
import dev.femboyware.module.combat.SelfTrapModule;
import dev.femboyware.module.combat.SurroundModule;
import dev.femboyware.module.combat.TrapModule;
import dev.femboyware.module.effects.AuraParticlesModule;
import dev.femboyware.module.effects.AuroraModule;
import dev.femboyware.module.effects.ComboCounterModule;
import dev.femboyware.module.effects.ConeHatModule;
import dev.femboyware.module.effects.CryptoOrbitModule;
import dev.femboyware.module.effects.FairyCompanionModule;
import dev.femboyware.module.effects.FirefliesModule;
import dev.femboyware.module.effects.HaloModule;
import dev.femboyware.module.effects.JumpCirclesModule;
import dev.femboyware.module.effects.KillEffectsModule;
import dev.femboyware.module.effects.LightningLinkModule;
import dev.femboyware.module.effects.MagicCircleModule;
import dev.femboyware.module.effects.NekoModule;
import dev.femboyware.module.effects.PetalRainModule;
import dev.femboyware.module.effects.PlanetModule;
import dev.femboyware.module.effects.RainbowModule;
import dev.femboyware.module.effects.RibbonTrailModule;
import dev.femboyware.module.effects.SkyLanternsModule;
import dev.femboyware.module.effects.SkySwirlsModule;
import dev.femboyware.module.effects.StarfieldModule;
import dev.femboyware.module.effects.SwingTrailModule;
import dev.femboyware.module.effects.TotemPopsModule;
import dev.femboyware.module.effects.WingsModule;
import dev.femboyware.module.hud.ArmorModule;
import dev.femboyware.module.hud.CoordinatesModule;
import dev.femboyware.module.hud.InfoModule;
import dev.femboyware.module.hud.KeystrokesModule;
import dev.femboyware.module.hud.IslandModule;
import dev.femboyware.module.hud.LyricsModule;
import dev.femboyware.module.hud.MediaPlayerModule;
import dev.femboyware.module.hud.ModuleListModule;
import dev.femboyware.module.hud.NotificationsModule;
import dev.femboyware.module.hud.PotionEffectsModule;
import dev.femboyware.module.hud.TargetHudModule;
import dev.femboyware.module.hud.WatermarkModule;
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
import dev.femboyware.module.render.BlockOutlineModule;
import dev.femboyware.module.render.BreadcrumbsModule;
import dev.femboyware.module.render.CapeModule;
import dev.femboyware.module.render.CleanFontModule;
import dev.femboyware.module.render.CosmeticsModule;
import dev.femboyware.module.render.EmotesModule;
import dev.femboyware.module.render.CrosshairModule;
import dev.femboyware.module.render.CrystalChamsModule;
import dev.femboyware.module.render.DamageParticlesModule;
import dev.femboyware.module.render.DamageTintModule;
import dev.femboyware.module.render.DismembermentModule;
import dev.femboyware.module.render.EspModule;
import dev.femboyware.module.render.FarmEspModule;
import dev.femboyware.module.render.FreeLookModule;
import dev.femboyware.module.render.FullBrightModule;
import dev.femboyware.module.render.FurryModule;
import dev.femboyware.module.render.GeodeEspModule;
import dev.femboyware.module.render.HandChamsModule;
import dev.femboyware.module.render.HitFxModule;
import dev.femboyware.module.render.HoleEspModule;
import dev.femboyware.module.render.ItemPhysicsModule;
import dev.femboyware.module.render.KelpEspModule;
import dev.femboyware.module.render.LogoutSpotsModule;
import dev.femboyware.module.render.MainMenuModule;
import dev.femboyware.module.render.NametagsModule;
import dev.femboyware.module.render.NewChunksModule;
import dev.femboyware.module.render.NoCameraClipModule;
import dev.femboyware.module.render.ParticlesModule;
import dev.femboyware.module.render.PinkSkyModule;
import dev.femboyware.module.render.PopChamsModule;
import dev.femboyware.module.render.PortalEspModule;
import dev.femboyware.module.render.RainbowArmorModule;
import dev.femboyware.module.render.SpawnerEspModule;
import dev.femboyware.module.render.SpinbotModule;
import dev.femboyware.module.render.StashEspModule;
import dev.femboyware.module.render.StorageEspModule;
import dev.femboyware.module.render.SwingAnimationsModule;
import dev.femboyware.module.render.ThrowPetardModule;
import dev.femboyware.module.render.TimeChangerModule;
import dev.femboyware.module.render.TotemAnimationModule;
import dev.femboyware.module.render.TracersModule;
import dev.femboyware.module.render.TrajectoriesModule;
import dev.femboyware.module.render.TungSahurModule;
import dev.femboyware.module.render.ViewModelModule;
import dev.femboyware.module.render.WorldRendererModule;
import dev.femboyware.module.theme.BackgroundModule;
import dev.femboyware.module.theme.ColorsModule;
import dev.femboyware.module.theme.GlassModule;
import dev.femboyware.module.theme.ShineModule;
import dev.femboyware.module.utility.AirPlaceModule;
import dev.femboyware.module.utility.AntiAfkModule;
import dev.femboyware.module.utility.AutoArmorModule;
import dev.femboyware.module.utility.AutoBoneMealModule;
import dev.femboyware.module.utility.AutoClickerModule;
import dev.femboyware.module.utility.AutoEatModule;
import dev.femboyware.module.utility.AutoFishModule;
import dev.femboyware.module.utility.AutoFlirtModule;
import dev.femboyware.module.utility.HeadpatModule;
import dev.femboyware.module.utility.AutoHugModule;
import dev.femboyware.module.utility.AutoLandModule;
import dev.femboyware.module.utility.AutoMendModule;
import dev.femboyware.module.utility.AutoMeowModule;
import dev.femboyware.module.utility.AutoMoanModule;
import dev.femboyware.module.utility.AutoReconnectModule;
import dev.femboyware.module.utility.AutoReplenishModule;
import dev.femboyware.module.utility.AutoRespawnModule;
import dev.femboyware.module.utility.AutoShearModule;
import dev.femboyware.module.utility.AutoSignModule;
import dev.femboyware.module.utility.AutoToolModule;
import dev.femboyware.module.utility.ChatPlusModule;
import dev.femboyware.module.utility.FastUseModule;
import dev.femboyware.module.utility.FreeCamModule;
import dev.femboyware.module.utility.FriendsModule;
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
import dev.femboyware.module.utility.SoundBlockerModule;
import dev.femboyware.module.utility.SpeedMineModule;
import dev.femboyware.module.utility.TpsSyncModule;
import dev.femboyware.module.utility.WaypointsModule;
import dev.femboyware.module.utility.ZoomModule;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;

public class ModuleManager {
	private final List<Module> modules = new ArrayList<>();
	private final Map<Class<? extends Module>, Module> byType = new HashMap<>();

	public ModuleManager() {
		// Pvp: Attack
		register(new AutoAnchorModule());
		register(new AuraModule());
		register(new LegitAuraModule());
		register(new dev.femboyware.module.combat.BacktrackModule());
		register(new LegitCrystalModule());
		register(new LegitAnchorModule());
		register(new LegitTotemModule());
		register(new LegitPotModule());
		register(new BodyguardModule());
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
		register(new dev.femboyware.module.utility.ServerLarpModule());
		register(new dev.femboyware.module.larp.ForceDispenserModule());
		register(new dev.femboyware.module.render.ScoreboardEditModule());
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
		register(new MainMenuModule());
		register(new TimeChangerModule());
		register(new BetterTabModule());
		register(new BreadcrumbsModule());
		register(new CleanFontModule());
		register(new CapeModule());
		register(new FurryModule());
		register(new CosmeticsModule());
		register(new dev.femboyware.module.render.RobloxHatsModule());
		register(new EmotesModule());
		register(new dev.femboyware.module.larp.LedgerModule());
		register(new dev.femboyware.module.larp.CinematicCameraModule());
		register(new dev.femboyware.module.larp.PhoneModule());
		register(new dev.femboyware.module.larp.PaparazziModule());
		register(new dev.femboyware.module.larp.CarsModule());
		register(new dev.femboyware.module.larp.JetModule());
		register(new dev.femboyware.module.larp.HelicopterModule());
		register(new dev.femboyware.module.larp.HangarModule());
		register(new dev.femboyware.module.larp.YachtModule());
		register(new dev.femboyware.module.larp.TvModule());
		register(new dev.femboyware.module.larp.FbiRaidModule());
		register(new dev.femboyware.module.larp.MansionModule());
		register(new dev.femboyware.module.larp.CoinbaseAgentModule());
		register(new CrosshairModule());
		register(new BlockOutlineModule());
		register(new NewChunksModule());
		register(new ColorsModule());
		register(new GlassModule());
		register(new ShineModule());
		register(new BackgroundModule());
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
		register(new RainbowArmorModule());
		register(new dev.femboyware.module.render.SwordModelsModule());
		register(new dev.femboyware.module.render.WalkStyleModule());
		register(new dev.femboyware.module.render.PetsModule());
		register(new dev.femboyware.module.render.GuiAnimationModule());
		register(new dev.femboyware.module.render.SelfVisualsModule());
		register(new dev.femboyware.module.render.SkinChangerModule());
		register(new dev.femboyware.module.render.PlayerModelModule());
		register(new SpinbotModule());
		register(new SwingAnimationsModule());
		register(new ThrowPetardModule());
		register(new TotemAnimationModule());
		register(new TrajectoriesModule());
		register(new TungSahurModule());
		register(new ViewModelModule());
		register(new WorldRendererModule());
		// Visual: Esp
		register(new BaseEspModule());
		register(new GeodeEspModule());
		register(new KelpEspModule());
		register(new PortalEspModule());
		register(new SpawnerEspModule());
		register(new StashEspModule());
		register(new BlockEspModule());
		register(new EspModule());
		register(new FarmEspModule());
		register(new HoleEspModule());
		register(new LogoutSpotsModule());
		register(new NametagsModule());
		register(new StorageEspModule());
		register(new TracersModule());
		// Customize: Targets
		register(new dev.femboyware.module.effects.TargetParticlesModule());
		register(new dev.femboyware.module.effects.SpellHitsModule());
		register(new dev.femboyware.module.effects.KillAnimationsModule());
		register(new dev.femboyware.module.effects.SpectralAuraModule());
		register(new dev.femboyware.module.effects.StarHitsModule());
		register(new dev.femboyware.module.effects.IsraelAuraModule());
		register(new dev.femboyware.module.larp.FakeSpawnerModule());
		register(new dev.femboyware.module.larp.FakePayModule());
		register(new dev.femboyware.module.larp.CasinoModule());
		register(new KillEffectsModule());
		register(new LightningLinkModule());
		register(new ComboCounterModule());
		register(new TotemPopsModule());
		// Customize: Effects
		register(new WingsModule());
		register(new CryptoOrbitModule());
		register(new HaloModule());
		register(new ConeHatModule());
		register(new MagicCircleModule());
		register(new JumpCirclesModule());
		register(new RibbonTrailModule());
		register(new dev.femboyware.module.effects.ClickPulseModule());
		register(new dev.femboyware.module.effects.FlameHandsModule());
		register(new dev.femboyware.module.effects.PearlParticlesModule());
		register(new dev.femboyware.module.effects.SnowGroundModule());
		register(new dev.femboyware.module.effects.WetGroundModule());
		register(new dev.femboyware.module.effects.FogModule());
		register(new dev.femboyware.module.effects.WorldColorModule());
		register(new dev.femboyware.module.effects.PlayerTrailModule());
		register(new dev.femboyware.module.effects.CelestialWeatherModule());
		register(new dev.femboyware.module.effects.SkyShaderModule());
		register(new dev.femboyware.module.effects.RealisticWaterModule());
		register(new dev.femboyware.module.effects.CustomSkyModule());
		register(new dev.femboyware.module.render.NoBlockRotationModule());
		register(new dev.femboyware.module.render.ThreeDUiModule());
		register(new dev.femboyware.module.render.MotionBlurModule());
		register(new dev.femboyware.module.hud.StaffListModule());
		register(new dev.femboyware.module.larp.LarpAsModule());
		register(new dev.femboyware.module.utility.FakeFpsModule());
		register(new dev.femboyware.module.movement.NoJumpDelayModule());
		register(new dev.femboyware.module.utility.NoBreakDelayModule());
		register(new dev.femboyware.module.movement.AirStuckModule());
		register(new dev.femboyware.module.utility.AutoDisconnectModule());
		register(new dev.femboyware.module.utility.HomeMacroModule());
		register(new dev.femboyware.module.utility.ChestStealerModule());
		register(new dev.femboyware.module.utility.InvCleanerModule());
		register(new dev.femboyware.module.movement.AutoMlgModule());
		register(new dev.femboyware.module.utility.AutoDrainModule());
		register(new dev.femboyware.module.utility.AutoFarmModule());
		register(new dev.femboyware.module.movement.AutoElytraSwapModule());
		register(new dev.femboyware.module.movement.AutoPearlCatchModule());
		register(new dev.femboyware.module.movement.GrimSpeedModule());
		register(new dev.femboyware.module.utility.AutoTunnelModule());
		register(new dev.femboyware.module.utility.AutoSellModule());
		register(new dev.femboyware.module.utility.IgnoreEntityModule());
		register(new dev.femboyware.module.render.BaseFinderModule());
		register(new dev.femboyware.module.render.LightDebugModule());
		register(new dev.femboyware.module.render.MotionScannerModule());
		register(new dev.femboyware.module.render.SusChunkFinderModule());
		register(new dev.femboyware.module.render.AnchorHunterModule());
		register(new dev.femboyware.module.utility.OrderDropperModule());
		register(new dev.femboyware.module.utility.AutoAreaMinerModule());
		register(new dev.femboyware.module.utility.AutoBuilderModule());
		register(new dev.femboyware.module.movement.AutoElytraPilotModule());
		register(new NekoModule());
		register(new FairyCompanionModule());
		register(new AuraParticlesModule());
		register(new SwingTrailModule());
		register(new dev.femboyware.module.effects.AstralTrailsModule());
		// Customize: Sky
		register(new PinkSkyModule());
		register(new SkySwirlsModule());
		register(new AuroraModule());
		register(new StarfieldModule());
		register(new PlanetModule());
		register(new dev.femboyware.module.effects.AuraSkyModule());
		register(new RainbowModule());
		register(new SkyLanternsModule());
		register(new PetalRainModule());
		register(new FirefliesModule());
		// Utility: World
		register(new AutoBoneMealModule());
		register(new AutoLandModule());
		register(new AutoShearModule());
		register(new AutoSignModule());
		register(new FakePlayerModule());
		register(new dev.femboyware.module.client.ClientSyncModule());
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
		register(new FriendsModule());
		register(new WaypointsModule());
		register(new ChatPlusModule());
		register(new NoRotateModule());
		register(new ReachModule());
		register(new AntiAfkModule());
		register(new FastUseModule());
		// Utility: Misc
		register(new AutoMeowModule());
		register(new AutoFlirtModule());
		register(new HeadpatModule());
		register(new AutoHugModule());
		register(new AutoMoanModule());
		// Hit Sounds is hidden for now; register it again to bring it back
		// register(new HitSoundsModule());
		register(new AutoReconnectModule());
		register(new NameProtectModule());
		register(new SoundBlockerModule());
		register(new ClickGuiModule());
		register(new dev.femboyware.module.client.LarpOnlyModule());
		register(new WatermarkModule());
		register(new ModuleListModule());
		register(new CoordinatesModule());
		register(new TargetHudModule());
		register(new PotionEffectsModule());
		register(new ArmorModule());
		register(new InfoModule());
		register(new KeystrokesModule());
		register(new MediaPlayerModule());
		register(new IslandModule());
		register(new LyricsModule());
		register(new dev.femboyware.module.hud.GuardHudModule());
		register(new dev.femboyware.module.hud.SessionStatsModule());
		register(new dev.femboyware.module.combat.GuardCommandModule());
		register(new NotificationsModule());
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
		dispatch(module -> module.onKey(key));
	}

	public void onTick() {
		// The phone and Ledger items open on right-click whether or not their modules are on
		dev.femboyware.module.larp.LarpItems.tick();
		// Your mansion, cars and the rest, kept for each world across logging out and restarts
		dev.femboyware.module.larp.LarpSave.tick();
		dispatch(Module::onTick);
	}

	public void onInput() {
		dispatch(Module::onInput);
	}

	public void onFrame() {
		dispatch(Module::onFrame);
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
		// Where the camera is, for the larp props' day and night lighting
		dev.femboyware.module.larp.LarpLight.frame(event.camera().pos);
		// Our labels and nametags use the clean font even though Minecraft's in-game text doesn't
		UiScale.beginClient();
		try {
			dispatch(module -> module.onRender3D(event));
			// Draw everything solid now, so see-through effects drawn late aren't painted over
			event.consumers().endBatch();
			dispatch(module -> module.onRender3DLate(event));
		} finally {
			UiScale.endClient();
		}
	}

	public void onRender2D(GuiGraphics context, float tickDelta) {
		UiScale.beginClient();
		try {
			var raid = get(dev.femboyware.module.larp.FbiRaidModule.class);
			// The raid's end card, and its film shots: nothing else drawn over them but the letterbox
			if (raid.seizedScreenShowing() || raid.hidesHud()) {
				raid.onRender2D(context, tickDelta);
				return;
			}
			dispatch(module -> module.onRender2D(context, tickDelta));
		} finally {
			UiScale.endClient();
		}
	}

	public boolean onPacketReceive(PacketEvent event) {
		dispatch(module -> module.onPacketReceive(event));
		return event.isCancelled();
	}

	public boolean onPacketSend(PacketEvent event) {
		// The fake balance's commands (/fakebal, /bal, and /sell and /shop while larping), whatever's on
		if (!Module.nullCheck() && dev.femboyware.module.utility.FakeEconomy.intercept(event.getPacket())) {
			event.cancel();
			return true;
		}
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
