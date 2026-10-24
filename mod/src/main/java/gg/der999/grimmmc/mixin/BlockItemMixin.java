package gg.der999.grimmmc.mixin;

import gg.der999.grimmmc.GrimmMcClient;
import net.minecraft.block.BlockState;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Remembers the blocks players place, so Ruby's and the Grimm's digging can spare them when that option is on. */
@Mixin(BlockItem.class)
public abstract class BlockItemMixin {
	@Inject(method = "place(Lnet/minecraft/item/ItemPlacementContext;Lnet/minecraft/block/BlockState;)Z", at = @At("RETURN"))
	private void grimmmc$placed(ItemPlacementContext context, BlockState state, CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ() && !context.getWorld().isClient() && context.getPlayer() instanceof ServerPlayerEntity) {
			GrimmMcClient.blockPlacedByPlayer(context.getWorld(), context.getBlockPos());
		}
	}
}
