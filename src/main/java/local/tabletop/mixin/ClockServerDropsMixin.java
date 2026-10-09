package local.tabletop.mixin;

import local.tabletop.ClockEmbodiment;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayerEntity.class)
public abstract class ClockServerDropsMixin {
    @Inject(method="dropSelectedItem",at=@At("HEAD"),cancellable=true)
    private void tabletop$keepHeldProp(boolean all,CallbackInfoReturnable<Boolean> cir){
        ServerPlayerEntity player=(ServerPlayerEntity)(Object)this;if(ClockEmbodiment.managed(player.getMainHandStack()))cir.setReturnValue(false);
    }
    @Inject(method="dropItem(Lnet/minecraft/item/ItemStack;ZZ)Lnet/minecraft/entity/ItemEntity;",at=@At("HEAD"),cancellable=true)
    private void tabletop$keepSceneProp(ItemStack item,boolean random,boolean retainOwnership,CallbackInfoReturnable<ItemEntity> cir){if(ClockEmbodiment.managed(item))cir.setReturnValue(null);}
}
