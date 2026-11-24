package local.tabletop;

import java.util.*;

public final class GameRuleList {
    public record Rule(String key,String title,String unit,int min,int max,int initial,int step) {
        public boolean accepts(int n){ return n>=min && n<=max && (n-min)%step==0; }
    }
    static final String FLIP="flip7", BED="bedwars", HOT="hot_potato", TNT="tnt_run", UHC="classic_pvp";
    static final Map<String,String> TITLES=Map.of(FLIP,"七连翻",BED,"起床战争",HOT,"烫手山芋",TNT,"TNT Run",UHC,"UHC PvP");
    private static Rule r(String k,String t,String u,int lo,int hi,int def){return new Rule(k,t,u,lo,hi,def,1);}
    private static Rule flag(String k,String t,int def){return r(k,t,"开关",0,1,def);}
    public static final Map<String,List<Rule>> LISTS=Map.of(
        FLIP,List.of(r("goal","胜利分数","分",1,10000,200),r("bonus","集齐七种数字的奖励","分",0,200,15),flag("auto","自动进入下一轮",0),r("delay","自动换轮前展示计分","秒",1,60,3),r("min","最少开局人数","人",3,8,3),r("max","最多入座人数","人",3,8,8)),
        BED,List.of(new Rule("teams","启用队伍数","队",2,4,4,2),r("maxPerTeam","每队人数上限","人",1,8,4),r("minPlayers","最少开局人数","人",2,32,2),r("respawnSeconds","复活等待","秒",1,30,5),r("shieldTicks","复活保护","刻",0,200,40),r("bedLossSeconds","开局后清床时间","秒",60,7199,1800),r("maxSeconds","整局时限","秒",61,7200,3600),r("bronzeTicks","铜生成间隔","刻",1,12000,20),r("ironTicks","铁生成间隔","刻",1,12000,200),r("goldTicks","金生成间隔","刻",1,12000,800),
            flag("bowEnabled","死斗启用凋零弓",1),r("bowDamage","命中伤害","HP",0,80,10),flag("bowExplosion","命中爆炸",1),r("bowBlastRadius","伤人范围","格",1,8,3),r("bowWitherSeconds","凋零持续","秒",0,30,5),r("bowKnockback","爆炸击退","%",0,200,100),flag("bowTerrain","炸毁本局搭建的方块",1),r("bowBreakRadius","拆方块范围","格",1,6,2),flag("bowObsidian","可炸本局放置的黑曜石",0),
            r("magnetChance","磁力靴免击退概率","%",0,100,75),r("magnetDamage","磁力靴承伤比例","%",100,400,200),
            r("rescueSeconds","急救平台持续","秒",2,30,5),r("rescueCooldown","急救平台冷却","秒",0,120,20),r("rescueFall","踩平台的摔落伤害","%",0,100,0),
            new Rule("wallWidth","防护砖墙宽度","格",3,9,5,2),r("wallHeight","防护砖墙高度","格",1,6,3),r("wallDistance","砖墙生成距离","格",1,5,2),r("wallCooldown","防护砖墙冷却","秒",0,120,20)),
        UHC,List.of(r("spawnGuard","出生保护半径","格",1,5,2),flag("flowWater","水向周围流动",1),flag("flowLava","岩浆向周围流动",1),r("blocks","携带方块","个",1,64,64),r("apples","金苹果","个",1,64,6),r("arrows","箭矢","支",0,64,16)),
        TNT,List.of(r("delayTicks","踩过后地板消失延迟","刻",1,100,8),r("graceTicks","开场地板保护时间","刻",0,200,40),r("minPlayers","最少开局人数","人",2,32,2),r("maxPlayers","最多参加人数","人",2,32,10)),
        HOT,List.of(r("round1","第 1 轮引信","秒",5,300,110),r("round2","第 2 轮引信","秒",5,300,90),r("round3","第 3 轮引信","秒",5,300,70),r("round4","第 4 轮引信","秒",5,300,50),r("round5","第 5 轮引信","秒",5,300,35),r("roundDecreaseSeconds","每轮缩短","秒",0,30,2),r("minimumRoundSeconds","引信下限","秒",5,60,5),r("finalSeconds","双人决赛引信上限","秒",5,60,20),r("passCooldownTicks","传递冷却","刻",1,100,10),r("passKnockbackPercent","传递击退强度","%",0,100,25),r("runnerSpeed","普通玩家速度等级","级",0,3,1),r("holderSpeed","持有者速度等级","级",0,4,2),r("assistThresholdSeconds","持有多久可用辅助","秒",1,300,20),r("assistDurationSeconds","辅助持续时间","秒",1,30,6),r("assistSpeed","辅助速度等级","级",0,6,3),flag("assistRevealOthers","辅助期间高亮其他人",1),r("resultSeconds","结算展示时间","秒",3,15,5),r("minPlayers","最少开局人数","人",2,32,2),r("maxPlayers","最多参加人数","人",2,32,10)));
    static LinkedHashMap<String,Integer> defaults(String game){var out=new LinkedHashMap<String,Integer>();for(Rule r:LISTS.get(game))out.put(r.key(),r.initial());return out;}
    public static void validate(String game,Map<String,Integer> v){
        List<Rule> rules=LISTS.get(game);
        if(v.size()!=rules.size())throw new IllegalArgumentException("参数数量不符，请重新打开菜单。");
        for(Rule r:rules)if(!v.containsKey(r.key())||!r.accepts(v.get(r.key())))throw new IllegalArgumentException(r.title()+"：请输入 "+r.min()+"–"+r.max()+r.unit()+(r.key().equals("teams")?"（2 或 4）":r.key().equals("wallWidth")?"（奇数）":""));
        if(game.equals(BED)){
            if(v.get("minPlayers")>v.get("teams")*v.get("maxPerTeam"))throw new IllegalArgumentException("最少开局人数不能超过所有队伍的总容量。");
            if(v.get("bedLossSeconds")>=v.get("maxSeconds"))throw new IllegalArgumentException("清床时间必须早于整局结束时间。");
        } else if(!game.equals(UHC)&&v.get(game.equals(FLIP)?"min":"minPlayers")>v.get(game.equals(FLIP)?"max":"maxPlayers"))throw new IllegalArgumentException("最少开局人数不能超过人数上限。");
        if(game.equals(HOT)){
            if(v.get("minimumRoundSeconds")>Math.min(v.get("round1"),v.get("finalSeconds")))throw new IllegalArgumentException("引信下限不能超过首轮或决赛时间。");
            if(v.get("holderSpeed")<v.get("runnerSpeed"))throw new IllegalArgumentException("持有者速度不能低于普通玩家速度。");
        }
    }
    private GameRuleList(){}
}
