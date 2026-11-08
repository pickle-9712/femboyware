package dev.femboyware.event;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.state.CameraRenderState;

public record Render3DEvent(PoseStack matrices, WorldBuffers consumers, CameraRenderState camera) {
}
