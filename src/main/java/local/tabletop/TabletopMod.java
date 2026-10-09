package local.tabletop;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.*;
import net.minecraft.registry.*;
import net.minecraft.text.Text;
import net.minecraft.util.*;
import net.minecraft.world.World;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.command.CommandManager;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.*;

public final class TabletopMod implements ModInitializer {
    public static final String MOD_ID="tabletop";
    public static final Map<String,Item> ITEMS=new LinkedHashMap<>();
    public static Identifier id(String path) { return Identifier.of(MOD_ID,path); }
    public static boolean hasPhone(PlayerEntity p) {
        for(int i=0;i<p.getInventory().size();i++) if(p.getInventory().getStack(i).isOf(ITEMS.get("phone"))) return true;
        return false;
    }
    @Override public void onInitialize() {
        List<String> ids=new ArrayList<>(List.of("phone","grimoire","flip_deck","identity_card","script_card","rules_card","rules_book","faq_book","flip_rules_book","card_back","times_two","freeze","flip_three","second_chance","clock_box","storyteller_wand","vote_token","nomination_token","death_shroud"));
        for(int i=0;i<=12;i++) ids.add("number_"+i);
        for(int i:new int[]{2,4,6,8,10}) ids.add("plus_"+i);
        for(var r:local.tabletop.game.RoleCatalog.all()) ids.add("role_"+r.id());
        for(String key:ids) ITEMS.put(key,Registry.register(Registries.ITEM,id(key),new PropItem(key)));
        TabletopFurniture.register();
        FlipControls.register();
        ClockEmbodiment.register();
        // Phone and reading props open directly, including in front of interactive blocks.
        UseBlockCallback.EVENT.register((player,world,hand,hit)->{
            ActionResult control=FlipControls.interact(player,world,hand,hit);if(control!=ActionResult.PASS)return control;
            if(FlipInventory.managed(player.getStackInHand(hand))){if(player instanceof ServerPlayerEntity p)FlipInventory.use(p,hand,null);return ActionResult.SUCCESS;}
            if(player.getStackInHand(hand).getItem() instanceof PropItem prop && (prop.key.equals("phone")||prop.readingBook()||prop.key.equals("script_card")&&!player.isSneaking())) {
                if(!world.isClient && player instanceof ServerPlayerEntity p) Sessions.open(p,prop.key,true);
                return ActionResult.SUCCESS;
            }
            return ActionResult.PASS;
        });
        UseEntityCallback.EVENT.register((player,world,hand,entity,hit)->{
            if(!FlipInventory.managed(player.getStackInHand(hand)))return ActionResult.PASS;
            if(player instanceof ServerPlayerEntity p)FlipInventory.use(p,hand,entity instanceof PlayerEntity target?target:null);
            return ActionResult.SUCCESS;
        });
        ServerEntityEvents.ENTITY_LOAD.register((entity,world)->{if(entity instanceof ItemEntity item&&FlipInventory.managed(item.getStack()))item.discard();});
        Registry.register(Registries.ITEM_GROUP,id("props"),FabricItemGroup.builder().displayName(Text.translatable("itemGroup.tabletop")).icon(()->ITEMS.get("grimoire").getDefaultStack()).entries((c,e)->ITEMS.values().forEach(e::add)).build());
        PayloadTypeRegistry.playC2S().register(TabletopNet.Action.ID,TabletopNet.Action.CODEC);
        PayloadTypeRegistry.playS2C().register(TabletopNet.View.ID,TabletopNet.View.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(TabletopNet.Action.ID,(packet,ctx)->ctx.server().execute(()->Sessions.action(ctx.player(),packet)));
        ServerPlayConnectionEvents.JOIN.register((handler,sender,server)->{FlipInventory.clear(handler.player);Sessions.rejoin(handler.player);});
        ServerPlayConnectionEvents.DISCONNECT.register((handler,server)->Sessions.disconnect(handler.player));
        ServerTickEvents.END_SERVER_TICK.register(server->{Sessions.tick(server);TabletopFurniture.tick(server);FlipControls.tick(server);});
        ServerLifecycleEvents.SERVER_STOPPING.register(server->{FlipInventory.clearAll(server);Sessions.clear();TabletopFurniture.clear();FlipControls.clear();});
        CommandRegistrationCallback.EVENT.register((dispatcher,registry,environment)-> {
            var node=dispatcher.register(CommandManager.literal("tabletop")
                .executes(ctx->Sessions.command(ctx.getSource().getPlayerOrThrow(),"help"))
                .then(CommandManager.argument("arguments",StringArgumentType.greedyString()).executes(ctx->Sessions.command(ctx.getSource().getPlayerOrThrow(),StringArgumentType.getString(ctx,"arguments")))));
            dispatcher.register(CommandManager.literal("tt").redirect(node));
        });
        ClockRoomDoors.register();
    }
    public static final class PropItem extends Item {
        private final String key;
        PropItem(String key) { super(new Settings().maxCount(key.equals("phone")||key.equals("grimoire")||key.equals("flip_deck")||key.equals("identity_card")||key.equals("rules_book")||key.equals("faq_book")||key.equals("flip_rules_book")?1:64)); this.key=key; }
        private boolean readingBook() { return key.equals("rules_book")||key.equals("faq_book")||key.equals("flip_rules_book"); }
        @Override public TypedActionResult<ItemStack> use(World world,PlayerEntity player,Hand hand) {
            if(ClockEmbodiment.managed(player.getStackInHand(hand))){if(player instanceof ServerPlayerEntity p)ClockEmbodiment.use(p,hand);return TypedActionResult.success(player.getStackInHand(hand),world.isClient);}
            if(FlipInventory.managed(player.getStackInHand(hand))){if(player instanceof ServerPlayerEntity p)FlipInventory.use(p,hand,null);return TypedActionResult.success(player.getStackInHand(hand),world.isClient);}
            if(!world.isClient && player instanceof ServerPlayerEntity p) {
                if(key.equals("flip_deck")) Sessions.useDeck(p,p.isSneaking());
                else if(key.equals("storyteller_wand")){if(p.isSneaking())Sessions.command(p,"phones");else Sessions.open(p,"grimoire",true);}
                else if(key.equals("clock_box"))Sessions.open(p,"grimoire",true);
                else Sessions.open(p,key,true);
            }
            return TypedActionResult.success(player.getStackInHand(hand),world.isClient);
        }
        @Override public ActionResult useOnBlock(ItemUsageContext context) {
            if(ClockEmbodiment.managed(context.getStack())){if(context.getPlayer() instanceof ServerPlayerEntity p)ClockEmbodiment.use(p,context.getHand());return ActionResult.SUCCESS;}
            if(FlipInventory.managed(context.getStack())){if(context.getPlayer() instanceof ServerPlayerEntity p)FlipInventory.use(p,context.getHand(),null);return ActionResult.SUCCESS;}
            if(!context.getWorld().isClient && context.getPlayer() instanceof ServerPlayerEntity p) {
                if(key.equals("phone")||readingBook()||key.equals("script_card")&&!p.isSneaking()) Sessions.open(p,key,true);
                else if(key.equals("flip_deck") && p.isSneaking()) Sessions.create(p,"flip",context.getBlockPos().up());
                else if((key.equals("grimoire")||key.equals("clock_box")) && p.isSneaking()) Sessions.create(p,"clock",context.getBlockPos().up());
                else if(key.startsWith("number_")||key.startsWith("plus_")||key.startsWith("role_")||List.of("card_back","script_card","rules_card","freeze","flip_three","second_chance","times_two","clock_box","grimoire","storyteller_wand","vote_token","nomination_token","death_shroud").contains(key)) Sessions.placeProp(p,key,context.getBlockPos().up(),context.getStack());
                else return super.useOnBlock(context);
            }
            return ActionResult.success(context.getWorld().isClient);
        }
    }
}
