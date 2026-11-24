package local.tabletop;

import com.google.gson.*;
import net.minecraft.server.MinecraftServer;
import java.lang.reflect.*;
import java.util.*;

final class ArenaRules {
    private static final Map<String,ArenaRules> bridges=new HashMap<>();
    private static final Gson JSON=new Gson();
    private final String game;
    private final Field config,valid;
    private final Method stage,check,save,queue;
    static ArenaRules of(String game)throws ReflectiveOperationException{
        var b=bridges.get(game);if(b==null){b=new ArenaRules(game);bridges.put(game,b);}return b;
    }
    private ArenaRules(String id)throws ReflectiveOperationException{
        game=id;boolean bed=id.equals(GameRuleList.BED),uhc=id.equals(GameRuleList.UHC);
        String name=switch(id){case GameRuleList.BED->"BedWars";case GameRuleList.HOT->"HotPotato";case GameRuleList.TNT->"TntRun";case GameRuleList.UHC->"ClassicDuel";default->throw new IllegalArgumentException("不支持的游戏");};
        Class<?> c=Class.forName("com.megawalls.minigame."+name);
        config=c.getDeclaredField("config");config.setAccessible(true);valid=c.getDeclaredField(bed?"valid":"validConfig");valid.setAccessible(true);
        stage=c.getMethod("stage");check=c.getDeclaredMethod("values",config.getType());check.setAccessible(true);
        save=bed||uhc?c.getDeclaredMethod("save",MinecraftServer.class,String.class):c.getDeclaredMethod("save",MinecraftServer.class);save.setAccessible(true);
        queue=bed?c.getMethod("chosenCount",String.class):c.getMethod("queueSize");
    }
    boolean waiting()throws ReflectiveOperationException{return ((Enum<?>)stage.invoke(null)).name().equals("WAITING");}
    private JsonObject current()throws ReflectiveOperationException{
        if(!valid.getBoolean(null))throw new IllegalStateException("当前地图的玩法配置未能读取，请先检查游戏日志。");return JSON.toJsonTree(config.get(null)).getAsJsonObject();
    }
    private static boolean round(String k){return k.length()==6 && k.startsWith("round") && k.charAt(5)>='1' && k.charAt(5)<='5';}
    Map<String,Integer> read()throws ReflectiveOperationException{
        var obj=current();var result=new LinkedHashMap<String,Integer>();
        for(var r:GameRuleList.LISTS.get(game)){
            String k=r.key();JsonElement e=round(k)?obj.getAsJsonArray("roundSeconds").get(k.charAt(5)-'1'):obj.get(k);
            result.put(k,r.unit().equals("开关")?(e.getAsBoolean()?1:0):e.getAsInt());
        }return result;
    }
    void write(MinecraftServer server,Map<String,Integer> vals)throws ReflectiveOperationException{
        if(!waiting())throw new IllegalStateException("本局已开始，请在准备阶段修改。");
        if(game.equals(GameRuleList.BED)){
            String[] ids={"red","blue","green","yellow"};for(int i=0;i<4;i++){
                int count=((Number)queue.invoke(null,ids[i])).intValue();
                if(count>(i<vals.get("teams")?vals.get("maxPerTeam"):0))throw new IllegalArgumentException("已有玩家选队，请先调整队伍或提高容量。");
            }
        }else if(!game.equals(GameRuleList.UHC)&&((Number)queue.invoke(null)).intValue()>vals.get("maxPlayers"))throw new IllegalArgumentException("人数上限不能小于已排队的人数。");
        Object old=config.get(null);JsonObject obj=current();
        for(var r:GameRuleList.LISTS.get(game)){
            String k=r.key();int v=vals.get(k);
            if(round(k))obj.getAsJsonArray("roundSeconds").set(k.charAt(5)-'1',new JsonPrimitive(v));else if(r.unit().equals("开关"))obj.addProperty(k,v==1);else obj.addProperty(k,v);
        }
        Object next=JSON.fromJson(obj,config.getType());if(!Boolean.TRUE.equals(check.invoke(null,next)))throw new IllegalStateException("玩法未接受这组参数，请检查设置。");
        config.set(null,next);boolean ok=false;
        try{ok=save.getParameterCount()==2?Integer.valueOf(1).equals(save.invoke(null,server,JSON.toJson(old))):Boolean.TRUE.equals(save.invoke(null,server));}
        finally{if(!ok)config.set(null,old);}
        if(!ok)throw new IllegalStateException("地图配置保存失败，原参数已保留。");
    }
}
