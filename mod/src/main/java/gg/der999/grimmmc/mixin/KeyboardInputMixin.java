package gg.der999.grimmmc.mixin;

import gg.der999.grimmmc.CombatController;
import gg.der999.grimmmc.GrimmMcClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.util.PlayerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Grimm Eclipse puts dodge and sprint on one button: pressing Shift dodges, holding it sprints. So while Ruby is possessed
 * Shift never sneaks; it sprints while held. Movement and jumping are frozen while one of her moves is running.
 */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends Input {
	@Inject(method = "tick", at = @At("TAIL"))
	private void grimmmc$ruby(CallbackInfo ci) {
		if (!GrimmMcClient.possessed) return;
		PlayerInput in = this.playerInput;
		boolean shift = MinecraftClient.getInstance().options.sneakKey.isPressed();
		CombatController combat = GrimmMcClient.combat;
		if (combat != null && combat.lockMovement()) {
			this.playerInput = new PlayerInput(false, false, false, false, false, false, false);
			this.movementForward = 0f;
			this.movementSideways = 0f;
			return;
		}
		boolean moving = in.forward() || in.backward() || in.left() || in.right();
		var p = MinecraftClient.getInstance().player;
		boolean water = p != null && p.isTouchingWater();
		// In water Shift is not a dodge: held on its own it sinks. Moving forward under water swims (Minecraft's sprint-swim) without any button.
		boolean sink = water && shift && !moving;
		this.playerInput = new PlayerInput(in.forward(), in.backward(), in.left(), in.right(), in.jump(),
			sink, in.sprint() || (shift && in.forward()) || (p != null && p.isSubmergedInWater() && in.forward()));
	}
}
