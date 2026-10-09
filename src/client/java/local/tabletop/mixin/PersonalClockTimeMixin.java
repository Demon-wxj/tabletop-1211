package local.tabletop.mixin;

import local.tabletop.client.NightCurtain;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.WorldTimeUpdateS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class PersonalClockTimeMixin {
    @Inject(method="onWorldTimeUpdate",at=@At("TAIL"))
    private void tabletop$personalClock(WorldTimeUpdateS2CPacket packet,CallbackInfo ci){NightCurtain.enforceTime();}
}
