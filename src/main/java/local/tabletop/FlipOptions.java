package local.tabletop;

import com.google.gson.*;
import local.tabletop.game.FlipGame;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;
import java.nio.file.*;
import java.util.*;

record FlipOptions(int goal,int bonus,int auto,int delay,int min,int max) {
    static final FlipOptions DEFAULT=new FlipOptions(200,15,0,3,3,8);
    private static final Map<MinecraftServer,FlipOptions> cache=new WeakHashMap<>();
    private static final Set<MinecraftServer> unreadable=Collections.newSetFromMap(new WeakHashMap<>());
    static FlipOptions get(MinecraftServer s){return cache.computeIfAbsent(s,server->{try{
        Path p=file(server);if(!Files.exists(p))return DEFAULT;
        var obj=JsonParser.parseString(Files.readString(p)).getAsJsonObject();var values=GameRuleList.defaults(GameRuleList.FLIP);
        for(String k:values.keySet()){var n=obj.get(k);if(n==null||!n.isJsonPrimitive()||!n.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException(k);values.put(k,n.getAsBigDecimal().intValueExact());}
        GameRuleList.validate(GameRuleList.FLIP,values);return from(values);
    }catch(Exception e){unreadable.add(server);org.slf4j.LoggerFactory.getLogger("tabletop").warn("Could not read flip-settings.json",e);return DEFAULT;}});}
    static FlipOptions from(Map<String,Integer> n){return new FlipOptions(n.get("goal"),n.get("bonus"),n.get("auto"),n.get("delay"),n.get("min"),n.get("max"));}
    private static Path file(MinecraftServer s){return s.getSavePath(WorldSavePath.ROOT).resolve("tabletop/flip-settings.json");}
    Map<String,Integer> values(){return Map.of("goal",goal,"bonus",bonus,"auto",auto,"delay",delay,"min",min,"max",max);}
    FlipGame.Rules rules(){return new FlipGame.Rules(goal,bonus,auto!=0,delay*20);}
    static void save(MinecraftServer s,Map<String,Integer> n)throws java.io.IOException{
        get(s);if(unreadable.contains(s))throw new IllegalStateException("现有七连翻配置无法读取，请先修复 flip-settings.json。");
        var next=from(n);var file=file(s);Files.createDirectories(file.getParent());var tmp=Files.createTempFile(file.getParent(),"flip-settings-",".tmp");
        try{Files.writeString(tmp,new GsonBuilder().setPrettyPrinting().create().toJson(next));try{Files.move(tmp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING);}cache.put(s,next);}finally{Files.deleteIfExists(tmp);}
    }
}
