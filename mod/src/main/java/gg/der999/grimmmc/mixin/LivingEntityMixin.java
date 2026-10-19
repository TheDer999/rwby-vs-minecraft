package gg.der999.grimmmc.mixin;

import gg.der999.grimmmc.GrimmMcClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Ruby's dodge makes her invulnerable for a short time, as in Grimm Eclipse. */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
	/** Ruby's aura soaks up damage before her health. */
	@ModifyVariable(method = "damage", at = @At("HEAD"), argsOnly = true)
	private float grimmmc$aura(float amount) {
		Entity self = (Entity) (Object) this;
		return GrimmMcClient.dodgeProtects(self.getUuid()) ? amount : GrimmMcClient.auraAbsorb(self.getUuid(), amount);
	}

	@Inject(method = "damage", at = @At("HEAD"), cancellable = true)
	private void grimmmc$dodgeInvulnerability(ServerWorld world, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
		if (GrimmMcClient.dodgeProtects(((Entity) (Object) this).getUuid())) cir.setReturnValue(false);
	}
}
