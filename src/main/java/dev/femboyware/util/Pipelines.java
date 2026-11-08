package dev.femboyware.util;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
//? if >=26.1 {
/*import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.platform.CompareOp;
*///?} else {
import com.mojang.blaze3d.platform.DepthTestFunction;
//?}

/** Render pipeline settings that are spelled differently between game versions. */
public final class Pipelines {
	private Pipelines() {
	}

	/** {@code test}: hidden behind nearer things (less-or-equal depth test), otherwise drawn through them. */
	public static RenderPipeline.Builder depth(RenderPipeline.Builder builder, boolean test, boolean write) {
		//? if >=26.1 {
		/*return builder.withDepthStencilState(new DepthStencilState(test ? CompareOp.LESS_THAN_OR_EQUAL : CompareOp.ALWAYS_PASS, write));
		*///?} else {
		return builder.withDepthWrite(write)
			.withDepthTestFunction(test ? DepthTestFunction.LEQUAL_DEPTH_TEST : DepthTestFunction.NO_DEPTH_TEST);
		//?}
	}

	public static RenderPipeline.Builder blend(RenderPipeline.Builder builder, BlendFunction blend) {
		//? if >=26.1 {
		/*return builder.withColorTargetState(new ColorTargetState(blend));
		*///?} else {
		return builder.withBlend(blend);
		//?}
	}
}
