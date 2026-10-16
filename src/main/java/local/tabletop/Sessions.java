package local.tabletop;

import com.google.gson.Gson;
import local.tabletop.game.*;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.MinecraftServer;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;
import net.minecraft.item.ItemStack;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.AffineTransformation;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.Direction;
import org.joml.Vector3f;
import org.joml.Quaternionf;
import net.minecraft.util.WorldSavePath;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.time.Instant;
import java.util.*;

/** Only the logical server owns game, message, and audience state. */
public final class Sessions {
    static final int CLOCK_PLAYERS=12;
    static final int DEFAULT_CLOCK_SEATS=8;
    static final int MIN_CLOCK_PLAYERS=5;
    private static final Gson JSON=new Gson();
    private static final org.slf4j.Logger LOGGER=org.slf4j.LoggerFactory.getLogger("tabletop-sessions");
    static final Map<String,Session> GAMES=new LinkedHashMap<>();
    static final Map<UUID,String> MEMBERS=new HashMap<>();
    private static final Map<UUID,String> OPEN_KIND=new HashMap<>();
    private static final Map<UUID,String> PUBLIC_GRIMOIRE_VIEWERS=new HashMap<>();
    private static final Map<UUID,String> OPEN_TOOL=new HashMap<>();
    private static final Map<UUID,int[]> THROTTLES=new HashMap<>();
    private static final Map<UUID,ActionFeedback> ACTION_FEEDBACK=new HashMap<>();
    private static long feedbackSequence;
    // A game hub may isolate inventories before admission. Standalone worlds need no hub.
    private static java.util.function.BiPredicate<ServerPlayerEntity,String> admission=(player,type)->true;
    public static void setAdmission(java.util.function.BiPredicate<ServerPlayerEntity,String> policy) {
        admission=java.util.Objects.requireNonNull(policy);
    }
    public static String gameOf(ServerPlayerEntity player) {
        Session session=GAMES.get(MEMBERS.get(player.getUuid()));
        return session==null?"":session.type;
    }
    private static void admit(ServerPlayerEntity player,String type) {
        if(!admission.test(player,type))throw new IllegalStateException("暂不能加入，请先检查小游戏入口和背包空间。");
    }
    private static final class ActionFeedback {
        final String action,session,requestId;boolean ok,fixed;String message="";
        ActionFeedback(String action,String session){this.action=action;this.session=session;this.requestId=Long.toString(++feedbackSequence);}
    }
    private record Message(Set<UUID> recipients,String channel,UUID author,String channelKey,String sender,String text,long sequence) {}
    static final class Session {
        final String id,type; UUID host; String hostName; final MinecraftServer server; final RegistryKey<World> dimension; BlockPos anchor,grimoirePos; final BlockPos arenaAnchor;
        final LinkedHashMap<UUID,String> roster=new LinkedHashMap<>();
        final ClockSeats clockSeats=new ClockSeats();
        final FlipSeats flipSeats=new FlipSeats();
        final Set<UUID> seatSelectionRequests=new HashSet<>();
        final Map<UUID,String[]> assignments=new HashMap<>();
        final Map<UUID,Map<String,Boolean>> setupMarks=new HashMap<>();
        final Map<UUID,String> setupNotes=new HashMap<>();
        final ArrayDeque<Message> messages=new ArrayDeque<>();
        final Map<String,Set<UUID>> groups=new LinkedHashMap<>();
        final Map<UUID,Integer> reviewPages=new HashMap<>();
        final Map<UUID,List<String>> deliveredRoleMessages=new HashMap<>();
        final Map<UUID,Integer> deliveredSpyNights=new HashMap<>();
        String promptedTask="";
        final List<Entity> displays=new ArrayList<>();
        final FlipTableRenderer.Scene flipScene=new FlipTableRenderer.Scene();
        final Set<UUID> offline=new HashSet<>();
        int inventoryRound=-1,flipInputRevision;
        final Map<UUID,String> lastDrawn=new HashMap<>();
        FlipGame flip; ClockGame clock,preparedClock; boolean running; int groupSequence,voteSeconds=45,notifiedFlipRound=-1;
        List<String> announced=List.of(); String clockDisplayState="",replayFile=""; long messageSequence,savedLogCount=-1; int generation;
        Session(ServerPlayerEntity p,String type,BlockPos anchor) {
            this.id=UUID.randomUUID().toString().substring(0,8);this.type=type;server=p.getServer();host=p.getUuid();hostName=p.getGameProfile().getName();dimension=p.getWorld().getRegistryKey();this.anchor=anchor.toImmutable();this.arenaAnchor=this.anchor;
            if(type.equals("flip")){roster.put(host,p.getGameProfile().getName());flipSeats.add(host);seatSelectionRequests.add(host);}
        }
        Set<UUID> audience() { var ids=new LinkedHashSet<>(roster.keySet());ids.add(host);return ids; }
        boolean ended() { return flip!=null&&flip.finished()||clock!=null&&clock.finished(); }
    }
    private Sessions() {}
    static Session current(ServerPlayerEntity p) {
        var s=GAMES.get(MEMBERS.get(p.getUuid()));if(s==null)throw new IllegalStateException("请打开手机，在开始游戏中创建或加入七连翻、暗流涌动。");return s;
    }
    static void host(Session s,UUID actor) { if(!s.host.equals(actor))throw new IllegalArgumentException("此操作只属于本桌主持人。"); }
    static void tell(ServerPlayerEntity p,String message) { ActionFeedback pending=ACTION_FEEDBACK.get(p.getUuid());if(pending!=null){if(!pending.fixed)pending.message=message;return;}TabletopMessages.info(p,message); }
    private static void respond(ServerPlayerEntity p,String message){tell(p,message);ActionFeedback pending=ACTION_FEEDBACK.get(p.getUuid());if(pending!=null){pending.message=message;pending.fixed=true;}}
    private static void ensureClockSuccess(String operation,String result){if(!ClockGame.acceptedResponse(operation,result))throw new IllegalStateException(result);}
    static void all(Session s,MinecraftServer server,String message) { for(UUID id:s.audience()) {var p=server.getPlayerManager().getPlayer(id);if(p!=null)p.sendMessage(TabletopMessages.publicLine(s.type,message),false);} }
    public static void create(ServerPlayerEntity p,String type,BlockPos pos) {
        createChecked(p,type,pos);
    }
    private static boolean createChecked(ServerPlayerEntity p,String type,BlockPos pos) {
        try {
            if(!List.of("flip","clock").contains(type))throw new IllegalArgumentException("类型是 flip（七连翻）或 clock（暗流涌动）。");
            if(MEMBERS.containsKey(p.getUuid()))throw new IllegalStateException("你已经在一桌游戏中，请先在手机中离桌，或由主持人关闭本桌。");
            if(GAMES.size()>=24)throw new IllegalStateException("当前桌数已达到上限。");
            admit(p,type);
            if(type.equals("clock"))pos=FixedClockTable.creationAnchor(p,FixedClockTable.get(p.getServer())==null?ClockArena.reusableAnchor(p,pos):pos);
            if(type.equals("flip")){pos=FlipTableLayout.reusableAnchor(p,pos);BlockPos candidate=pos;if(GAMES.values().stream().anyMatch(existing->existing.type.equals("flip")&&existing.dimension.equals(p.getWorld().getRegistryKey())&&existing.anchor.equals(candidate)))throw new IllegalStateException("这处牌桌已经准备好，请加入现有牌桌。");}
            requireKitSpace(p,type.equals("clock")?List.of("phone","grimoire","storyteller_wand"):List.of("phone","flip_deck","flip_draw","flip_stay"));
            Session s=new Session(p,type,pos);
            if(type.equals("clock")){var fixed=FixedClockTable.get(p.getServer());if(fixed!=null&&fixed.grimoirePos()!=null){s.grimoirePos=fixed.grimoirePos();placeDisplayAnchor(s,p);}FixedClockTable.save(s,p.getServer());}
            GAMES.put(s.id,s);MEMBERS.put(p.getUuid(),s.id);gameKit(p,s);TabletopPresentation.labels(s,p.getServer());
            if(type.equals("flip"))FlipTableLayout.moveToSeat(s,p,flipSeat(s,p.getUuid()));
            tell(p,type.equals("clock")?"钟楼已准备 · 你是说书人 · 等待玩家入座":"七连翻已准备 · 等待至少3人入座");
            if(type.equals("flip")&&!ACTION_FEEDBACK.containsKey(p.getUuid()))open(p,"phone",true);
            return true;
        }catch(RuntimeException e){tell(p,e.getMessage());return false;}
    }
    public static int command(ServerPlayerEntity p,String command) {
        try {
            String[] args=command.trim().split("\\s+",3);String op=args[0];
            if(op.equals("help")){tell(p,"场地设置：/tt layout；其余操作请打开手机或魔典。");return 1;}
            if(op.equals("kit")){kit(p);return 1;}
            if(op.equals("pickup")){pickup(p);return 1;}
            if(op.equals("create"))return createChecked(p,arg(args,1),p.getBlockPos())?1:0;
            if(op.equals("join")){join(p,arg(args,1));return 1;}
            if(op.equals("phone")){open(p,"phone",true);return 1;}
            if(op.equals("script")){open(p,"script_card",true);return 1;}
            if(op.equals("rules")){open(p,"rules_card",true);return 1;}
            if(op.equals("rulebook")){open(p,"rules_book",true);return 1;}
            if(op.equals("faq")){open(p,"faq_book",true);return 1;}
            if(op.equals("fliprules")){open(p,"flip_rules_book",true);return 1;}
            Session s=current(p);UUID actor=p.getUuid();String result;
            if(op.equals("layout")){result=ClockArena.command(s,p,command.length()>op.length()?command.substring(op.length()).trim():"status");respond(p,result);refresh(s,p.getServer());return 1;}
            if(op.equals("flipseat")){result=FlipTableLayout.command(s,p,command.length()>op.length()?command.substring(op.length()).trim():"");respond(p,result);return 1;}
            if(op.equals("seat")){result=chooseSeat(s,actor,Integer.parseInt(arg(args,1)));respond(p,result);refresh(s,p.getServer());return 1;}
            if(op.equals("status")){tell(p,status(s));return 1;}
            if(op.equals("leave")) { if(s.running&&!s.ended())throw new IllegalStateException("对局中保留席位；需要退出本局请联系主持人结束或关闭。");if(actor.equals(s.host)){close(p,s);return 1;}FlipInventory.clear(p);TabletopPresentation.release(actor);s.roster.remove(actor);s.clockSeats.remove(actor);s.flipSeats.remove(actor);s.seatSelectionRequests.remove(actor);s.assignments.remove(actor);s.setupMarks.remove(actor);s.setupNotes.remove(actor);s.preparedClock=null;s.offline.remove(actor);MEMBERS.remove(actor);OPEN_KIND.remove(actor);send(p,Map.of("kind","closed"),false);tell(p,"已离桌。");refresh(s,p.getServer());return 1; }
            if(op.equals("close")){host(s,actor);close(p,s);return 1;}
            if(op.equals("reset")){host(s,actor);if(s.running&&!s.ended())throw new IllegalStateException("先完成结算再重置；取消本局请在魔典中关闭。");saveReplay(s,p.getServer());ClockEmbodiment.release(s,p.getServer());FlipInventory.clear(s,p.getServer());clearDisplays(s);s.deliveredRoleMessages.clear();s.deliveredSpyNights.clear();s.promptedTask="";s.running=false;s.flip=null;s.clock=null;s.preparedClock=null;s.assignments.clear();s.setupMarks.clear();s.setupNotes.clear();s.messages.clear();s.groups.clear();s.reviewPages.clear();s.generation++;s.replayFile="";s.savedLogCount=-1;s.announced=List.of();s.clockDisplayState="";result="本桌已重置，可准备下一局。";}
            else if(op.equals("autoassign")){host(s,actor);if(!s.type.equals("clock")||s.running)throw new IllegalStateException("自动配牌只在暗流涌动准备阶段可用。");requireClockPlayers(s);requireOnline(s);ClockGame game=new ClockGame(s.host,clockPlayers(s),s.clockSeats.snapshot());String dealt=game.autoAssign(actor,System.nanoTime());if(!dealt.startsWith("已自动"))throw new IllegalStateException(dealt);s.assignments.clear();s.setupMarks.clear();s.setupNotes.clear();for(var e:game.setupAssignments(actor).entrySet())s.assignments.put(e.getKey(),e.getValue().toArray(String[]::new));s.preparedClock=game;respond(p,dealt);refresh(s,p.getServer());return 1;}
            else if(op.equals("storyteller")){result=transferStoryteller(s,p,member(s,arg(args,1)));}
            else if(op.equals("votetime")){host(s,actor);if(!s.type.equals("clock"))throw new IllegalStateException("投票时限只用于暗流涌动。");int seconds=Integer.parseInt(arg(args,1));if(seconds<15||seconds>300)throw new IllegalArgumentException("投票时限须为15–300秒。");if(s.clock!=null){result=s.clock.setVoteTimeout(actor,seconds);if(!result.startsWith("投票时限已"))throw new IllegalStateException(result);}else result="投票时限已设为 "+seconds+" 秒。";s.voteSeconds=seconds;}
            else if(op.equals("review")){host(s,actor);String setting=arg(args,1);if(!List.of("on","off").contains(setting))throw new IllegalArgumentException("review on 或 off。");result=clock(s).setReview(actor,setting.equals("on"));if(!s.clock.finished())throw new IllegalStateException(result);s.reviewPages.clear();s.generation++;if(setting.equals("off"))for(UUID id:s.roster.keySet()){var viewer=p.getServer().getPlayerManager().getPlayer(id);if(viewer!=null)send(viewer,Map.of("kind","closed"),false);OPEN_KIND.remove(id);}saveReplay(s,p.getServer());}
            else if(op.equals("logpage")){if(!s.host.equals(actor)&&(s.clock==null||!s.clock.canReview(actor)))throw new IllegalStateException("只有说书人，或已获局后复盘授权的本桌玩家可读完整记录。");Map<String,Object> state=s.clock==null?preparationState(s):readingState(s,actor);int pages=((Number)state.getOrDefault("logPages",1)).intValue(),page=((Number)state.getOrDefault("logPage",1)).intValue()-1;String direction=arg(args,1);page=switch(direction){case "previous"->page-1;case "next"->page+1;case "first"->0;case "last"->pages-1;default->Integer.parseInt(direction)-1;};s.reviewPages.put(actor,Math.clamp(page,0,pages-1));open(p,OPEN_KIND.getOrDefault(actor,s.host.equals(actor)?"grimoire":"clock_review"),false);return 1;}
            else if(op.equals("exportlog")){host(s,actor);if(s.clock==null)throw new IllegalStateException("开局后才可以导出完整对局记录。");saveReplay(s,p.getServer());tell(p,"完整对局记录已保存到服务器世界目录："+s.replayFile);refresh(s,p.getServer());return 1;}
            else if(op.equals("role")){host(s,actor);if(!s.type.equals("clock")||s.running)throw new IllegalStateException("只在暗流涌动准备阶段分配角色。");UUID target=member(s,arg(args,1));if(target.equals(s.host))throw new IllegalArgumentException("说书人不参与角色分配。");String[] ids=arg(args,2).split("\\s+");var role=RoleCatalog.get(ids[0]);var shownRole=RoleCatalog.get(ids.length>1?ids[1]:ids[0]);if(role==null||shownRole==null)throw new IllegalArgumentException("角色不属于暗流涌动，请从魔典的选角色按钮选择。");String shown=shownRole.id();if(role.id().equals("drunk")&&!shownRole.type().equals("townsfolk"))throw new IllegalArgumentException("酒鬼需要填写一个镇民假身份：drunk 镇民id。");if(!role.id().equals("drunk")&&!role.id().equals(shown))throw new IllegalArgumentException("只有酒鬼可分配不同的初始假身份。");for(var e:s.assignments.entrySet())if(!e.getKey().equals(target)&&(e.getValue()[0].equals(role.id())||e.getValue()[1].equals(shown)))throw new IllegalArgumentException("真实角色或对玩家展示的身份重复，请检查已分配角色。");s.assignments.put(target,new String[]{role.id(),shown});s.preparedClock=null;respond(p,"已分配 "+s.roster.get(target)+" → "+role.name()+"（对玩家展示 "+shownRole.name()+"）。");refresh(s,p.getServer());return 1;}
            else if(op.equals("start")){host(s,actor);if(s.running)throw new IllegalStateException("本局已经开始。");requireOnline(s);if(s.type.equals("flip")){requireFlipPlayers(s);s.flip=new FlipGame(flipPlayers(s),System.nanoTime());s.running=true;result="七连翻开始 · 从红色1号顺时针发牌 · 右键摸牌或收手，行动牌右键选人。";}else{requireClockPlayers(s);if(!ClockArena.enabled(s,p.getServer()))throw new IllegalStateException("请先配置并启用钟楼场地。");ClockGame game=preparedGame(s);String layoutIssue=ClockArena.issue(s,p.getServer());if(!layoutIssue.isEmpty())throw new IllegalStateException(layoutIssue);layoutIssue=ClockEmbodiment.lockIssue(s,p.getServer());if(!layoutIssue.isEmpty())throw new IllegalStateException(layoutIssue);result=game.start(s.host);if(!game.started())throw new IllegalStateException(result);s.clock=game;s.preparedClock=null;s.running=true;try{ClockEmbodiment.start(s,p.getServer());}catch(RuntimeException e){ClockEmbodiment.release(s,p.getServer());s.clock=null;s.preparedClock=null;s.running=false;throw e;}}}
            else if(op.equals("draw")){requireOnline(s);result=flip(s).draw(actor);}
            else if(op.equals("stay")){requireOnline(s);result=flip(s).stay(actor);}
            else if(op.equals("target")){requireOnline(s);result=flip(s).target(actor,member(s,arg(args,1)));}
            else if(op.equals("next")){host(s,actor);requireOnline(s);clearDisplays(s);result=flip(s).nextRound();}
            else if(op.equals("autonext")){host(s,actor);String setting=arg(args,1);if(!List.of("on","off").contains(setting))throw new IllegalArgumentException("autonext on 或 off。");result=flip(s).setAutoNext(setting.equals("on"));}
            else if(op.equals("phase")){requireOnline(s);result=clock(s).phase(actor,arg(args,1));}
            else if(op.equals("nominate")){requireOnline(s);result=clock(s).nominate(actor,member(s,arg(args,1)));}
            else if(op.equals("vote")){requireOnline(s);String vote=arg(args,1);if(!List.of("yes","no").contains(vote))throw new IllegalArgumentException("vote yes 或 vote no。");result=clock(s).vote(actor,vote.equals("yes"));}
            else if(op.equals("unvote")){requireOnline(s);result=clock(s).unvote(actor);}
            else if(op.equals("slay")){requireOnline(s);result=clock(s).slay(actor,member(s,arg(args,1)));}
            else if(op.equals("closevote")){requireOnline(s);result=clock(s).closeVote(actor);}
            else if(op.equals("execute")){requireOnline(s);result=clock(s).execute(actor);}
            else if(op.equals("mark")){host(s,actor);String[] parts=arg(args,2).split("\\s+");if(parts.length!=2||!List.of("true","false","on","off").contains(parts[1]))throw new IllegalArgumentException("mark 玩家 dead/poisoned/drunk/red_herring true/false");UUID who=member(s,arg(args,1));boolean enabled=parts[1].equals("true")||parts[1].equals("on");if(s.clock==null){if(!s.roster.containsKey(who))throw new IllegalArgumentException("状态目标必须是本桌基础玩家。");if(parts[0].equals("red_herring")){String[] assigned=s.assignments.get(who);if(assigned==null||List.of("minion","demon").contains(RoleCatalog.get(assigned[0]).type()))throw new IllegalArgumentException("红鲱鱼须为已分配角色的善良玩家。");}if(!s.type.equals("clock")||!List.of("red_herring","poisoned","drunk").contains(parts[0]))throw new IllegalArgumentException("准备阶段可标记 red_herring、poisoned、drunk。");if(parts[0].equals("red_herring")&&enabled)for(var marks:s.setupMarks.values())marks.remove("red_herring");s.setupMarks.computeIfAbsent(who,id->new HashMap<>()).put(parts[0],enabled);result="准备阶段标记已记录，开局时校验。";}else {result=s.clock.mark(actor,who,parts[0],enabled);ensureClockSuccess("mark",result);}respond(p,result);refresh(s,p.getServer());return 1;}
            else if(op.equals("finish")){result=clock(s).finish(actor,arg(args,1));if(s.host.equals(actor)&&s.clock.finished())saveReplay(s,p.getServer());}
            else if(op.equals("submit")){List<UUID> targets=new ArrayList<>();for(String name:arg(args,1).split(","))if(!name.isBlank())targets.add(member(s,name));result=clock(s).submit(actor,targets);ensureClockSuccess("submit",result);respond(p,result);refresh(s,p.getServer());return 1;}
            else if(op.equals("resolve")){host(s,actor);requireOnline(s);String info=command.length()>op.length()?command.substring(op.length()).trim():"";if(info.startsWith("pass:")||info.startsWith("kill:")){int split=info.indexOf(':');info=info.substring(0,split+1)+member(s,info.substring(split+1).trim());}result=ClockEmbodiment.resolve(s,p,info);if(result==null)result=info.isEmpty()?clock(s).resolve(actor):clock(s).resolve(actor,info);ensureClockSuccess("resolve",result);respond(p,result);refresh(s,p.getServer());return 1;}
            else if(op.equals("phones")){host(s,actor);String text=command.length()>op.length()?command.substring(op.length()).trim():"";if(text.isBlank())text="请查看手机，回复说书人。";sendMessage(s,actor,"public",text);for(UUID id:s.audience()){var viewer=p.getServer().getPlayerManager().getPlayer(id);if(viewer!=null){TabletopMessages.info(viewer,"说书人请你查看手机");}}return 1;}
            else if(op.equals("msg")){if(!TabletopMod.hasPhone(p))throw new IllegalStateException("请携带手机。");sendMessage(s,actor,arg(args,1),arg(args,2));refresh(s,p.getServer());return 1;}
            else if(op.equals("group")){if(!TabletopMod.hasPhone(p))throw new IllegalStateException("请携带手机。");if(s.groups.size()>=12)throw new IllegalStateException("本局小组已达到上限。");Set<UUID> members=new LinkedHashSet<>();members.add(actor);for(String name:arg(args,1).split(","))members.add(member(s,name));if(members.size()<2)throw new IllegalArgumentException("小组至少两人。");String id="g"+(++s.groupSequence);s.groups.put(id,Set.copyOf(members));sendMessage(s,actor,id,"已建立私密小组。");tell(p,"私密小组已建立，可从手机消息中打开。");refresh(s,p.getServer());return 1;}
            else throw new IllegalArgumentException("未知操作，输入 /tt 查看用法。");
            if(s.type.equals("clock")&&Set.of("phase","nominate","vote","unvote","slay","closevote","execute","finish").contains(op))ensureClockSuccess(op,result);
            if(s.type.equals("flip")&&op.equals("start")){s.notifiedFlipRound=-1;all(s,p.getServer(),"七连翻开始");}
            if(s.type.equals("flip")&&Set.of("start","draw","stay","target","next","reset").contains(op))s.flipInputRevision++;
            renderTable(s,p.getServer());refresh(s,p.getServer());ActionFeedback pending=ACTION_FEEDBACK.get(actor);if(pending!=null){pending.message=publicResult(s,result);pending.fixed=true;}return 1;
        }catch(RuntimeException e){respond(p,e.getMessage()==null?"操作失败":e.getMessage());return 0;}
    }
    private static String publicResult(Session s,String result){String text=result==null?"操作已处理。":result;var ids=s.type.equals("clock")?clockPlayers(s):new ArrayList<>(s.roster.keySet());for(int i=ids.size()-1;i>=0;i--){int number=TabletopPresentation.seat(s,ids.get(i));text=text.replace(number+" 号",number+"号（"+name(s,ids.get(i))+"）");}return text;}
    private static String arg(String[] args,int n){if(args.length<=n||args[n].isBlank())throw new IllegalArgumentException("参数不足，输入 /tt 查看用法。");return args[n];}
    private static FlipGame flip(Session s){if(s.flip==null)throw new IllegalStateException("七连翻尚未开始。");return s.flip;}
    private static ClockGame clock(Session s){if(s.clock==null)throw new IllegalStateException("暗流涌动尚未开始。");return s.clock;}
    private static UUID member(Session s,String name){if(name.equals("storyteller"))return s.host;try{UUID id=UUID.fromString(name);if(s.audience().contains(id))return id;}catch(IllegalArgumentException ignored){}for(var e:s.roster.entrySet())if(e.getValue().equalsIgnoreCase(name))return e.getKey();throw new IllegalArgumentException("目标不属于本桌："+name);}
    static List<UUID> clockPlayers(Session s){for(UUID id:s.roster.keySet())s.clockSeats.add(id);return s.clockSeats.ordered(s.roster.keySet());}
    static List<UUID> flipPlayers(Session s){
        if(!s.type.equals("flip"))return List.of();
        for(UUID id:s.roster.keySet())if(s.flipSeats.add(id)==0)throw new IllegalStateException("七连翻本桌最多8名玩家。");
        return s.flipSeats.ordered(s.roster.keySet());
    }
    static int flipSeat(Session s,UUID id){return s.type.equals("flip")?s.flipSeats.number(id):0;}
    static Map<String,Object> flipPublicState(Session s){
        flipPlayers(s);
        Map<String,Object> state=new LinkedHashMap<>();
        if(s.flip!=null)state.putAll(s.flip.publicView());
        else{state.put("phase","setup");state.put("started",false);state.put("finished",false);state.put("readyToStart",flipReady(s));state.put("minimumPlayers",3);state.put("maximumPlayers",FlipSeats.LIMIT);state.put("playerCount",s.roster.size());state.put("startIssue",flipStartIssue(s));}
        state.put("game","flip7");state.put("tablePaused",!s.offline.isEmpty());
        var rows=new LinkedHashMap<String,Object>();Map<?,?> raw=state.get("players") instanceof Map<?,?> value?value:Map.of();
        for(UUID id:flipPlayers(s)){
            var row=new LinkedHashMap<String,Object>();
            if(raw.get(id.toString()) instanceof Map<?,?> data)data.forEach((key,value)->row.put(String.valueOf(key),value));
            row.put("seat",flipSeat(s,id));row.put("color",FlipSeats.color(flipSeat(s,id)));row.put("colorName",FlipSeats.colorName(flipSeat(s,id)));rows.put(id.toString(),row);
        }
        if(s.flip!=null)state.put("players",rows);var numbers=new LinkedHashMap<String,Integer>();s.flipSeats.snapshot().forEach((id,number)->numbers.put(id.toString(),number));state.put("seatNumbers",numbers);
        Object turn=state.get("turn");int number=0;if(turn!=null)try{number=flipSeat(s,UUID.fromString(turn.toString()));}catch(IllegalArgumentException ignored){}state.put("turnSeat",number);
        return state;
    }
    private static String flipStartIssue(Session s){
        if(s.roster.size()<3||s.roster.size()>FlipSeats.LIMIT)return "七连翻需要3–8名玩家，主持人也参加。";
        flipPlayers(s);if(!s.flipSeats.snapshot().containsValue(1))return "红色1号需要有人入座，第一轮从1号开始。";
        return s.offline.isEmpty()?"":"本桌有人离线，等待重连后继续。";
    }
    private static boolean flipReady(Session s){return !s.running&&flipStartIssue(s).isEmpty();}
    private static void requireFlipPlayers(Session s){String issue=flipStartIssue(s);if(!issue.isEmpty())throw new IllegalStateException(issue);}
    static UUID clockPlayerAt(Session s,int number){clockPlayers(s);for(UUID id:s.roster.keySet())if(s.clockSeats.number(id)==number)return id;return null;}
    static int seatCapacity(Session s){clockPlayers(s);return Math.max(DEFAULT_CLOCK_SEATS,s.clockSeats.maxNumber());}
    static String clockGatheringLabel(Session s){return "玩家 "+s.roster.size()+" / "+seatCapacity(s)+" · 说书人 1/1";}
    private static String chooseSeat(Session s,UUID actor,int number){
        if(s.running)throw new IllegalStateException("只能在开局前选择号码。开始后号码固定。");
        if(s.type.equals("flip")){
            if(!s.roster.containsKey(actor))throw new IllegalStateException("请先加入本桌，再选择自己的号码。");
            flipPlayers(s);if(number<1||number>FlipSeats.LIMIT)throw new IllegalArgumentException("七连翻请选择1–8号。");
            if(!s.flipSeats.choose(actor,number))throw new IllegalStateException(number+"号已有人选择，请选空号。");
            s.seatSelectionRequests.remove(actor);s.flipInputRevision++;
            var player=s.server.getPlayerManager().getPlayer(actor);boolean moved=player!=null&&FlipTableLayout.moveToSeat(s,player,number);
            return "已选择 "+number+"号 · "+FlipSeats.colorName(number)+(moved?"":"；站位未配置或不安全，请走到对应颜色的座位。");
        }
        if(!s.type.equals("clock"))throw new IllegalStateException("这桌没有可选择的玩家号码。");
        if(actor.equals(s.host)||!s.roster.containsKey(actor))throw new IllegalStateException("说书人不占玩家号码。");
        clockPlayers(s);int previous=s.clockSeats.number(actor);
        if(number<1||number>CLOCK_PLAYERS)throw new IllegalArgumentException("请选择1–12号。");
        if(!s.clockSeats.choose(actor,number))throw new IllegalStateException(number+"号已有人选择，请选空号。");
        if(s.preparedClock!=null&&!s.preparedClock.reseat(s.host,s.clockSeats.snapshot())){s.clockSeats.choose(actor,previous);throw new IllegalStateException("号码未修改，请刷新后重试。");}
        s.seatSelectionRequests.remove(actor);s.clockDisplayState="";
        return "已选择 "+number+"号 · "+ClockSeats.colorName(number);
    }
    private static Map<String,Object> phoneFlipState(Session s,UUID viewer){
        Map<String,Object> state=flipPublicState(s);boolean canChoose=!s.running&&s.roster.containsKey(viewer);
        state.put("canChooseSeat",canChoose);state.put("ownSeat",flipSeat(s,viewer));
        if(canChoose){var choices=new ArrayList<Map<String,Object>>();Map<UUID,Integer> numbers=s.flipSeats.snapshot();
            for(int number=1;number<=FlipSeats.LIMIT;number++){
                UUID owner=null;for(var entry:numbers.entrySet())if(entry.getValue()==number){owner=entry.getKey();break;}
                var choice=new LinkedHashMap<String,Object>();choice.put("number",number);choice.put("color",FlipSeats.color(number));choice.put("colorName",FlipSeats.colorName(number));choice.put("owner",owner==null?"":owner.toString());choice.put("ownerName",owner==null?"":name(s,owner));choice.put("current",viewer.equals(owner));choice.put("available",owner==null||viewer.equals(owner));choices.add(choice);
            }state.put("seatChoices",choices);
        }return state;
    }
    private static Map<String,Object> phoneClockState(Session s,UUID viewer){
        Map<String,Object> state=new LinkedHashMap<>(s.clock==null?publicPreparation(s):readingState(s,viewer));
        boolean canChoose=!s.running&&s.roster.containsKey(viewer)&&!viewer.equals(s.host);
        state.put("canChooseSeat",canChoose);state.put("ownSeat",s.clockSeats.number(viewer));
        if(canChoose){
            List<Map<String,Object>> choices=new ArrayList<>();Map<UUID,Integer> numbers=s.clockSeats.snapshot();
            for(int number=1;number<=CLOCK_PLAYERS;number++){
                UUID owner=null;for(var entry:numbers.entrySet())if(entry.getValue()==number){owner=entry.getKey();break;}
                Map<String,Object> choice=new LinkedHashMap<>();choice.put("number",number);choice.put("color",ClockSeats.color(number));choice.put("colorName",ClockSeats.colorName(number));choice.put("owner",owner==null?"":owner.toString());choice.put("ownerName",owner==null?"":name(s,owner));choice.put("current",viewer.equals(owner));choice.put("available",owner==null||viewer.equals(owner));choices.add(choice);
            }
            state.put("seatChoices",choices);
        }
        return state;
    }
    private static ClockGame preparedGame(Session s) {
        List<UUID> seats=clockPlayers(s);ClockGame game=s.preparedClock;
        if(game==null||!game.storyteller().equals(s.host)||!game.players().equals(seats)) {
            game=new ClockGame(s.host,seats,s.clockSeats.snapshot());
            for(var e:s.assignments.entrySet()){String result=game.assign(s.host,e.getKey(),e.getValue()[0],e.getValue()[1]);if(!result.startsWith("已分配"))throw new IllegalStateException(result);}
        }
        if(game.voteTimeoutSeconds()!=s.voteSeconds)game.setVoteTimeout(s.host,s.voteSeconds);
        for(var e:s.setupMarks.entrySet())for(var mark:e.getValue().entrySet()){String result=game.mark(s.host,e.getKey(),mark.getKey(),mark.getValue());if(!result.startsWith("魔典状态已更新"))throw new IllegalStateException(result);}
        for(var e:s.setupNotes.entrySet())game.mark(s.host,e.getKey(),"note:"+e.getValue(),true);s.preparedClock=game;return game;
    }
    private static String transferStoryteller(Session s,ServerPlayerEntity actor,UUID next) {
        host(s,actor.getUuid());
        if(!s.type.equals("clock")||s.running)throw new IllegalStateException("只能在暗流涌动准备阶段转交说书人，开始后请先结算并重置。");
        if(next.equals(s.host))return "该玩家已经是本桌说书人。";
        if(!s.roster.containsKey(next))throw new IllegalArgumentException("新说书人必须来自本桌基础玩家席位。");
        clockPlayers(s);
        requireOnline(s);requireKitSpace(actor.getServer().getPlayerManager().getPlayer(next),List.of("phone","grimoire","storyteller_wand"));String nextName=s.roster.get(next);UUID previous=s.host;
        var seats=new LinkedHashMap<UUID,String>();
        for(var e:s.roster.entrySet())if(e.getKey().equals(next))seats.put(previous,s.hostName);else seats.put(e.getKey(),e.getValue());
        s.roster.clear();s.roster.putAll(seats);s.host=next;s.hostName=nextName;
        try{FixedClockTable.save(s,actor.getServer());}catch(RuntimeException e){s.roster.clear();for(var entry:seats.entrySet())if(entry.getKey().equals(previous))s.roster.put(next,nextName);else s.roster.put(entry.getKey(),entry.getValue());s.host=previous;s.hostName=actor.getGameProfile().getName();throw e;}
        s.clockSeats.replace(next,previous);s.seatSelectionRequests.remove(next);s.seatSelectionRequests.add(previous);
        MEMBERS.put(previous,s.id);MEMBERS.put(next,s.id);
        s.clock=null;s.preparedClock=null;s.assignments.clear();s.setupMarks.clear();s.setupNotes.clear();s.messages.clear();s.groups.clear();s.reviewPages.clear();s.deliveredRoleMessages.clear();s.deliveredSpyNights.clear();s.promptedTask="";s.groupSequence=0;s.generation++;
        s.announced=List.of();s.clockDisplayState="";clearDisplays(s);
        // Revoke already-open private dashboards, then issue newly authorized views on demand.
        for(UUID id:s.audience()){var viewer=actor.getServer().getPlayerManager().getPlayer(id);if(viewer!=null)send(viewer,Map.of("kind","closed","session",s.id,"generation",s.generation),false);OPEN_KIND.remove(id);}
        gameKit(actor,s);gameKit(actor.getServer().getPlayerManager().getPlayer(next),s);
        return "说书人已转交给 "+nextName+"；原说书人已入座，准备配置与私密聊天已清理，请重新配牌。";
    }
    private static void requireOnline(Session s){if(!s.offline.isEmpty())throw new IllegalStateException("本桌有人离线，流程暂缓；重连后继续。主持人可关闭本桌取消。");}
    private static void requireClockPlayers(Session s){if(s.type.equals("clock")&&(s.roster.size()<MIN_CLOCK_PLAYERS||s.roster.size()>CLOCK_PLAYERS))throw new IllegalStateException("至少5名玩家，另加说书人。目前玩家 "+s.roster.size()+" 人。");}
    private static void join(ServerPlayerEntity p,String id){
        join(p,id,0);
    }
    private static void join(ServerPlayerEntity p,String id,int preferredSeat){
        if(MEMBERS.containsKey(p.getUuid()))throw new IllegalStateException("你已经入桌，请先离桌。");
        Session s=GAMES.get(id);if(s==null)throw new IllegalArgumentException("桌号不存在。");
        if(s.running)throw new IllegalStateException("本局已经开始，等待下一局。");
        int maximum=s.type.equals("flip")?FlipSeats.LIMIT:CLOCK_PLAYERS;
        if(s.roster.size()>=maximum)throw new IllegalStateException(s.type.equals("flip")?"七连翻本桌最多8名玩家，主持人也参加。":"本桌已有12名玩家，另设1名说书人。");
        if(!p.getWorld().getRegistryKey().equals(s.dimension)||p.squaredDistanceTo(s.anchor.getX()+.5,s.anchor.getY(),s.anchor.getZ()+.5)>64*64)throw new IllegalStateException("请到固定场地64格内加入。");
        if(s.type.equals("flip")){flipPlayers(s);if(preferredSeat<0||preferredSeat>FlipSeats.LIMIT)throw new IllegalArgumentException("七连翻请选择1–8号。");if(preferredSeat>0&&s.flipSeats.snapshot().containsValue(preferredSeat))throw new IllegalStateException(preferredSeat+"号已有人选择，请选空号。");}
        admit(p,s.type);requireKitSpace(p,List.of("phone"));
        if(s.type.equals("clock")){clockPlayers(s);if(s.clockSeats.add(p.getUuid())==0)throw new IllegalStateException("没有空余玩家号码。");}
        else{if(s.flipSeats.add(p.getUuid())==0)throw new IllegalStateException("七连翻本桌没有空余号码。");if(preferredSeat>0&&!s.flipSeats.choose(p.getUuid(),preferredSeat)){s.flipSeats.remove(p.getUuid());throw new IllegalStateException(preferredSeat+"号已有人选择，请选空号。");}}
        s.seatSelectionRequests.add(p.getUuid());s.roster.put(p.getUuid(),p.getGameProfile().getName());s.preparedClock=null;MEMBERS.put(p.getUuid(),id);gameKit(p,s);
        if(s.type.equals("flip"))FlipTableLayout.moveToSeat(s,p,flipSeat(s,p.getUuid()));
        all(s,p.getServer(),p.getGameProfile().getName()+"已入座。");refresh(s,p.getServer());
        if(s.type.equals("flip")&&!ACTION_FEEDBACK.containsKey(p.getUuid()))open(p,"phone",true);
    }
    static boolean joinFlipStation(ServerPlayerEntity p,Session s,BlockPos pos){
        try{
            if(!s.type.equals("flip")||FlipTableLayout.bound(p.getServerWorld(),pos)!=s)throw new IllegalStateException("请使用这桌对应的入座按钮。");
            int number=FlipTableLayout.stationSeat(s,p.getServerWorld(),pos);if(number<1||number>FlipSeats.LIMIT)throw new IllegalStateException("此按钮尚未绑定1–8号座位，请用手机加入并选择号码。");
            join(p,s.id,number);return true;
        }catch(RuntimeException error){tell(p,error.getMessage());return false;}
    }
    private static boolean hasItem(ServerPlayerEntity p,String id){var item=TabletopMod.ITEMS.get(id);for(int i=0;i<p.getInventory().size();i++)if(p.getInventory().getStack(i).isOf(item))return true;return false;}
    private static void requireKitSpace(ServerPlayerEntity p,List<String> items){int missing=0,empty=0;for(String id:items)if(!hasItem(p,id))missing++;for(int i=0;i<p.getInventory().main.size();i++)if(p.getInventory().main.get(i).isEmpty())empty++;if(missing>empty)throw new IllegalStateException("背包需留出 "+missing+" 个空格，以接收本阶段道具。");}
    private static void giveItems(ServerPlayerEntity p,List<String> items){requireKitSpace(p,items);for(String id:items)if(!hasItem(p,id)){ItemStack stack=new ItemStack(TabletopMod.ITEMS.get(id),Set.of("flip_draw","flip_stay").contains(id)?18:1);if(!p.getInventory().insertStack(stack))throw new IllegalStateException("背包已满，请留出空格领取道具。");}p.getInventory().markDirty();}
    private static final String ISSUED_TOOL="tabletop_issued_tool";
    private static void giveStorytellerTool(ServerPlayerEntity p,String kind){
        if(hasItem(p,kind))return;ItemStack stack=new ItemStack(TabletopMod.ITEMS.get(kind));var tag=new NbtCompound();tag.putString("owner",p.getUuidAsString());tag.putString("kind",kind);var data=new NbtCompound();data.put(ISSUED_TOOL,tag);stack.set(DataComponentTypes.CUSTOM_DATA,NbtComponent.of(data));if(!p.getInventory().insertStack(stack))throw new IllegalStateException("请留出背包空格领取主持道具。");
    }
    private static void retractStorytellerTools(ServerPlayerEntity p){
        for(int i=0;i<p.getInventory().size();i++){ItemStack stack=p.getInventory().getStack(i);var tag=stack.getOrDefault(DataComponentTypes.CUSTOM_DATA,NbtComponent.DEFAULT).copyNbt().getCompound(ISSUED_TOOL);if(tag.getString("owner").equals(p.getUuidAsString())&&Set.of("grimoire","storyteller_wand").contains(tag.getString("kind")))p.getInventory().setStack(i,ItemStack.EMPTY);}p.getInventory().markDirty();
    }
    private static void gameKit(ServerPlayerEntity p,Session s){
        if(p==null)return;
        if(s.type.equals("clock")&&s.host.equals(p.getUuid())){requireKitSpace(p,List.of("phone","grimoire","storyteller_wand"));giveItems(p,List.of("phone"));giveStorytellerTool(p,"grimoire");giveStorytellerTool(p,"storyteller_wand");}
        else {if(s.type.equals("clock"))retractStorytellerTools(p);giveItems(p,s.type.equals("flip")&&s.host.equals(p.getUuid())?List.of("phone","flip_deck","flip_draw","flip_stay"):List.of("phone"));}
    }
    private static void kit(ServerPlayerEntity p){Session s=GAMES.get(MEMBERS.get(p.getUuid()));if(s!=null)gameKit(p,s);else giveItems(p,List.of("phone","grimoire_lectern","flip_deck","flip_draw","flip_stay"));tell(p,"道具已准备");}
    private static void sendMessage(Session s,UUID from,String recipient,String text){
        if(text.isBlank()||text.length()>600)throw new IllegalArgumentException("消息长度应为1–600字。");
        Set<UUID> recipients;String channel;
        if(recipient.equals("public")){recipients=Set.copyOf(s.audience());channel="公聊";}
        else if(s.groups.containsKey(recipient)){recipients=s.groups.get(recipient);if(!recipients.contains(from))throw new IllegalArgumentException("你不是此私密小组成员。");channel="小组 "+recipient;}
        else{UUID to=member(s,recipient);recipients=new HashSet<>(List.of(from,to));channel="私聊 → "+name(s,to);}
        String clean=text.replaceAll("[\\p{Cntrl}§]","");if(clean.isBlank())throw new IllegalArgumentException("消息内容不能为空。");s.messages.addLast(new Message(Set.copyOf(recipients),channel,from,recipient,name(s,from),clean,++s.messageSequence));while(s.messages.size()>400)s.messages.removeFirst();
    }
    private static String name(Session s,UUID id){return id.equals(s.host)&&!s.roster.containsKey(id)?"说书人":s.roster.getOrDefault(id,"玩家");}
    private static String status(Session s){return "桌号 "+s.id+" · "+(s.type.equals("flip")?"七连翻":"暗流涌动")+" · "+(s.type.equals("clock")?clockGatheringLabel(s):s.roster.size()+" / 8人")+" · "+(!s.running?"准备中":s.ended()?"已结算":"对局中")+(s.offline.isEmpty()?"":" · 等待重连");}
    public static void action(ServerPlayerEntity p,TabletopNet.Action packet){
        String a=packet.action(),target=packet.target(),text=packet.text();
        int tick=p.getServer().getTicks();int[] rate=THROTTLES.computeIfAbsent(p.getUuid(),id->new int[]{tick,0});if(tick-rate[0]>=20){rate[0]=tick;rate[1]=0;}
        if(++rate[1]>8){if(!a.equals("open")&&!a.equals("refresh")){String message="操作过快，请稍候再试；本次请求未执行。";tell(p,message);feedback(p,new ActionFeedback(a,MEMBERS.getOrDefault(p.getUuid(),"")),false,message);}return;}
        if(a.equals("open")){open(p,target,true);return;}
        if(a.equals("refresh")){open(p,OPEN_KIND.getOrDefault(p.getUuid(),"phone"),false);return;}
        ActionFeedback pending=new ActionFeedback(a,MEMBERS.getOrDefault(p.getUuid(),""));ACTION_FEEDBACK.put(p.getUuid(),pending);
        try{
            if(a.equals("create")){
                if(!TabletopMod.hasPhone(p))throw new IllegalStateException("请携带实体手机再创建游戏。");
                if(!List.of("flip","clock").contains(target))throw new IllegalArgumentException("请选择七连翻或暗流涌动。");
                pending.ok=createChecked(p,target,p.getBlockPos());
                if(pending.ok){pending.message=target.equals("clock")?"钟楼已准备 · 你是说书人":"七连翻已准备 · 等待入座";pending.fixed=true;open(p,"phone",true);}
                return;
            }
            if(a.equals("join")){pending.ok=command(p,"join "+target)==1&&target.equals(MEMBERS.get(p.getUuid()));if(pending.ok){pending.message="已入座 · 开局前可选择号码与颜色";pending.fixed=true;open(p,"phone",true);}return;}
            Session current=GAMES.get(MEMBERS.get(p.getUuid()));
            if(current!=null&&current.type.equals("clock")&&(ST_CONTROLS.contains(a)||a.equals("leave")&&current.host.equals(p.getUuid()))){
                host(current,p.getUuid());
                if(!controlContext(current).equals(packet.context()))throw new IllegalStateException("步骤已更新，请重新选择。");
            }
            boolean atGrimoire=current!=null&&current.host.equals(p.getUuid())&&OPEN_KIND.getOrDefault(p.getUuid(),"").equals("grimoire")&&!List.of("message","group").contains(a);
            boolean flipAction=current!=null&&current.type.equals("flip")&&Set.of("draw","stay","target","flip_target").contains(a);
            if(!TabletopMod.hasPhone(p)&&!atGrimoire&&!flipAction)throw new IllegalStateException("请携带实体手机，或由说书人右键魔典讲台操作。");
            switch(a){
                case "flip_target"->{if(current==null||!current.type.equals("flip")||!flipContext(current).equals(packet.context()))throw new IllegalStateException("目标步骤已更新。");pending.ok=command(p,"target "+target)==1;}
                case "choose_seat"->{Session s=current(p);int number;try{number=Integer.parseInt(target);}catch(NumberFormatException invalid){throw new IllegalArgumentException(s.type.equals("flip")?"七连翻请选择1–8号。":"请选择1–12号。");}String result=chooseSeat(s,p.getUuid(),number);pending.ok=true;respond(p,result);refresh(s,p.getServer());}
                case "message"->{Session s=current(p);sendMessage(s,p.getUuid(),target.isBlank()?"storyteller":target,text);pending.ok=true;pending.message="消息已发送。";pending.fixed=true;refresh(s,p.getServer());}
                case "group"->pending.ok=command(p,"group "+target)==1;
                case "assign"->pending.ok=command(p,"role "+target+" "+text)==1;
                case "prepare_tools"->{Session s=current(p);host(s,p.getUuid());gameKit(p,s);pending.ok=true;respond(p,"道具已补齐。");refresh(s,p.getServer());}
                case "clear_role","swap","note","clear_note"->{Session s=current(p);host(s,p.getUuid());UUID who=member(s,target);if(!s.roster.containsKey(who))throw new IllegalArgumentException("请选择玩家席位。");
                    if(a.equals("note")||a.equals("clear_note")){String clean=text.replaceAll("[\\p{Cntrl}§]","").trim();if(a.equals("note")&&(clean.isEmpty()||clean.length()>160))throw new IllegalArgumentException("备注请填写1–160字。");if(s.clock==null){if(a.equals("clear_note"))s.setupNotes.remove(who);else s.setupNotes.put(who,clean);s.preparedClock=null;}else ensureClockSuccess("mark",s.clock.mark(p.getUuid(),who,"note:"+clean,a.equals("note")));}
                    else {if(s.running)throw new IllegalStateException("只能在配牌阶段修改角色。");if(a.equals("clear_role")){s.assignments.remove(who);s.setupMarks.remove(who);s.preparedClock=null;}else {UUID other=member(s,text);if(who.equals(other)||!s.assignments.containsKey(who)||!s.assignments.containsKey(other))throw new IllegalArgumentException("请选择两个已配角色的不同席位。");String[] original=s.assignments.get(who);s.assignments.put(who,s.assignments.get(other));s.assignments.put(other,original);s.preparedClock=null;}}
                    pending.ok=true;respond(p,"已更新。");refresh(s,p.getServer());}
                case "target","nominate","slay","submit","phase","vote","finish","storyteller","autonext","votetime","review","logpage"->pending.ok=command(p,a+" "+target)==1;
                case "resolve"->pending.ok=command(p,"resolve "+text)==1;
                case "cancel_imp"->{Session s=current(p);host(s,p.getUuid());requireOnline(s);String result=ClockEmbodiment.cancel(s,p,target);respond(p,result);pending.ok=true;refresh(s,p.getServer());}
                case "decision"->{Session s=current(p);host(s,p.getUuid());requireOnline(s);UUID selected=target.isBlank()?null:member(s,target);String result=ClockEmbodiment.decide(s,p,text,selected);if(result==null)result=clock(s).decide(p.getUuid(),text,selected);ensureClockSuccess("resolve",result);respond(p,result);pending.ok=true;renderTable(s,p.getServer());refresh(s,p.getServer());}
                case "phones"->pending.ok=command(p,"phones "+text)==1;
                case "mark"->pending.ok=command(p,"mark "+target+" "+text)==1;
                case "start","draw","stay","next","closevote","execute","reset","autoassign","unvote","exportlog","close","leave"->pending.ok=command(p,a)==1;
                default->throw new IllegalArgumentException("未知的交互请求。");
            }
        }catch(RuntimeException e){pending.ok=false;respond(p,e.getMessage()==null?"操作失败，请刷新后重试。":e.getMessage());}
        finally{
            if(!pending.ok){Session s=GAMES.get(MEMBERS.get(p.getUuid()));if(s!=null)refresh(s,p.getServer());}
            ACTION_FEEDBACK.remove(p.getUuid());feedback(p,pending,pending.ok,pending.message.isBlank()?(pending.ok?"操作已完成。":"操作未完成，请检查当前待办。"):pending.message);
        }
    }
    private static void feedback(ServerPlayerEntity p,ActionFeedback pending,boolean ok,String message){
        String session=pending.session.isBlank()?MEMBERS.getOrDefault(p.getUuid(),""):pending.session;
        send(p,Map.of("kind","action_feedback","action",pending.action,"ok",ok,"message",message,"session",session,"requestId",pending.requestId),false);
    }
    static String flipContext(Session s){return s.id+":"+s.generation+":"+s.flipInputRevision;}
    static boolean physicalFlipCommand(ServerPlayerEntity p,String command){
        Session s=current(p);if(!s.type.equals("flip"))throw new IllegalStateException("此操作只用于七连翻。");
        ActionFeedback result=new ActionFeedback(command,s.id);ACTION_FEEDBACK.put(p.getUuid(),result);
        try{result.ok=command(p,command)==1;return result.ok;}
        finally{ACTION_FEEDBACK.remove(p.getUuid());String message=result.message.isBlank()?(result.ok?"已完成。":"操作未完成。"):result.message;feedback(p,result,result.ok,message);if(!result.ok)TabletopMessages.info(p,message);}
    }
    /** Physical deck enters the same validated phone workflow; new players can join a nearby table. */
    public static void useDeck(ServerPlayerEntity p,boolean stay) {
        Session current=GAMES.get(MEMBERS.get(p.getUuid()));
        if(current!=null&&current.type.equals("flip")){physicalFlipCommand(p,stay?"stay":"draw");return;}
        if(!TabletopMod.hasPhone(p))kit(p);open(p,"phone",true);
    }
    private static void placeDisplayAnchor(Session s,ServerPlayerEntity p){if(s.grimoirePos==null)return;var block=p.getWorld().getBlockState(s.grimoirePos);Direction facing=block.contains(Properties.HORIZONTAL_FACING)?block.get(Properties.HORIZONTAL_FACING):Direction.SOUTH;s.anchor=s.grimoirePos.offset(facing,3).up();}
    /** The world owns one fixed table and lectern; only its storyteller can start a session. */
    public static void openGrimoireBlock(ServerPlayerEntity p,BlockPos pos) {
        if(p.squaredDistanceTo(pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5)>36)return;
        try{
            FixedClockTable.checkLectern(p,pos);
            Session bound=GAMES.values().stream().filter(s->s.type.equals("clock")&&s.dimension.equals(p.getWorld().getRegistryKey())).findFirst().orElse(null);
            if(bound==null){
                if(!createChecked(p,"clock",pos))return;bound=current(p);
            }
            if(bound.grimoirePos==null){
                host(bound,p.getUuid());if(bound.running)throw new IllegalStateException("首次绑定魔典须在准备阶段。");if(pos.getSquaredDistance(bound.arenaAnchor)>64d*64d)throw new IllegalStateException("魔典须在固定场地64格内。");
                bound.grimoirePos=pos.toImmutable();try{FixedClockTable.save(bound,p.getServer());}catch(RuntimeException e){bound.grimoirePos=null;throw e;}clearDisplays(bound);placeDisplayAnchor(bound,p);
            }else if(!bound.grimoirePos.equals(pos))throw new IllegalStateException("请使用已经绑定的固定魔典。");
            if(bound.host.equals(p.getUuid()))open(p,"grimoire",true);else openPublicGrimoire(bound,p,true);
        }catch(RuntimeException e){tell(p,e.getMessage());}
    }
    private static void openPublicGrimoire(Session s,ServerPlayerEntity p,boolean opening) {
        if(s.running&&!s.host.equals(p.getUuid()))rejectGrimoire(p);
        PUBLIC_GRIMOIRE_VIEWERS.put(p.getUuid(),s.id);
        OPEN_KIND.put(p.getUuid(),"grimoire_public");
        Map<String,Object> state=s.clock==null?publicPreparation(s):s.clock.publicView();
        List<String> body=new ArrayList<>();body.add(status(s));body.add("公开座位："+human(state.get("seats"),s));
        List<Map<String,String>> buttons=new ArrayList<>();if(s.id.equals(MEMBERS.get(p.getUuid()))){button(buttons,"打开自己的手机","open","phone","");OPEN_KIND.put(p.getUuid(),"grimoire_public");}else if(!MEMBERS.containsKey(p.getUuid())&&!s.running&&s.dimension.equals(p.getWorld().getRegistryKey())&&p.squaredDistanceTo(s.anchor.getX()+.5,s.anchor.getY(),s.anchor.getZ()+.5)<=64*64)button(buttons,"加入这桌","join",s.id,"");
        send(p,Map.of("kind","grimoire_public","title","暗流涌动 · 公开席位","viewer",p.getUuid().toString(),"session",s.id,"generation",s.generation,"body",body,"state",state,"buttons",buttons),opening);
    }
    private static void revokeGrimoire(ServerPlayerEntity p){
        PUBLIC_GRIMOIRE_VIEWERS.remove(p.getUuid());
        if(Set.of("grimoire","grimoire_public").contains(OPEN_KIND.getOrDefault(p.getUuid(),"")))OPEN_KIND.remove(p.getUuid());
        send(p,Map.of("kind","grimoire_locked"),false);
    }
    private static void rejectGrimoire(ServerPlayerEntity p){revokeGrimoire(p);throw new IllegalStateException("魔典仅供说书人使用。");}
    private static void revokePublicGrimoires(Session s,MinecraftServer server){
        if(!s.running)return;
        for(var entry:new ArrayList<>(PUBLIC_GRIMOIRE_VIEWERS.entrySet()))if(entry.getValue().equals(s.id)&&!s.host.equals(entry.getKey())){
            var viewer=server.getPlayerManager().getPlayer(entry.getKey());
            if(viewer!=null)revokeGrimoire(viewer);else PUBLIC_GRIMOIRE_VIEWERS.remove(entry.getKey());
        }
    }
    private static Map<String,Object> publicPreparation(Session s) {
        List<Map<String,Object>> seats=new ArrayList<>();for(UUID id:clockPlayers(s))seats.add(Map.of("uuid",id.toString(),"seat",s.clockSeats.number(id),"dead",false,"ghostVote",false));
        return Map.of("game","clock","script","trouble_brewing","phase","setup","storyteller",s.host.toString(),"seats",seats,"started",false,"finished",false,"seatCapacity",seatCapacity(s),"fixedTable",true,"tableSizeLabel",clockGatheringLabel(s));
    }
    private static Map<String,Object> preparationState(Session s) {
        if(s.roster.size()>=MIN_CLOCK_PLAYERS) {
            try{return pagedState(s,s.host,preparedGame(s).privateView(s.host));}
            catch(RuntimeException e){Map<String,Object> state=manualPreparation(s);state.put("suggestion","准备配置需要调整："+e.getMessage());return state;}
        }
        Map<String,Object> state=manualPreparation(s);state.put("suggestion","至少5名玩家可开局，另设说书人。");return state;
    }
    private static Map<String,Object> manualPreparation(Session s) {
        Map<String,Object> state=new LinkedHashMap<>(publicPreparation(s));List<Map<String,Object>> grimoire=new ArrayList<>();
        for(UUID id:clockPlayers(s)){String[] roles=s.assignments.get(id);Map<String,Boolean> marks=s.setupMarks.getOrDefault(id,Map.of());Map<String,Object> row=new LinkedHashMap<>();row.put("uuid",id.toString());row.put("seat",s.clockSeats.number(id));row.put("role",roles==null?"":roles[0]);row.put("shownRole",roles==null?"":roles[1]);row.put("dead",false);row.put("poisoned",marks.getOrDefault("poisoned",false));row.put("drunk",roles!=null&&roles[0].equals("drunk")||marks.getOrDefault("drunk",false));row.put("redHerring",marks.getOrDefault("red_herring",false));row.put("used",false);row.put("safe",false);grimoire.add(row);}
        state.put("grimoire",grimoire);state.put("nightTasks",List.of());state.put("adjudications",List.of());state.put("notes",List.of());state.put("nightReference",List.of());
        List<String> blockers=new ArrayList<>();if(s.roster.size()<MIN_CLOCK_PLAYERS)blockers.add("还差 "+(MIN_CLOCK_PLAYERS-s.roster.size())+" 名玩家。");else blockers.add("准备角色或标记需要调整，请重新配牌或检查配置。");if(!s.offline.isEmpty())blockers.add("有本桌成员离线，等待重连。");
        boolean canDeal=s.roster.size()>=MIN_CLOCK_PLAYERS&&s.roster.size()<=CLOCK_PLAYERS&&s.offline.isEmpty();Map<String,String> primary=Map.of("label","自动合法配牌","action","autoassign","target","","text","","group","setup","confirm","重新生成全部角色和准备标记？尚未向玩家发放身份。","dangerous","false","enabled",String.valueOf(canDeal),"reason",canDeal?"":blockers.getFirst());
        state.put("workflow",Map.of("title","准备检查","summary","入座 → 配牌 → 开始首夜","blockers",blockers,"canAdvance",canDeal,"completed",s.assignments.size(),"total",s.roster.size(),"primaryAction",primary,"waitingKind",canDeal?"storyteller":s.offline.isEmpty()?"join":"reconnect","waitingFor",canDeal?s.host.toString():""));state.put("decisionChoices",List.of());state.put("seatActions",Map.of());decorateSeatActions(s,state);bindControls(s,state);return state;
    }
    public static void open(ServerPlayerEntity p,String kind,boolean opening){
        try {
            if(kind.equals("flip_targets")){openFlipTargets(p,opening);return;}
            if(Set.of("clock_box","storyteller_wand","grimoire_lectern").contains(kind))kind="grimoire";
            boolean handCards=kind.equals("hand_cards");if(handCards)kind="phone";
            if(ReferenceLibrary.supports(kind)){openReference(p,kind,opening);return;}
            Session s=GAMES.get(MEMBERS.get(p.getUuid()));
            if(kind.equals("phone")&&!handCards&&!TabletopMod.hasPhone(p))throw new IllegalStateException("请携带实体手机，再右键手机或按手机快捷键打开。");
            if(kind.equals("grimoire")){if(s==null||!s.type.equals("clock"))throw new IllegalStateException("请先加入钟楼桌。");if(!s.host.equals(p.getUuid()))rejectGrimoire(p);}
            if(kind.equals("grimoire_public")){if(s==null||!s.type.equals("clock"))throw new IllegalStateException("请先加入一桌暗流涌动。");openPublicGrimoire(s,p,opening);return;}
            if(kind.equals("clock_review")){if(s==null||s.clock==null||!s.clock.canReview(p.getUuid()))throw new IllegalStateException("只有说书人或已获局后授权的本桌玩家可查看完整复盘。");openReview(s,p,opening);return;}
            if(!(kind.equals("phone")||kind.equals("grimoire")||kind.equals("identity_card")||kind.equals("script_card")||kind.equals("rules_card")||kind.startsWith("role_")||kind.startsWith("number_")||kind.startsWith("plus_")||TabletopMod.ITEMS.containsKey(kind)))throw new IllegalArgumentException("未知道具。");
            Map<String,Object> view=new LinkedHashMap<>();var body=new ArrayList<String>();var buttons=new ArrayList<Map<String,String>>();
            view.put("kind",kind);view.put("title",title(kind));view.put("body",body);view.put("buttons",buttons);
            view.put("openCards",handCards);
            String tool=OPEN_TOOL.remove(p.getUuid());
            view.put("openNomination","nominate".equals(tool));view.put("openVoting","vote".equals(tool));view.put("openSpy","spy".equals(tool));
            view.put("viewer",p.getUuid().toString());if(s!=null){view.put("session",s.id);view.put("generation",s.generation);body.add(status(s));var contacts=new ArrayList<Map<String,String>>();for(UUID id:s.audience())contacts.add(Map.of("id",id.toString(),"name",name(s,id),"key",id.equals(s.host)?"storyteller":id.toString(),"seat",String.valueOf(TabletopPresentation.seat(s,id)),"color",String.valueOf(TabletopPresentation.color(s,id)),"title",TabletopPresentation.storyteller(s,id)?"说书人":""));view.put("contacts",contacts);}
            if(kind.equals("phone")){
                if(s==null){
                    body.add("创建游戏，或加入附近牌桌。");
                    button(buttons,"创建七连翻 · 3–8人","create","flip","");
                    var fixed=FixedClockTable.get(p.getServer());
                    if(GAMES.values().stream().noneMatch(table->table.type.equals("clock"))&&(fixed==null||fixed.hostUUID().equals(p.getUuid())))button(buttons,fixed==null?"准备钟楼桌":"打开固定钟楼桌","create","clock","");
                    int nearbyCount=0;
                    for(Session nearby:GAMES.values())if(!nearby.running&&nearby.dimension.equals(p.getWorld().getRegistryKey())&&nearby.roster.size()<(nearby.type.equals("flip")?FlipSeats.LIMIT:CLOCK_PLAYERS)&&p.squaredDistanceTo(nearby.anchor.getX()+.5,nearby.anchor.getY(),nearby.anchor.getZ()+.5)<=64*64){button(buttons,"加入 "+nearby.hostName+" · "+(nearby.type.equals("flip")?"七连翻":"暗流涌动")+"（"+nearby.roster.size()+"人）","join",nearby.id,"");nearbyCount++;}
                    if(nearbyCount==0)body.add("附近没有等待中的游戏。");
                }else{
                    if(s.type.equals("flip")){Map<String,Object> flipState=phoneFlipState(s,p.getUuid());view.put("state",flipState);boolean canChoose=Boolean.TRUE.equals(flipState.get("canChooseSeat"));view.put("openSeats",opening&&canChoose&&s.seatSelectionRequests.remove(p.getUuid()));if(canChoose)button(buttons,"选择号码与颜色","choose_seat","$seat","");}
                    if(s.type.equals("clock")){Map<String,Object> clockState=phoneClockState(s,p.getUuid());view.put("state",clockState);boolean canChoose=Boolean.TRUE.equals(clockState.get("canChooseSeat"));view.put("openSeats",opening&&canChoose&&s.seatSelectionRequests.remove(p.getUuid()));if(canChoose)button(buttons,"选择号码与颜色","choose_seat","$seat","");}
                    for(var e:s.groups.entrySet())if(e.getValue().contains(p.getUuid()))body.add("私密小组 "+e.getKey()+"："+String.join("、",e.getValue().stream().map(id->name(s,id)).toList()));
                    var groupViews=new ArrayList<Map<String,Object>>();for(var group:s.groups.entrySet())if(group.getValue().contains(p.getUuid()))groupViews.add(Map.of("key",group.getKey(),"name","私密小组 "+group.getKey(),"members",group.getValue().stream().map(id->name(s,id)).toList()));view.put("groups",groupViews);
                    var visible=s.messages.stream().filter(m->m.recipients.contains(p.getUuid())).toList();var chats=new ArrayList<Map<String,Object>>();int start=Math.max(0,visible.size()-18);for(int i=start;i<visible.size();i++){var m=visible.get(i);String key=m.channelKey;if(!key.equals("public")&&!s.groups.containsKey(key)){UUID peer=m.author.equals(p.getUuid())?m.recipients.stream().filter(id->!id.equals(p.getUuid())).findFirst().orElse(p.getUuid()):m.author;key=peer.equals(s.host)?"storyteller":peer.toString();}chats.add(Map.of("key",key,"sender",m.sender,"text",m.text,"outgoing",m.author.equals(p.getUuid()),"sequence",m.sequence));}view.put("chats",chats);
                    if(s.clock!=null){Map<String,Object> own=readingState(s,p.getUuid());body.add("阶段："+human(own.get("phase"),s));if(own.get("vote") instanceof Map<?,?> voting){body.add("投票目标："+human(voting.get("nominee"),s)+" · 所有座位同时投票 · 已回应 "+human(voting.get("counted"),s));}Object publicMessages=own.get("announcements");if(publicMessages instanceof Collection<?> list)for(Object message:list.stream().skip(Math.max(0,list.size()-4)).toList())body.add("[公共公告] "+message);Object spy=own.get("spyGrimoire");if(spy!=null)body.add("[本夜获准查看的魔典] "+human(spy,s));Object pending=own.get("pendingTask");if(pending!=null)body.add("当前行动："+(pending instanceof Map<?,?> task?human(task.get("status"),s):"等待确认"));}
                    if(s.flip!=null){Map<String,Object> state=s.flip.publicView();body.add("当前行动玩家："+human(state.get("turn"),s));body.add("自动下一轮："+(s.flip.autoNext()?"开启，轮末展示后推进":"关闭，由主持人推进"));if(state.get("autoNextInTicks") instanceof Number remaining)body.add("结果展示剩余约 "+Math.max(1,(remaining.longValue()+19)/20)+" 秒。");if(state.get("pendingOwner")!=null)body.add("待选目标："+human(state.get("pendingKind"),s)+" · "+human(state.get("pendingOwner"),s));Map<?,?> own=state.get("players") instanceof Map<?,?> rows&&rows.get(p.getUuid().toString()) instanceof Map<?,?> row?row:Map.of();if(Boolean.TRUE.equals(own.get("canDraw")))button(buttons,"翻牌","draw","","");if(Boolean.TRUE.equals(own.get("canStay")))button(buttons,"收手","stay","","");if(Boolean.TRUE.equals(own.get("canSelectTarget")))button(buttons,"牌的目标","target","$target","");if(s.host.equals(p.getUuid())&&s.flip.roundOver()&&!s.flip.finished())button(buttons,"开始下一轮","next","","");if(s.host.equals(p.getUuid())&&s.flip.finished())button(buttons,"准备下一局","reset","","");button(buttons,"七连翻规则书","open","flip_rules_book","");}
                    if(s.flip==null&&s.type.equals("flip")){body.add("准备阶段："+s.roster.size()+" / 8 人；至少3人同时在线、红色1号有人才能开始。主持人也参与七连翻。");String issue=flipStartIssue(s);if(!issue.isEmpty())body.add(issue);button(buttons,"七连翻规则书","open","flip_rules_book","");if(s.host.equals(p.getUuid()))stateButton(buttons,"开始七连翻","start","","",flipReady(s),issue);}
                    if(s.clock==null&&s.type.equals("clock")){body.add(clockGatheringLabel(s));button(buttons,"角色技能","open","script_card","");}
                    if(s.clock!=null){Map<String,Object> own=readingState(s,p.getUuid());if(own.get("pendingTask") instanceof Map<?,?> pending&&pending.get("targetCount") instanceof Number count&&count.intValue()>0&&"awaiting_player".equals(pending.get("status")))button(buttons,"提交夜间选择","submit","$target","");if(Boolean.TRUE.equals(own.get("canNominate")))button(buttons,"提名","nominate","$target","");if(own.get("vote") instanceof Map<?,?> voting&&s.roster.containsKey(p.getUuid())){boolean mayVote=Boolean.TRUE.equals(voting.get("mayVote")),maySupport=Boolean.TRUE.equals(voting.get("maySupport"));String waiting=!s.offline.isEmpty()?"本桌有人离线，等待重连后继续。":"当前无法投票，请等待本桌恢复联机。";stateButton(buttons,"举手赞成","vote","yes","",mayVote&&maySupport,!mayVote?waiting:!maySupport?"你的最后一张幽灵票已使用，只能不支持。":"");stateButton(buttons,"不支持","vote","no","",mayVote,mayVote?"":waiting);if(Boolean.TRUE.equals(voting.get("myVote")))stateButton(buttons,"取消赞成","unvote","","",mayVote,mayVote?"":waiting);}if(Boolean.TRUE.equals(own.get("canClaimSlayer")))button(buttons,"宣称猎手","slay","$target","");if(s.clock.finished()&&s.clock.canReview(p.getUuid()))button(buttons,"查看完整复盘","open","clock_review","");}
                    button(buttons,"建私密小组","group","$target","");button(buttons,"查看身份卡","open","identity_card","");if(s.type.equals("clock")&&s.host.equals(p.getUuid()))button(buttons,"说书人魔典助手","open","grimoire","");
                    if((!s.running||s.ended())&&!(s.type.equals("clock")&&s.host.equals(p.getUuid())))groupedButton(buttons,s.host.equals(p.getUuid())?"关闭本桌":"离开本桌",s.host.equals(p.getUuid())?"close":"leave","","","flow",s.host.equals(p.getUuid())?"确认关闭本桌并让所有玩家离桌？":"确认离开本桌？",false);
                    if(!s.offline.isEmpty())for(int i=0;i<buttons.size();i++){Map<String,String> action=buttons.get(i);if(Set.of("draw","stay","target","start","nominate","vote","unvote","slay").contains(action.get("action"))){Map<String,String> disabled=new LinkedHashMap<>(action);disabled.put("enabled","false");disabled.put("reason","本桌有人离线，等待重连后继续。");buttons.set(i,disabled);}}
                }
            }else if(kind.equals("grimoire")){
                Map<String,Object> grimoireState=s.clock!=null?readingState(s,p.getUuid()):preparationState(s);view.put("state",grimoireState);
                view.put("roleChoices",RoleCatalog.all().stream().map(r->Map.of("id",r.id(),"name",r.name(),"type",r.type(),"alignment",List.of("minion","demon").contains(r.type())?"evil":"good")).toList());
                if(grimoireState.get("workflow") instanceof Map<?,?> workflow){body.add(String.valueOf(workflow.get("title")));body.add(String.valueOf(workflow.get("summary")));}
                grimoireButtons(s,grimoireState,buttons);
            }else if(kind.equals("identity_card")){
                if(s==null||s.clock==null||p.getUuid().equals(s.host))body.add("身份尚未发放。");else{Map<String,Object> own=readingState(s,p.getUuid());body.add("身份："+human(own.get("roleName"),s));body.add("能力："+human(own.get("ability"),s));}
            }else if(kind.startsWith("role_")){var role=RoleCatalog.get(kind.substring(5));body.add(role.name());body.add(role.ability());}
            else{}
            if(s!=null&&s.type.equals("clock")&&s.host.equals(p.getUuid())){view.put("controlContext",controlContext(s));bindButtonList(s,buttons);}
            PUBLIC_GRIMOIRE_VIEWERS.remove(p.getUuid());OPEN_KIND.put(p.getUuid(),kind);send(p,view,opening);
        }catch(RuntimeException e){if(opening)tell(p,e.getMessage());}
    }
    private static void openFlipTargets(ServerPlayerEntity p,boolean opening){
        Session s=GAMES.get(MEMBERS.get(p.getUuid()));Map<?,?> pending=s!=null&&s.type.equals("flip")&&s.flip!=null&&s.flip.publicView().get("pendingAction") instanceof Map<?,?> value?value:Map.of();
        if(s==null||!p.getUuidAsString().equals(pending.get("owner"))){
            if(OPEN_KIND.getOrDefault(p.getUuid(),"").equals("flip_targets"))OPEN_KIND.remove(p.getUuid());send(p,Map.of("kind","flip_targets_done"),false);
            if(opening)throw new IllegalStateException("当前没有要选择目标的行动牌。");return;
        }
        var choices=new ArrayList<Map<String,Object>>();if(pending.get("targets") instanceof List<?> targets)for(Object raw:targets){UUID id=UUID.fromString(raw.toString());int seat=flipSeat(s,id);choices.add(Map.of("uuid",id.toString(),"seat",seat,"label",seat+"号 · "+(id.equals(p.getUuid())?"自己":name(s,id))));}
        OPEN_KIND.put(p.getUuid(),"flip_targets");send(p,Map.of("kind","flip_targets","title",pending.get("title"),"session",s.id,"context",flipContext(s),"choices",choices,"paused",!s.offline.isEmpty()),opening);
    }
    private static Map<String,Object> readingState(Session s,UUID viewer){return pagedState(s,viewer,s.clock.privateView(viewer));}
    private static Map<String,Object> pagedState(Session s,UUID viewer,Map<String,Object> raw){
        Map<String,Object> state=new LinkedHashMap<>(raw);
        state.put("tablePaused",!s.offline.isEmpty());
        if(!s.offline.isEmpty()){state.put("canNominate",false);state.put("canClaimSlayer",false);if(state.get("vote") instanceof Map<?,?> voting){Map<String,Object> pausedVote=new LinkedHashMap<>();voting.forEach((key,value)->pausedVote.put(String.valueOf(key),value));pausedVote.put("mayVote",false);state.put("vote",pausedVote);}}
        for(String key:List.of("messages","announcements","adjudications","notes"))if(state.get(key) instanceof List<?> values){int keep=key.equals("messages")?6:12;state.put(key,List.copyOf(values.subList(Math.max(0,values.size()-keep),values.size())));}
        if(state.get("flowLog") instanceof List<?> entries){int pages=Math.max(1,(entries.size()+7)/8),page=Math.clamp(s.reviewPages.getOrDefault(viewer,pages-1),0,pages-1);state.put("logCount",entries.size());state.put("logPages",pages);state.put("logPage",page+1);state.put("flowLog",List.copyOf(entries.subList(page*8,Math.min(entries.size(),(page+1)*8))));}
        if(viewer.equals(s.host)){state.put("nightReference",List.of());if(!s.replayFile.isBlank())state.put("replayFile",s.replayFile);if(!s.offline.isEmpty()&&!s.ended()&&state.get("workflow") instanceof Map<?,?> original){Map<String,Object> workflow=new LinkedHashMap<>();original.forEach((key,value)->workflow.put(String.valueOf(key),value));List<String> blockers=new ArrayList<>();if(original.get("blockers") instanceof List<?> old)old.forEach(b->blockers.add(String.valueOf(b)));String reason="等待重连："+String.join("、",s.offline.stream().map(id->name(s,id)).toList());blockers.add(reason);workflow.put("blockers",blockers);workflow.put("canAdvance",false);workflow.put("paused",true);workflow.put("waitingKind","reconnect");if(original.get("primaryAction") instanceof Map<?,?> primary&&!primary.isEmpty()){Map<String,String> disabled=new LinkedHashMap<>();primary.forEach((key,value)->disabled.put(String.valueOf(key),String.valueOf(value)));disabled.put("enabled","false");disabled.put("reason",reason);workflow.put("primaryAction",disabled);}state.put("workflow",workflow);if(state.get("decisionChoices") instanceof List<?> choices){List<Map<String,String>> disabledChoices=new ArrayList<>();for(Object value:choices)if(value instanceof Map<?,?> choice){Map<String,String> disabled=new LinkedHashMap<>();choice.forEach((key,item)->disabled.put(String.valueOf(key),String.valueOf(item)));disabled.put("enabled","false");disabled.put("reason",reason);disabledChoices.add(disabled);}state.put("decisionChoices",disabledChoices);}}}
        if(viewer.equals(s.host)&&s.offline.isEmpty())physicalWorkflow(s,state);
        if(viewer.equals(s.host)){
            if(s.clock==null&&state.get("workflow") instanceof Map<?,?> original&&original.get("primaryAction") instanceof Map<?,?> primary&&"start".equals(primary.get("action"))){
                String issue=ClockArena.enabled(s,s.server)?ClockArena.issue(s,s.server):"场地尚未启用，请先配置席位、房间、房门与牢笼。";
                if(!issue.isEmpty()){var workflow=new LinkedHashMap<String,Object>();original.forEach((key,value)->workflow.put(String.valueOf(key),value));var disabled=new LinkedHashMap<String,String>();primary.forEach((key,value)->disabled.put(String.valueOf(key),String.valueOf(value)));disabled.put("enabled","false");disabled.put("reason",issue);workflow.put("primaryAction",disabled);workflow.put("canAdvance",false);workflow.put("blockers",List.of(issue));state.put("workflow",workflow);}
            }
            decorateSeatActions(s,state);bindControls(s,state);
        }
        state.put("seatCapacity",seatCapacity(s));state.put("tableSizeLabel",clockGatheringLabel(s));state.put("fixedTable",true);return state;
    }
    private static void physicalWorkflow(Session s,Map<String,Object> state){
        Map<String,Object> physical=ClockEmbodiment.storytellerState(s);state.put("physical",physical);
        if(state.get("workflow") instanceof Map<?,?> original){
            Map<String,Object> workflow=new LinkedHashMap<>();original.forEach((key,value)->workflow.put(String.valueOf(key),value));
            if(Boolean.TRUE.equals(physical.get("impApproved"))){
                workflow.put("summary",physical.getOrDefault("impStatus","等待刀命中目标"));workflow.put("primaryAction",Map.of());workflow.put("canAdvance",false);workflow.put("requiresChoice",false);workflow.put("blockers",List.of());state.put("decisionChoices",List.of());
            }else if(Boolean.TRUE.equals(physical.get("impPending"))){
                workflow.put("summary",physical.getOrDefault("impStatus","等待确认夜间行动"));
                if(workflow.get("primaryAction") instanceof Map<?,?> action&&!action.isEmpty()){
                    Map<String,String> primary=new LinkedHashMap<>();action.forEach((key,value)->primary.put(String.valueOf(key),String.valueOf(value)));primary.put("label","确认本次行动");primary.put("confirm","确认当前目标？");workflow.put("primaryAction",primary);
                }
            }
            state.put("workflow",workflow);
        }
    }
    private static List<String> nightReference(){
        return List.of("首夜：7人及以上先爪牙互认 → 恶魔信息与三个诈唬；随后 投毒者 → 洗衣妇 → 图书管理员 → 调查员 → 厨师 → 共情者 → 占卜师 → 管家 → 间谍。",
            "后续夜：投毒者 → 僧侣 → 红唇女郎 → 小恶魔 → 守鸦人 → 共情者 → 占卜师 → 管家 → 送葬者 → 间谍。",
            "条件步骤：红唇女郎只在身份变更通知时唤醒；守鸦人只在本夜死亡时行动；送葬者只在白天有人因处决死亡时唤醒。首夜恶魔不杀人，僧侣不行动。",
            "无需固定夜间唤醒：贞洁者、猎手、士兵、镇长、酒鬼、陌客、圣徒、男爵。酒鬼按假镇民的步骤收信息，实际能力无效。醉毒及错误登记仍由说书人确认。");
    }
    private static void openReview(Session s,ServerPlayerEntity p,boolean opening){
        var state=readingState(s,p.getUuid());List<Map<String,String>> buttons=new ArrayList<>();button(buttons,"上一页","logpage","previous","");button(buttons,"下一页","logpage","next","");button(buttons,"第一页","logpage","first","");button(buttons,"最后一页","logpage","last","");button(buttons,"返回手机","open","phone","");
        if(p.getUuid().equals(s.host))button(buttons,"导出完整记录","exportlog","","");
        var body=new ArrayList<String>();body.add("本局复盘："+human(state.get("winner"),s)+"阵营获胜。");body.add("按首夜、第N夜、第N天记录角色、行动、裁定与投票。私密记录只对说书人及其局后授权的本桌玩家展示。");body.add("最终魔典："+human(state.getOrDefault("grimoire",state.get("reviewGrimoire")),s));body.add("记录第 "+state.getOrDefault("logPage",1)+" / "+state.getOrDefault("logPages",1)+" 页，总计 "+state.getOrDefault("logCount",0)+" 条。");body.add(human(state.get("flowLog"),s));
        var view=new LinkedHashMap<String,Object>();view.put("kind","clock_review");view.put("title","暗流涌动 · 完整复盘");view.put("viewer",p.getUuid().toString());view.put("session",s.id);view.put("generation",s.generation);view.put("contacts",s.audience().stream().map(id->Map.of("id",id.toString(),"name",name(s,id),"key",id.equals(s.host)?"storyteller":id.toString(),"seat",String.valueOf(TabletopPresentation.seat(s,id)),"color",String.valueOf(TabletopPresentation.color(s,id)),"title",TabletopPresentation.storyteller(s,id)?"说书人":"")).toList());view.put("body",body);view.put("buttons",buttons);view.put("state",state);if(p.getUuid().equals(s.host)){view.put("controlContext",controlContext(s));bindButtonList(s,buttons);}OPEN_KIND.put(p.getUuid(),"clock_review");send(p,view,opening);
    }
    /** Complete hidden replay stays in the server's world folder, never in the public payload. */
    private static void saveReplay(Session s,MinecraftServer server){
        if(s.clock==null)return;
        try {
            var raw=s.clock.privateView(s.host);var document=new LinkedHashMap<String,Object>();document.put("format","tabletop-clock-replay-v1");document.put("savedAt",Instant.now().toString());document.put("session",s.id);document.put("generation",s.generation);document.put("storyteller",s.host.toString());document.put("storytellerName",s.hostName);document.put("players",s.roster.entrySet().stream().map(e->Map.of("uuid",e.getKey().toString(),"name",e.getValue())).toList());document.put("state",raw);document.put("nightReference",nightReference());
            var directory=server.getSavePath(WorldSavePath.ROOT).resolve("tabletop").resolve("replays");Files.createDirectories(directory);String filename=s.id+"-"+s.generation+".json";var destination=directory.resolve(filename);var temporary=directory.resolve(filename+".tmp");Files.writeString(temporary,JSON.toJson(document),StandardCharsets.UTF_8);Files.move(temporary,destination,StandardCopyOption.REPLACE_EXISTING);s.replayFile="tabletop/replays/"+filename;s.savedLogCount=((List<?>)raw.get("flowLog")).size();
        }catch(IOException e){throw new IllegalStateException("完整记录保存失败，请检查服务器世界目录的写入权限："+e.getMessage(),e);}
    }
    private static void openReference(ServerPlayerEntity p,String kind,boolean opening){
        ReferenceLibrary.Document document=ReferenceLibrary.get(kind);List<String> overview=new ArrayList<>();
        for(var section:document.sections())overview.add(section.title()+" · "+section.category()+(section.alignment().isBlank()?"":" · "+(section.alignment().equals("good")?"善良":"邪恶"))+" — "+section.summary());
        PUBLIC_GRIMOIRE_VIEWERS.remove(p.getUuid());OPEN_KIND.put(p.getUuid(),kind);send(p,Map.of("kind",kind,"title",document.title(),"body",overview,"buttons",List.of(),"reference",document),opening);
    }
    private static String title(String kind){if(ReferenceLibrary.supports(kind))return ReferenceLibrary.get(kind).title();return switch(kind){case "phone"->"桌间手机";case "grimoire"->"暗流涌动 · 魔典助手";case "identity_card"->"你的身份卡";default->"桌游道具";};}
    private static void button(List<Map<String,String>> buttons,String label,String action,String target,String text){buttons.add(Map.of("label",label,"action",action,"target",target,"text",text));}
    private static void stateButton(List<Map<String,String>> buttons,String label,String action,String target,String text,boolean enabled,String reason){buttons.add(Map.of("label",label,"action",action,"target",target,"text",text,"enabled",String.valueOf(enabled),"reason",reason));}
    private static void groupedButton(List<Map<String,String>> buttons,String label,String action,String target,String text,String group,String confirm,boolean dangerous){buttons.add(Map.of("label",label,"action",action,"target",target,"text",text,"group",group,"confirm",confirm,"dangerous",String.valueOf(dangerous)));}
    private static final Set<String> ST_CONTROLS=Set.of("assign","clear_role","swap","note","clear_note","prepare_tools","autoassign","start","storyteller","phase","resolve","decision","cancel_imp","mark","finish","closevote","execute","reset","close","phones","review","exportlog","votetime");
    static String controlContext(Session s){
        var parts=new ArrayList<String>();parts.add(s.id);parts.add(String.valueOf(s.generation));parts.add(s.host.toString());parts.add(String.valueOf(s.running));parts.add(s.roster.keySet().toString());parts.add(s.clockSeats.snapshot().toString());parts.add(new TreeSet<>(s.offline).toString());
        for(UUID id:s.roster.keySet()){parts.add(id+":"+Arrays.toString(s.assignments.get(id)));parts.add(new TreeMap<>(s.setupMarks.getOrDefault(id,Map.of())).toString());parts.add(s.setupNotes.getOrDefault(id,""));}
        if(s.clock!=null){parts.add(s.clock.controlVersion());parts.add(ClockEmbodiment.storytellerState(s).toString());}
        return UUID.nameUUIDFromBytes(String.join("|",parts).getBytes(StandardCharsets.UTF_8)).toString();
    }
    private static Map<String,String> boundButton(Session s,Map<?,?> raw){var button=new LinkedHashMap<String,String>();raw.forEach((key,value)->button.put(String.valueOf(key),String.valueOf(value)));button.put("context",controlContext(s));return button;}
    private static void bindButtonList(Session s,List<Map<String,String>> buttons){for(int i=0;i<buttons.size();i++)buttons.set(i,boundButton(s,buttons.get(i)));}
    private static void bindControls(Session s,Map<String,Object> state){
        state.put("seatCapacity",seatCapacity(s));state.put("tableSizeLabel",clockGatheringLabel(s));state.put("fixedTable",true);
        if(state.get("workflow") instanceof Map<?,?> original){var workflow=new LinkedHashMap<String,Object>();original.forEach((key,value)->workflow.put(String.valueOf(key),value));if(original.get("primaryAction") instanceof Map<?,?> primary&&!primary.isEmpty())workflow.put("primaryAction",boundButton(s,primary));state.put("workflow",workflow);}
        if(state.get("decisionChoices") instanceof List<?> choices){var bound=new ArrayList<Map<String,String>>();for(Object value:choices)if(value instanceof Map<?,?> choice)bound.add(boundButton(s,choice));state.put("decisionChoices",bound);}
        if(state.get("seatActions") instanceof Map<?,?> actions){var bound=new LinkedHashMap<String,List<Map<String,String>>>();for(var entry:actions.entrySet()){var list=new ArrayList<Map<String,String>>();if(entry.getValue() instanceof List<?> rows)for(Object row:rows)if(row instanceof Map<?,?> button)list.add(boundButton(s,button));bound.put(String.valueOf(entry.getKey()),list);}state.put("seatActions",bound);}
    }
    private static void decorateSeatActions(Session s,Map<String,Object> state){
        if(s.clock!=null&&s.clock.finished()){state.put("seatActions",Map.of());return;}
        var result=new LinkedHashMap<String,List<Map<String,String>>>();var notes=new ArrayList<Map<String,Object>>();
        if(state.get("seatActions") instanceof Map<?,?> old)for(var entry:old.entrySet()){var copy=new ArrayList<Map<String,String>>();if(entry.getValue() instanceof List<?> rows)for(Object row:rows)if(row instanceof Map<?,?> action){var a=new LinkedHashMap<String,String>();action.forEach((key,value)->a.put(String.valueOf(key),String.valueOf(value)));copy.add(a);}result.put(String.valueOf(entry.getKey()),copy);}
        for(UUID id:s.roster.keySet()){
            var actions=result.computeIfAbsent(id.toString(),key->new ArrayList<>());String target=id.toString();
            if(s.clock==null){
                groupedButton(actions,"选角色","assign",target,"$text","seat","确认分配此角色？",false);
                if(s.assignments.containsKey(id)){groupedButton(actions,"与另一席互换角色","swap","$target",target,"seat","确认互换这两个席位的角色？",false);groupedButton(actions,"清除角色","clear_role",target,"","seat","确认清除这席角色？",false);}
                if(actions.stream().noneMatch(a->"mark".equals(a.get("action")))){var marks=s.setupMarks.getOrDefault(id,Map.of());groupedButton(actions,marks.getOrDefault("poisoned",false)?"清除中毒":"中毒","mark",target,"poisoned "+!marks.getOrDefault("poisoned",false),"seat","确认修改中毒？",false);groupedButton(actions,marks.getOrDefault("drunk",false)?"清除醉酒":"醉酒","mark",target,"drunk "+!marks.getOrDefault("drunk",false),"seat","确认修改醉酒？",false);String[] role=s.assignments.get(id);if(role!=null&&!List.of("minion","demon").contains(RoleCatalog.get(role[0]).type()))groupedButton(actions,marks.getOrDefault("red_herring",false)?"移除红鲱鱼":"红鲱鱼","mark",target,"red_herring "+!marks.getOrDefault("red_herring",false),"seat","确认修改红鲱鱼？",false);}
                if(s.setupNotes.containsKey(id))notes.add(Map.of("uuid",target,"text",s.setupNotes.get(id)));
            }
            groupedButton(actions,"备注","note",target,"$text","seat","保存本席备注？",false);groupedButton(actions,"清除备注","clear_note",target,"","seat","清除此席备注？",false);
        }
        if(s.clock==null)state.put("notes",notes);state.put("seatActions",result);
    }
    private static void grimoireButtons(Session s,Map<String,Object> state,List<Map<String,String>> buttons) {
        if(state.get("workflow") instanceof Map<?,?> workflow&&workflow.get("primaryAction") instanceof Map<?,?> primary&&!primary.isEmpty()){Map<String,String> action=new LinkedHashMap<>();primary.forEach((key,value)->action.put(String.valueOf(key),String.valueOf(value)));action.put("enabled",String.valueOf(Boolean.TRUE.equals(workflow.get("canAdvance"))));action.put("group","primary");buttons.add(action);}
        if(s.clock==null) {
            if(s.roster.size()>=MIN_CLOCK_PLAYERS)groupedButton(buttons,"重新配牌","autoassign","","","config","重新配发全部角色？",false);
            if(!s.roster.isEmpty())groupedButton(buttons,"转交说书人","storyteller","$target","","config","互换说书人与选中席位？需要重新配牌。",true);
            groupedButton(buttons,"选席配角色","assign","$target","$text","config","确认分配此角色？",false);
        } else if(!s.clock.finished()) {
            Map<String,Object> physical=ClockEmbodiment.storytellerState(s);
            if(Boolean.TRUE.equals(physical.get("impPending")))groupedButton(buttons,"取消本次行动","cancel_imp",String.valueOf(physical.get("impTaskKey")),"","night","本次行动不产生死亡并结束？",true);
            if(!Boolean.TRUE.equals(physical.get("impApproved"))&&(s.clock.isNight()||!s.clock.decisionChoices(s.host).isEmpty()))groupedButton(buttons,"私发本步信息","resolve","","$text","night","确认发送给当前玩家？",false);
            groupedButton(buttons,"善良获胜","finish","good","","control","确认结束本局：善良获胜？",true);
            groupedButton(buttons,"邪恶获胜","finish","evil","","control","确认结束本局：邪恶获胜？",true);
        }
        if(s.clock!=null) {
            groupedButton(buttons,"导出记录","exportlog","","","log","",false);
            groupedButton(buttons,"上一页","logpage","previous","","log","",false);groupedButton(buttons,"下一页","logpage","next","","log","",false);
            if(s.clock.finished())groupedButton(buttons,Boolean.TRUE.equals(state.get("reviewEnabled"))?"收回复盘":"开放复盘","review",Boolean.TRUE.equals(state.get("reviewEnabled"))?"off":"on","","log","复盘包含真实身份与私密行动，确认改变查看权限？",true);
        }
        groupedButton(buttons,"提醒查看手机","phones","","","flow","",false);groupedButton(buttons,"角色技能","open","script_card","","flow","",false);
        groupedButton(buttons,"关闭本桌","close","","","control","确认关闭本桌？记录保存，场地保留。",true);
        if(!s.offline.isEmpty())for(int i=0;i<buttons.size();i++){Map<String,String> action=buttons.get(i);if(Set.of("autoassign","start","storyteller","resolve","decision","cancel_imp","phase","closevote","execute").contains(action.get("action"))){Map<String,String> disabled=new LinkedHashMap<>(action);disabled.put("enabled","false");disabled.put("reason","等待离线玩家重连。");buttons.set(i,disabled);}}
        bindButtonList(s,buttons);
    }
    private static String human(Object object,Session s){
        if(object==null)return "无";if(object instanceof UUID id)return name(s,id);if(object instanceof String str){try{UUID id=UUID.fromString(str);return name(s,id);}catch(IllegalArgumentException ignored){}try{return RoleCatalog.get(str).name();}catch(RuntimeException ignored){}return switch(str){case "day"->"白天";case "night"->"夜晚";case "first_night"->"首夜";case "waiting","awaiting_player"->"等待选择";case "awaiting_confirmation","awaiting_storyteller"->"等待说书人确认";case "skipped"->"已跳过";case "submitted"->"已提交";case "resolved"->"已完成";default->str;};}
        if(object instanceof Map<?,?> map){var parts=new ArrayList<String>();for(var e:map.entrySet()){String key=String.valueOf(e.getKey());String label=switch(key){case "player","actor","owner"->"玩家";case "role","shownRole"->"角色";case "dead"->"死亡";case "ghostVote","ghost"->"幽灵票";case "poisoned"->"中毒";case "drunk"->"醉酒";case "status"->"进度";case "targets"->"选择";case "targetCount"->"目标数";case "help"->"提示";case "suggestion"->"建议";case "name"->"名称";case "uuid"->"玩家";case "seat"->"座位";case "used"->"一次能力已使用";case "redHerring"->"红鲱鱼";case "safe"->"僧侣保护";case "impaired"->"醉毒影响";case "current"->"当前步骤";default->key;};parts.add(label+"："+human(e.getValue(),s));}return String.join(" · ",parts);}
        if(object instanceof Collection<?> values)return String.join("\n",values.stream().map(v->human(v,s)).toList());return String.valueOf(object);
    }
    private static void send(ServerPlayerEntity p,Map<String,Object> view,boolean opening){if(ServerPlayNetworking.canSend(p,TabletopNet.View.ID)){String json=JSON.toJson(view);if(json.length()<32767)ServerPlayNetworking.send(p,new TabletopNet.View(json,opening));else tell(p,"内容过长，请使用剧本卡或查看随包说明。");}else if(opening){tell(p,"此交互需要客户端安装同版本桌间Mod；命令仍可用于测试。");}}
    static void sendPresentation(ServerPlayerEntity p,Map<String,Object> view){send(p,view,false);}
    static void refresh(Session s,MinecraftServer server){
        FlipTurnBar.update(s,server);
        revokePublicGrimoires(s,server);
        ClockEmbodiment.sync(s,server);
        bridgeClockMessages(s,server);
        TabletopPresentation.labels(s,server);TabletopPresentation.night(s,server);
        if(s.flip!=null)FlipInventory.update(s,server,List.of());
        if(s.flip!=null&&s.flip.roundOver()){
            Map<String,Object> state=s.flip.publicView();int round=((Number)state.get("round")).intValue();
            if(s.notifiedFlipRound!=round){s.notifiedFlipRound=round;all(s,server,s.flip.finished()?"七连翻结束 · "+human(state.get("winner"),s)+" 获胜":"第 "+round+" 轮结束 · 分数已结算");}
        }
        if(s.clock!=null){
            Map<String,Object> state=s.clock.publicView();
            var current=new ArrayList<String>();if(state.get("announcements") instanceof Collection<?> messages)for(Object message:messages)current.add(String.valueOf(message));
            int overlap=Math.min(s.announced.size(),current.size());
            while(overlap>0&&!s.announced.subList(s.announced.size()-overlap,s.announced.size()).equals(current.subList(0,overlap)))overlap--;
            for(int i=overlap;i<current.size();i++){String message=current.get(i);if(!message.startsWith("提名投票已开始"))all(s,server,publicResult(s,message.replace("请所有人取出手机并等待各自的私密提示。","").replace("请所有人查看手机。","请回房。")));}
            s.announced=List.copyOf(current);renderClockTable(s,server,state);
        }
        for(UUID id:s.audience()){
            var p=server.getPlayerManager().getPlayer(id);if(p==null)continue;
            if(s.clock!=null&&s.roster.containsKey(id)&&s.clock.privateView(id).get("spyGrimoire") instanceof Map<?,?> snapshot&&snapshot.get("night") instanceof Number night
                    &&night.intValue()>s.deliveredSpyNights.getOrDefault(id,0)){
                s.deliveredSpyNights.put(id,night.intValue());OPEN_TOOL.put(id,"spy");open(p,"phone",true);
            }else {String kind=OPEN_KIND.get(id);if(kind!=null)open(p,kind,false);}
        }
    }
    private static void bridgeClockMessages(Session s,MinecraftServer server){
        if(s.clock==null)return;
        for(UUID id:s.roster.keySet()){
            Object raw=s.clock.privateView(id).get("messages");
            List<String> current=raw instanceof List<?> list?list.stream().map(String::valueOf).toList():List.of();
            List<String> old=s.deliveredRoleMessages.getOrDefault(id,List.of());
            int overlap=Math.min(old.size(),current.size());
            while(overlap>0&&!old.subList(old.size()-overlap,old.size()).equals(current.subList(0,overlap)))overlap--;
            for(int i=overlap;i<current.size();i++)privateStoryteller(s,id,publicResult(s,current.get(i)),server);
            s.deliveredRoleMessages.put(id,current);
        }
        var task=s.clock.physicalTask();
        if(task!=null&&!task.key().equals(s.promptedTask)&&s.offline.isEmpty()){
            s.promptedTask=task.key();
            privateStoryteller(s,task.actor(),clockTurnMessage(s,task),server);
        }
    }
    private static String clockTurnMessage(Session s,ClockGame.PhysicalTask task){
        RoleCatalog.Role role=RoleCatalog.get(task.role());
        String ability=role==null?switch(task.role()){case "minion_info"->"爪牙互认，等待说书人私下发送信息。";case "demon_info"->"恶魔信息与诈唬，等待说书人私下发送信息。";default->"等待说书人发送本步信息。";}:role.name()+" · "+role.ability();
        return "轮到你行动 · "+ability+(task.submitted()?" 已提交本次选择，等待说书人确认。":task.targetCount()>0?" 本次请选择 "+task.targetCount()+" 名不同玩家，通过手机提交给说书人。":" 本步无需选人，请查看说书人的私密信息。");
    }
    /** Review control/reconnect may replay only the controlled recipient's current prompt. */
    public static void remindClockTurn(ServerPlayerEntity p){
        Session s=GAMES.get(MEMBERS.get(p.getUuid()));if(s==null||s.clock==null||!s.offline.isEmpty())return;
        var task=s.clock.physicalTask();if(task!=null&&task.actor().equals(p.getUuid()))p.sendMessage(TabletopMessages.privateLine(clockTurnMessage(s,task)),false);
    }
    private static void privateStoryteller(Session s,UUID recipient,String message,MinecraftServer server){
        sendMessage(s,s.host,recipient.toString(),message);
        var player=server.getPlayerManager().getPlayer(recipient);
        if(player!=null)player.sendMessage(TabletopMessages.privateLine(message),false);
    }
    public static void openNomination(ServerPlayerEntity p){OPEN_TOOL.put(p.getUuid(),"nominate");open(p,"phone",true);}
    public static void openVoting(ServerPlayerEntity p){OPEN_TOOL.put(p.getUuid(),"vote");open(p,"phone",true);}
    public static void rejoin(ServerPlayerEntity p){Session s=GAMES.get(MEMBERS.get(p.getUuid()));ClockEmbodiment.rejoin(p);if(s!=null){s.offline.remove(p.getUuid());tell(p,"已恢复本桌席位和私密消息。");all(s,p.getServer(),"一名玩家已重连，可继续流程。");refresh(s,p.getServer());}}
    public static void disconnect(ServerPlayerEntity p){if(!p.getServer().isOnThread()){p.getServer().execute(()->disconnect(p));return;}ClockEmbodiment.disconnect(p);FlipInventory.clear(p);TabletopPresentation.disconnect(p);FlipTurnBar.disconnect(p);THROTTLES.remove(p.getUuid());OPEN_KIND.remove(p.getUuid());PUBLIC_GRIMOIRE_VIEWERS.remove(p.getUuid());Session s=GAMES.get(MEMBERS.get(p.getUuid()));if(s!=null){s.offline.add(p.getUuid());FlipTurnBar.update(s,p.getServer());all(s,p.getServer(),"一名玩家离线，流程暂停等待重连。");}}
    /** Server authority schedules finished rounds and closes ballots; unclicked ballots mean no. */
    public static void tick(MinecraftServer server) {
        FlipInventory.tick(server);ClockEmbodiment.tick(server);
        for(Session s:GAMES.values()) {
            if(server.getTicks()%5==0){FlipTurnBar.syncAudience(s,server);TabletopPresentation.tickLabels(s,server);}
            if(server.getTicks()%20==0){TabletopPresentation.labels(s,server);TabletopPresentation.night(s,server);}
            s.flipScene.tick(server.getTicks());
            if(s.flip!=null&&s.flip.roundOver()&&!s.flip.finished()) {
                Object before=s.flip.publicView().get("autoNextAtTick");String result=s.flip.updateAutomation(server.getTicks(),s.offline.isEmpty());Object after=s.flip.publicView().get("autoNextAtTick");
                if(result!=null){clearDisplays(s);renderTable(s,server);refresh(s,server);}
                else if(!Objects.equals(before,after))refresh(s,server);
            }
            if(s.clock!=null){String result=s.clock.updateAutomation(server.getTicks(),s.offline.isEmpty());if(result!=null)all(s,server,publicResult(s,result));if(result!=null||server.getTicks()%20==0&&s.clock.publicView().containsKey("vote"))refresh(s,server);if(server.getTicks()%200==0){Object log=s.clock.privateView(s.host).get("flowLog");if(log instanceof List<?> entries&&entries.size()!=s.savedLogCount)try{saveReplay(s,server);}catch(RuntimeException e){LOGGER.error("Unable to save tabletop replay {}",s.id,e);}}}
        }
    }
    private static void close(ServerPlayerEntity p,Session s){ClockEmbodiment.release(s,p.getServer());FlipInventory.clear(s,p.getServer());saveReplay(s,p.getServer());all(s,p.getServer(),"此桌已关闭，临时展示与私密消息已清理。");FlipTurnBar.clear(s);clearDisplays(s);for(UUID id:s.audience()){var viewer=p.getServer().getPlayerManager().getPlayer(id);if(viewer!=null)send(viewer,Map.of("kind","closed"),false);TabletopPresentation.release(id);MEMBERS.remove(id);OPEN_KIND.remove(id);}PUBLIC_GRIMOIRE_VIEWERS.entrySet().removeIf(entry->entry.getValue().equals(s.id));GAMES.remove(s.id);}
    public static void clear(){FlipTurnBar.clear();FixedClockTable.clearCache();ClockEmbodiment.clear();TabletopPresentation.clear();for(var s:GAMES.values())clearDisplays(s);GAMES.clear();MEMBERS.clear();OPEN_KIND.clear();PUBLIC_GRIMOIRE_VIEWERS.clear();OPEN_TOOL.clear();THROTTLES.clear();ACTION_FEEDBACK.clear();feedbackSequence=0;}
    private static void clearDisplays(Session s){s.flipScene.clear();for(Entity e:s.displays)e.discard();s.displays.clear();}
    private static DisplayEntity.ItemDisplayEntity display(ServerWorld world,String item,double x,double y,double z,float scale){
        var e=new DisplayEntity.ItemDisplayEntity(net.minecraft.entity.EntityType.ITEM_DISPLAY,world);e.setItemStack(new ItemStack(TabletopMod.ITEMS.getOrDefault(item,TabletopMod.ITEMS.get("card_back"))));e.setTransformationMode(ModelTransformationMode.GROUND);e.setTransformation(new AffineTransformation(new Vector3f(),new Quaternionf(),new Vector3f(scale),new Quaternionf()));e.setPosition(x,y,z);e.setViewRange(.8f);world.spawnEntity(e);return e;
    }
    static void renderTable(Session s,MinecraftServer server){
        if(s.flip==null)return;var reveals=s.flip.drainReveals();FlipInventory.update(s,server,reveals);ServerWorld world=server.getWorld(s.dimension);if(world==null)return;s.flipScene.update(world,s.anchor,flipPublicState(s),id->name(s,id),reveals,server.getTicks(),FlipTableLayout.seats(s));FlipEffects.play(world,s.flip.drainEffects());s.displays.clear();s.displays.addAll(s.flipScene.entities());
    }
    private static void renderClockTable(Session s,MinecraftServer server,Map<String,Object> state){
        ClockArena.Layout layout=ClockArena.get(s,server);
        String signature=String.valueOf(state.get("seats"))+state.get("phase")+state.get("onBlock")+state.get("winner")+(layout==null?"":layout.seats);if(signature.equals(s.clockDisplayState))return;
        ServerWorld world=server.getWorld(s.dimension);if(world==null)return;clearDisplays(s);s.clockDisplayState=signature;
        if(state.get("seats") instanceof List<?> seats)for(int i=0;i<seats.size();i++){
            if(!(seats.get(i) instanceof Map<?,?> seat))continue;
            int number=seat.get("seat") instanceof Number n?n.intValue():i+1;
            ClockArena.Point point=layout==null?null:layout.seats.get(number);
            double angle=2*Math.PI*i/seats.size(),yaw=point==null?0:Math.toRadians(point.yaw());
            double x=point==null?s.anchor.getX()+.5+2.2*Math.cos(angle):point.x()+Math.cos(yaw)*.9,z=point==null?s.anchor.getZ()+.5+2.2*Math.sin(angle):point.z()+Math.sin(yaw)*.9,y=point==null?s.anchor.getY()+.08:point.y()+.08;
            s.displays.add(display(world,"identity_card",x,y,z,.8f));
            if(Boolean.TRUE.equals(seat.get("dead")))s.displays.add(display(world,"death_shroud",x,y+.04,z,.8f));
            var label=new DisplayEntity.TextDisplayEntity(net.minecraft.entity.EntityType.TEXT_DISPLAY,world);
            String info=seat.get("seat")+"号 · "+(Boolean.TRUE.equals(seat.get("dead"))?"死亡 · 票"+(Boolean.TRUE.equals(seat.get("ghostVote"))?"1":"0"):"存活");
            label.setText(Text.literal(info));label.setPosition(x,y+.5,z);label.setBillboardMode(DisplayEntity.BillboardMode.CENTER);label.setTransformation(new AffineTransformation(new Vector3f(),new Quaternionf(),new Vector3f(.4f),new Quaternionf()));world.spawnEntity(label);s.displays.add(label);
        }
    }
    public static void placeProp(ServerPlayerEntity p,String key,BlockPos pos,ItemStack stack){
        if(p.squaredDistanceTo(pos.getX()+.5,pos.getY(),pos.getZ()+.5)>36)return;
        var nearby=p.getServerWorld().getEntitiesByClass(DisplayEntity.ItemDisplayEntity.class,new net.minecraft.util.math.Box(pos).expand(1),e->e.getCommandTags().contains("tabletop_prop"));
        if(nearby.size()>=8){tell(p,"这里已放满道具，请先拾回。");return;}
        var e=display(p.getServerWorld(),key,pos.getX()+.5,pos.getY()+.03,pos.getZ()+.5,1f);e.addCommandTag("tabletop_prop");e.addCommandTag("tabletop_owner_"+p.getUuid());if(!p.isCreative())stack.decrement(1);
    }
    private static void pickup(ServerPlayerEntity p){
        var nearby=p.getServerWorld().getEntitiesByClass(DisplayEntity.ItemDisplayEntity.class,p.getBoundingBox().expand(3),e->e.getCommandTags().contains("tabletop_prop")&&e.getCommandTags().contains("tabletop_owner_"+p.getUuid()));
        if(nearby.isEmpty())throw new IllegalStateException("三格内没有你放置的道具。");
        var e=nearby.stream().min(Comparator.comparingDouble(p::squaredDistanceTo)).orElseThrow();var stack=e.getItemStack().copy();if(!p.isCreative()&&!p.getInventory().insertStack(stack))throw new IllegalStateException("背包已满，先腾出位置。");e.discard();tell(p,"已拾回道具。");
    }
}
