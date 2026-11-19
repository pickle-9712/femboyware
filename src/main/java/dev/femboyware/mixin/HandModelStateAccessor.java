package dev.femboyware.mixin;

/** 3D Hand: the held item's model transform and pixel extents (1.21.11; empty on 26.x). Ported from Anubis (GPL-3.0, 4ldenz). */
//? if <26.1 {
@org.spongepowered.asm.mixin.Mixin(net.minecraft.client.renderer.item.ItemStackRenderState.class)
public interface HandModelStateAccessor {
	@org.spongepowered.asm.mixin.gen.Accessor("layers")
	net.minecraft.client.renderer.item.ItemStackRenderState.LayerRenderState[] femboyware$layers();

	@org.spongepowered.asm.mixin.gen.Accessor("activeLayerCount")
	int femboyware$layerCount();
}
//?} else {
/*@org.spongepowered.asm.mixin.Mixin(net.minecraft.client.Minecraft.class)
public interface HandModelStateAccessor {
}
*///?}
