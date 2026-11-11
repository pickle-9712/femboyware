package dev.femboyware.mixin;

import net.minecraft.world.entity.WalkAnimationState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** For Client Sync: other people's walk animation, set from what their game says it is. */
@Mixin(WalkAnimationState.class)
public interface WalkAnimationStateAccessor {
	@Accessor("speedOld")
	void femboyware$setSpeedOld(float speed);

	@Accessor("speed")
	void femboyware$setSpeed(float speed);

	@Accessor("position")
	void femboyware$setPosition(float position);
}
