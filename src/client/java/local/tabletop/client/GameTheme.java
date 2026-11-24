package local.tabletop.client;
import local.tabletop.GameRuleList;
import local.tabletop.TabletopMod;
import net.minecraft.item.*;
import java.util.*;
record GameTheme(int bg,int panel,int row,int edge,int light,Item icon,String tagline,List<Section> tabs){
    record Section(String name,List<String> keys){Section(String n,String keys){this(n,List.of(keys.split(" ")));}}
    static Section tab(String n,String k){return new Section(n,k);}
    static GameTheme of(String g){return switch(g){
        case "flip7"->new GameTheme(0xff101e1b,0xff18392f,0xff23503e,0xffe2bd68,0xffffdf91,TabletopMod.ITEMS.getOrDefault("flip_deck",Items.PAPER),"牌桌规则  /  FLIP 7",List.of(tab("计分与换轮","goal bonus auto delay"),tab("桌上座位","min max")));
        case "bedwars"->new GameTheme(0xff251822,0xff39222e,0xff523142,0xffe46c75,0xffffbab2,Items.RED_BED,"守床 · 升级 · 死斗",List.of(tab("队伍与复活","teams maxPerTeam minPlayers respawnSeconds shieldTicks"),tab("资源与时间","bronzeTicks ironTicks goldTicks bedLossSeconds maxSeconds"),tab("凋零弓","bowEnabled bowDamage bowExplosion bowBlastRadius bowWitherSeconds bowKnockback bowTerrain bowBreakRadius bowObsidian"),tab("特殊道具","magnetChance magnetDamage rescueSeconds rescueCooldown rescueFall wallWidth wallHeight wallDistance wallCooldown")));
        case "hot_potato"->new GameTheme(0xff291b13,0xff442b19,0xff5c3922,0xffed993f,0xffffc887,Items.BAKED_POTATO,"倒计时结束前，把山芋传出去",List.of(tab("引信","round1 round2 round3 round4 round5 roundDecreaseSeconds minimumRoundSeconds finalSeconds"),tab("传递与追逐","passCooldownTicks passKnockbackPercent runnerSpeed holderSpeed assistThresholdSeconds assistDurationSeconds assistSpeed assistRevealOthers"),tab("人数与结算","minPlayers maxPlayers resultSeconds")));
        case "tnt_run"->new GameTheme(0xff251f18,0xff382e25,0xff4a3e30,0xffefba52,0xffffdb90,Items.TNT,"脚下的地板，会在倒计时后消失",List.of(tab("地板","delayTicks graceTicks"),tab("参赛人数","minPlayers maxPlayers")));
        case "classic_pvp"->new GameTheme(0xff141b2b,0xff202c41,0xff2d3c54,0xff7cb9f2,0xffbbddff,Items.DIAMOND_SWORD,"出生点禁放方块和液体 · 不影响场外",List.of(tab("出生点与流体","spawnGuard flowWater flowLava"),tab("对决装备","blocks apples arrows")));
        default->throw new IllegalArgumentException(g);};}
    List<GameRuleList.Rule> group(String id,int index){var all=GameRuleList.LISTS.get(id);return tabs.get(index).keys().stream().map(k->all.stream().filter(r->r.key().equals(k)).findFirst().orElseThrow()).toList();}
}
