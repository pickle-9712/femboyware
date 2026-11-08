package dev.femboyware.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.movement.AvoidModule;
import dev.femboyware.module.movement.JesusModule;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class AbstractBlockStateMixin {
	/** Avoid: dangerous blocks are solid for the local player only. */
	@ModifyReturnValue(method = "getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;", at = @At("RETURN"))
	private VoxelShape femboyware$avoid(VoxelShape shape, @Local(argsOnly = true) CollisionContext context) {
		if (!(context instanceof EntityCollisionContext entityContext) || !FemboyWare.isEnabled(AvoidModule.class)) return shape;
		if (entityContext.getEntity() != Minecraft.getInstance().player) return shape;
		BlockState state = (BlockState) (Object) this;
		return FemboyWare.getModules().get(AvoidModule.class).shouldBlock(state) ? Shapes.block() : shape;
	}

	/** Jesus: liquids below the local player are solid. */
	@ModifyReturnValue(method = "getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;", at = @At("RETURN"))
	private VoxelShape femboyware$jesus(VoxelShape shape, @Local(argsOnly = true) BlockPos pos, @Local(argsOnly = true) CollisionContext context) {
		if (!(context instanceof EntityCollisionContext entityContext) || !FemboyWare.isEnabled(JesusModule.class)) return shape;
		Minecraft mc = Minecraft.getInstance();
		Entity entity = entityContext.getEntity();
		if (entity == null || (entity != mc.player && entity != mc.player.getVehicle())) return shape;
		return FemboyWare.getModules().get(JesusModule.class).isSolid((BlockState) (Object) this, pos) ? Shapes.block() : shape;
	}
}
