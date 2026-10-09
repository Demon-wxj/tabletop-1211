package local.tabletop;

import com.mojang.serialization.MapCodec;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.block.*;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.block.piston.PistonBehavior;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.*;
import net.minecraft.registry.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.*;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** Only configured room doors are replaced; their complete original states are journaled first. */
public final class ClockRoomDoors {
    private static final Logger LOGGER=LoggerFactory.getLogger("tabletop/room-doors");
    private static final int FLAGS=Block.NOTIFY_LISTENERS|Block.FORCE_STATE|Block.SKIP_DROPS;
    private static final Map<MinecraftServer,Prepared> ACTIVE=new WeakHashMap<>();
    public static Block ROOM_DOOR;
    private record Saved(RegistryKey<World> dimension,BlockPos pos,BlockState lower,BlockState upper) {}
    private record Prepared(String session,List<Saved> doors) {}
    private ClockRoomDoors(){}

    public static void register(){
        ROOM_DOOR=Registry.register(Registries.BLOCK,TabletopMod.id("clock_room_door"),
                new RoomDoor(AbstractBlock.Settings.copy(Blocks.IRON_DOOR).strength(-1f,3_600_000f).pistonBehavior(PistonBehavior.BLOCK).dropsNothing()));
        ServerLifecycleEvents.SERVER_STARTED.register(ClockRoomDoors::restore);
        ServerLifecycleEvents.SERVER_STOPPING.register(ClockRoomDoors::restore);
    }

    private static Path recovery(MinecraftServer server){return server.getSavePath(WorldSavePath.ROOT).resolve("tabletop/clock-doors-recovery.dat");}

    public static void prepare(Sessions.Session session,ClockArena.Layout layout,MinecraftServer server){
        if(layout==null||!layout.enabled)return;
        Prepared active=ACTIVE.get(server);
        if(active!=null){
            if(active.session.equals(session.id)){
                for(Saved saved:active.doors){
                    ServerWorld activeWorld=server.getWorld(saved.dimension);
                    if(activeWorld==null||!isRoomDoor(activeWorld,saved.pos)||!isRoomDoor(activeWorld,saved.pos.up()))throw new IllegalStateException("房门恢复尚未完成，请先检查原恢复记录。");
                }
                return;
            }
            throw new IllegalStateException("另一桌的房门尚未恢复。");
        }
        if(Files.exists(recovery(server)))restore(server);
        ServerWorld world=server.getWorld(session.dimension);
        if(world==null||!world.getRegistryKey().getValue().toString().equals(layout.dimension))throw new IllegalStateException("房门维度不匹配。");
        var originals=new ArrayList<Saved>();var positions=new HashSet<BlockPos>();
        for(UUID player:Sessions.clockPlayers(session)){
            int number=TabletopPresentation.seat(session,player);ClockArena.Room room=layout.rooms.get(number);
            if(room==null||room.door==null)throw new IllegalStateException(number+" 号房门未设置。");
            BlockPos pos=room.door.block();BlockState lower=world.getBlockState(pos),upper=world.getBlockState(pos.up());
            if(!(lower.getBlock() instanceof DoorBlock)||lower.isOf(ROOM_DOOR)||!upper.isOf(lower.getBlock())
                    ||lower.get(DoorBlock.HALF)!=DoubleBlockHalf.LOWER||upper.get(DoorBlock.HALF)!=DoubleBlockHalf.UPPER)
                throw new IllegalStateException(number+" 号房门必须有完整上下两半。");
            if(world.getBlockEntity(pos)!=null||world.getBlockEntity(pos.up())!=null)throw new IllegalStateException(number+" 号房门含有额外数据，请改用普通铁门。");
            if(!positions.add(pos)||!positions.add(pos.up()))throw new IllegalStateException("不同房间不能共用房门。");
            originals.add(new Saved(session.dimension,pos.toImmutable(),lower,upper));
        }
        if(originals.isEmpty())return;
        try{
            write(server,originals);
            ACTIVE.put(server,new Prepared(session.id,List.copyOf(originals)));
            for(Saved saved:originals){
                put(world,saved.pos,temporary(saved.lower,DoubleBlockHalf.LOWER));
                put(world,saved.pos.up(),temporary(saved.upper,DoubleBlockHalf.UPPER));
            }
        }catch(Exception error){
            try{restore(server);}catch(Exception restoreError){error.addSuppressed(restoreError);}
            throw new IllegalStateException("钟楼房门准备失败，请检查恢复状态。",error);
        }
    }

    private static BlockState temporary(BlockState original,DoubleBlockHalf half){
        return ROOM_DOOR.getDefaultState().with(DoorBlock.FACING,original.get(DoorBlock.FACING))
                .with(DoorBlock.HINGE,original.get(DoorBlock.HINGE)).with(DoorBlock.HALF,half)
                .with(DoorBlock.OPEN,false).with(DoorBlock.POWERED,false);
    }
    private static void put(ServerWorld world,BlockPos pos,BlockState state){
        if(!world.getBlockState(pos).equals(state)&&!world.setBlockState(pos,state,FLAGS))throw new IllegalStateException("房门方块无法写入："+pos.toShortString());
    }

    public static boolean isRoomDoor(World world,BlockPos pos){return ROOM_DOOR!=null&&world.getBlockState(pos).isOf(ROOM_DOOR);}

    public static boolean setOpen(ServerWorld world,ClockArena.Door door,boolean open){
        if(world==null||door==null)return false;
        Prepared active=ACTIVE.get(world.getServer());
        if(active==null||active.doors.stream().noneMatch(s->s.dimension.equals(world.getRegistryKey())&&s.pos.equals(door.block())))return false;
        BlockPos pos=door.block();BlockState lower=world.getBlockState(pos),upper=world.getBlockState(pos.up());
        if(!lower.isOf(ROOM_DOOR)||!upper.isOf(ROOM_DOOR)||lower.get(DoorBlock.HALF)!=DoubleBlockHalf.LOWER||upper.get(DoorBlock.HALF)!=DoubleBlockHalf.UPPER)return false;
        try{
            put(world,pos,lower.with(DoorBlock.OPEN,open).with(DoorBlock.POWERED,false));
            put(world,pos.up(),upper.with(DoorBlock.OPEN,open).with(DoorBlock.POWERED,false));
        }catch(RuntimeException error){
            if(isRoomDoor(world,pos))put(world,pos,lower);
            if(isRoomDoor(world,pos.up()))put(world,pos.up(),upper);
            throw error;
        }
        return true;
    }

    private static void write(MinecraftServer server,List<Saved> originals)throws Exception{
        NbtCompound root=new NbtCompound();root.putInt("version",1);NbtList entries=new NbtList();
        for(Saved saved:originals){
            NbtCompound entry=new NbtCompound();entry.putString("dimension",saved.dimension.getValue().toString());entry.putLong("pos",saved.pos.asLong());
            entry.put("lower",NbtHelper.fromBlockState(saved.lower));entry.put("upper",NbtHelper.fromBlockState(saved.upper));entries.add(entry);
        }
        root.put("doors",entries);Path path=recovery(server);Files.createDirectories(path.getParent());Path temp=path.resolveSibling(path.getFileName()+".tmp");
        NbtIo.writeCompressed(root,temp);try(var channel=FileChannel.open(temp,StandardOpenOption.WRITE)){channel.force(true);}
        try{Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(temp,path);}
    }

    private static List<Saved> read(MinecraftServer server)throws Exception{
        NbtCompound root=NbtIo.readCompressed(recovery(server),NbtSizeTracker.of(1_048_576));
        if(root.getInt("version")!=1||!root.contains("doors",NbtElement.LIST_TYPE))throw new IllegalStateException("房门恢复记录格式错误。");
        NbtList entries=root.getList("doors",NbtElement.COMPOUND_TYPE);if(entries.isEmpty()||entries.size()>12)throw new IllegalStateException("房门恢复记录数量错误。");
        var result=new ArrayList<Saved>();var positions=new HashSet<String>();
        for(int i=0;i<entries.size();i++){
            NbtCompound entry=entries.getCompound(i);Identifier dimension=Identifier.tryParse(entry.getString("dimension"));
            if(dimension==null||!entry.contains("pos",NbtElement.LONG_TYPE))throw new IllegalStateException("房门恢复坐标缺失。");
            RegistryKey<World> key=RegistryKey.of(RegistryKeys.WORLD,dimension);ServerWorld world=server.getWorld(key);BlockPos pos=BlockPos.fromLong(entry.getLong("pos"));
            if(world==null||world.isOutOfHeightLimit(pos)||world.isOutOfHeightLimit(pos.up())
                    ||!positions.add(key+":"+pos)||!positions.add(key+":"+pos.up()))throw new IllegalStateException("房门恢复维度或坐标错误。");
            BlockState lower=decode(entry.getCompound("lower")),upper=decode(entry.getCompound("upper"));
            if(!(lower.getBlock() instanceof DoorBlock)||lower.isOf(ROOM_DOOR)||!upper.isOf(lower.getBlock())
                    ||lower.get(DoorBlock.HALF)!=DoubleBlockHalf.LOWER||upper.get(DoorBlock.HALF)!=DoubleBlockHalf.UPPER)throw new IllegalStateException("原房门状态不完整。");
            result.add(new Saved(key,pos,lower,upper));
        }
        return result;
    }
    private static BlockState decode(NbtCompound data){
        Identifier id=Identifier.tryParse(data.getString("Name"));if(id==null||!Registries.BLOCK.containsId(id))throw new IllegalStateException("原房门模组缺失。");
        BlockState state=NbtHelper.toBlockState(Registries.BLOCK.getReadOnlyWrapper(),data);
        if(!NbtHelper.fromBlockState(state).equals(data))throw new IllegalStateException("原房门属性无法完整恢复。");
        return state;
    }

    public static void restore(MinecraftServer server){
        Path path=recovery(server);if(!Files.exists(path)){ACTIVE.remove(server);return;}
        try{
            List<Saved> saved=read(server);var worlds=new HashSet<ServerWorld>();var conflicts=new ArrayList<String>();
            for(Saved door:saved){
                ServerWorld world=server.getWorld(door.dimension);worlds.add(world);
                restoreBlock(world,door.pos,door.lower,conflicts);restoreBlock(world,door.pos.up(),door.upper,conflicts);
            }
            // Persist restored chunks before deleting the journal, including after a crash.
            for(ServerWorld world:worlds)world.getChunkManager().save(true);
            if(!conflicts.isEmpty())throw new IllegalStateException("房门位置已被修改，未覆盖："+String.join("、",conflicts));
            Files.delete(path);ACTIVE.remove(server);
        }catch(Exception error){LOGGER.error("Room door recovery failed; journal retained at {}",path,error);throw new IllegalStateException("钟楼房门恢复失败，原方块和恢复记录已保留。",error);}
    }
    private static void restoreBlock(ServerWorld world,BlockPos pos,BlockState original,List<String> conflicts){
        BlockState current=world.getBlockState(pos);
        if(current.isOf(ROOM_DOOR))put(world,pos,original);
        else if(!current.equals(original))conflicts.add(pos.toShortString());
    }

    private static final class RoomDoor extends DoorBlock {
        private static final MapCodec<RoomDoor> CODEC=createCodec(RoomDoor::new);
        RoomDoor(Settings settings){super(BlockSetType.IRON,settings);}
        @Override public MapCodec<? extends DoorBlock> getCodec(){return CODEC;}
        @Override protected ActionResult onUse(BlockState state,World world,BlockPos pos,PlayerEntity player,BlockHitResult hit){return ActionResult.SUCCESS;}
        @Override protected void neighborUpdate(BlockState state,World world,BlockPos pos,Block source,BlockPos sourcePos,boolean notify){}
        @Override public void setOpen(Entity actor,World world,BlockState state,BlockPos pos,boolean open){}
    }
}
