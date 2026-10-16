package local.tabletop.game;

import java.util.*;

/** Server-authoritative, Minecraft-independent Trouble Brewing facilitator.
 * Hidden state must only be serialized through privateView for its recipient.
 * All night effects are applied after the Storyteller confirms a queued task. */
public final class ClockGame {
    private static final Set<String> INFORMATION_ROLES=Set.of("washerwoman","librarian","investigator","chef","empath","fortune_teller","undertaker","ravenkeeper","spy");
    private static final int[][] SETUP = {
        {3,0,1,1},{3,1,1,1},{5,0,1,1},{5,1,1,1},{5,2,1,1},{7,0,2,1},
        {7,1,2,1},{7,2,2,1}
    };
    private final UUID storyteller;
    private final List<UUID> players=new ArrayList<>();
    private final Map<UUID,Integer> seatNumbers=new LinkedHashMap<>();
    private final Map<UUID,String> roles = new LinkedHashMap<>(), shown = new LinkedHashMap<>();
    private final Set<UUID> dead = new HashSet<>(), publicDead = new HashSet<>(), spentGhost = new HashSet<>();
    private final Set<UUID> poisoned = new HashSet<>(), drunk = new HashSet<>(), used = new HashSet<>();
    private final Set<UUID> nominators = new HashSet<>(), nominees = new HashSet<>(), diedTonight = new HashSet<>();
    private final Set<UUID> executedDeaths = new HashSet<>();
    private final Set<UUID> pendingDemonNotice = new HashSet<>();
    private final Map<UUID,String> notes = new HashMap<>();
    private final Map<UUID,List<String>> messages = new HashMap<>();
    private final List<Task> tasks = new ArrayList<>();
    private final List<String> adjudications = new ArrayList<>();
    private final List<String> announcements = new ArrayList<>();
    private final List<Map<String,Object>> flowLog=new ArrayList<>();
    private final Map<UUID,Map<String,Object>> spySnapshots = new HashMap<>();
    private List<String> demonBluffs=List.of();
    private UUID poisonTarget, poisonSource, safeTarget, safeSource, master, redHerring;
    private UUID onBlock, lastExecutedDeath;
    private Vote activeVote;
    private DayDecision dayDecision;
    private long dayRequestSequence;
    private boolean started, finished;
    private boolean reviewEnabled,automationOnline=true;
    private long serverTick=-1;
    private int voteTimeoutSeconds=45;
    private int nightNumber, dayNumber, bestVotes;
    private String phase = "setup", winner = "";
    private final String decisionEpoch=UUID.randomUUID().toString().substring(0,8);
    private String specialVictory="",specialVictoryReason="";

    private static final class Task {
        final String role;
        final UUID actor;
        final int targetCount;
        final String help;
        List<UUID> targets = List.of();
        UUID redirectedImp;
        boolean submitted, resolved, skipped;
        String informationKey="", informationDraft="";
        Task(String role, UUID actor, int count, String help) {
            this.role=role; this.actor=actor; this.targetCount=count; this.help=help;
        }
    }
    private static final class Vote {
        final UUID nominee;
        final List<UUID> order;
        final int threshold;
        final Map<UUID,Boolean> ballots = new LinkedHashMap<>();
        long deadline=-1,remainingTicks;
        Vote(UUID nominee, List<UUID> order, int threshold,long duration) { this.nominee=nominee; this.order=order; this.threshold=threshold;this.remainingTicks=duration; }
    }
    private record DayDecision(String kind, UUID actor, UUID target, boolean effective, long request) {}
    /** Internal server presentation snapshot. Never include this in a public payload. */
    public record PhysicalTask(String key, UUID actor, String role, List<UUID> targets,
                               boolean submitted, int targetCount, boolean resolved, boolean current) {
        public PhysicalTask { targets=List.copyOf(targets); }
    }

    public ClockGame(UUID storyteller, List<UUID> players) {
        this(storyteller,players,defaultSeatNumbers(players));
    }
    public ClockGame(UUID storyteller, List<UUID> players, Map<UUID,Integer> numbers) {
        this.storyteller=Objects.requireNonNull(storyteller,"storyteller");
        Objects.requireNonNull(players,"players");
        if (players.size()<5 || players.size()>12 || new HashSet<>(players).size()!=players.size()
            || players.stream().anyMatch(Objects::isNull) || players.contains(storyteller)) {
            throw new IllegalArgumentException("暗流涌动需要 5–12 名不同玩家，另设一名说书人。");
        }
        seatNumbers.putAll(validatedSeatNumbers(players,numbers));
        this.players.addAll(players.stream().sorted(Comparator.comparingInt(seatNumbers::get)).toList());
        players.forEach(p -> messages.put(p,new ArrayList<>()));
    }
    private static Map<UUID,Integer> defaultSeatNumbers(List<UUID> players) {
        Objects.requireNonNull(players,"players");
        Map<UUID,Integer> result=new LinkedHashMap<>();
        for(int index=0;index<players.size();index++)result.put(players.get(index),index+1);
        return result;
    }
    private static Map<UUID,Integer> validatedSeatNumbers(List<UUID> players,Map<UUID,Integer> numbers) {
        if(numbers==null||!numbers.keySet().equals(new HashSet<>(players)))throw new IllegalArgumentException("座位号码必须对应本局每一名玩家。");
        Set<Integer> seen=new HashSet<>();Map<UUID,Integer> result=new LinkedHashMap<>();
        for(UUID player:players){Integer number=numbers.get(player);if(number==null||number<1||number>ClockSeats.LIMIT||!seen.add(number))throw new IllegalArgumentException("座位号码必须为 1–12 且不能重复。");result.put(player,number);}
        return result;
    }
    public synchronized List<UUID> players() { return List.copyOf(players); }
    public synchronized int seatNumber(UUID player){return seatNumbers.getOrDefault(player,0);}
    /** Preparation-only seat edits preserve all UUID-owned assignments and hidden state. */
    public synchronized boolean reseat(UUID actor,Map<UUID,Integer> numbers) {
        if(!st(actor)||started)return false;
        Map<UUID,Integer> validated;
        try{validated=validatedSeatNumbers(players,numbers);}catch(IllegalArgumentException invalid){return false;}
        if(seatNumbers.equals(validated))return true;
        seatNumbers.clear();seatNumbers.putAll(validated);players.sort(Comparator.comparingInt(seatNumbers::get));
        log("seats_adjusted",actor,null,"准备阶段座位号码已更新。",false);return true;
    }
    public UUID storyteller() { return storyteller; }
    public synchronized boolean started() { return started; }
    public synchronized boolean finished() { return finished; }
    public synchronized String phase() { return phase; }
    /** Server-only rule queries: callers must keep these out of recipient/public views. */
    public synchronized boolean isAlive(UUID player) { return players.contains(player)&&!dead.contains(player); }
    public synchronized String trueRole(UUID player) { return roles.getOrDefault(player,""); }
    public synchronized boolean impairedPlayer(UUID player) { return players.contains(player)&&impaired(player); }
    public synchronized boolean demonProtected(UUID player) { return players.contains(player)&&protectedFromDemon(player); }
    public synchronized Set<UUID> deadIds() { return Set.copyOf(dead); }
    public synchronized Set<UUID> nightDeathIds() { return Set.copyOf(diedTonight); }
    public synchronized Set<UUID> executedDeathIds() { return Set.copyOf(executedDeaths); }
    public synchronized PhysicalTask physicalTask() {
        Task task=currentTask();
        return task==null?null:new PhysicalTask(taskKey(task),task.actor,task.role,task.targets,
            task.submitted,task.targetCount,task.resolved,true);
    }
    /** Entry eligibility only; the Storyteller still authorizes entry and resolves the attack. */
    public synchronized boolean impEntryPossible(UUID actor,UUID target) {
        Task task=currentTask();
        return task!=null&&task.role.equals("imp")&&task.redirectedImp==null&&task.actor.equals(actor)&&task.submitted
            &&task.targets.size()==1&&task.targets.getFirst().equals(target)&&!Objects.equals(actor,target)
            &&"imp".equals(roles.get(actor))&&healthy(actor)&&isAlive(target)&&!protectedFromDemon(target);
    }
    /** Explicit Storyteller correction; never invoke automatically when someone leaves the scene. */
    public synchronized String cancelPhysicalImp(UUID actor,String expectedTaskKey) {
        if(!st(actor))return "只有说书人可以取消本次行动。";
        Task task=currentTask();
        if(task==null||!task.role.equals("imp")||!task.submitted||!taskKey(task).equals(expectedTaskKey))return "本次行动已失效，请刷新魔典。";
        tell(task.actor,"说书人已结束你的本次行动。");
        log("night_cancel",actor,task.actor,"说书人取消本次已提交的小恶魔行动；不产生死亡或转生。",true);
        task.resolved=true;currentTask();
        return "步骤已确认，本次行动已取消；已推进至下一待办。";
    }
    public synchronized int voteTimeoutSeconds(){return voteTimeoutSeconds;}
    /** Adapter for the legacy text-return API. Rejections never become successful UI acknowledgements. */
    public static boolean acceptedResponse(String operation,String result) {
        if(operation==null||result==null)return false;
        return switch(operation) {
            case "phase"->result.startsWith("已进入白天；")||result.startsWith("白天无人被处决；已进入下一夜。");
            case "nominate"->result.startsWith("投票按同轮同时统计")||result.startsWith("提名已记录；")||result.startsWith("处决完成，");
            case "vote","unvote"->result.startsWith("已举手投票")||result.equals("已记录不投票。")||result.equals("已记录不支持（改票）。")||result.equals("本轮已经记录相同选择。");
            case "closevote"->result.startsWith("计票完成：");
            case "execute"->result.startsWith("处决完成，")||result.equals("无人达到唯一最高票，今天无人被处决，进入夜晚。");
            case "slay"->result.startsWith("公开声明已记录，")||result.startsWith("公开声明已处理；");
            case "mark"->result.equals("魔典状态已更新，仅说书人可见隐藏标记。");
            case "finish"->result.equals("本局已经结算。");
            case "submit"->result.equals("回复已私下提交给说书人。");
            case "resolve"->result.startsWith("步骤已确认，")||result.startsWith("处决完成，")||result.equals("相关判定已确认，开始普通投票，所有座位可同时回应。")||result.equals("猎手相关判定已确认。")||result.equals("房门已打开，等待夜刃命中。");
            default->false;
        };
    }
    private boolean st(UUID actor) { return storyteller.equals(actor); }
    private boolean active() { return started && !finished; }
    private boolean evil(UUID p) { RoleCatalog.Role r=RoleCatalog.get(roles.get(p)); return r!=null && (r.type().equals("minion")||r.type().equals("demon")); }
    private boolean impaired(UUID p) { return "drunk".equals(roles.get(p))||drunk.contains(p)||poisoned.contains(p)||effectivePoison(p); }
    private boolean effectivePoison(UUID p) {
        // A healthy Poisoner may poison themself. Their produced poison must not recursively cancel its source.
        return Objects.equals(p,poisonTarget)&&poisonSource!=null&&!dead.contains(poisonSource)
            &&"poisoner".equals(roles.get(poisonSource))&&!drunk.contains(poisonSource)&&!poisoned.contains(poisonSource);
    }
    private boolean effectiveSafe(UUID p) {
        return Objects.equals(p,safeTarget)&&safeSource!=null&&"monk".equals(roles.get(safeSource))&&healthy(safeSource);
    }
    private boolean healthy(UUID p) { return p!=null&&!dead.contains(p)&&!impaired(p); }
    private int aliveCount() { return players.size()-dead.size(); }
    private String seat(UUID p) { int number=seatNumber(p);return number==0?"无":number+" 号"; }
    private String roleName(UUID p) { RoleCatalog.Role r=RoleCatalog.get(roles.get(p)); return r==null?"未分配":r.name(); }
    private void tell(UUID p,String message) { List<String> list=messages.get(p); if(list!=null) { list.add(message); if(list.size()>100)list.remove(0); } }
    private void log(String kind,UUID actor,UUID target,String text,boolean secret){flowLog.add(Map.of("sequence",flowLog.size()+1L,"phase",phase,"day",dayNumber,"night",nightNumber,"kind",kind,"actor",actor==null?"":actor.toString(),"target",target==null?"":target.toString(),"text",text,"secret",secret));}
    public synchronized String controlVersion(){currentTask();return phase+":"+nightNumber+":"+dayNumber+":"+flowLog.size()+":"+dayRequestSequence+":"+decisionKey()+":"+started+":"+finished;}
    private void advice(String message) { if(!adjudications.contains(message)){adjudications.add(message);log("adjudication",storyteller,null,message,true);} if(adjudications.size()>60)adjudications.remove(0); }
    private void announce(String message) { announcements.add(message);log("announcement",null,null,message,false); if(announcements.size()>60)announcements.remove(0); }

    public synchronized String assign(UUID actor, UUID target, String role, String shownRole) {
        if(!st(actor))return "只有说书人可以分配角色。";
        if(started)return "开局后不可重新分配初始角色。";
        if(!players.contains(target))return "目标不在本局座位中。";
        RoleCatalog.Role real=RoleCatalog.get(role), appearance=RoleCatalog.get(shownRole==null||shownRole.isBlank()?role:shownRole);
        if(real==null||appearance==null)return "角色不属于暗流涌动。";
        if(real.id().equals("drunk")) {
            if(!appearance.type().equals("townsfolk"))return "酒鬼必须以一个镇民身份开始。";
        } else if(!real.id().equals(appearance.id()))return "只有酒鬼可设置不同的初始表面身份。";
        for(UUID p:players)if(!p.equals(target)) {
            if(real.id().equals(roles.get(p)))return "初始真实角色不可重复。";
            if(appearance.id().equals(shown.get(p)))return "抽取的表面身份不可重复。";
        }
        roles.put(target,real.id()); shown.put(target,appearance.id());
        demonBluffs=List.of();
        log("assignment",actor,target,"真实角色："+real.name()+"；表面身份："+appearance.name()+"。",true);
        return "已分配 "+seat(target)+" 的角色，仅说书人可见真实配置。";
    }
    /** Shuffle a legal base-script bag; no identity is issued to players before start. */
    public synchronized String autoAssign(UUID actor,long seed) {
        if(!st(actor))return "只有说书人可以自动配牌。";
        if(started)return "开局后不可重新配牌。";
        Random random=new Random(seed);
        List<String> minions=ofType("minion"),townsfolk=ofType("townsfolk"),outsiders=ofType("outsider");
        Collections.shuffle(minions,random);Collections.shuffle(townsfolk,random);Collections.shuffle(outsiders,random);
        int[] counts=SETUP[players.size()-5].clone();
        List<String> chosenMinions=new ArrayList<>(minions.subList(0,counts[2]));
        if(chosenMinions.contains("baron")){counts[0]-=2;counts[1]+=2;}
        List<String> bag=new ArrayList<>(townsfolk.subList(0,counts[0]));
        bag.addAll(outsiders.subList(0,counts[1]));bag.addAll(chosenMinions);bag.add("imp");
        String apparentDrunk=townsfolk.get(counts[0]);
        Collections.shuffle(bag,random);roles.clear();shown.clear();
        poisoned.clear();drunk.clear();used.clear();notes.clear();adjudications.clear();redHerring=null;
        for(int i=0;i<players.size();i++){UUID player=players.get(i);String role=bag.get(i);roles.put(player,role);shown.put(player,role.equals("drunk")?apparentDrunk:role);}
        if(roles.containsValue("fortune_teller")){List<UUID> good=players.stream().filter(p->!evil(p)).toList();redHerring=good.get(random.nextInt(good.size()));}
        chooseBluffs(random);
        for(UUID p:players)log("assignment",actor,p,"自动配牌：真实角色 "+roleName(p)+"；表面身份 "+RoleCatalog.get(shown.get(p)).name()+"。",true);
        log("special_setup",actor,redHerring,"红鲱鱼："+seat(redHerring)+"；恶魔诈唬："+String.join("、",demonBluffs.stream().map(id->RoleCatalog.get(id).name()).toList())+"。",true);
        advice("自动配置已按本桌人数生成：男爵的外来者修正、酒鬼的未重复镇民假身份、善良红鲱鱼和未在场善良诈唬已私下核验。说书人可在开局前调整。");
        return "已自动配牌，真实角色与特殊配置仅说书人可见；确认后开始首夜。";
    }
    private static List<String> ofType(String type){return new ArrayList<>(RoleCatalog.all().stream().filter(r->r.type().equals(type)).map(RoleCatalog.Role::id).toList());}
    private void chooseBluffs(Random random) {
        List<String> absent=new ArrayList<>(RoleCatalog.all().stream().filter(r->(r.type().equals("townsfolk")||r.type().equals("outsider"))&&!roles.containsValue(r.id())&&!shown.containsValue(r.id())).map(RoleCatalog.Role::id).toList());
        Collections.shuffle(absent,random);demonBluffs=List.copyOf(absent.subList(0,Math.min(3,absent.size())));
    }
    /** Storyteller-only immutable preparation snapshot, for the owning server session. */
    public synchronized Map<UUID,List<String>> setupAssignments(UUID actor) {
        if(!st(actor))return Map.of();
        Map<UUID,List<String>> result=new LinkedHashMap<>();for(UUID p:players)if(roles.containsKey(p))result.put(p,List.of(roles.get(p),shown.get(p)));return Collections.unmodifiableMap(result);
    }
    public synchronized String start(UUID actor) {
        if(!st(actor))return "只有说书人可以开局。";
        if(started)return "本局已经开始。";
        if(roles.size()!=players.size())return "请先为每个座位分配角色。";
        int[] expected=expectedCounts(),actual=actualCounts();
        if(!Arrays.equals(expected,actual))return "无法开局：初始角色配比不符合本局人数，请说书人私下检查魔典。";
        if(redHerring!=null&&evil(redHerring))return "无法开局：私密准备配置需要说书人检查。";
        if(roles.containsValue("fortune_teller")&&redHerring==null) {
            List<UUID> good=players.stream().filter(p->!evil(p)).toList();
            redHerring=good.get(new Random().nextInt(good.size()));
            advice("占卜师红鲱鱼未手动设置：开局已从善良座位中随机选择并固定为 "+seat(redHerring)+"。此信息仅说书人可见。");
        }
        if(demonBluffs.size()!=3)chooseBluffs(new Random());
        log("special_setup",actor,redHerring,"开局固定红鲱鱼："+seat(redHerring)+"；三个恶魔诈唬："+String.join("、",demonBluffs.stream().map(id->RoleCatalog.get(id).name()).toList())+"。",true);
        started=true;phase="first_night";nightNumber=1;buildNight();
        announce("游戏开始，进入首夜。请所有人取出手机并等待各自的私密提示。");
        return "首夜队列已生成；说书人逐项确认，玩家通过手机回复。";
    }
    private int[] expectedCounts(){int[] counts=SETUP[players.size()-5].clone();if(roles.containsValue("baron")){counts[0]-=2;counts[1]+=2;}return counts;}
    private int[] actualCounts(){int[] counts=new int[4];for(String id:roles.values())switch(RoleCatalog.get(id).type()){case "townsfolk"->counts[0]++;case "outsider"->counts[1]++;case "minion"->counts[2]++;case "demon"->counts[3]++;}return counts;}
    public synchronized String phase(UUID actor, String next) {
        if(!st(actor))return "只有说书人可以推进阶段。";
        if(!active())return "当前没有进行中的对局。";
        if(next==null)return "阶段必须为 day 或 night。";
        next=next.toLowerCase(Locale.ROOT);
        if(next.equals(phase))return "已经处于该阶段。";
        if(next.equals("day")&&(phase.equals("night")||phase.equals("first_night"))) {
            if(currentTask()!=null)return "仍有未完成的夜间步骤，请先收集回复并确认结果。";
            phase="day";dayNumber=nightNumber;publicDead.clear();publicDead.addAll(dead);
            safeTarget=null;safeSource=null; nominators.clear();nominees.clear();activeVote=null;dayDecision=null;onBlock=null;bestVotes=0;
            announce("第 "+dayNumber+" 天开始。存活 "+aliveCount()+" 人；请公开讨论。");
            victoryAdvice(); return "已进入白天；夜间死亡已公开，提名次数已重置。";
        }
        if(next.equals("night")&&phase.equals("day")) {
            if(activeVote!=null||dayDecision!=null)return "请先完成当前投票或角色裁定。";
            if(onBlock!=null)return "已有待处决玩家，请使用 execute 完成处决。";
            lastExecutedDeath=null; endDay();return "白天无人被处决；已进入下一夜。";
        }
        return "允许的流程为首夜→白天→后续夜→白天。";
    }
    private void endDay() {
        if(aliveCount()==3)for(UUID p:players)if("mayor".equals(roles.get(p))&&healthy(p)){specialVictory="good";specialVictoryReason="三名玩家存活且今天无人被处决，健康清醒镇长的胜利条件已满足。";advice("三人存活且当天无人被处决：请确认镇长胜利条件，并以 finish good 结算。");}
        phase="night";nightNumber++;poisonTarget=null;poisonSource=null;safeTarget=null;safeSource=null;diedTonight.clear();
        buildNight();announce("进入第 "+nightNumber+" 夜，请所有人查看手机。");
    }
    public synchronized String nominate(UUID nominator, UUID nominee) {
        if(!active()||!phase.equals("day"))return "只能在白天提名。";
        if(activeVote!=null||dayDecision!=null)return "请先完成当前投票或裁定。";
        if(!players.contains(nominator)||!players.contains(nominee))return "提名双方必须属于本局。";
        if(dead.contains(nominator))return "死亡玩家不能提名。";
        if(nominators.contains(nominator))return "你今天已使用提名机会。";
        if(nominees.contains(nominee))return "该玩家今天已经被提名。";
        nominators.add(nominator);nominees.add(nominee);announce(seat(nominator)+" 提名 "+seat(nominee)+"。");
        if("virgin".equals(shown.get(nominee))&&!used.contains(nominee)&&!dead.contains(nominee)) {
            used.add(nominee);
            if("virgin".equals(roles.get(nominee))&&healthy(nominee)) {
                if("spy".equals(roles.get(nominator))&&!impaired(nominator)) {
                    dayDecision=new DayDecision("virgin",nominator,nominee,false,++dayRequestSequence);
                    advice("贞洁者第一次被间谍提名：说书人裁定间谍是否登记为镇民。resolve execute 或 resolve continue。");
                    return "提名已记录；说书人正在处理相关判定。";
                }
                if(RoleCatalog.get(roles.get(nominator)).type().equals("townsfolk"))return execution(nominator);
            }
        }
        openVote(nominee);return "投票按同轮同时统计，所有座位均可立即支持或不支持，由说书人确认计票。";
    }
    private void openVote(UUID nominee) {
        int start=players.indexOf(nominee);List<UUID> order=new ArrayList<>();
        for(int offset=1;offset<=players.size();offset++)order.add(players.get((start+offset)%players.size()));
        activeVote=new Vote(nominee,List.copyOf(order),(aliveCount()+1)/2,voteTimeoutSeconds*20L);
        if(serverTick>=0&&automationOnline)activeVote.deadline=serverTick+activeVote.remainingTicks;
        announce("提名投票已开始，参考时间 "+voteTimeoutSeconds+" 秒，由说书人确认计票。");
    }
    public synchronized String vote(UUID voter, boolean yes) {
        if(!active()||!phase.equals("day")||activeVote==null)return "当前没有进行中的投票。";
        if(!players.contains(voter))return "你不是本局基础玩家。";
        if(activeVote.ballots.containsKey(voter)) {
            if(yes&&dead.contains(voter)&&spentGhost.contains(voter))return "你的幽灵票已使用，只能选择不投票。";
            if(activeVote.ballots.get(voter)==yes)return "本轮已经记录相同选择。";
            activeVote.ballots.put(voter,yes);log("vote_change",voter,activeVote.nominee,yes?"改为支持。":"取消支持，改为不支持。",false);return yes?"已举手投票（改票）。":"已记录不支持（改票）。";
        }
        if(yes&&dead.contains(voter)&&spentGhost.contains(voter))return "你的幽灵票已使用，只能选择不投票。";
        activeVote.ballots.put(voter,yes);log("vote",voter,activeVote.nominee,yes?"支持。":"不支持。",false);return yes?"已举手投票。":"已记录不投票。";
    }
    public synchronized String unvote(UUID voter) {
        if(!active()||!phase.equals("day")||activeVote==null)return "当前没有进行中的投票；已计票的幽灵票不能返还。";
        if(!Boolean.TRUE.equals(activeVote.ballots.get(voter)))return "你本轮没有待取消的支持票。";
        return vote(voter,false);
    }
    public synchronized String setVoteTimeout(UUID actor,int seconds) {
        if(!st(actor))return "只有说书人可以调整投票时限。";
        if(finished)return "本局已经结算。";
        if(seconds<15||seconds>300)return "投票时限须为15–300秒。";
        voteTimeoutSeconds=seconds;
        if(activeVote!=null){activeVote.remainingTicks=seconds*20L;activeVote.deadline=serverTick>=0&&automationOnline?serverTick+activeVote.remainingTicks:-1;}
        return "投票时限已设为 "+seconds+" 秒。";
    }
    /** Deadlines use authoritative server ticks and pause while a table member is offline. */
    public synchronized String updateAutomation(long tick,boolean everyoneOnline) {
        serverTick=tick;automationOnline=everyoneOnline;if(activeVote==null||!active())return null;
        if(!everyoneOnline){if(activeVote.deadline>=0){activeVote.remainingTicks=Math.max(0,activeVote.deadline-tick);activeVote.deadline=-1;}return null;}
        if(activeVote.deadline<0)activeVote.deadline=tick+activeVote.remainingTicks;
        activeVote.remainingTicks=Math.max(0,activeVote.deadline-tick);
        // The clock is advisory. Only closeVote by the Storyteller may commit
        // ballots, consume ghost votes, and choose an execution candidate.
        return null;
    }
    public synchronized String closeVote(UUID actor) {
        if(!st(actor))return "只有说书人可以计票。";
        if(activeVote==null)return "当前没有待计票投票。";
        return tally("说书人手动结束，未操作按不支持");
    }
    private String tally(String reason) {
        for(UUID p:activeVote.order)if(!activeVote.ballots.containsKey(p)){activeVote.ballots.put(p,false);log("default_vote",p,activeVote.nominee,"未操作，按不支持处理（"+reason+"）。",false);}
        int total=0;for(var entry:activeVote.ballots.entrySet())if(entry.getValue()) {
            UUID p=entry.getKey();
            if("butler".equals(roles.get(p))&&healthy(p)&&!Boolean.TRUE.equals(activeVote.ballots.get(master))){log("ability_vote",p,activeVote.nominee,"健康管家所选主人未支持，管家票不计入。",true);continue;}
            if(dead.contains(p)){spentGhost.add(p);log("ghost_vote",p,activeVote.nominee,"赞成的幽灵票已在计票时消耗。",false);}total++;
        }
        UUID target=activeVote.nominee;int threshold=activeVote.threshold;activeVote=null;
        if(total>=threshold) {
            if(total>bestVotes){bestVotes=total;onBlock=target;}
            else if(total==bestVotes){onBlock=null;}
        }
        announce(reason+"。"+seat(target)+" 获得 "+total+" 票；门槛 "+threshold+" 票；待处决："+seat(onBlock)+"。");
        return "计票完成："+total+" 票，待处决："+seat(onBlock)+"。";
    }
    public synchronized String execute(UUID actor) {
        if(!st(actor))return "只有说书人可以确认处决。";
        if(!active()||!phase.equals("day"))return "只能在白天结算处决。";
        if(activeVote!=null||dayDecision!=null)return "请先完成投票或角色裁定。";
        if(onBlock==null){lastExecutedDeath=null;endDay();return "无人达到唯一最高票，今天无人被处决，进入夜晚。";}
        return execution(onBlock);
    }
    private String execution(UUID target) {
        lastExecutedDeath=dead.contains(target)?null:target;
        announce(seat(target)+" 被处决。");
        if(!dead.contains(target)) {
            boolean saint="saint".equals(roles.get(target))&&healthy(target);
            executedDeaths.add(target);
            die(target);
            if(saint){specialVictory="evil";specialVictoryReason="健康清醒的圣徒因处决死亡。";advice("健康清醒的圣徒因处决死亡：请以 finish evil 确认邪恶获胜。");}
        }
        activeVote=null;dayDecision=null;onBlock=null;
        phase="night";nightNumber++;poisonTarget=null;poisonSource=null;safeTarget=null;safeSource=null;diedTonight.clear();buildNight();
        victoryAdvice();return "处决完成，进入下一夜；说书人请检查胜利条件。";
    }
    private void die(UUID p) {
        if(dead.contains(p))return;
        int aliveBefore=aliveCount();boolean wasDemon="imp".equals(roles.get(p));dead.add(p);
        log("death",storyteller,p,"规则判定死亡。",!phase.equals("day"));
        if(phase.equals("day"))publicDead.add(p);else diedTonight.add(p);
        if(p.equals(poisonSource)){poisonTarget=null;poisonSource=null;}
        if(p.equals(safeSource)){safeTarget=null;safeSource=null;}
        if(wasDemon&&aliveBefore>=5)for(UUID q:players)if("scarlet_woman".equals(roles.get(q))&&healthy(q)) {
            transformImp(q);advice("红唇女郎已依照五人存活条件接替小恶魔；真实身份改变仅私下通知本人。");break;
        }
        victoryAdvice();
    }
    private void transformImp(UUID p) {
        String previous=roleName(p);
        if(p.equals(poisonSource)){poisonTarget=null;poisonSource=null;}
        if(p.equals(safeSource)){safeTarget=null;safeSource=null;}
        roles.put(p,"imp");used.remove(p);
        log("role_change",storyteller,p,"身份由 "+previous+" 改为小恶魔。",true);
        if(phase.equals("day"))pendingDemonNotice.add(p);
        else { shown.put(p,"imp");tell(p,"说书人通知：你现在是小恶魔。当夜不会额外再次发动杀人，从后续夜晚开始正常行动。"); }
    }
    private void victoryAdvice() {
        adjudications.removeIf(s->s.startsWith("没有存活恶魔：")||s.startsWith("存活人数至多两人"));
        if(players.stream().noneMatch(p->"imp".equals(roles.get(p))&&!dead.contains(p)))advice("没有存活恶魔：核对转生后确认善良获胜。");
        else if(aliveCount()<=2)advice("存活至多两人且有恶魔：请确认邪恶获胜。");
    }
    public synchronized String mark(UUID actor,UUID target,String state,boolean enabled) {
        if(!st(actor))return "只有说书人可以修改魔典状态。";
        if(!players.contains(target))return "目标不在本局。";
        if(finished)return "本局已结算，不能修改状态。";
        if(state==null)return "缺少状态名称。";
        String rawState=state.trim();state=rawState.toLowerCase(Locale.ROOT);
        String before=markFingerprint();
        switch(state) {
            case "dead" -> {
                if(!started)return "开局前不能标记死亡。";
                if(activeVote!=null)return "投票中不能改变生死，请先完成投票。";
                if(enabled)die(target);
                else { dead.remove(target);publicDead.remove(target);spentGhost.remove(target);used.remove(target);diedTonight.remove(target);executedDeaths.remove(target); }
            }
            case "poisoned" -> { if(enabled)poisoned.add(target);else poisoned.remove(target); }
            case "drunk" -> { if(enabled)drunk.add(target);else drunk.remove(target); }
            case "used", "ability_used" -> { if(enabled)used.add(target);else used.remove(target); }
            case "red_herring" -> {
                if(started)return "红鲱鱼须开局前固定，不可在对局中移动。";
                if(!roles.containsKey(target)||evil(target))return "红鲱鱼须为已分配角色的善良玩家。";
                if(enabled)redHerring=target;else if(target.equals(redHerring))redHerring=null;
            }
            case "imp" -> {
                if(!active()||!enabled||dead.contains(target)||!"minion".equals(RoleCatalog.get(roles.get(target)).type()))return "仅可将进行中对局的存活爪牙转为小恶魔。";
                if(players.stream().anyMatch(p->"imp".equals(roles.get(p))&&!dead.contains(p)))return "本局已有存活小恶魔，不能创建第二个恶魔。";
                transformImp(target);victoryAdvice();
            }
            default -> {
                if(state.startsWith("note:")){if(enabled)notes.put(target,rawState.substring(5));else notes.remove(target);}
                else return "支持 dead、poisoned、drunk、used、red_herring、imp、note:内容。";
            }
        }
        if(!before.equals(markFingerprint()))log("mark",actor,target,"状态 "+rawState+" → "+(enabled?"开启":"关闭")+"。",true);
        return "魔典状态已更新，仅说书人可见隐藏标记。";
    }
    private String markFingerprint(){return grimoire().toString()+notes+poisoned+drunk;}
    public synchronized boolean canReview(UUID viewer){return st(viewer)||finished&&reviewEnabled&&players.contains(viewer);}
    public synchronized String setReview(UUID actor,boolean enabled) {
        if(!st(actor))return "只有说书人可以授权复盘。";
        if(!finished)return "本局结算后才可以向基础玩家开放复盘。";
        reviewEnabled=enabled;announce(enabled?"说书人已开放本局完整复盘，本桌玩家可以查看身份与私密流程记录。":"说书人已撤回本局完整复盘授权。");
        return enabled?"本桌玩家的复盘查看权限已开放。":"本桌玩家的复盘查看权限已撤回。";
    }
    public synchronized String finish(UUID actor,String result) {
        if(!st(actor))return "只有说书人可以裁定胜负。";
        if(!active())return "没有可结算的进行中对局。";
        if(!"good".equals(result)&&!"evil".equals(result))return "胜方须为 good 或 evil。";
        winner=result;finished=true;phase="finished";activeVote=null;dayDecision=null;tasks.clear();poisonTarget=null;poisonSource=null;safeTarget=null;safeSource=null;
        publicDead.clear();publicDead.addAll(dead);announce("说书人裁定："+(result.equals("good")?"善良":"邪恶")+"阵营获胜。");
        return "本局已经结算。";
    }

    private void buildNight() {
        tasks.clear();
        if(nightNumber==1&&players.size()>=7) {
            for(UUID p:players)if(RoleCatalog.get(roles.get(p)).type().equals("minion"))tasks.add(new Task("minion_info",p,0,"首夜爪牙互认与恶魔身份信息，等待说书人确认。"));
            for(UUID p:players)if("imp".equals(roles.get(p)))tasks.add(new Task("demon_info",p,0,"首夜认识爪牙并获得三个未在场的善良角色作为诈唬。"));
        }
        for(UUID p:players)if(!dead.contains(p)) {
            if(pendingDemonNotice.contains(p)) {
                tasks.add(new Task("scarlet_woman",p,0,"说书人将私下通知你的身份变化。"));
                if(nightNumber>1)tasks.add(task("imp",p));
            } else { String id=shown.get(p);if(rank(id)>0&&!id.equals("scarlet_woman"))tasks.add(task(id,p)); }
        }
        if(nightNumber>1)tasks.sort(Comparator.comparingInt(t->rank(t.role)));
        else tasks.sort(Comparator.comparingInt(t->t.role.equals("minion_info")?1:t.role.equals("demon_info")?2:rank(t.role)));
        // A Ravenkeeper may die after the queue is built. Its conditional task remains dormant until reached.
        if(nightNumber>1)for(UUID p:players)if("ravenkeeper".equals(shown.get(p))&&tasks.stream().noneMatch(t->t.actor.equals(p)&&t.role.equals("ravenkeeper"))) {
            Task t=task("ravenkeeper",p);int insert=0;while(insert<tasks.size()&&rank(tasks.get(insert).role)<=rank("ravenkeeper"))insert++;tasks.add(insert,t);
        }
        currentTask();
    }
    private int rank(String id) { RoleCatalog.Role r=RoleCatalog.get(id);return r==null?0:nightNumber==1?r.firstNight():r.otherNight(); }
    private Task task(String id,UUID p) {
        int count=switch(id){case "fortune_teller"->2;case "poisoner","monk","imp","ravenkeeper","butler"->1;default->0;};
        return new Task(id,p,count,RoleCatalog.get(id).ability());
    }
    private Task currentTask() {
        if(!active()||(!phase.equals("night")&&!phase.equals("first_night")))return null;
        for(Task t:tasks)if(!t.resolved&&!t.skipped) {
            boolean special=t.role.equals("minion_info")||t.role.equals("demon_info");
            boolean eligible=t.role.equals("ravenkeeper")?diedTonight.contains(t.actor):!dead.contains(t.actor);
            boolean notice=t.role.equals("scarlet_woman")&&pendingDemonNotice.contains(t.actor);
            if(!eligible||(!special&&!notice&&!t.role.equals(shown.get(t.actor)))){t.skipped=true;log("night_skip",storyteller,t.actor,"跳过本夜不适用的步骤："+t.role+"。",true);continue;}
            if(t.role.equals("undertaker")&&lastExecutedDeath==null){t.skipped=true;log("night_skip",storyteller,t.actor,"送葬者：今天没有因处刑死亡的玩家，不唤醒。",true);continue;}
            return t;
        }
        return null;
    }
    private Map<String,Object> taskView(Task t,boolean storytellerView) {
        Map<String,Object> m=new LinkedHashMap<>();m.put("role",t.role);m.put("actor",t.actor.toString());m.put("targetCount",t.targetCount);m.put("help",t.help);
        m.put("status",t.skipped?"skipped":t.resolved?"resolved":t.submitted||t.targetCount==0?"awaiting_confirmation":"awaiting_player");
        m.put("current",currentTask()==t);
        if(storytellerView){m.put("targets",t.targets.stream().map(UUID::toString).toList());m.put("impaired",impaired(t.actor));m.put("trueRole",roles.getOrDefault(t.actor,""));m.put("shownRole",shown.getOrDefault(t.actor,""));m.put("fakeRole",!t.role.equals(roles.get(t.actor))&&t.role.equals(shown.get(t.actor)));}
        return m;
    }
    /** Internal Storyteller API. Never send this list in a public payload. */
    public synchronized List<Map<String,Object>> nightTasks() { currentTask();return tasks.stream().map(t->taskView(t,true)).toList(); }
    public synchronized String submit(UUID player,List<UUID> targets) {
        if(!active()||!(phase.equals("first_night")||phase.equals("night")))return "当前没有进行中的夜间步骤。";
        Task t=currentTask();
        if(t==null||!t.actor.equals(player))return "当前没有需要你选择目标的夜间步骤。";
        if(t.targetCount==0)return "此步骤不需要选人，请等待说书人发送信息。";
        if(t.submitted)return "本步骤已回复，等待说书人确认。";
        if(targets==null||targets.size()!=t.targetCount||new HashSet<>(targets).size()!=targets.size())return "请选择 "+t.targetCount+" 名不同玩家。";
        if(targets.stream().anyMatch(p->p==null||!players.contains(p)))return "目标必须来自本局座位。";
        if((t.role.equals("monk")||t.role.equals("butler"))&&targets.contains(player))return "该能力不能选择自己。";
        t.targets=List.copyOf(targets);t.submitted=true;log("night_choice",player,null,t.role+" 选择："+String.join("、",targets.stream().map(this::seat).toList())+"。",true);return "回复已私下提交给说书人。";
    }
    public synchronized String suggestion(UUID actor) {
        if(!st(actor))return "只有说书人可以查看判定建议。";
        if(!started)return roles.size()!=players.size()?"准备阶段：请自动配牌，或逐座手动分配角色。":Arrays.equals(expectedCounts(),actualCounts())?"角色已经配齐且人数配比合法，请确认红鲱鱼及诈唬后开始首夜。":"准备角色的类别配比不合法，请查看魔典中的预期/实际配比或重新自动配牌。";
        if(finished)return "本局已结算，重置后可准备下一局。";
        if(dayDecision!=null)return dayDecision.kind.equals("virgin")?"贞洁者与间谍登记：resolve execute 处决提名者，或 resolve continue 继续投票。":"猎手："+seat(dayDecision.actor)+" → "+seat(dayDecision.target)+"；"+(slayerRegistration(dayDecision)?"请裁定陌客登记。":slayerCanKill(dayDecision)?"有效命中恶魔，确认后死亡。":"无事发生，等待确认。 ");
        if(phase.equals("day")) {
            if(activeVote!=null)return activeVote.ballots.size()==activeVote.order.size()?"本次提名的投票已齐，点击核算当前投票。":"所有座位可同时投票，已回应 "+activeVote.ballots.size()+" / "+players.size()+"；说书人确认计票前可以改票。";
            if(onBlock!=null)return "唯一最高票处刑候选为 "+seat(onBlock)+"。讨论结束后确认处刑，或继续合法提名。";
            return "白天讨论中：存活玩家可发起提名；无人处刑时点击进入夜晚。请检查魔典中的胜利提示。";
        }
        Task t=currentTask();if(t==null)return "夜间队列已完成，可进入白天。";
        if(t.targetCount>0&&!t.submitted)return "等待 "+seat(t.actor)+" 私密选择 "+t.targetCount+" 个目标。";
        if(t.role.equals("minion_info"))return evilInfo(t.actor,false);
        if(t.role.equals("demon_info"))return evilInfo(t.actor,true);
        if(t.role.equals("scarlet_woman"))return info(t);
        if(impaired(t.actor))return INFORMATION_ROLES.contains(t.role)?"此玩家醉酒/中毒，已生成替代信息草稿；请核对或修改后确认发送。":"此玩家醉酒/中毒，能力无效；确认其选择，不向玩家展示真实状态。";
        return info(t);
    }
    private String evilInfo(UUID actor,boolean demon) {
        List<String> minions=players.stream().filter(p->!p.equals(actor)&&RoleCatalog.get(roles.get(p)).type().equals("minion")).map(this::seat).toList();
        String text="爪牙："+String.join("、",minions)+"。";
        if(!demon)text+="恶魔："+players.stream().filter(p->"imp".equals(roles.get(p))).map(this::seat).findFirst().orElse("无")+"。";
        else { List<String> bluffs=demonBluffs.stream().map(id->RoleCatalog.get(id).name()).toList();text+="建议诈唬："+String.join("、",bluffs)+"。说书人可改选三个不在场善良角色。"; }
        return text;
    }
    private String info(Task t) {
        return switch(t.role) {
            case "poisoner" -> "确认后使 "+seat(t.targets.get(0))+" 中毒，持续至下一黄昏。";
            case "monk" -> "确认后使 "+seat(t.targets.get(0))+" 今夜免受恶魔能力影响。";
            case "butler" -> "确认主人为 "+seat(t.targets.get(0))+"。";
            case "imp" -> t.redirectedImp==null?impAdvice(t.targets.get(0)):"镇长替死已锁定为小恶魔；死亡尚未结算。"+impAdvice(t.redirectedImp);
            case "chef" -> { int pairs=0;for(int i=0;i<players.size();i++)if(evil(players.get(i))&&evil(players.get((i+1)%players.size())))pairs++;yield "按真实阵营计有 "+pairs+" 对相邻邪恶玩家。"+registrationAdvice(players); }
            case "empath" -> { List<UUID> neighbours=livingNeighbours(t.actor);long count=neighbours.stream().filter(this::evil).count();yield "存活邻居为 "+neighbours.stream().map(this::seat).toList()+"；真实邪恶人数 "+count+"。"+registrationAdvice(neighbours); }
            case "fortune_teller" -> { boolean yes=t.targets.stream().anyMatch(p->"imp".equals(roles.get(p))||p.equals(redHerring));yield "按真实身份与固定红鲱鱼："+(yes?"是":"否")+"。"+registrationAdvice(t.targets); }
            case "undertaker" -> "当天处决死亡者为 "+seat(lastExecutedDeath)+"，真实角色 "+roleName(lastExecutedDeath)+"。"+registrationAdvice(List.of(lastExecutedDeath));
            case "ravenkeeper" -> "所选 "+seat(t.targets.get(0))+" 的真实角色为 "+roleName(t.targets.get(0))+"。"+registrationAdvice(t.targets);
            case "washerwoman","librarian","investigator" -> initialInfo(t);
            case "spy" -> "确认后向间谍展示本刻全部真实身份与状态；醉毒时仅发送替代草稿。";
            case "scarlet_woman" -> "向此玩家私下通知其已经成为小恶魔。此步骤是身份通知，不受醉毒影响。";
            default -> "请按角色能力确认本步骤。";
        };
    }
    private String initialInfo(Task t) {
        return informationDraft(t)+"说书人可调整角色和信息对。"+registrationAdvice(players);
    }
    private List<UUID> livingNeighbours(UUID actor) {
        List<UUID> result=new ArrayList<>();int index=players.indexOf(actor);
        for(int direction:new int[]{-1,1})for(int d=1;d<players.size();d++){UUID p=players.get(Math.floorMod(index+direction*d,players.size()));if(!dead.contains(p)){result.add(p);break;}}
        return result;
    }
    private String registrationAdvice(Collection<UUID> relevant) {
        if(relevant.stream().anyMatch(p->"recluse".equals(roles.get(p))||"spy".equals(roles.get(p))))return " 待裁定：相关陌客/间谍可能错误登记；草稿按真实身份生成，可修改后发送。";
        return "";
    }
    private String impAdvice(UUID target) {
        if(dead.contains(target))return "所选玩家已死亡，今晚不会造成新死亡。";
        if(protectedFromDemon(target))return "目标受有效僧侣保护或为健康士兵，今晚不会死亡；恶魔不获知原因。";
        if("mayor".equals(roles.get(target))&&healthy(target))return "待裁定：让镇长死亡，或选择替死席位。";
        if("imp".equals(roles.get(target)))return "待裁定：选择接替爪牙；符合五人条件时由红唇女郎接替。无人可接替时确认死亡。";
        return "确认后 "+seat(target)+" 死亡；天亮前不向公共状态公布。";
    }
    private boolean protectedFromDemon(UUID p) { return effectiveSafe(p)||("soldier".equals(roles.get(p))&&healthy(p)); }
    public synchronized String resolve(UUID actor) { return resolve(actor,""); }
    public synchronized String resolve(UUID actor,String privateInfo) {
        if(!st(actor))return "只有说书人可以确认角色步骤。";
        if(!active())return "当前没有进行中的对局。";
        String override=privateInfo==null?"":privateInfo.trim();
        if(dayDecision!=null){String result=resolveDay(override);if(result.startsWith("处决完成")||result.contains("开始普通")||result.contains("判定已确认"))log("day_adjudication",actor,null,"说书人确认日间裁定："+override+"；"+result,true);return result;}
        Task t=currentTask();if(t==null)return "当前夜间步骤已全部完成。";
        if(t.targetCount>0&&!t.submitted)return "玩家尚未回复，不能代替其选择目标。";
        boolean bad=impaired(t.actor), information=INFORMATION_ROLES.contains(t.role);
        String suggestion=suggestion(actor);
        if(information&&override.isBlank()&&(bad||suggestion.contains("待裁定")))return "此步骤需要说书人填写最终私密信息，避免错误登记或醉毒泄密。";
        String delivered="";
        switch(t.role) {
            case "minion_info","demon_info" -> delivered=override.isBlank()?evilInfo(t.actor,t.role.equals("demon_info")):override;
            case "poisoner" -> { if(!bad){poisonSource=t.actor;poisonTarget=t.targets.get(0);}delivered="说书人已确认你的选择。"; }
            case "monk" -> { if(!bad){safeSource=t.actor;safeTarget=t.targets.get(0);}delivered="说书人已确认你的选择。"; }
            case "butler" -> { if(!bad)master=t.targets.get(0);delivered="你今晚选择的主人为 "+seat(t.targets.get(0))+"。"; }
            case "imp" -> {
                if(!bad){
                    boolean transferPending=t.redirectedImp!=null;
                    String error=applyImp(t,override);if(error!=null)return error;
                    if(!transferPending&&t.redirectedImp!=null){
                        log("mayor_transfer",actor,t.redirectedImp,"镇长替死锁定为小恶魔；等待说书人确认合法继位后结算死亡。",true);
                        return "步骤已确认，镇长替死已锁定；等待说书人确认继位，尚未结算死亡。";
                    }
                }
                delivered="说书人已确认你的选择。";
            }
            case "scarlet_woman" -> { pendingDemonNotice.remove(t.actor);shown.put(t.actor,"imp");delivered="你的身份已经变为小恶魔。从本夜开始正常行动。"; }
            case "spy" -> {
                if(!bad){spySnapshots.put(t.actor,spySnapshot());delivered="全桌真实身份与状态已送达。";}
                else { spySnapshots.remove(t.actor);delivered=override; }
            }
            default -> delivered=override.isBlank()?plainInfo(t):override;
        }
        tell(t.actor,"第 "+nightNumber+" 夜："+delivered);log("night_result",actor,t.actor,t.role+" 已确认；醉毒影响 "+bad+"；覆写 "+!override.isBlank()+"；收到信息："+delivered,true);t.resolved=true;currentTask();
        return "步骤已确认，结果只送达对应玩家；已推进至下一待办。";
    }
    private String plainInfo(Task t) {
        return switch(t.role) {
            case "chef" -> {int n=0;for(int i=0;i<players.size();i++)if(evil(players.get(i))&&evil(players.get((i+1)%players.size())))n++;yield "相邻邪恶玩家共有 "+n+" 对。";}
            case "empath" -> "你的两名存活邻居中有 "+livingNeighbours(t.actor).stream().filter(this::evil).count()+" 名邪恶玩家。";
            case "fortune_teller" -> "所选两人中是否有恶魔："+(t.targets.stream().anyMatch(p->"imp".equals(roles.get(p))||p.equals(redHerring))?"是。":"否。");
            case "undertaker" -> "当天因处决死亡的玩家是 "+roleName(lastExecutedDeath)+"。";
            case "ravenkeeper" -> "你选择的 "+seat(t.targets.get(0))+" 是 "+roleName(t.targets.get(0))+"。";
            default -> initialInformation(t,false,draftRandom(t));
        };
    }
    /** Only the Storyteller receives drafts. A task's draft changes only when relevant hidden state changes. */
    private String informationDraft(Task t) {
        if(t==null||!INFORMATION_ROLES.contains(t.role)||(t.targetCount>0&&!t.submitted))return "";
        String key=informationFingerprint(t);
        if(!key.equals(t.informationKey)){
            t.informationKey=key;
            t.informationDraft=impaired(t.actor)?replacementInformation(t,draftRandom(t)):
                t.role.equals("spy")?"确认发送当前全桌魔典（全部真实身份与状态）。":plainInfo(t);
        }
        return t.informationDraft;
    }
    private String informationFingerprint(Task t) {
        return nightNumber+":"+t.role+":"+t.actor+":"+t.targets+":"+t.submitted+":"+grimoire()+":"+lastExecutedDeath+":"+master;
    }
    private Random draftRandom(Task t) { return new Random(Objects.hash(decisionEpoch,informationFingerprint(t))); }
    private String initialInformation(Task t,boolean replacement,Random random) {
        String type=switch(t.role){case "washerwoman"->"townsfolk";case "librarian"->"outsider";default->"minion";};
        List<UUID> candidates=new ArrayList<>(players.stream().filter(p->!p.equals(t.actor)).toList());
        Collections.shuffle(candidates,random);
        if(replacement){
            if(t.role.equals("librarian")&&random.nextInt(3)==0)return "没有外来者在场。";
            List<String> types=ofType(type);String role=types.get(random.nextInt(types.size()));
            return pairInformation(candidates.get(0),candidates.get(1),RoleCatalog.get(role).name());
        }
        List<UUID> matches=new ArrayList<>(candidates.stream().filter(p->RoleCatalog.get(roles.get(p)).type().equals(type)).toList());
        if(matches.isEmpty()&&RoleCatalog.get(roles.get(t.actor)).type().equals(type))matches.add(t.actor);
        if(matches.isEmpty())return "没有外来者在场。";
        UUID a=matches.get(random.nextInt(matches.size())),b=candidates.stream().filter(p->!p.equals(a)).findFirst().orElseThrow();
        return pairInformation(a,b,roleName(a));
    }
    private String pairInformation(UUID a,UUID b,String role) {
        if(players.indexOf(a)>players.indexOf(b)){UUID swap=a;a=b;b=swap;}
        return seat(a)+" / "+seat(b)+"，其中一人是 "+role+"。";
    }
    private String replacementInformation(Task t,Random random) {
        return switch(t.role){
            case "washerwoman","librarian","investigator" -> initialInformation(t,true,random);
            case "chef" -> "相邻邪恶玩家共有 "+random.nextInt(Math.min(3,players.size())+1)+" 对。";
            case "empath" -> "你的两名存活邻居中有 "+random.nextInt(3)+" 名邪恶玩家。";
            case "fortune_teller" -> "所选两人中是否有恶魔："+(random.nextBoolean()?"是。":"否。");
            case "undertaker","ravenkeeper" -> {
                List<String> roles=new ArrayList<>(RoleCatalog.all().stream().map(RoleCatalog.Role::id).toList());
                UUID target=t.role.equals("undertaker")?lastExecutedDeath:t.targets.get(0);
                roles.remove(this.roles.get(target));String name=RoleCatalog.get(roles.get(random.nextInt(roles.size()))).name();
                yield t.role.equals("undertaker")?"当天因处决死亡的玩家是 "+name+"。":"你选择的 "+seat(target)+" 是 "+name+"。";
            }
            case "spy" -> {
                List<String> alternatives=RoleCatalog.all().stream().map(RoleCatalog.Role::id).toList();List<String> rows=new ArrayList<>();
                for(UUID p:players){List<String> options=new ArrayList<>(alternatives);options.remove(roles.get(p));String role=options.get(random.nextInt(options.size()));rows.add(seat(p)+"："+RoleCatalog.get(role).name()+"（"+(random.nextBoolean()?"存活":"死亡")+"）。");}
                yield "你今晚看到的魔典："+String.join("",rows);
            }
            default -> "说书人已确认本步骤。";
        };
    }
    private String informationWarning(Task t) {
        if(t==null||!INFORMATION_ROLES.contains(t.role)||(t.targetCount>0&&!t.submitted))return "";
        if(impaired(t.actor))return "醉毒：替代草稿可修改，不发送真实魔典。";
        Collection<UUID> relevant=switch(t.role){case "empath"->livingNeighbours(t.actor);case "fortune_teller","ravenkeeper"->t.targets;case "undertaker"->List.of(lastExecutedDeath);default->players;};
        return t.role.equals("spy")?"全部真实身份与状态，仅发送给当前间谍。":registrationAdvice(relevant).isEmpty()?"":"相关陌客/间谍可误登记；草稿默认真实登记，可修改。";
    }
    private String applyImp(Task t,String override) {
        if(t.redirectedImp!=null) {
            if(dead.contains(t.redirectedImp)||protectedFromDemon(t.redirectedImp))return null;
            return applyImpSuccession(t.redirectedImp,override);
        }
        UUID target=t.targets.get(0);
        if(dead.contains(target)||protectedFromDemon(target))return null;
        if("mayor".equals(roles.get(target))&&healthy(target)) {
            if(override.equals("default")){die(target);return null;}
            if(!override.startsWith("kill:"))return "镇长需要明确裁定：default 或 kill:<玩家UUID>。";
            UUID redirect=parsePlayer(override.substring(5));if(redirect==null)return "替死者必须为本局玩家 UUID。";
            if(redirect.equals(target))return "镇长的替死者须为另一名玩家。";
            if(!dead.contains(redirect)&&!protectedFromDemon(redirect)) {
                if(redirect.equals(t.actor))t.redirectedImp=redirect;
                else die(redirect);
            }
            return null;
        }
        if(target.equals(t.actor))return applyImpSuccession(target,override);
        die(target);return null;
    }
    private UUID forcedScarletWoman() {
        return players.stream().filter(p->"scarlet_woman".equals(roles.get(p))&&healthy(p)&&aliveCount()>=5).findFirst().orElse(null);
    }
    private List<UUID> livingMinions() {
        return players.stream().filter(p->!dead.contains(p)&&RoleCatalog.get(roles.get(p)).type().equals("minion")).toList();
    }
    private String applyImpSuccession(UUID target,String override) {
        UUID forced=forcedScarletWoman();List<UUID> eligible=livingMinions();
        if(override.equals("default")&&eligible.isEmpty()){die(target);return null;}
        if(!override.startsWith("pass:"))return "请以 pass:<存活爪牙UUID> 指定接替者，无存活爪牙才可 default。";
        UUID next=parsePlayer(override.substring(5));
        if(next==null||!eligible.contains(next)||(forced!=null&&!forced.equals(next)))return "接替者不合法，或须由符合五人条件的红唇女郎接替。";
        die(target);if(!"imp".equals(roles.get(next)))transformImp(next);victoryAdvice();return null;
    }
    private UUID parsePlayer(String text) { try {UUID p=UUID.fromString(text.trim());return players.contains(p)?p:null;}catch(IllegalArgumentException ex){return null;} }
    /** Each choice is scoped to a fresh game and the exact current task. Never expose to other viewers. */
    private String decisionKey() {
        if(dayDecision!=null)return decisionEpoch+":d:"+dayNumber+":"+dayDecision.request+":"+dayDecision.kind+":"+dayDecision.actor+":"+dayDecision.target;
        Task t=currentTask();return t==null?"":taskKey(t)+(t.redirectedImp==null?"":":mayor_succession");
    }
    private String taskKey(Task t) { return decisionEpoch+":n:"+nightNumber+":"+t.role+":"+t.actor; }
    private Map<String,String> choice(String code,String label,UUID target,String confirm,boolean dangerous) {
        String id=decisionKey()+":"+code;
        return Map.of("id",id,"label",label,"action","decision","target",target==null?"":target.toString(),"text",id,"group",dayDecision==null?"night":"day","confirm",confirm,"dangerous",String.valueOf(dangerous));
    }
    public synchronized List<Map<String,String>> decisionChoices(UUID actor) {
        if(!st(actor)||!active())return List.of();
        List<Map<String,String>> choices=new ArrayList<>();
        if(dayDecision!=null) {
            if(dayDecision.kind.equals("virgin")) {
                choices.add(choice("execute","登记为镇民，处决提名者",null,"确认让 "+seat(dayDecision.actor)+" 因贞洁者能力被处决并结束白天？",true));
                choices.add(choice("continue","不登记为镇民，继续普通投票",null,"确认继续对 "+seat(dayDecision.target)+" 进行普通提名投票？",false));
            } else {
                if(slayerCanKill(dayDecision))choices.add(choice("kill","确认目标死亡",null,"确认本次猎手结果？",true));
                if(!slayerCanKill(dayDecision)||slayerRegistration(dayDecision))choices.add(choice("none","确认无事发生",null,"确认本次猎手结果？",false));
            }
            return List.copyOf(choices);
        }
        Task t=currentTask();if(t==null||t.targetCount>0&&!t.submitted)return List.of();
        if(t.role.equals("imp")&&!impaired(t.actor)) {
            UUID target=t.redirectedImp==null?t.targets.get(0):t.redirectedImp;
            if(t.redirectedImp==null&&!dead.contains(target)&&!protectedFromDemon(target)&&"mayor".equals(roles.get(target))&&healthy(target)) {
                choices.add(choice("mayor_default","让镇长死亡",null,"确认 "+seat(target)+" 镇长今夜死亡？死亡到天亮才公开。",true));
                for(UUID p:players)if(!p.equals(target))choices.add(choice("mayor_redirect","改由 "+seat(p)+" 承受死亡",p,"确认镇长转移至 "+seat(p)+"？死者或有效士兵/僧侣保护可能使本夜无人死亡。",true));
            } else if(!dead.contains(target)&&!protectedFromDemon(target)&&target.equals(t.actor)) {
                UUID forced=forcedScarletWoman();String cause=t.redirectedImp==null?"小恶魔自杀":"镇长替死导致小恶魔死亡";
                for(UUID p:livingMinions())if(forced==null||forced.equals(p))choices.add(choice("imp_pass",seat(p)+" 接替小恶魔"+(forced==null?"":"（红唇女郎必须接替）"),p,"确认"+cause+"，由 "+seat(p)+" 接替？新恶魔本夜不额外杀人，仅私下通知。",true));
                if(choices.isEmpty())choices.add(choice("imp_default","无存活爪牙，小恶魔死亡",null,"确认"+cause+"且无人继位？随后检查善良胜利条件。",true));
            }
        } else if(t.role.equals("empath")) {
            for(int n=0;n<=2;n++)choices.add(choice("empath_"+n,"发送 "+n+" 名邪恶邻居",null,"将此最终信息只发送给 "+seat(t.actor)+"？请先核对醉毒与陌客/间谍登记。",false));
        } else if(t.role.equals("fortune_teller")) {
            choices.add(choice("fortune_yes","发送：是",null,"确认发送“所选两人中有恶魔：是”？请核对红鲱鱼、醉毒和陌客登记。",false));
            choices.add(choice("fortune_no","发送：否",null,"确认发送“所选两人中有恶魔：否”？请核对红鲱鱼、醉毒和陌客登记。",false));
        } else if(t.role.equals("chef")&&(impaired(t.actor)||info(t).contains("待裁定"))) {
            for(int n=0;n<=players.size();n++)choices.add(choice("chef_"+n,"发送 "+n+" 对相邻邪恶",null,"将此最终数字只发送给 "+seat(t.actor)+"？请先核对醉毒与登记。",false));
        }
        return List.copyOf(choices);
    }
    /** Rebuild the legal list on receipt: client labels, hidden flags, and stale targets have no authority. */
    public synchronized String decide(UUID actor,String id,UUID selected) {
        if(!st(actor))return "只有说书人可以确认私密裁定。";
        Map<String,String> selectedChoice=decisionChoices(actor).stream().filter(c->c.get("id").equals(id)&&c.get("target").equals(selected==null?"":selected.toString())).findFirst().orElse(null);
        if(selectedChoice==null)return "这项裁定已失效或目标不合法，请刷新魔典后选择当前待办。";
        String code=id.substring(decisionKey().length()+1),override;
        if(code.equals("mayor_redirect"))override="kill:"+selected;
        else if(code.equals("imp_pass"))override="pass:"+selected;
        else if(code.equals("mayor_default")||code.equals("imp_default"))override="default";
        else if(code.startsWith("empath_"))override="你的两名存活邻居中有 "+code.substring(7)+" 名邪恶玩家。";
        else if(code.startsWith("chef_"))override="相邻邪恶玩家共有 "+code.substring(5)+" 对。";
        else if(code.equals("fortune_yes")||code.equals("fortune_no"))override="所选两人中是否有恶魔："+(code.equals("fortune_yes")?"是。":"否。");
        else override=code;
        String result=resolve(actor,override);
        if(result.startsWith("步骤已确认")||result.contains("判定已确认")||result.startsWith("处决完成"))log("structured_decision",actor,selected,"按钮裁定："+selectedChoice.get("label")+"。",true);
        return result;
    }
    private Map<String,String> workflowAction(String label,String action,String target,String text,String group,String confirm,boolean dangerous) {
        return Map.of("label",label,"action",action,"target",target,"text",text,"group",group,"confirm",confirm,"dangerous",String.valueOf(dangerous),"enabled","true");
    }
    /** Private, structured facilitator checklist; no secret fields are added to publicView. */
    public synchronized Map<String,Object> workflow(UUID actor) {
        if(!st(actor))return Map.of();
        Map<String,Object> result=new LinkedHashMap<>();List<String> blockers=new ArrayList<>();Map<String,String> primary=Map.of();
        result.put("waitingFor",storyteller.toString());result.put("waitingKind","storyteller");
        String title,summary;int completed=0,total=0;
        if(!started) {
            title="准备检查";total=players.size();completed=roles.size();
            if(roles.size()!=players.size())blockers.add("还有 "+(players.size()-roles.size())+" 个座位未分配角色。");
            if(!Arrays.equals(expectedCounts(),actualCounts()))blockers.add("角色类别配比不符合人数及男爵修正。");
            if(redHerring!=null&&evil(redHerring))blockers.add("红鲱鱼必须是善良玩家。");
            summary=blockers.isEmpty()?"配比合法。开始后发放表面身份并进入首夜；真实酒鬼和红鲱鱼继续保密。":"先自动配牌，或在配置页调整尚未满足的项目。";
            primary=blockers.isEmpty()?workflowAction("发牌并开始首夜","start","","","setup","确认发放身份并开始本局首夜？",false):workflowAction("自动合法配牌","autoassign","","","setup","重新生成全部角色和准备标记？尚未向玩家发放身份。",false);
        } else if(finished) {
            title="本局结束";summary=(winner.equals("good")?"善良":"邪恶")+"阵营获胜；可导出完整记录或授权本桌玩家复盘。";
            primary=workflowAction("准备下一局","reset","","","review","保留完整回顾，清理本局身份、标记和手机聊天，准备下一局？",true);
        } else {
            String victory=specialVictory,reason=specialVictoryReason;
            if(victory.isEmpty()&&players.stream().noneMatch(p->"imp".equals(roles.get(p))&&!dead.contains(p))){victory="good";reason="当前没有存活恶魔；请先核对转生裁定。";}
            if(victory.isEmpty()&&aliveCount()<=2){victory="evil";reason="存活人数至多两人，且存在存活恶魔。";}
            if(!victory.isEmpty()) {
                title="待确认胜负";summary=reason;primary=workflowAction("确认"+(victory.equals("good")?"善良":"邪恶")+"获胜","finish",victory,"","finish","确认结束本局并公布"+(victory.equals("good")?"善良":"邪恶")+"阵营获胜？",true);
            } else if(dayDecision!=null) {title="白天角色裁定";summary=suggestion(actor);blockers.add("先选择本次登记裁定，再继续提名或阶段。");result.put("requiresChoice",true);}
            else if(phase.equals("day")) {
                if(activeVote!=null) {
                    title="第 "+dayNumber+" 天 · 提名投票";total=players.size();completed=activeVote.ballots.size();summary=seat(activeVote.nominee)+" 被提名，门槛 "+activeVote.threshold+" 票；已回应 "+completed+" / "+total+"，未回应默认不支持。";
                    if(!automationOnline)blockers.add("有本桌成员离线，等待重连。");
                    if(completed<total){result.put("waitingKind","votes");}
                    primary=workflowAction("确认计票","closevote","","","day",completed==total?"确认核算全部席位的最终票？":"确认计票？未回应按不支持，已支持的幽灵票将消耗。",true);
                } else if(onBlock!=null) {title="第 "+dayNumber+" 天 · 等待处决";summary=seat(onBlock)+" 为唯一最高票候选（"+bestVotes+"票）。可继续合法提名，讨论结束再处决。";primary=workflowAction("处决 "+seat(onBlock)+" 并结束白天","execute","","","day","确认处决 "+seat(onBlock)+"？该操作结束今天并检查胜负。",true);}
                else {title="第 "+dayNumber+" 天 · 公开讨论";summary="存活玩家可从手机提名。无唯一最高票时，结束讨论可不处决进入夜晚。";primary=workflowAction("结束白天，无人处决","phase","night","","day","确认结束白天且无人被处决？先核对三人镇长条件。",false);}
            } else {
                total=(int)tasks.stream().filter(t->!t.skipped).count();completed=(int)tasks.stream().filter(t->t.resolved).count();Task t=currentTask();
                if(t==null) {title="第 "+nightNumber+" 夜 · 已完成";summary="本夜适用步骤已完成。天亮将统一公布死亡，隐藏身份和标记继续保密。";primary=workflowAction("天亮，进入白天","phase","day","","night","确认公布本夜死亡并开始第 "+nightNumber+" 天？",false);}
                else {RoleCatalog.Role role=RoleCatalog.get(t.role);title=(nightNumber==1?"首夜":"第 "+nightNumber+" 夜")+" · "+(role==null?t.role.equals("minion_info")?"爪牙互认":"恶魔信息":role.name());summary=suggestion(actor);result.put("actor",t.actor.toString());result.put("taskRole",t.role);result.put("requiresText",false);
                    if(t.targetCount>0&&!t.submitted){String waitReason="等待 "+seat(t.actor)+" 私密选择 "+t.targetCount+" 名玩家，不能代选。";blockers.add(waitReason);result.put("waitingFor",t.actor.toString());result.put("waitingKind","player_choice");Map<String,String> waiting=new LinkedHashMap<>(workflowAction("等待玩家选择后确认本步","resolve","","","night","",false));waiting.put("enabled","false");waiting.put("reason",waitReason);primary=waiting;}
                    else if(INFORMATION_ROLES.contains(t.role)){
                        String draft=informationDraft(t),warning=informationWarning(t);result.put("informationDraft",draft);result.put("informationWarning",warning);
                        result.put("requiresText",impaired(t.actor)||summary.contains("待裁定"));
                        primary=workflowAction("确认发送信息","resolve","",t.role.equals("spy")&&!impaired(t.actor)?"":draft,"night","确认仅向 "+seat(t.actor)+" 发送当前信息？",false);
                    }
                    else if(!decisionChoices(actor).isEmpty()&&(t.role.equals("imp")||impaired(t.actor)||summary.contains("待裁定"))){result.put("requiresChoice",true);}
                    else primary=workflowAction("确认本步，私发结果并推进","resolve","","","night","确认按当前建议结算，并只向当前玩家发送结果？",t.role.equals("imp"));
                }
            }
        }
        result.put("title",title);result.put("summary",summary);result.put("blockers",List.copyOf(blockers));result.put("canAdvance",!primary.isEmpty()&&!"false".equals(primary.get("enabled"))||Boolean.TRUE.equals(result.get("requiresChoice")));result.put("primaryAction",primary);result.put("completed",completed);result.put("total",total);return result;
    }
    public synchronized String slay(UUID actor,UUID target) {
        if(!active()||!phase.equals("day"))return "猎手声明只能在白天提出。";
        if(!players.contains(actor)||!players.contains(target)||dead.contains(actor))return "声明者必须是本局存活玩家，目标必须属于本局。";
        if(activeVote!=null||dayDecision!=null)return "请先完成当前投票或裁定。";
        if("slayer".equals(shown.get(actor))&&used.contains(actor))return "你的猎手技能已经使用。";
        announce(seat(actor)+" 宣称猎手能力，选择 "+seat(target)+"。");
        boolean genuine="slayer".equals(roles.get(actor))&&!used.contains(actor)&&healthy(actor);
        if("slayer".equals(shown.get(actor)))used.add(actor);
        dayDecision=new DayDecision("slayer",actor,target,genuine,++dayRequestSequence);
        log("slayer_request",actor,target,"宣称已提交，等待说书人确认；实际能力有效 "+genuine+"。",true);
        return "公开声明已记录，等待说书人确认结果；真实身份继续保密。";
    }
    private boolean slayerCanKill(DayDecision d) {
        return d.effective&&healthy(d.actor)&&!dead.contains(d.target)&&("imp".equals(roles.get(d.target))||("recluse".equals(roles.get(d.target))&&healthy(d.target)));
    }
    private boolean slayerRegistration(DayDecision d) {
        return slayerCanKill(d)&&"recluse".equals(roles.get(d.target));
    }
    private String resolveDay(String override) {
        DayDecision d=dayDecision;
        if(d.kind.equals("virgin")) {
            if(override.equals("execute")){dayDecision=null;return execution(d.actor);}
            if(override.equals("continue")){dayDecision=null;openVote(d.target);return "相关判定已确认，开始普通投票，所有座位可同时回应。";}
            return "请输入 execute 或 continue 裁定贞洁者与间谍。";
        }
        boolean canKill=slayerCanKill(d), registration=slayerRegistration(d);
        if(override.isBlank())override=canKill&&!registration?"kill":!canKill?"none":"";
        if(override.equals("kill")&&canKill){die(d.target);announce(seat(d.target)+" 死亡。 ");dayDecision=null;return "猎手相关判定已确认。";}
        if(override.equals("none")&&(!canKill||registration)){announce("无事发生。");dayDecision=null;return "猎手相关判定已确认。";}
        return registration?"请确认陌客登记：kill 或 none。":canKill?"本次有效命中恶魔，请确认目标死亡。":"本次宣称无事发生，请确认。";
    }

    private Map<String,Object> spySnapshot() {
        List<Map<String,Object>> seats=new ArrayList<>();
        for(Map<String,Object> original:grimoire()){
            Map<String,Object> row=new LinkedHashMap<>(original);UUID player=UUID.fromString((String)row.get("uuid"));
            row.put("alignment",evil(player)?"evil":"good");
            row.put("ghostVote",dead.contains(player)&&!spentGhost.contains(player));
            row.put("note",notes.getOrDefault(player,""));
            seats.add(Map.copyOf(row));
        }
        return Map.of("seats",List.copyOf(seats),"night",nightNumber,"phase",phase,
                "master",master==null?"":master.toString(),"demonBluffs",List.copyOf(demonBluffs));
    }
    private List<Map<String,Object>> grimoire() {
        List<Map<String,Object>> list=new ArrayList<>();
        for(UUID p:players){Map<String,Object> row=new LinkedHashMap<>();row.put("uuid",p.toString());row.put("seat",seatNumber(p));row.put("role",roles.getOrDefault(p,""));row.put("shownRole",shown.getOrDefault(p,""));row.put("dead",dead.contains(p));row.put("poisoned",poisoned.contains(p)||effectivePoison(p));row.put("drunk",drunk.contains(p)||"drunk".equals(roles.get(p)));row.put("used",used.contains(p));row.put("redHerring",p.equals(redHerring));row.put("safe",effectiveSafe(p));list.add(row);}
        return list;
    }
    private Map<String,List<Map<String,String>>> seatActions() {
        if(finished)return Map.of();Map<String,List<Map<String,String>>> result=new LinkedHashMap<>();
        for(UUID p:players) {
            List<Map<String,String>> actions=new ArrayList<>();String id=p.toString();
            actions.add(workflowAction(poisoned.contains(p)?"清除手动中毒":"添加手动中毒","mark",id,"poisoned "+!poisoned.contains(p),"config","修改 "+seat(p)+" 的手动中毒标记？投毒者当前能力另由夜间步骤管理。",false));
            actions.add(workflowAction(drunk.contains(p)?"清除手动醉酒":"添加手动醉酒","mark",id,"drunk "+!drunk.contains(p),"config","修改 "+seat(p)+" 的手动醉酒标记？真实酒鬼身份不会因此改变。",false));
            if(!started) {
                if(roles.containsKey(p)&&!evil(p))actions.add(workflowAction(p.equals(redHerring)?"移除红鲱鱼":"固定为红鲱鱼","mark",id,"red_herring "+!p.equals(redHerring),"config","确认修改开局前固定的善良红鲱鱼？开局后不能移动。",false));
            } else {
                actions.add(workflowAction(used.contains(p)?"恢复一次性能力标记":"标记能力已使用","mark",id,"used "+!used.contains(p),"config","确认修改 "+seat(p)+" 的一次性能力已用标记？",true));
                if(activeVote==null)actions.add(workflowAction(dead.contains(p)?"纠正为存活":"手动标记死亡","mark",id,"dead "+!dead.contains(p),"config",dead.contains(p)?"仅用于主持纠错。确认让 "+seat(p)+" 恢复存活并重置其幽灵票与一次性能力标记？":"仅用于主持纠错。确认 "+seat(p)+" 死亡并执行相关规则检查？",true));
                if(!dead.contains(p)&&RoleCatalog.get(roles.get(p)).type().equals("minion")&&players.stream().noneMatch(q->"imp".equals(roles.get(q))&&!dead.contains(q)))actions.add(workflowAction("主持纠错：转为小恶魔","mark",id,"imp true","config","确认将此存活爪牙转为小恶魔？仅用于需人工确认的转生纠错。",true));
            }
            result.put(id,List.copyOf(actions));
        }
        return result;
    }
    public synchronized Map<String,Object> publicView() {
        Map<String,Object> view=new LinkedHashMap<>();view.put("game","clock");view.put("script","trouble_brewing");view.put("phase",phase);view.put("started",started);view.put("finished",finished);view.put("winner",winner);view.put("night",nightNumber);view.put("day",dayNumber);view.put("storyteller",storyteller.toString());view.put("reviewEnabled",reviewEnabled);view.put("voteTimeoutSeconds",voteTimeoutSeconds);
        List<Map<String,Object>> seats=new ArrayList<>();for(UUID p:players){Map<String,Object> row=new LinkedHashMap<>();row.put("uuid",p.toString());row.put("seat",seatNumber(p));row.put("dead",publicDead.contains(p));row.put("ghostVote",publicDead.contains(p)&&!spentGhost.contains(p));seats.add(row);}view.put("seats",seats);
        view.put("onBlock",onBlock==null?"":onBlock.toString());view.put("bestVotes",bestVotes);view.put("announcements",List.copyOf(announcements));
        if(activeVote!=null){Map<String,Object> voting=new LinkedHashMap<>();voting.put("nominee",activeVote.nominee.toString());voting.put("threshold",activeVote.threshold);voting.put("simultaneous",true);voting.put("nextVoter","");voting.put("counted",activeVote.ballots.size());voting.put("order",activeVote.order.stream().map(UUID::toString).toList());voting.put("deadline",activeVote.deadline);voting.put("remainingTicks",activeVote.remainingTicks);voting.put("timeoutSeconds",voteTimeoutSeconds);voting.put("paused",!automationOnline);view.put("vote",voting);}
        return view;
    }
    /** Only each recipient's sleep bit is sent by the presentation layer. */
    public synchronized boolean isNight() {return active()&&(phase.equals("first_night")||phase.equals("night"));}
    public synchronized UUID awakePlayer() {Task task=isNight()?currentTask():null;return task==null?null:task.actor;}
    public synchronized Map<String,Object> privateView(UUID viewer) {
        Map<String,Object> view=new LinkedHashMap<>(publicView());
        if(st(viewer)) {
            view.put("grimoire",grimoire());view.put("notes",notes.entrySet().stream().map(e->Map.of("uuid",e.getKey().toString(),"text",e.getValue())).toList());view.put("nightTasks",nightTasks());view.put("suggestion",suggestion(viewer));view.put("adjudications",List.copyOf(adjudications));view.put("master",master==null?"":master.toString());
            view.put("demonBluffs",demonBluffs);view.put("nextStep",suggestion(viewer));
            Task informationTask=currentTask();view.put("informationDraft",informationDraft(informationTask));view.put("informationWarning",informationWarning(informationTask));
            view.put("workflow",workflow(viewer));view.put("decisionChoices",decisionChoices(viewer));view.put("seatActions",seatActions());
            if(!started)view.put("setupCounts",Map.of("order",List.of("镇民","外来者","爪牙","恶魔"),"expected",Arrays.stream(expectedCounts()).boxed().toList(),"actual",Arrays.stream(actualCounts()).boxed().toList()));
        } else if(players.contains(viewer)) {
            String id=started?shown.getOrDefault(viewer,""):"";RoleCatalog.Role r=RoleCatalog.get(id);view.put("shownRole",id);view.put("roleName",r==null?"等待发牌":r.name());view.put("ability",r==null?"":r.ability());view.put("messages",List.copyOf(messages.get(viewer)));
            Task t=active()?currentTask():null;if(t!=null&&t.actor.equals(viewer))view.put("pendingTask",taskView(t,false));
            if(spySnapshots.containsKey(viewer))view.put("spyGrimoire",spySnapshots.get(viewer));
            view.put("canNominate",automationOnline&&active()&&phase.equals("day")&&!dead.contains(viewer)&&!nominators.contains(viewer)&&activeVote==null&&dayDecision==null);
            view.put("canClaimSlayer",automationOnline&&active()&&phase.equals("day")&&!dead.contains(viewer)&&activeVote==null&&dayDecision==null&&(!"slayer".equals(shown.get(viewer))||!used.contains(viewer)));
        }
        if(activeVote!=null) {
            @SuppressWarnings("unchecked") Map<String,Object> voting=(Map<String,Object>)view.get("vote");
            if(st(viewer)){Map<String,Boolean> ballots=new LinkedHashMap<>();activeVote.ballots.forEach((id,value)->ballots.put(id.toString(),value));voting.put("ballots",ballots);voting.put("supportCount",activeVote.ballots.values().stream().filter(Boolean.TRUE::equals).count());voting.put("effectiveCount",pendingVoteCount());voting.put("voters",players.stream().map(id->{Map<String,Object> row=new LinkedHashMap<>();row.put("uuid",id.toString());row.put("seat",seatNumber(id));row.put("responded",activeVote.ballots.containsKey(id));row.put("support",Boolean.TRUE.equals(activeVote.ballots.get(id)));row.put("counts",countsVote(id));return row;}).toList());}
            else if(players.contains(viewer)){boolean responded=activeVote.ballots.containsKey(viewer);voting.put("hasVoted",responded);voting.put("myVote",activeVote.ballots.get(viewer));voting.put("mayVote",automationOnline);voting.put("maySupport",!dead.contains(viewer)||!spentGhost.contains(viewer));}
        }
        if(canReview(viewer)){view.put("flowLog",List.copyOf(flowLog));if(!st(viewer))view.put("reviewGrimoire",grimoire());}
        return view;
    }
    /** A read-only preview: ghost votes are consumed only by the confirmed tally. */
    private boolean countsVote(UUID player){return activeVote!=null&&Boolean.TRUE.equals(activeVote.ballots.get(player))&&!("butler".equals(roles.get(player))&&healthy(player)&&!Boolean.TRUE.equals(activeVote.ballots.get(master)));}
    private long pendingVoteCount(){return activeVote.ballots.keySet().stream().filter(this::countsVote).count();}
}
