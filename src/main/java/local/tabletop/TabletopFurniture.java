package local.tabletop;

import com.mojang.serialization.MapCodec;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.block.*;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.*;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.*;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.*;
import net.minecraft.world.*;
import java.util.*;

/** Physical props. Sitting never changes game identity, fixed rule seat order, or chat permissions. */
public final class TabletopFurniture {
    public static Block GRIMOIRE_LECTERN, CHAIR;
    private record Seat(BlockPos pos, ArmorStandEntity vehicle) {}
    private static final Map<UUID,Seat> SEATED=new HashMap<>();
    private TabletopFurniture(){}

    public static void register(){
        GRIMOIRE_LECTERN=Registry.register(Registries.BLOCK,TabletopMod.id("grimoire_lectern"),new Lectern(AbstractBlock.Settings.create().strength(2.5f).sounds(BlockSoundGroup.WOOD).nonOpaque()));
        CHAIR=Registry.register(Registries.BLOCK,TabletopMod.id("tabletop_chair"),new Chair(AbstractBlock.Settings.create().strength(2f).sounds(BlockSoundGroup.WOOD).nonOpaque()));
        for(var e:Map.of("grimoire_lectern",GRIMOIRE_LECTERN,"tabletop_chair",CHAIR).entrySet())
            TabletopMod.ITEMS.put(e.getKey(),Registry.register(Registries.ITEM,TabletopMod.id(e.getKey()),new BlockItem(e.getValue(),new Item.Settings())));
        ServerEntityEvents.ENTITY_LOAD.register((entity,world)->{
            if(entity.getCommandTags().contains("tabletop_seat")&&SEATED.values().stream().noneMatch(s->s.vehicle==entity))entity.discard();
        });
    }
    public static void tick(MinecraftServer server){
        if(server.getTicks()%10!=0)return;
        for(var iterator=SEATED.entrySet().iterator();iterator.hasNext();){var e=iterator.next();var s=e.getValue();var p=server.getPlayerManager().getPlayer(e.getKey());
            if(p==null||!p.isAlive()||s.vehicle.isRemoved()||p.getVehicle()!=s.vehicle||!s.vehicle.getWorld().getBlockState(s.pos).isOf(CHAIR)){
                if(p!=null&&p.getVehicle()==s.vehicle)p.stopRiding();s.vehicle.discard();iterator.remove();
            }
        }
    }
    public static void clear(){for(var seat:SEATED.values())seat.vehicle.discard();SEATED.clear();}
    private static void sit(ServerPlayerEntity p,BlockPos pos,BlockState state){
        if(p.isSpectator()||!p.isAlive())return;
        if(p.squaredDistanceTo(pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5)>16)return;
        if(p.hasVehicle()){TabletopMessages.info(p,"请先按潜行键离开当前座位或坐骑。");return;}
        // Dismount and a second click can arrive before the periodic cleanup tick.
        // Remove this player's old carrier before replacing their entry or it would become orphaned.
        var previous=SEATED.remove(p.getUuid());if(previous!=null)previous.vehicle.discard();
        for(var iterator=SEATED.entrySet().iterator();iterator.hasNext();){
            var entry=iterator.next();var occupied=entry.getValue();
            if(occupied.vehicle.getWorld()!=p.getWorld()||!occupied.pos.equals(pos))continue;
            var occupant=p.getServer().getPlayerManager().getPlayer(entry.getKey());
            if(occupant==null||!occupant.isAlive()||occupied.vehicle.isRemoved()||occupant.getVehicle()!=occupied.vehicle){occupied.vehicle.discard();iterator.remove();continue;}
            TabletopMessages.info(p,"这个座位已经有人了。");return;
        }
        // PlayerEntity.VEHICLE_ATTACHMENT_POS is (0,.6,0); a marker stand has no
        // passenger-height offset. Anchor the player's hip at the 8px cushion.
        var vehicle=new ArmorStandEntity(p.getServerWorld(),pos.getX()+.5,pos.getY()+.52,pos.getZ()+.5);
        var nbt=new NbtCompound();nbt.putBoolean("Marker",true);nbt.putBoolean("Invisible",true);nbt.putBoolean("NoGravity",true);nbt.putBoolean("Invulnerable",true);nbt.putBoolean("Silent",true);vehicle.readNbt(nbt);
        vehicle.setPosition(pos.getX()+.5,pos.getY()+.52,pos.getZ()+.5);vehicle.addCommandTag("tabletop_seat");
        var seat=new Seat(pos.toImmutable(),vehicle);SEATED.put(p.getUuid(),seat);p.getServerWorld().spawnEntity(vehicle);
        if(!p.startRiding(vehicle,true)){SEATED.remove(p.getUuid());vehicle.discard();return;}
        TabletopMessages.info(p,"已坐下 · 潜行起身 · 右键或 H 使用手机。");
    }
    private abstract static class FacingProp extends Block{
        FacingProp(Settings settings){super(settings);setDefaultState(getStateManager().getDefaultState().with(Properties.HORIZONTAL_FACING,net.minecraft.util.math.Direction.NORTH));}
        @Override protected void appendProperties(StateManager.Builder<Block,BlockState> builder){builder.add(Properties.HORIZONTAL_FACING);}
        @Override public BlockState getPlacementState(ItemPlacementContext context){return getDefaultState().with(Properties.HORIZONTAL_FACING,context.getHorizontalPlayerFacing().getOpposite());}
        @Override protected BlockState rotate(BlockState state,BlockRotation rotation){return state.with(Properties.HORIZONTAL_FACING,rotation.rotate(state.get(Properties.HORIZONTAL_FACING)));}
        @Override protected BlockState mirror(BlockState state,BlockMirror mirror){return state.rotate(mirror.getRotation(state.get(Properties.HORIZONTAL_FACING)));}
    }
    private static final class Lectern extends FacingProp{
        static final MapCodec<Lectern> CODEC=createCodec(Lectern::new);
        static final VoxelShape SHAPE=VoxelShapes.union(createCuboidShape(1,0,1,15,3,15),createCuboidShape(5,3,5,11,12,11),createCuboidShape(0,12,0,16,22,16));
        Lectern(Settings settings){super(settings);}
        @Override protected MapCodec<? extends Block> getCodec(){return CODEC;}
        @Override protected VoxelShape getOutlineShape(BlockState state,BlockView world,BlockPos pos,ShapeContext context){return SHAPE;}
        @Override protected VoxelShape getCollisionShape(BlockState state,BlockView world,BlockPos pos,ShapeContext context){return SHAPE;}
        @Override protected ActionResult onUse(BlockState state,World world,BlockPos pos,PlayerEntity player,BlockHitResult hit){
            if(!world.isClient&&player instanceof ServerPlayerEntity p)Sessions.openGrimoireBlock(p,pos);
            return ActionResult.success(world.isClient);
        }
    }
    private static final class Chair extends FacingProp{
        static final MapCodec<Chair> CODEC=createCodec(Chair::new);
        static final VoxelShape SEAT_SHAPE=createCuboidShape(2,0,2,14,8,14);
        static VoxelShape shape(BlockState state){return VoxelShapes.union(SEAT_SHAPE,switch(state.get(Properties.HORIZONTAL_FACING)){
            case NORTH->createCuboidShape(2,8,12,14,18,15);
            case EAST->createCuboidShape(1,8,2,4,18,14);
            case SOUTH->createCuboidShape(2,8,1,14,18,4);
            case WEST->createCuboidShape(12,8,2,15,18,14);
            default->VoxelShapes.empty();
        });}
        Chair(Settings settings){super(settings);}
        @Override protected MapCodec<? extends Block> getCodec(){return CODEC;}
        @Override protected VoxelShape getOutlineShape(BlockState state,BlockView world,BlockPos pos,ShapeContext context){return shape(state);}
        @Override protected VoxelShape getCollisionShape(BlockState state,BlockView world,BlockPos pos,ShapeContext context){return shape(state);}
        @Override protected ActionResult onUse(BlockState state,World world,BlockPos pos,PlayerEntity player,BlockHitResult hit){
            if(!world.isClient&&player instanceof ServerPlayerEntity p)sit(p,pos,state);
            return ActionResult.success(world.isClient);
        }
    }
}
