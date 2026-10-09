package local.tabletop;

import com.mojang.serialization.MapCodec;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.block.*;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.*;
import net.minecraft.registry.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.text.Text;
import net.minecraft.util.*;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.AffineTransformation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.*;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import java.util.*;

/** Small persistent table buttons; game state still changes only through Sessions/FlipGame. */
public final class FlipControls {
    public static Block DRAW,STAY;
    private static final String LABEL="tabletop_flip_control_label";
    private static final Map<UUID,Integer> USED_AT=new HashMap<>();
    private static final Map<String,DisplayEntity.TextDisplayEntity> LABELS=new HashMap<>();
    private FlipControls(){}
    public static void register(){
        DRAW=register("flip_draw",false);STAY=register("flip_stay",true);
        ServerEntityEvents.ENTITY_LOAD.register((entity,world)->{if(entity.getCommandTags().contains(LABEL)&&!LABELS.containsValue(entity))entity.discard();});
    }
    private static Block register(String id,boolean stay){
        var block=Registry.register(Registries.BLOCK,TabletopMod.id(id),new ControlBlock(AbstractBlock.Settings.create().strength(.7f).sounds(BlockSoundGroup.WOOD).nonOpaque(),stay));
        TabletopMod.ITEMS.put(id,Registry.register(Registries.ITEM,TabletopMod.id(id),new BlockItem(block,new Item.Settings())));return block;
    }
    public static boolean isControl(BlockState state){return state.isOf(DRAW)||state.isOf(STAY);}
    /** This runs before held-phone/book callbacks so the physical button wins. */
    public static ActionResult interact(PlayerEntity player,World world,Hand hand,BlockHitResult hit){
        BlockState state=world.getBlockState(hit.getBlockPos());if(!isControl(state))return ActionResult.PASS;
        if(!world.isClient&&hand==Hand.MAIN_HAND&&player instanceof ServerPlayerEntity serverPlayer)use(serverPlayer,hit.getBlockPos(),state.isOf(STAY));
        return ActionResult.SUCCESS;
    }
    static void use(ServerPlayerEntity player,BlockPos pos,boolean stay){
        try{
            if(player.isSpectator()||!player.isAlive()||player.squaredDistanceTo(pos.getX()+.5,pos.getY()+.1,pos.getZ()+.5)>36)return;
            int tick=player.getServer().getTicks();if(tick-USED_AT.getOrDefault(player.getUuid(),-100)<5)return;USED_AT.put(player.getUuid(),tick);
            ServerWorld world=player.getServerWorld();Sessions.Session s=Sessions.GAMES.get(Sessions.MEMBERS.get(player.getUuid()));
            if(s==null){
                s=FlipTableLayout.bound(world,pos);if(s==null)throw new IllegalStateException("先在牌桌中心用七连翻牌堆准备游戏。");
                if(s.running)throw new IllegalStateException("本局已开始，请等待下一局。");
                if(Sessions.joinFlipStation(player,s,pos))TabletopMessages.info(player,"已入座 · 等主持人开始");return;
            }
            if(!s.type.equals("flip")||!FlipTableLayout.accepts(FlipTableLayout.station(world,pos),s,world,pos))throw new IllegalStateException("请使用自己这桌的操作道具。");
            if(!s.offline.isEmpty())throw new IllegalStateException("本桌有人离线，等待重连。");
            int buttonSeat=FlipTableLayout.stationSeat(s,world,pos),ownSeat=Sessions.flipSeat(s,player.getUuid());
            if(s.running&&buttonSeat>0&&buttonSeat!=ownSeat)throw new IllegalStateException("请使用自己 "+ownSeat+" 号座位的摸牌或收手按钮。");
            String action=stay?"stay":"draw";
            if(!stay){
                if(s.flip==null){Sessions.host(s,player.getUuid());action="start";}
                else if(s.flip.finished()){
                    Sessions.host(s,player.getUuid());if(Sessions.command(player,"reset")!=1)return;action="start";
                }else if(s.flip.roundOver()){Sessions.host(s,player.getUuid());action="next";}
                else if(s.flip.publicView().get("pendingAction") instanceof Map<?,?> pending&&player.getUuidAsString().equals(pending.get("owner"))){Sessions.open(player,"flip_targets",true);return;}
            }
            if(Sessions.physicalFlipCommand(player,action)){
                FlipTableLayout.remember(s,player,pos,action.equals("draw")||action.equals("stay"));Sessions.renderTable(s,player.getServer());
                if(s.flip!=null&&s.flip.publicView().get("pendingAction") instanceof Map<?,?> pending&&player.getUuidAsString().equals(pending.get("owner")))Sessions.open(player,"flip_targets",true);
            }
        }catch(RuntimeException error){TabletopMessages.info(player,error.getMessage()==null?"操作未完成。":error.getMessage());}
    }
    public static void tick(MinecraftServer server){
        if(server.getTicks()%20!=0)return;Set<String> visible=new HashSet<>();
        for(var station:FlipTableLayout.stations(server)){
            ServerWorld world=server.getWorld(RegistryKey.of(RegistryKeys.WORLD,Identifier.of(station.dimension)));if(world==null||!world.isChunkLoaded(station.pos()))continue;
            BlockState state=world.getBlockState(station.pos());if(!isControl(state))continue;
            visible.add(labelKey(world,station.pos()));showLabel(world,station.pos());
        }
        for(var iterator=LABELS.entrySet().iterator();iterator.hasNext();){var entry=iterator.next();if(entry.getValue().getWorld().getServer()==server&&!visible.contains(entry.getKey())){entry.getValue().discard();iterator.remove();}}
    }
    private static String labelKey(ServerWorld world,BlockPos pos){return world.getRegistryKey().getValue()+"|"+pos.asLong();}
    private static void showLabel(ServerWorld world,BlockPos pos){
        BlockState state=world.getBlockState(pos);if(!isControl(state))return;String id=labelKey(world,pos);var label=LABELS.get(id);if(label!=null&&!label.isRemoved())return;
        label=new DisplayEntity.TextDisplayEntity(EntityType.TEXT_DISPLAY,world);label.addCommandTag(LABEL);label.setBillboardMode(DisplayEntity.BillboardMode.CENTER);label.setViewRange(.35f);label.setBackground(0x99202925);
        label.setText(Text.literal(state.isOf(STAY)?"收手":"摸牌").formatted(state.isOf(STAY)?Formatting.GOLD:Formatting.GREEN));
        label.setTransformation(new AffineTransformation(new Vector3f(),new Quaternionf(),new Vector3f(.27f),new Quaternionf()));label.setPosition(pos.getX()+.5,pos.getY()+.25,pos.getZ()+.5);LABELS.put(id,label);world.spawnEntity(label);
    }
    public static void clear(){for(var label:LABELS.values())label.discard();LABELS.clear();USED_AT.clear();FlipTableLayout.clear();}
    private static final class ControlBlock extends Block {
        private static final MapCodec<ControlBlock> DRAW_CODEC=createCodec(settings->new ControlBlock(settings,false)),STAY_CODEC=createCodec(settings->new ControlBlock(settings,true));
        private static final VoxelShape SHAPE=createCuboidShape(2,0,2,14,2,14);
        private final boolean stay;
        ControlBlock(Settings settings,boolean stay){super(settings);this.stay=stay;}
        @Override protected MapCodec<? extends Block> getCodec(){return stay?STAY_CODEC:DRAW_CODEC;}
        @Override protected VoxelShape getOutlineShape(BlockState state,BlockView world,BlockPos pos,ShapeContext context){return SHAPE;}
        @Override protected VoxelShape getCollisionShape(BlockState state,BlockView world,BlockPos pos,ShapeContext context){return SHAPE;}
        @Override protected ActionResult onUse(BlockState state,World world,BlockPos pos,PlayerEntity player,BlockHitResult hit){return interact(player,world,Hand.MAIN_HAND,hit);}
        @Override public void onPlaced(World world,BlockPos pos,BlockState state,LivingEntity placer,ItemStack stack){super.onPlaced(world,pos,state,placer,stack);if(world instanceof ServerWorld serverWorld&&placer instanceof ServerPlayerEntity player){FlipTableLayout.placed(serverWorld,pos,player);showLabel(serverWorld,pos);}}
        @Override protected void onStateReplaced(BlockState state,World world,BlockPos pos,BlockState replacement,boolean moved){if(!state.isOf(replacement.getBlock())&&world instanceof ServerWorld serverWorld){FlipTableLayout.removed(serverWorld,pos);var label=LABELS.remove(labelKey(serverWorld,pos));if(label!=null)label.discard();}super.onStateReplaced(state,world,pos,replacement,moved);}
    }
}
