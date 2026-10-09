package local.tabletop;

import local.tabletop.game.FlipGame;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import java.util.*;

/** Inventory cards are a projection of server rules, never an input to hand size or score. */
public final class FlipInventory {
    private static final String TAG="tabletop_hand";
    private static final Map<UUID,Map<String,ItemStack>> EXPECTED=new HashMap<>();
    private static final Set<UUID> FULL=new HashSet<>();
    private static final Map<UUID,Integer> USE_TICK=new HashMap<>();
    private FlipInventory() {}
    public static boolean managed(ItemStack stack){return !stack.isEmpty()&&stack.getOrDefault(DataComponentTypes.CUSTOM_DATA,NbtComponent.DEFAULT).copyNbt().contains(TAG);}
    private static NbtCompound tag(ItemStack stack){return stack.getOrDefault(DataComponentTypes.CUSTOM_DATA,NbtComponent.DEFAULT).copyNbt().getCompound(TAG);}
    private static String key(ItemStack stack){return tag(stack).getString("key");}
    private static String label(String id){if(id.startsWith("number_"))return "数字 "+id.substring(7);if(id.startsWith("plus_"))return "+"+id.substring(5);return switch(id){case "times_two"->"×2 翻倍";case "freeze"->"冰冻";case "flip_three"->"闪电 · 连翻三张";case "second_chance"->"保命牌";default->"卡牌";};}
    private static ItemStack card(Sessions.Session s,UUID owner,String id,boolean ready){
        var item=TabletopMod.ITEMS.get(id);if(item==null)return ItemStack.EMPTY;
        var stack=new ItemStack(item);String key=s.id+":"+s.generation+":"+s.inventoryRound+":"+owner+":"+(ready?"action:":"hand:")+id;
        NbtCompound inner=new NbtCompound();inner.putString("key",key);inner.putString("card",id);inner.putString("owner",owner.toString());inner.putBoolean("ready",ready);
        NbtCompound data=new NbtCompound();data.put(TAG,inner);stack.set(DataComponentTypes.CUSTOM_DATA,NbtComponent.of(data));
        boolean fresh=id.equals(s.lastDrawn.get(owner));
        stack.set(DataComponentTypes.MAX_STACK_SIZE,1);
        stack.set(DataComponentTypes.CUSTOM_NAME,Text.literal(ready&&id.equals("second_chance")?"多余保命牌 · 待转赠":label(id)+(ready?" · 待使用":fresh?" · 新抽到":"")).formatted(ready?Formatting.GOLD:Formatting.WHITE).styled(style->style.withItalic(false)));
        stack.set(DataComponentTypes.LORE,new LoreComponent(List.of(Text.literal(ready?"右键玩家；对空右键选人":id.equals("second_chance")?"重复时消耗保命牌，并移除新旧两张同号数字牌":"右键查看手牌").formatted(Formatting.GRAY))));
        return stack;
    }
    static void update(Sessions.Session s,MinecraftServer server,List<FlipGame.Reveal> reveals){
        if(s.flip==null)return;Map<String,Object> state=s.flip.publicView();int round=((Number)state.get("round")).intValue();
        if(s.inventoryRound!=round){s.inventoryRound=round;s.lastDrawn.clear();}
        for(var reveal:reveals)s.lastDrawn.put(reveal.player(),reveal.cardId());
        Map<?,?> rows=(Map<?,?>)state.get("players");Map<?,?> pending=state.get("pendingAction") instanceof Map<?,?> p?p:Map.of();
        for(UUID id:s.roster.keySet()){
            var expected=new LinkedHashMap<String,ItemStack>();Map<?,?> row=(Map<?,?>)rows.get(id.toString());
            if(row!=null&&row.get("cards") instanceof List<?> cards)for(Object value:cards){var stack=card(s,id,String.valueOf(value),false);if(!stack.isEmpty())expected.put(key(stack),stack);}
            if(id.toString().equals(pending.get("owner"))){var stack=card(s,id,String.valueOf(pending.get("kind")),true);if(!stack.isEmpty())expected.put(key(stack),stack);}
            EXPECTED.put(id,expected);var player=server.getPlayerManager().getPlayer(id);if(player!=null)sync(player);
        }
    }
    /** Called after slot interaction so cards cannot be stashed in containers or duplicated. */
    public static void sync(ServerPlayerEntity p){
        Map<String,ItemStack> expected=EXPECTED.getOrDefault(p.getUuid(),Map.of());Set<String> seen=new HashSet<>();boolean changed=false;
        for(int i=0;i<p.getInventory().size();i++){
            ItemStack stack=p.getInventory().getStack(i);if(!managed(stack))continue;String key=key(stack);ItemStack wanted=expected.get(key);
            if(wanted==null||!seen.add(key)){p.getInventory().setStack(i,ItemStack.EMPTY);changed=true;}
            else if(stack.getCount()!=1||!ItemStack.areItemsAndComponentsEqual(stack,wanted)){p.getInventory().setStack(i,wanted.copy());changed=true;}
        }
        var handler=p.currentScreenHandler;ItemStack cursor=handler.getCursorStack();
        if(managed(cursor)){String key=key(cursor);ItemStack wanted=expected.get(key);if(wanted==null||!seen.add(key)){handler.setCursorStack(ItemStack.EMPTY);changed=true;}else if(cursor.getCount()!=1||!ItemStack.areItemsAndComponentsEqual(cursor,wanted)){handler.setCursorStack(wanted.copy());changed=true;}}
        for(var slot:handler.slots)if(slot.inventory!=p.getInventory()&&managed(slot.getStack())){slot.setStack(ItemStack.EMPTY);changed=true;}
        boolean missing=false;
        for(var e:expected.entrySet())if(!seen.contains(e.getKey())){ItemStack copy=e.getValue().copy();if(p.getInventory().insertStack(copy)){changed=true;}else missing=true;}
        if(missing){if(FULL.add(p.getUuid()))TabletopMessages.info(p,"背包已满 · 可在手机查看手牌");}else FULL.remove(p.getUuid());
        if(changed){p.getInventory().markDirty();handler.sendContentUpdates();}
    }
    static void clear(ServerPlayerEntity p){EXPECTED.remove(p.getUuid());FULL.remove(p.getUuid());USE_TICK.remove(p.getUuid());sync(p);}
    static void clearAll(MinecraftServer server){EXPECTED.clear();FULL.clear();USE_TICK.clear();for(var p:server.getPlayerManager().getPlayerList())sync(p);}
    static void clear(Sessions.Session s,MinecraftServer server){for(UUID id:s.roster.keySet()){EXPECTED.remove(id);FULL.remove(id);USE_TICK.remove(id);var p=server.getPlayerManager().getPlayer(id);if(p!=null)sync(p);}s.lastDrawn.clear();s.inventoryRound=-1;}
    static void tick(MinecraftServer server){if(server.getTicks()%10==0)for(var p:server.getPlayerManager().getPlayerList())if(EXPECTED.containsKey(p.getUuid()))sync(p);}
    public static void use(ServerPlayerEntity p,Hand hand,PlayerEntity pointed){
        int tick=p.getServer().getTicks();if(tick-USE_TICK.getOrDefault(p.getUuid(),-100)<5)return;USE_TICK.put(p.getUuid(),tick);
        ItemStack held=p.getStackInHand(hand),wanted=EXPECTED.getOrDefault(p.getUuid(),Map.of()).get(key(held));
        if(wanted==null||!ItemStack.areItemsAndComponentsEqual(held,wanted)){sync(p);return;}
        if(!tag(held).getBoolean("ready")){Sessions.open(p,"hand_cards",true);return;}
        Sessions.Session s=Sessions.current(p);if(s.flip==null)return;
        Map<?,?> pending=s.flip.publicView().get("pendingAction") instanceof Map<?,?> value?value:Map.of();
        if(!p.getUuidAsString().equals(pending.get("owner"))||!tag(held).getString("card").equals(pending.get("kind"))){sync(p);return;}
        PlayerEntity target=pointed==null?lookingAt(p):pointed;
        if(target==null||target.getWorld()!=p.getWorld()||p.squaredDistanceTo(target)>32*32||!p.canSee(target)||!(pending.get("targets") instanceof List<?> allowed)||!allowed.contains(target.getUuidAsString())){
            Sessions.open(p,"flip_targets",true);return;
        }
        Sessions.command(p,"target "+target.getUuidAsString());
    }
    private static PlayerEntity lookingAt(ServerPlayerEntity p){
        Vec3d from=p.getEyePos(),to=from.add(p.getRotationVec(1).multiply(32));
        var block=p.getWorld().raycast(new RaycastContext(from,to,RaycastContext.ShapeType.COLLIDER,RaycastContext.FluidHandling.NONE,p));
        double best=block.getType()==HitResult.Type.MISS?32*32:from.squaredDistanceTo(block.getPos());PlayerEntity selected=null;
        for(var candidate:p.getServerWorld().getPlayers())if(candidate!=p){var hit=candidate.getBoundingBox().expand(.2).raycast(from,to);if(hit.isPresent()){double d=from.squaredDistanceTo(hit.get());if(d<best){best=d;selected=candidate;}}}
        return selected;
    }
}
