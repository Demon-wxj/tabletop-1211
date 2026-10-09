package local.tabletop.mixin;

import local.tabletop.FlipInventory;
import local.tabletop.ClockEmbodiment;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ScreenHandler.class)
public abstract class HandCardSlotsMixin {
    @Inject(method="onSlotClick",at=@At("HEAD"),cancellable=true)
    private void tabletop$keepSceneEquipment(int slot,int button,SlotActionType type,PlayerEntity player,CallbackInfo ci){
        if(!(player instanceof ServerPlayerEntity serverPlayer))return;var handler=(ScreenHandler)(Object)this;
        boolean cursor=ClockEmbodiment.managed(handler.getCursorStack());boolean held=slot>=0&&slot<handler.slots.size()&&ClockEmbodiment.managed(handler.slots.get(slot).getStack());boolean swapped=type==SlotActionType.SWAP&&button>=0&&button<player.getInventory().size()&&ClockEmbodiment.managed(player.getInventory().getStack(button));
        boolean armor=ClockEmbodiment.disguised(player.getUuid())&&slot>=0&&slot<handler.slots.size()&&handler.slots.get(slot).inventory==player.getInventory()&&handler.slots.get(slot).getIndex()>=36;
        if(armor||((cursor||held||swapped)&&(type==SlotActionType.THROW||type==SlotActionType.CLONE||type==SlotActionType.QUICK_MOVE||type==SlotActionType.SWAP&&button==40||slot<0||slot>=handler.slots.size()||handler.slots.get(slot).inventory!=player.getInventory()))){ci.cancel();handler.syncState();}
    }
    @Inject(method="onSlotClick",at=@At("TAIL"))
    private void tabletop$syncHand(int slot,int button,SlotActionType type,PlayerEntity player,CallbackInfo ci){
        if(player instanceof ServerPlayerEntity serverPlayer){FlipInventory.sync(serverPlayer);ClockEmbodiment.syncInventory(serverPlayer);}
    }
}
