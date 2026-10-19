package gg.der999.grimmmc.mixin;

import gg.der999.grimmmc.GrimmMcClient;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** While Ruby is possessed, left and right click belong to her attacks, not to mining, hitting or placing. */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
	@Inject(method = "doAttack", at = @At("HEAD"), cancellable = true)
	private void grimmmc$cancelAttack(CallbackInfoReturnable<Boolean> cir) {
		if (GrimmMcClient.possessed) cir.setReturnValue(false);
	}

	@Inject(method = "doItemUse", at = @At("HEAD"), cancellable = true)
	private void grimmmc$cancelUse(CallbackInfo ci) {
		if (GrimmMcClient.possessed) ci.cancel();
	}

	/** Q fires Ruby's gun and F is her counter, so the vanilla drop and hand swap are swallowed. */
	@Inject(method = "handleInputEvents", at = @At("HEAD"))
	private void grimmmc$takeRubyKeys(CallbackInfo ci) {
		if (!GrimmMcClient.possessed) return;
		MinecraftClient self = (MinecraftClient) (Object) this;
		while (self.options.dropKey.wasPressed()) { }
		while (self.options.pickItemKey.wasPressed()) GrimmMcClient.specialPressed = true;
		while (self.options.swapHandsKey.wasPressed()) GrimmMcClient.counterPressed = true;
	}

	@Inject(method = "handleBlockBreaking", at = @At("HEAD"), cancellable = true)
	private void grimmmc$cancelBreaking(boolean breaking, CallbackInfo ci) {
		if (GrimmMcClient.possessed) ci.cancel();
	}
}
