package local.tabletop.mixin;

import local.tabletop.ClockEmbodiment;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class ClockDamageMixin {
    @Inject(method="damage",at=@At("HEAD"),cancellable=true)
    private void tabletop$onlyApprovedDeath(DamageSource source,float amount,CallbackInfoReturnable<Boolean> cir){
        if((Object)this instanceof ServerPlayerEntity player&&!ClockEmbodiment.allowsDamage(player))cir.setReturnValue(false);
    }
}
