package dev.femboyware.mixin;

/** 3D Hand: the held item's model transform and pixel extents (1.21.11; empty on 26.x). Ported from Anubis (GPL-3.0, 4ldenz). */
//? if <26.1 {
@org.spongepowered.asm.mixin.Mixin(net.minecraft.client.renderer.item.ItemStackRenderState.LayerRenderState.class)
public interface HandModelLayerAccessor {
	@org.spongepowered.asm.mixin.gen.Accessor("transform")
	net.minecraft.client.renderer.block.model.ItemTransform femboyware$transform();

	@org.spongepowered.asm.mixin.gen.Accessor("extents")
	java.util.function.Supplier<org.joml.Vector3fc[]> femboyware$extents();
}
//?} else {
/*@org.spongepowered.asm.mixin.Mixin(net.minecraft.client.Minecraft.class)
public interface HandModelLayerAccessor {
}
*///?}
