package dev.femboyware.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.movement.AvoidModule;
import dev.femboyware.module.movement.JesusModule;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.EntityShapeContext;
import net.minecraft.block.ShapeContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(AbstractBlock.AbstractBlockState.class)
public abstract class AbstractBlockStateMixin {
	/** Avoid: dangerous blocks are solid for the local player only. */
	@ModifyReturnValue(method = "getCollisionShape(Lnet/minecraft/world/BlockView;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/ShapeContext;)Lnet/minecraft/util/shape/VoxelShape;", at = @At("RETURN"))
	private VoxelShape femboyware$avoid(VoxelShape shape, @Local(argsOnly = true) ShapeContext context) {
		if (!(context instanceof EntityShapeContext entityContext) || !FemboyWare.isEnabled(AvoidModule.class)) return shape;
		if (entityContext.getEntity() != MinecraftClient.getInstance().player) return shape;
		BlockState state = (BlockState) (Object) this;
		return FemboyWare.getModules().get(AvoidModule.class).shouldBlock(state) ? VoxelShapes.fullCube() : shape;
	}

	/** Jesus: liquids below the local player are solid. */
	@ModifyReturnValue(method = "getCollisionShape(Lnet/minecraft/world/BlockView;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/ShapeContext;)Lnet/minecraft/util/shape/VoxelShape;", at = @At("RETURN"))
	private VoxelShape femboyware$jesus(VoxelShape shape, @Local(argsOnly = true) BlockPos pos, @Local(argsOnly = true) ShapeContext context) {
		if (!(context instanceof EntityShapeContext entityContext) || !FemboyWare.isEnabled(JesusModule.class)) return shape;
		MinecraftClient mc = MinecraftClient.getInstance();
		Entity entity = entityContext.getEntity();
		if (entity == null || (entity != mc.player && entity != mc.player.getVehicle())) return shape;
		return FemboyWare.getModules().get(JesusModule.class).isSolid((BlockState) (Object) this, pos) ? VoxelShapes.fullCube() : shape;
	}
}
