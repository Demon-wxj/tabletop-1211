package local.tabletop;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.block.DoorBlock;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** World-owned coordinates only. Configuring a stage never builds or replaces blocks. */
public final class ClockArena {
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    private static final Map<MinecraftServer,Map<String,Layout>> STORES=new WeakHashMap<>();
    public record Point(double x,double y,double z,float yaw,float pitch) {
        public BlockPos block(){return BlockPos.ofFloored(x,y,z);}
        public void teleport(ServerPlayerEntity p,ServerWorld world){p.stopRiding();p.closeHandledScreen();p.stopUsingItem();p.teleport(world,x,y,z,yaw,pitch);p.fallDistance=0;p.setVelocity(net.minecraft.util.math.Vec3d.ZERO);p.extinguish();}
    }
    public record Region(int minX,int minY,int minZ,int maxX,int maxY,int maxZ) {
        public boolean contains(double x,double y,double z){return x>=minX&&x<maxX+1d&&y>=minY&&y<maxY+1d&&z>=minZ&&z<maxZ+1d;}
        public boolean contains(Point p){return contains(p.x,p.y,p.z);}
        public boolean contains(BlockPos p){return contains(p.getX()+.5,p.getY(),p.getZ()+.5);}
        public boolean overlaps(Region other){return minX<=other.maxX&&maxX>=other.minX&&minY<=other.maxY&&maxY>=other.minY&&minZ<=other.maxZ&&maxZ>=other.minZ;}
        public long volume(){return ((long)maxX-minX+1)*((long)maxY-minY+1)*((long)maxZ-minZ+1);}
        public static Region of(BlockPos a,BlockPos b){return new Region(Math.min(a.getX(),b.getX()),Math.min(a.getY(),b.getY()),Math.min(a.getZ(),b.getZ()),Math.max(a.getX(),b.getX()),Math.max(a.getY(),b.getY()),Math.max(a.getZ(),b.getZ()));}
    }
    public record Door(int x,int y,int z) {
        public BlockPos block(){return new BlockPos(x,y,z);}
        public boolean matches(BlockPos p){return p.getX()==x&&p.getZ()==z&&(p.getY()==y||p.getY()==y+1);}
    }
    public static final class Room {
        public Point spawn;
        public Region bounds;
        public Door door;
    }
    public static final class Layout {
        public String dimension;
        public boolean enabled;
        public Point center,jail;
        public Map<Integer,Point> seats=new TreeMap<>();
        public Map<Integer,Room> rooms=new TreeMap<>();
    }
    private ClockArena(){}
    static String key(Sessions.Session s){return s.dimension.getValue()+":"+s.arenaAnchor.getX()+","+s.arenaAnchor.getY()+","+s.arenaAnchor.getZ();}
    private static Path file(MinecraftServer server){return server.getSavePath(WorldSavePath.ROOT).resolve("tabletop/clock-arenas.json");}
    private static Map<String,Layout> store(MinecraftServer server){
        return STORES.computeIfAbsent(server,k->{
            var result=new LinkedHashMap<String,Layout>();Path path=file(k);if(!Files.exists(path))return result;
            try{var root=JSON.fromJson(Files.readString(path,StandardCharsets.UTF_8),com.google.gson.JsonObject.class);if(root!=null)for(var entry:root.entrySet()){Layout layout=JSON.fromJson(entry.getValue(),Layout.class);if(layout==null||layout.dimension==null||layout.seats==null||layout.rooms==null)throw new IllegalStateException("场地配置缺少数据");result.put(entry.getKey(),layout);}return result;}
            catch(Exception e){throw new IllegalStateException("钟楼场地配置无法读取；原文件已保留。",e);}
        });
    }
    private static void save(MinecraftServer server){
        try{Path path=file(server);Files.createDirectories(path.getParent());Path temp=path.resolveSibling(path.getFileName()+".tmp");Files.writeString(temp,JSON.toJson(store(server)),StandardCharsets.UTF_8);try{Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING);}}
        catch(Exception e){throw new IllegalStateException("钟楼场地配置保存失败。",e);}
    }
    public static Layout get(Sessions.Session s,MinecraftServer server){return store(server).get(key(s));}
    public static boolean enabled(Sessions.Session s,MinecraftServer server){Layout layout=get(s,server);return layout!=null&&layout.enabled;}
    private static Layout editable(Sessions.Session s,MinecraftServer server){return store(server).computeIfAbsent(key(s),k->{Layout layout=new Layout();layout.dimension=s.dimension.getValue().toString();return layout;});}
    private static Point here(ServerPlayerEntity p){return new Point(p.getX(),p.getY(),p.getZ(),p.getYaw(),p.getPitch());}
    private static int seat(String text){int seat=Integer.parseInt(text);if(seat<1||seat>Sessions.CLOCK_PLAYERS)throw new IllegalArgumentException("座号须为 1–12。");return seat;}
    private static BlockPos pos(String[] parts,int index){return new BlockPos(Integer.parseInt(parts[index]),Integer.parseInt(parts[index+1]),Integer.parseInt(parts[index+2]));}
    private static Point point(ServerPlayerEntity p,String[] parts,int index){if(parts.length==index)return here(p);if(parts.length!=index+3)throw new IllegalArgumentException("坐标填写 x y z，或站在目标位置设置。");return new Point(Double.parseDouble(parts[index]),Double.parseDouble(parts[index+1]),Double.parseDouble(parts[index+2]),p.getYaw(),p.getPitch());}
    public static String command(Sessions.Session s,ServerPlayerEntity p,String arguments){
        Sessions.host(s,p.getUuid());if(!s.type.equals("clock"))throw new IllegalStateException("场地设置仅用于钟楼。");if(s.running&&!s.ended())throw new IllegalStateException("请在准备阶段设置场地。");if(!p.getWorld().getRegistryKey().equals(s.dimension))throw new IllegalArgumentException("请在本桌维度设置。");
        String[] parts=arguments.trim().split("\\s+");String op=parts.length==0?"status":parts[0];Layout layout=editable(s,p.getServer());
        switch(op){
            case "","status"->{String issue=issue(s,p.getServer());return "场地"+(layout.enabled?"已启用":"未启用")+" · 座位 "+layout.seats.size()+" / 房间 "+layout.rooms.size()+" · "+(issue.isEmpty()?"配置完整":issue);}
            case "center"->layout.center=point(p,parts,1);
            case "jail"->layout.jail=point(p,parts,1);
            case "seat"->{if(parts.length<2)throw new IllegalArgumentException("layout seat 座号 [x y z]");layout.seats.put(seat(parts[1]),point(p,parts,2));}
            case "room"->{if(parts.length<2)throw new IllegalArgumentException("layout room 座号 [x y z]");layout.rooms.computeIfAbsent(seat(parts[1]),k->new Room()).spawn=point(p,parts,2);}
            case "bounds"->{if(parts.length!=8)throw new IllegalArgumentException("layout bounds 座号 x1 y1 z1 x2 y2 z2");layout.rooms.computeIfAbsent(seat(parts[1]),k->new Room()).bounds=Region.of(pos(parts,2),pos(parts,5));}
            case "door"->{if(parts.length!=2&&parts.length!=5)throw new IllegalArgumentException("layout door 座号 [x y z]（门下半格）");BlockPos door=parts.length==5?pos(parts,2):p.getBlockPos();var state=p.getWorld().getBlockState(door);if(state.getBlock() instanceof DoorBlock&&state.contains(net.minecraft.state.property.Properties.DOUBLE_BLOCK_HALF)&&state.get(net.minecraft.state.property.Properties.DOUBLE_BLOCK_HALF)==net.minecraft.block.enums.DoubleBlockHalf.UPPER)door=door.down();layout.rooms.computeIfAbsent(seat(parts[1]),k->new Room()).door=new Door(door.getX(),door.getY(),door.getZ());}
            case "enable"->{String issue=issue(s,p.getServer());if(!issue.isEmpty())throw new IllegalStateException(issue);layout.enabled=true;}
            case "disable"->layout.enabled=false;
            default->throw new IllegalArgumentException("layout center | seat 1–12 | room 1–12 | bounds 1–12 x1 y1 z1 x2 y2 z2 | door 1–12 | jail | enable | disable | status");
        }
        save(p.getServer());return "钟楼场地已保存。";
    }
    private static boolean validPoint(ServerWorld world,Point p){return p!=null&&Double.isFinite(p.x)&&Double.isFinite(p.y)&&Double.isFinite(p.z)&&Float.isFinite(p.yaw)&&Float.isFinite(p.pitch)&&Math.abs(p.x)<29_999_984&&Math.abs(p.z)<29_999_984&&!world.isOutOfHeightLimit(p.block())&&world.getWorldBorder().contains(p.block());}
    private static boolean safePoint(ServerWorld world,Point p){
        if(!validPoint(world,p))return false;
        // Collision views skip unloaded chunks; inspect the actual terrain beneath
        // every part of the player's footprint, even when nobody is nearby.
        int minX=net.minecraft.util.math.MathHelper.floor(p.x-.29)>>4,maxX=net.minecraft.util.math.MathHelper.floor(p.x+.29)>>4;
        int minZ=net.minecraft.util.math.MathHelper.floor(p.z-.29)>>4,maxZ=net.minecraft.util.math.MathHelper.floor(p.z+.29)>>4;
        for(int x=minX;x<=maxX;x++)for(int z=minZ;z<=maxZ;z++)world.getChunk(x,z);
        return world.isBlockSpaceEmpty(null,new net.minecraft.util.math.Box(p.x-.29,p.y+.01,p.z-.29,p.x+.29,p.y+1.8,p.z+.29))&&!world.isBlockSpaceEmpty(null,new net.minecraft.util.math.Box(p.x-.29,p.y-.06,p.z-.29,p.x+.29,p.y+.001,p.z+.29));
    }
    public static String issue(Sessions.Session s,MinecraftServer server){
        Layout layout=get(s,server);if(layout==null)return "尚未配置钟楼场地。";ServerWorld world=server.getWorld(s.dimension);if(world==null||!s.dimension.getValue().toString().equals(layout.dimension))return "场地维度不匹配。";
        if(!safePoint(world,layout.center))return "请设置有地面和两格净空的中间说书人位置。";if(!safePoint(world,layout.jail))return "请设置有地面和两格净空的处决牢笼位置。";
        List<Integer> selected=Sessions.clockPlayers(s).stream().map(id->TabletopPresentation.seat(s,id)).toList();if(selected.isEmpty())selected=List.of(1);
        for(int index=0;index<selected.size();index++){int i=selected.get(index);
            if(!safePoint(world,layout.seats.get(i)))return "请设置有地面和两格净空的 "+i+" 号发言位置。";Room room=layout.rooms.get(i);if(room==null||!safePoint(world,room.spawn)||room.bounds==null||room.door==null)return "请完整设置安全的 "+i+" 号房间、范围和房门。";
            if(room.bounds.volume()<4||room.bounds.volume()>1_000_000||room.bounds.minY>room.bounds.maxY||world.isOutOfHeightLimit(new BlockPos(room.bounds.minX,room.bounds.minY,room.bounds.minZ))||world.isOutOfHeightLimit(new BlockPos(room.bounds.maxX,room.bounds.maxY,room.bounds.maxZ)))return i+" 号房间范围不合法。";
            if(!room.bounds.contains(room.spawn)||room.spawn.y+1.8>=room.bounds.maxY+1d)return i+" 号房间出生点和玩家头部必须在房间内。";
            if(!(world.getBlockState(room.door.block()).getBlock() instanceof DoorBlock))return i+" 号房门坐标必须指向已建造的门。";
            if(!room.bounds.contains(room.door.block())&&!room.bounds.contains(room.door.block().offset(net.minecraft.util.math.Direction.NORTH))&&!room.bounds.contains(room.door.block().offset(net.minecraft.util.math.Direction.SOUTH))&&!room.bounds.contains(room.door.block().offset(net.minecraft.util.math.Direction.EAST))&&!room.bounds.contains(room.door.block().offset(net.minecraft.util.math.Direction.WEST)))return i+" 号房门必须在房间边界旁。";
            for(int prior=0;prior<index;prior++){int j=selected.get(prior);Room other=layout.rooms.get(j);if(other.bounds.overlaps(room.bounds))return i+" 号与 "+j+" 号房间范围重叠。";if(other.door.block().equals(room.door.block()))return "不同房间不能共用同一扇门。";if(layout.seats.get(j).block().equals(layout.seats.get(i).block()))return "发言位置不能重叠。";}
        }
        for(Room room:layout.rooms.values())if(room.bounds!=null&&(room.bounds.contains(layout.center)||room.bounds.contains(layout.jail)))return "说书人位置、处决牢笼须在玩家房间之外。";
        return ClockEmbodiment.lockIssue(s,server);
    }
    public static void clear(){STORES.clear();}
    static BlockPos reusableAnchor(ServerPlayerEntity p,BlockPos requested){
        String prefix=p.getWorld().getRegistryKey().getValue()+":";var found=new ArrayList<BlockPos>();
        for(var e:store(p.getServer()).entrySet())if(e.getValue().enabled&&e.getKey().startsWith(prefix)){String[] xyz=e.getKey().substring(prefix.length()).split(",");if(xyz.length!=3)throw new IllegalStateException("原钟楼场地记录坐标不合法，原记录已保留。");BlockPos anchor=new BlockPos(Integer.parseInt(xyz[0]),Integer.parseInt(xyz[1]),Integer.parseInt(xyz[2]));if(anchor.getSquaredDistance(requested)<=64d*64d)found.add(anchor);}
        if(found.size()>1)throw new IllegalStateException("附近有多个旧钟楼场地，请先保留要使用的一份场地配置。");return found.isEmpty()?requested:found.getFirst();
    }
}
