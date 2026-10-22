package dev.femboyware.event;

import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;

public record Render3DEvent(MatrixStack matrices, VertexConsumerProvider consumers, CameraRenderState camera) {
}
