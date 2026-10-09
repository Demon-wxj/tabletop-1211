package local.tabletop.mixin;

import local.tabletop.ClockEmbodiment;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayerEntity.class)
public abstract class ClockDeathMixin {
    @Inject(method="onDeath",at=@At("HEAD"),cancellable=true)
    private void tabletop$silentRoomDeath(DamageSource source,CallbackInfo ci){
        ServerPlayerEntity player=(ServerPlayerEntity)(Object)this;
        if(ClockEmbodiment.silentDeath(player)){ClockEmbodiment.onSilentDeath(player);ci.cancel();}
    }
}
