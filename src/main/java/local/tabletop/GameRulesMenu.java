package local.tabletop;

import com.google.gson.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.event.player.*;
import net.fabricmc.fabric.api.networking.v1.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.*;
import net.minecraft.registry.*;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.*;
import java.util.*;

final class GameRulesMenu {
    static Item item;
    private static final Gson JSON=new Gson();
    private static final boolean HUB=FabricLoader.getInstance().isModLoaded("megawalls");
    private record Edit(String game,String ticket,Map<String,Integer> before){}
    private static final Map<UUID,Edit> open=new HashMap<>();
    static void register(){
        item=Registry.register(Registries.ITEM,TabletopMod.id("game_settings"),new Item(new Item.Settings().maxCount(1)){
            @Override public TypedActionResult<ItemStack> use(net.minecraft.world.World w,net.minecraft.entity.player.PlayerEntity p,Hand h){return TypedActionResult.success(p.getStackInHand(h));}
        });TabletopMod.ITEMS.put("game_settings",item);
        // Let the client send its selected hotbar slot before the vanilla use packet, including a same-tick scroll + click.
        UseItemCallback.EVENT.register((p,w,h)->{var st=p.getStackInHand(h);if(!st.isOf(item)||!(p instanceof ServerPlayerEntity sp))return TypedActionResult.pass(st);open(sp);return TypedActionResult.success(st);});
        UseBlockCallback.EVENT.register((p,w,h,hit)->{if(!p.getStackInHand(h).isOf(item))return ActionResult.PASS;if(p instanceof ServerPlayerEntity sp)open(sp);return ActionResult.SUCCESS;});
        UseEntityCallback.EVENT.register((p,w,h,e,hit)->{if(!p.getStackInHand(h).isOf(item))return ActionResult.PASS;if(p instanceof ServerPlayerEntity sp)open(sp);return ActionResult.SUCCESS;});
        ServerEntityEvents.ENTITY_LOAD.register((e,w)->{if(e instanceof ItemEntity drop&&drop.getStack().isOf(item))drop.discard();});
        ServerTickEvents.END_SERVER_TICK.register(s->{if(s.getTicks()%10==0)for(var p:s.getPlayerManager().getPlayerList())sync(p);});
        ServerPlayConnectionEvents.DISCONNECT.register((h,s)->open.remove(h.player.getUuid()));ServerLifecycleEvents.SERVER_STOPPED.register(s->open.clear());
    }
    static String game(ServerPlayerEntity p){
        String table=Sessions.gameOf(p);if(!table.isEmpty())return table.equals("flip")?GameRuleList.FLIP:"";
        if(HUB)for(String tag:p.getCommandTags())if(tag.startsWith("mg_game_")){String g=tag.substring(8);return GameRuleList.LISTS.containsKey(g)?g:"";}
        return "";
    }
    static boolean waiting(ServerPlayerEntity p,String g)throws ReflectiveOperationException{
        if(g.equals(GameRuleList.FLIP))return Sessions.GAMES.values().stream().noneMatch(t->t.server==p.getServer()&&t.type.equals("flip")&&t.running&&!t.ended());
        return ArenaRules.of(g).waiting();
    }
    static void sync(ServerPlayerEntity p){
        String g=game(p);boolean need=!g.isEmpty()&&p.isAlive()&&!p.isSpectator();
        if(need)try{need=waiting(p,g);}catch(ReflectiveOperationException e){need=false;}
        var inv=p.getInventory();boolean found=false,changed=false;
        Text label=need?Text.literal(GameRuleList.TITLES.get(g)+" · 对局设置").formatted(Formatting.AQUA):null;
        for(int i=0;i<inv.size();i++){var stack=inv.getStack(i);if(!stack.isOf(item))continue;
            if(!need||found){inv.setStack(i,ItemStack.EMPTY);changed=true;}else{found=true;if(!label.equals(stack.get(DataComponentTypes.CUSTOM_NAME))){stack.set(DataComponentTypes.CUSTOM_NAME,label);changed=true;}}
        }
        if(need&&!found){int slot=inv.getEmptySlot();if(slot>=0){var stack=item.getDefaultStack();stack.set(DataComponentTypes.CUSTOM_NAME,label);inv.setStack(slot,stack);changed=true;}}
        if(changed){inv.markDirty();p.currentScreenHandler.syncState();}
    }
    private static Map<String,Integer> read(ServerPlayerEntity p,String g)throws ReflectiveOperationException{return g.equals(GameRuleList.FLIP)?FlipOptions.get(p.getServer()).values():ArenaRules.of(g).read();}
    private static boolean editable(ServerPlayerEntity p,String g)throws ReflectiveOperationException{return p.isAlive()&&!p.isSpectator()&&waiting(p,g);}
    static void open(ServerPlayerEntity p){
        String g=game(p);if(g.isEmpty()){Sessions.tell(p,"请先进入对应的小游戏大厅。");return;}
        try{show(p,g,"",false);}catch(Exception e){Sessions.tell(p,message(e));}
    }
    private static String message(Exception e){if(e instanceof IllegalArgumentException||e instanceof IllegalStateException)return e.getMessage();org.slf4j.LoggerFactory.getLogger("tabletop").error("Could not update minigame rules",e);return "读取或保存失败，请查看游戏日志。";}
    private static void show(ServerPlayerEntity p,String g,String note,boolean success)throws ReflectiveOperationException{
        var v=read(p,g);var edit=new Edit(g,UUID.randomUUID().toString(),Map.copyOf(v));open.put(p.getUuid(),edit);
        boolean allowed=editable(p,g);var packet=Map.of("kind","game_settings","game",g,"title",GameRuleList.TITLES.get(g),"ticket",edit.ticket(),"values",v,"editable",allowed,"message",note,"saved",success);
        if(ServerPlayNetworking.canSend(p,TabletopNet.View.ID))ServerPlayNetworking.send(p,new TabletopNet.View(JSON.toJson(packet),note.isEmpty()));
    }
    static void apply(ServerPlayerEntity p,String ticket,String raw){
        Edit e=open.get(p.getUuid());if(e==null||!e.ticket().equals(ticket))return;
        String g=game(p);
        if(!g.equals(e.game())||!p.getInventory().contains(s->s.isOf(item))){open.remove(p.getUuid());Sessions.tell(p,"你已离开准备区，请重新进入后打开设置。");return;}
        String reply;boolean ok=false;
        try{
            if(!editable(p,g))throw new IllegalStateException("本局已经开始，请在准备阶段修改。");
            if(!e.before().equals(read(p,g)))throw new IllegalStateException("其他玩家已修改设置，已刷新当前值。");
            var rules=GameRuleList.LISTS.get(g);var values=new LinkedHashMap<String,Integer>();
            String[] a=raw.split(",",-1);if(a.length!=rules.size())throw new IllegalArgumentException("参数不完整。");
            for(int i=0;i<a.length;i++){if(!a[i].matches("[0-9]{1,5}"))throw new IllegalArgumentException("请输入范围内的整数。");values.put(rules.get(i).key(),Integer.parseInt(a[i]));}GameRuleList.validate(g,values);
            if(g.equals(GameRuleList.FLIP)){
                if(Sessions.GAMES.values().stream().anyMatch(s->s.server==p.getServer()&&s.type.equals("flip")&&s.roster.size()>values.get("max")))throw new IllegalArgumentException("人数上限不能小于当前桌上的人数。");
                FlipOptions.save(p.getServer(),values);
            }else ArenaRules.of(g).write(p.getServer(),values);
            reply="已保存至本地图，下一局使用新参数。";ok=true;
        }catch(Exception x){reply=message(x);}
        try{show(p,g,reply,ok);}catch(Exception x){Sessions.tell(p,message(x));}
    }
    private GameRulesMenu(){}
}
