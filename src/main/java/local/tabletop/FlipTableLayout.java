package local.tabletop;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Box;
import net.minecraft.registry.Registries;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Persistent coordinates, independent of round/session IDs. Never changes the user's table blocks. */
public final class FlipTableLayout {
    public record Point(double x,double y,double z,float yaw,Boolean automatic) {
        public Point(double x,double y,double z,float yaw){this(x,y,z,yaw,false);}
    }
    public static final class Station {
        public String dimension;
        public int x,y,z;
        public String table="";
        public int seat;
        public BlockPos pos(){return new BlockPos(x,y,z);}
    }
    private static final class Store {
        Map<String,Station> stations=new LinkedHashMap<>();
        Map<String,Map<Integer,Point>> seats=new LinkedHashMap<>();
        Map<String,Map<Integer,Point>> standing=new LinkedHashMap<>();
        Map<String,Integer> coloredVersions=new LinkedHashMap<>();
    }
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    private static final Map<MinecraftServer,Store> STORES=new WeakHashMap<>();
    private static final Map<MinecraftServer,Map<String,Optional<Colored>>> COLORED=new WeakHashMap<>();
    private record Colored(double centerX,double centerZ,int y,Map<Integer,List<Station>> controls,Map<Integer,Point> cards) {}
    private FlipTableLayout(){}
    static String table(Sessions.Session s){return key(s.dimension.getValue().toString(),s.anchor);}
    private static String key(String dimension,BlockPos p){return dimension+"|"+p.getX()+","+p.getY()+","+p.getZ();}
    private static String key(ServerWorld world,BlockPos p){return key(world.getRegistryKey().getValue().toString(),p);}
    private static Path file(MinecraftServer server){return server.getSavePath(WorldSavePath.ROOT).resolve("tabletop/flip-tables.json");}
    private static Store store(MinecraftServer server){return STORES.computeIfAbsent(server,s->{
        Path path=file(s);if(!Files.exists(path))return new Store();
        try{Store result=JSON.fromJson(Files.readString(path,StandardCharsets.UTF_8),Store.class);if(result==null||result.stations==null||result.seats==null)throw new IllegalStateException("缺少七连翻场地数据");if(result.standing==null)result.standing=new LinkedHashMap<>();if(result.coloredVersions==null)result.coloredVersions=new LinkedHashMap<>();return result;}
        catch(Exception error){throw new IllegalStateException("七连翻场地记录无法读取，原文件已保留。",error);}
    });}
    private static void save(MinecraftServer server){try{
        Path path=file(server);Files.createDirectories(path.getParent());Path temp=path.resolveSibling(path.getFileName()+".tmp");Files.writeString(temp,JSON.toJson(store(server)),StandardCharsets.UTF_8);
        try{Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        catch(AtomicMoveNotSupportedException|AccessDeniedException|FileAlreadyExistsException atomicFailure){
            // Windows may refuse atomic replacement of an existing file. Never delete/truncate it ourselves.
            try{Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING);}
            catch(Exception replacementFailure){replacementFailure.addSuppressed(atomicFailure);throw replacementFailure;}
        }
    }catch(Exception error){throw new IllegalStateException("七连翻场地保存失败。",error);}}
    static Collection<Station> stations(MinecraftServer server){return List.copyOf(store(server).stations.values());}
    static Station station(ServerWorld world,BlockPos pos){return store(world.getServer()).stations.get(key(world,pos));}
    static void placed(ServerWorld world,BlockPos pos,ServerPlayerEntity player){
        Station station=new Station();station.dimension=world.getRegistryKey().getValue().toString();station.x=pos.getX();station.y=pos.getY();station.z=pos.getZ();
        Sessions.Session current=Sessions.GAMES.get(Sessions.MEMBERS.get(player.getUuid()));
        if(current!=null&&current.type.equals("flip")&&near(current,world,pos))station.table=table(current);
        else {var nearby=nearby(world,pos);if(nearby.size()==1)station.table=table(nearby.getFirst());}
        store(world.getServer()).stations.put(key(world,pos),station);save(world.getServer());
        COLORED.remove(world.getServer());
    }
    static void removed(ServerWorld world,BlockPos pos){if(store(world.getServer()).stations.remove(key(world,pos))!=null){COLORED.remove(world.getServer());save(world.getServer());}}
    static List<Sessions.Session> nearby(ServerWorld world,BlockPos pos){return Sessions.GAMES.values().stream().filter(s->s.type.equals("flip")&&near(s,world,pos)).toList();}
    private static boolean near(Sessions.Session s,ServerWorld world,BlockPos pos){return s.dimension.equals(world.getRegistryKey())&&s.anchor.getSquaredDistance(pos)<=64d*64;}
    static boolean accepts(Station station,Sessions.Session s,ServerWorld world,BlockPos pos){return near(s,world,pos)&&(station==null||station.table==null||station.table.isBlank()||station.table.equals(table(s)));}
    static Sessions.Session bound(ServerWorld world,BlockPos pos){
        Station station=station(world,pos);var tables=nearby(world,pos);
        if(station!=null&&station.table!=null&&!station.table.isBlank())return tables.stream().filter(s->station.table.equals(table(s))).findFirst().orElse(null);
        return tables.size()==1?tables.getFirst():null;
    }
    /** Only call after the same click has successfully executed its rule action. */
    static void remember(Sessions.Session s,ServerPlayerEntity player,BlockPos pos,boolean recordSeat){
        ServerWorld world=player.getServerWorld();Station station=station(world,pos);
        if(station==null){station=new Station();station.dimension=world.getRegistryKey().getValue().toString();station.x=pos.getX();station.y=pos.getY();station.z=pos.getZ();store(player.getServer()).stations.put(key(world,pos),station);}
        boolean changed=false;if(station.table==null||station.table.isBlank()){station.table=table(s);changed=true;}
        int seat=Sessions.flipSeat(s,player.getUuid());
        if(recordSeat&&seat>0){
            Map<Integer,Point> places=store(player.getServer()).seats.computeIfAbsent(table(s),ignored->new TreeMap<>());
            if(!places.containsKey(seat)){
                places.put(seat,automaticPoint(world,pos,s.anchor));
                station.seat=seat;changed=true;
            }
        }
        if(changed)save(player.getServer());
    }
    private static Point automaticPoint(ServerWorld world,BlockPos button,BlockPos anchor){
        double dx=anchor.getX()-button.getX(),dz=anchor.getZ()-button.getZ(),length=Math.hypot(dx,dz);
        double forwardX=length>.01?dx/length:0,forwardZ=length>.01?dz/length:1,rightX=forwardZ,rightZ=-forwardX;
        float yawDegrees=(float)Math.toDegrees(Math.atan2(-forwardX,forwardZ));
        for(double depth=1.75;depth<=3.75;depth+=.25)for(double side:new double[]{0,-.35,.35}){
            double x=button.getX()+.5+forwardX*depth+rightX*side,z=button.getZ()+.5+forwardZ*depth+rightZ*side;
            double y=surfaceY(world,x,z,button.getY());if(!Double.isFinite(y))continue;
            Point point=new Point(x,y+.02,z,yawDegrees,true);
            if(supportedFootprint(world,point,button.getY(),forwardX,forwardZ,rightX,rightZ)&&clearOfControls(world,button.getY(),point,forwardX,forwardZ,rightX,rightZ))return point;
        }
        // Bounded fallback still points into the designated table, never along the player's look.
        double x=button.getX()+.5+forwardX*2.25,z=button.getZ()+.5+forwardZ*2.25,y=surfaceY(world,x,z,button.getY());
        return new Point(x,Double.isFinite(y)?y+.02:button.getY()+.02,z,yawDegrees,true);
    }
    private static double surfaceY(ServerWorld world,double x,double z,int referenceY){
        for(int y=referenceY;y>=referenceY-1;y--){
            BlockPos pos=BlockPos.ofFloored(x,y,z);var state=world.getBlockState(pos);
            if(FlipControls.isControl(state)||!state.getFluidState().isEmpty())continue;
            String name=Registries.BLOCK.getId(state.getBlock()).getPath();
            if(name.contains("chair")||name.contains("pouffe")||name.contains("sofa")||name.contains("stool"))continue;
            for(var box:state.getCollisionShape(world,pos).getBoundingBoxes()){
                double top=y+box.maxY;
                if(x>=pos.getX()+box.minX&&x<=pos.getX()+box.maxX&&z>=pos.getZ()+box.minZ&&z<=pos.getZ()+box.maxZ&&top<=referenceY+.25&&top>=referenceY-.6)return top;
            }
        }
        return Double.NaN;
    }
    private static boolean supportedFootprint(ServerWorld world,Point point,int y,double fx,double fz,double rx,double rz){
        for(double side:new double[]{-.98,0,.98})for(double depth:new double[]{0,.8,1.7}){
            double top=surfaceY(world,point.x+rx*side+fx*depth,point.z+rz*side+fz*depth,y);
            if(!Double.isFinite(top)||Math.abs(top+.02-point.y)>.12)return false;
        }
        return true;
    }
    private static boolean clearOfControls(ServerWorld world,int y,Point point,double fx,double fz,double rx,double rz){
        BlockPos center=BlockPos.ofFloored(point.x,y,point.z);double padding=.5*(Math.abs(fx)+Math.abs(fz));
        for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++){
            BlockPos block=center.add(x,0,z);if(!FlipControls.isControl(world.getBlockState(block)))continue;
            double dx=block.getX()+.5-point.x,dz=block.getZ()+.5-point.z,side=dx*rx+dz*rz,forward=dx*fx+dz*fz;
            if(Math.abs(side)<1.05+padding&&forward>-.3-padding&&forward<1.5+padding)return false;
        }
        return true;
    }
    private static boolean automatic(Point point){
        if(point.automatic!=null)return point.automatic;
        // Old automatically recorded points used these exact heights. Other legacy/manual points stay intact.
        double fraction=point.y-Math.floor(point.y);
        return Math.abs(fraction-.04)<1e-6||Math.abs(fraction-.18)<1e-6;
    }
    /** Eight colored controls are recognized once per table; no world/tick scan. */
    private static Colored colored(Sessions.Session s){
        var cache=COLORED.computeIfAbsent(s.server,ignored->new HashMap<>());
        String table=table(s);if(cache.containsKey(table))return cache.get(table).orElse(null);
        ServerWorld world=s.server.getWorld(s.dimension);Colored found=world==null?null:discoverColored(s,world);
        cache.put(table,Optional.ofNullable(found));
        if(found!=null&&store(s.server).coloredVersions.getOrDefault(table,0)<1){
            // Only this positively identified eight-color table is migrated.
            // Preserve its complete previous JSON before replacing legacy points.
            Path path=file(s.server);
            try{if(Files.exists(path))Files.copy(path,path.resolveSibling("flip-tables.pre-eight-seats-"+System.currentTimeMillis()+"-"+UUID.randomUUID().toString().substring(0,8)+".json"));}
            catch(Exception error){cache.remove(table);throw new IllegalStateException("旧座位记录备份失败，未迁移。",error);}
            Store data=store(s.server);Map<Integer,Point> previous=data.seats.get(table);Map<Station,Integer> oldNumbers=new IdentityHashMap<>();
            for(var entry:found.controls.entrySet())for(var station:entry.getValue()){oldNumbers.put(station,station.seat);station.seat=entry.getKey();}
            data.seats.put(table,new TreeMap<>(found.cards));data.coloredVersions.put(table,1);
            try{save(s.server);}catch(RuntimeException error){if(previous==null)data.seats.remove(table);else data.seats.put(table,previous);data.coloredVersions.remove(table);oldNumbers.forEach((station,number)->station.seat=number);cache.remove(table);throw error;}
        }
        return found;
    }
    private static Colored discoverColored(Sessions.Session s,ServerWorld world){
        var controls=stations(s.server).stream().filter(station->table(s).equals(station.table)&&FlipControls.isControl(world.getBlockState(station.pos()))).toList();
        if(controls.size()!=16||!Registries.BLOCK.getId(world.getBlockState(s.anchor.down()).getBlock()).toString().equals("handcrafted:spruce_table"))return null;
        int y=controls.getFirst().y;if(controls.stream().anyMatch(station->station.y!=y))return null;
        Map<Integer,List<Station>> groups=new TreeMap<>();
        for(var station:controls){int number=nearbyColor(world,station.pos());if(number==0)return null;groups.computeIfAbsent(number,ignored->new ArrayList<>()).add(station);}
        if(groups.size()!=8)return null;
        for(int number=1;number<=8;number++){
            var pair=groups.get(number);if(pair==null||pair.size()!=2||pair.get(0).pos().getSquaredDistance(pair.get(1).pos())>10)return null;
            if(pair.stream().filter(station->world.getBlockState(station.pos()).isOf(FlipControls.DRAW)).count()!=1)return null;
        }
        double cx=controls.stream().mapToDouble(station->station.x+.5).average().orElseThrow(),cz=controls.stream().mapToDouble(station->station.z+.5).average().orElseThrow();
        Map<Integer,Point> cards=new TreeMap<>();
        for(var entry:groups.entrySet()){
            double bx=entry.getValue().stream().mapToDouble(station->station.x+.5).average().orElseThrow(),bz=entry.getValue().stream().mapToDouble(station->station.z+.5).average().orElseThrow();
            double length=Math.hypot(cx-bx,cz-bz);if(length<2||length>12)return null;
            double fx=(cx-bx)/length,fz=(cz-bz)/length,rx=fz,rz=-fx;Point point=null;
            for(double depth=1.75;depth<=2.75;depth+=.25){
                double x=bx+fx*depth,z=bz+fz*depth,top=surfaceY(world,x,z,y);if(!Double.isFinite(top))continue;
                Point candidate=new Point(x,top+.02,z,(float)Math.toDegrees(Math.atan2(-fx,fz)),true);
                if(supportedFootprint(world,candidate,y,fx,fz,rx,rz)&&clearOfControls(world,y,candidate,fx,fz,rx,rz)){point=candidate;break;}
            }
            if(point==null)return null;cards.put(entry.getKey(),point);
        }
        return new Colored(cx,cz,y,groups,cards);
    }
    private static int nearbyColor(ServerWorld world,BlockPos button){
        int chosen=0,best=Integer.MAX_VALUE;boolean tied=false;
        for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++){
            int distance=x*x+z*z;if(distance>best)continue;
            String id=Registries.BLOCK.getId(world.getBlockState(button.add(x,-1,z)).getBlock()).toString();
            int number=switch(id){case "minecraft:red_concrete"->1;case "minecraft:brown_concrete"->2;case "minecraft:yellow_concrete"->3;case "minecraft:pink_concrete"->4;case "minecraft:blue_concrete"->5;case "minecraft:green_concrete","minecraft:lime_concrete"->6;case "minecraft:purple_concrete"->7;case "minecraft:white_concrete"->8;default->0;};
            if(number==0)continue;if(distance<best){chosen=number;best=distance;tied=false;}else if(number!=chosen)tied=true;
        }
        return tied?0:chosen;
    }
    static int stationSeat(ServerWorld world,BlockPos pos){Sessions.Session s=bound(world,pos);return s==null?0:stationSeat(s,world,pos);}
    static int stationSeat(Sessions.Session s,ServerWorld world,BlockPos pos){
        if(!s.type.equals("flip")||!s.dimension.equals(world.getRegistryKey()))return 0;
        Colored layout=colored(s);if(layout!=null)for(var entry:layout.controls.entrySet())if(entry.getValue().stream().anyMatch(station->station.pos().equals(pos)))return entry.getKey();
        Station station=station(world,pos);return station!=null&&table(s).equals(station.table)&&station.seat>=1&&station.seat<=8?station.seat:0;
    }
    /** Move beside the chosen color, facing the actual table center; walking stays free. */
    static boolean moveToSeat(Sessions.Session s,ServerPlayerEntity player,int number){
        if(!s.type.equals("flip")||number<1||number>8)return false;ServerWorld world=s.server.getWorld(s.dimension);if(world==null)return false;
        Colored layout=colored(s);Point point=store(s.server).standing.getOrDefault(table(s),Map.of()).get(number);
        if(point==null&&layout!=null){
            var pair=layout.controls.get(number);double bx=pair.stream().mapToDouble(station->station.x+.5).average().orElseThrow(),bz=pair.stream().mapToDouble(station->station.z+.5).average().orElseThrow();
            double length=Math.hypot(layout.centerX-bx,layout.centerZ-bz),fx=(layout.centerX-bx)/length,fz=(layout.centerZ-bz)/length;
            for(double depth=1.25;depth<=3.5;depth+=.25){
                double x=bx-fx*depth,z=bz-fz*depth;Double top=standingSurface(world,player,x,z,layout.y);
                if(top!=null){point=new Point(x,top,z,(float)Math.toDegrees(Math.atan2(-fx,fz)),true);break;}
            }
        }
        if(point==null)return false;
        Box box=new Box(point.x-.3,point.y,point.z-.3,point.x+.3,point.y+1.8,point.z+.3);
        if(!world.getWorldBorder().contains(BlockPos.ofFloored(point.x,point.y,point.z))||!world.isSpaceEmpty(player,box))return false;
        Double supported=standingSurface(world,player,point.x,point.z,(int)Math.ceil(point.y));if(supported==null||Math.abs(supported-point.y)>.05)return false;
        player.stopRiding();player.teleport(world,point.x,point.y,point.z,Set.of(),point.yaw,0);player.fallDistance=0;player.setVelocity(Vec3d.ZERO);return true;
    }
    private static Double standingSurface(ServerWorld world,ServerPlayerEntity player,double x,double z,int referenceY){
        for(int y=referenceY;y>=referenceY-3;y--){
            BlockPos pos=BlockPos.ofFloored(x,y,z);var state=world.getBlockState(pos);if(!state.getFluidState().isEmpty()||FlipControls.isControl(state))continue;
            String name=Registries.BLOCK.getId(state.getBlock()).getPath();if(name.contains("chair")||name.contains("pouffe")||name.contains("sofa")||name.contains("stool"))continue;
            for(var shape:state.getCollisionShape(world,pos).getBoundingBoxes()){
                double top=y+shape.maxY;if(x<pos.getX()+shape.minX||x>pos.getX()+shape.maxX||z<pos.getZ()+shape.minZ||z>pos.getZ()+shape.maxZ)continue;
                Box box=new Box(x-.3,top,z-.3,x+.3,top+1.8,z+.3);if(world.getWorldBorder().contains(BlockPos.ofFloored(x,top,z))&&world.isSpaceEmpty(player,box))return top;
            }
        }
        return null;
    }
    static Map<Integer,Point> seats(Sessions.Session s){
        Colored layout=colored(s);
        var saved=store(s.server).seats.getOrDefault(table(s),Map.of());var result=new TreeMap<Integer,Point>();
        ServerWorld world=s.server.getWorld(s.dimension);if(world==null)return Map.copyOf(saved);
        if(layout!=null){for(UUID id:Sessions.flipPlayers(s)){int seat=Sessions.flipSeat(s,id);Point point=saved.get(seat);result.put(seat,point!=null&&!automatic(point)?point:layout.cards.get(seat));}return Map.copyOf(result);}
        var controls=stations(s.server).stream().filter(station->table(s).equals(station.table)&&FlipControls.isControl(world.getBlockState(station.pos()))).toList();
        // Draw/stay are two buttons for one place. Never allocate that same place twice.
        var drawPlaces=controls.stream().filter(station->world.getBlockState(station.pos()).isOf(FlipControls.DRAW)).toList();
        var places=drawPlaces.isEmpty()?controls:drawPlaces;
        Set<Station> assigned=new HashSet<>();
        for(var station:controls)if(station.seat>0&&station.seat<=8){
            Station place=nearest(places,station.x+.5,station.z+.5);if(place!=null)assigned.add(place);
        }
        for(UUID id:Sessions.flipPlayers(s)){
            int seat=Sessions.flipSeat(s,id);Point point=saved.get(seat);
            if(point!=null&&!automatic(point)){result.put(seat,point);continue;}
            Station control=controls.stream().filter(candidate->candidate.seat==seat).findFirst().orElse(null);
            Station station=control==null?null:nearest(places,control.x+.5,control.z+.5);
            var player=s.server.getPlayerManager().getPlayer(id);
            if(station==null&&(point!=null||player!=null)){
                double x=point!=null?point.x:player.getX(),z=point!=null?point.z:player.getZ();
                station=nearest(places.stream().filter(candidate->!assigned.contains(candidate)).toList(),x,z);
            }
            if(station!=null){assigned.add(station);result.put(seat,automaticPoint(world,station.pos(),s.anchor));}
            else if(point!=null)result.put(seat,point);
        }
        return Map.copyOf(result);
    }
    private static Station nearest(List<Station> places,double x,double z){
        return places.stream().min(Comparator.comparingDouble(candidate->Math.pow(candidate.x+.5-x,2)+Math.pow(candidate.z+.5-z,2))).orElse(null);
    }
    static BlockPos reusableAnchor(ServerPlayerEntity player,BlockPos requested){
        String prefix=player.getWorld().getRegistryKey().getValue()+"|";Set<BlockPos> found=new LinkedHashSet<>();
        for(Station station:stations(player.getServer()))if(station.table!=null&&station.table.startsWith(prefix)){
            String[] xyz=station.table.substring(prefix.length()).split(",");if(xyz.length!=3)throw new IllegalStateException("七连翻桌子坐标记录不完整。");
            BlockPos anchor=new BlockPos(Integer.parseInt(xyz[0]),Integer.parseInt(xyz[1]),Integer.parseInt(xyz[2]));if(anchor.getSquaredDistance(requested)<=64d*64)found.add(anchor);
        }
        if(found.contains(requested)||found.isEmpty())return requested;
        if(found.size()==1)return found.iterator().next();
        throw new IllegalStateException("附近有多桌，请在要使用的牌堆中心创建。");
    }
    static String command(Sessions.Session s,ServerPlayerEntity p,String arguments){
        Sessions.host(s,p.getUuid());if(!s.type.equals("flip"))throw new IllegalStateException("桌面落点仅用于七连翻。");if(s.running&&!s.ended())throw new IllegalStateException("请在开局前调整桌面。");
        colored(s);String[] parts=arguments.trim().split("\\s+");boolean standing=parts.length==2&&parts[0].equals("stand");if(parts.length!=1&&!standing)throw new IllegalArgumentException("对准铺牌桌面 /tt flipseat 1–8；站在座旁 /tt flipseat stand 1–8。");int seat=Integer.parseInt(parts[standing?1:0]);if(seat<1||seat>8)throw new IllegalArgumentException("座号须为 1–8。");
        if(standing){if(!s.dimension.equals(p.getWorld().getRegistryKey())||s.anchor.getSquaredDistance(p.getBlockPos())>64d*64)throw new IllegalArgumentException("请在本桌附近设置。");Double top=standingSurface(p.getServerWorld(),p,p.getX(),p.getZ(),(int)Math.ceil(p.getY()));if(top==null||Math.abs(top-p.getY())>.05)throw new IllegalStateException("请站在有地面支撑、两格净空的座旁。");store(p.getServer()).standing.computeIfAbsent(table(s),ignored->new TreeMap<>()).put(seat,new Point(p.getX(),p.getY(),p.getZ(),p.getYaw()));save(p.getServer());return seat+"号站位与朝向已保存。";}
        HitResult hit=p.raycast(6,1,false);if(!(hit instanceof BlockHitResult block)||hit.getType()!=HitResult.Type.BLOCK||block.getSide()!=Direction.UP)throw new IllegalStateException("请对准已有桌面的上表面。");
        Vec3d point=block.getPos();if(!s.dimension.equals(p.getWorld().getRegistryKey())||s.anchor.getSquaredDistance(BlockPos.ofFloored(point))>64d*64)throw new IllegalArgumentException("请在本桌附近设置。");
        store(p.getServer()).seats.computeIfAbsent(table(s),ignored->new TreeMap<>()).put(seat,new Point(point.x,point.y+.035,point.z,p.getYaw()));save(p.getServer());return seat+"号铺牌位置已保存。";
    }
    static void clear(){STORES.clear();COLORED.clear();}
}
