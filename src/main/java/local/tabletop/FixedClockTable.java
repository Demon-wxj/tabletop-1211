package local.tabletop;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;

/** A fixed world table survives closing a match; hidden match state is not persisted here. */
public final class FixedClockTable {
    private static final String FORMAT="tabletop-fixed-clock-v1";
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    private static final Map<MinecraftServer,Optional<Binding>> CACHE=new WeakHashMap<>();

    public record Binding(String dimension,BlockPos arenaAnchor,BlockPos grimoirePos,UUID hostUUID) {
        public Binding {
            if(dimension==null||Identifier.tryParse(dimension)==null)throw new IllegalArgumentException("固定钟楼桌维度不合法。");
            arenaAnchor=Objects.requireNonNull(arenaAnchor,"固定钟楼桌缺少场地坐标。").toImmutable();
            if(grimoirePos!=null)grimoirePos=grimoirePos.toImmutable();
            Objects.requireNonNull(hostUUID,"固定钟楼桌缺少说书人。");
        }
    }

    private FixedClockTable(){}
    private static Path file(MinecraftServer server){return server.getSavePath(WorldSavePath.ROOT).resolve("tabletop/fixed-clock.json");}

    public static synchronized Binding get(MinecraftServer server){
        Objects.requireNonNull(server);
        Optional<Binding> cached=CACHE.get(server);
        if(cached==null){cached=Optional.ofNullable(read(server));CACHE.put(server,cached);}
        return cached.orElse(null);
    }

    public static Binding existing(MinecraftServer server){return get(server);}

    public static Binding forCreation(ServerPlayerEntity p,BlockPos requestedPos){
        Objects.requireNonNull(requestedPos,"缺少开桌位置。");
        if(Sessions.GAMES.values().stream().anyMatch(session->session.type.equals("clock")))throw new IllegalStateException("固定钟楼桌已开启，请使用现有魔典继续。");
        Binding binding=get(p.getServer());
        if(binding==null){checkPosition(p,requestedPos);return null;}
        if(!binding.hostUUID.equals(p.getUuid()))throw new IllegalStateException("请由本场地的说书人右键固定魔典开桌；其他玩家从手机加入。");
        checkDimension(p,binding);
        checkNearby(p,binding);
        checkPosition(p,binding.arenaAnchor);
        return binding;
    }

    public static BlockPos creationAnchor(ServerPlayerEntity p,BlockPos requestedPos){
        Binding binding=forCreation(p,requestedPos);
        return binding==null?requestedPos.toImmutable():binding.arenaAnchor;
    }

    /** Existing fixed lecterns remain publicly readable by other players. */
    public static void checkLectern(ServerPlayerEntity p,BlockPos pos){
        Objects.requireNonNull(pos,"缺少魔典讲台位置。");
        Binding binding=get(p.getServer());
        if(binding==null){checkPosition(p,pos);return;}
        checkDimension(p,binding);
        if(binding.grimoirePos!=null){
            if(!binding.grimoirePos.equals(pos))throw new IllegalStateException("请使用本场地已经绑定的固定魔典讲台。");
        }else{
            if(!binding.hostUUID.equals(p.getUuid()))throw new IllegalStateException("请由本场地的说书人在准备阶段首次绑定魔典讲台。");
            checkNearby(p,binding);
            if(!nearAnchor(binding.arenaAnchor,pos))throw new IllegalStateException("魔典讲台须在固定场地64格内。");
        }
        checkPosition(p,pos);
    }

    public static boolean fixedLecternMatches(ServerPlayerEntity p,BlockPos pos){
        Binding binding=get(p.getServer());
        return binding!=null&&binding.grimoirePos!=null&&binding.dimension.equals(p.getWorld().getRegistryKey().getValue().toString())&&binding.grimoirePos.equals(pos);
    }

    /** The caller authorizes storyteller transfers before updating the session and saving it. */
    public static synchronized void save(Sessions.Session session,MinecraftServer server){
        if(!session.type.equals("clock"))throw new IllegalArgumentException("固定场地只用于暗流涌动。");
        Binding cached=get(server),disk=read(server);
        if(!Objects.equals(cached,disk))throw new IllegalStateException("固定钟楼桌记录在运行中发生变化，请先检查原记录并重新载入世界。");
        validateIdentity(session,cached);
        validateIdentity(session,disk);
        Binding previous=disk!=null?disk:cached;
        BlockPos lectern=session.grimoirePos;
        if(previous!=null&&previous.grimoirePos!=null){
            if(lectern!=null&&!previous.grimoirePos.equals(lectern))throw new IllegalStateException("固定魔典讲台已绑定，不能换到另一位置。");
            lectern=previous.grimoirePos;
        }else if(lectern!=null&&session.running){
            throw new IllegalStateException("首次绑定魔典讲台须在准备阶段进行。");
        }
        if(lectern!=null&&!nearAnchor(session.arenaAnchor,lectern))throw new IllegalStateException("魔典讲台须在固定场地64格内。");
        Binding next=new Binding(session.dimension.getValue().toString(),session.arenaAnchor,lectern,session.host);
        JsonObject root=new JsonObject();root.addProperty("format",FORMAT);root.addProperty("dimension",next.dimension);
        root.add("arenaAnchor",position(next.arenaAnchor));
        if(next.grimoirePos!=null)root.add("grimoirePos",position(next.grimoirePos));
        root.addProperty("hostUUID",next.hostUUID.toString());
        try{
            Path destination=file(server);Files.createDirectories(destination.getParent());Path temporary=destination.resolveSibling(destination.getFileName()+".tmp");
            Files.writeString(temporary,JSON.toJson(root),StandardCharsets.UTF_8);
            try{Files.move(temporary,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException e){Files.move(temporary,destination,StandardCopyOption.REPLACE_EXISTING);}
        }catch(Exception e){throw new IllegalStateException("固定钟楼桌保存失败，请检查世界目录 tabletop/fixed-clock.json。",e);}
        CACHE.put(server,Optional.of(next));
    }

    public static void recordBinding(Sessions.Session session,MinecraftServer server){save(session,server);}

    private static void validateIdentity(Sessions.Session session,Binding previous){
        if(previous!=null&&(!previous.dimension.equals(session.dimension.getValue().toString())||!previous.arenaAnchor.equals(session.arenaAnchor)))throw new IllegalStateException("此世界已经有固定钟楼场地，请在原场地开桌。");
        if(previous!=null&&previous.grimoirePos!=null&&session.grimoirePos!=null&&!previous.grimoirePos.equals(session.grimoirePos))throw new IllegalStateException("固定魔典讲台已绑定，不能换到另一位置。");
    }

    private static Binding read(MinecraftServer server){
        Path path=file(server);if(!Files.exists(path))return null;
        try{
            JsonObject root=JsonParser.parseString(Files.readString(path,StandardCharsets.UTF_8)).getAsJsonObject();
            if(root.has("format")&&!FORMAT.equals(string(root,"format")))throw new IllegalArgumentException("不支持的记录格式");
            JsonElement lectern=root.get("grimoirePos");
            return new Binding(string(root,"dimension"),readPosition(root.get("arenaAnchor")),lectern==null||lectern.isJsonNull()?null:readPosition(lectern),UUID.fromString(string(root,"hostUUID")));
        }catch(Exception e){throw new IllegalStateException("固定钟楼桌记录无法读取，原文件已保留，请检查世界目录 tabletop/fixed-clock.json。",e);}
    }

    private static String string(JsonObject root,String key){
        JsonElement value=root.get(key);
        if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString())throw new IllegalArgumentException("缺少 "+key);
        return value.getAsString();
    }

    private static BlockPos readPosition(JsonElement value){
        if(value==null||!value.isJsonObject())throw new IllegalArgumentException("坐标缺失");JsonObject object=value.getAsJsonObject();
        return new BlockPos(integer(object,"x"),integer(object,"y"),integer(object,"z"));
    }

    private static int integer(JsonObject object,String key){
        JsonElement value=object.get(key);
        if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("坐标 "+key+" 不合法");
        return value.getAsBigDecimal().intValueExact();
    }

    private static JsonObject position(BlockPos pos){JsonObject result=new JsonObject();result.addProperty("x",pos.getX());result.addProperty("y",pos.getY());result.addProperty("z",pos.getZ());return result;}
    private static void checkDimension(ServerPlayerEntity p,Binding binding){if(!binding.dimension.equals(p.getWorld().getRegistryKey().getValue().toString()))throw new IllegalStateException("请到固定钟楼场地所在的维度开桌。");}
    private static void checkNearby(ServerPlayerEntity p,Binding binding){if(p.squaredDistanceTo(binding.arenaAnchor.getX()+.5,binding.arenaAnchor.getY()+.5,binding.arenaAnchor.getZ()+.5)>64d*64d)throw new IllegalStateException("请到固定钟楼场地64格内开桌。");}
    private static boolean nearAnchor(BlockPos anchor,BlockPos pos){double x=(double)anchor.getX()-pos.getX(),y=(double)anchor.getY()-pos.getY(),z=(double)anchor.getZ()-pos.getZ();return x*x+y*y+z*z<=64d*64d;}
    private static void checkPosition(ServerPlayerEntity p,BlockPos pos){if(Math.abs((long)pos.getX())>=29_999_984L||Math.abs((long)pos.getZ())>=29_999_984L||p.getServerWorld().isOutOfHeightLimit(pos)||!p.getServerWorld().getWorldBorder().contains(pos))throw new IllegalArgumentException("固定钟楼场地坐标不合法。");}

    public static synchronized void clearCache(){CACHE.clear();}
}
