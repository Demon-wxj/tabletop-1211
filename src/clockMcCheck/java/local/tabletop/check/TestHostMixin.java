package local.tabletop.check;

import com.mojang.authlib.GameProfile;
import local.tabletop.ClockSharingCheck;
import net.minecraft.server.dedicated.MinecraftDedicatedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Test fixture: let the isolated dedicated server model a LAN owner's identity. */
@Mixin(MinecraftDedicatedServer.class)
abstract class TestHostMixin {
    @Inject(method="isHost", at=@At("HEAD"), cancellable=true)
    private void testOwner(GameProfile profile, CallbackInfoReturnable<Boolean> result) {
        if (profile.getId().equals(ClockSharingCheck.LAN_OWNER)) result.setReturnValue(true);
    }
}
