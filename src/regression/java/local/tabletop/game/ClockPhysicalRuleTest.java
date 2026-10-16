package local.tabletop.game;

import java.util.*;

/** Rules that the Minecraft room, weapon and respawn presentation must preserve. */
public final class ClockPhysicalRuleTest {
    private static final UUID ST=new UUID(0,80000);
    private static int checks;
    private static UUID p(int seat) { return new UUID(0,81000+seat); }
    private static void check(boolean condition,String label) {
        checks++;
        if(!condition)throw new AssertionError("physical rules: "+label);
    }
    private static List<UUID> players(int count) {
        List<UUID> result=new ArrayList<>();for(int i=0;i<count;i++)result.add(p(i));return result;
    }
    private static ClockGame game(String... roles) {
        ClockGame game=new ClockGame(ST,players(roles.length));
        for(int i=0;i<roles.length;i++)check(game.assign(ST,p(i),roles[i],roles[i]).startsWith("已分配"),"assign "+roles[i]);
        return game;
    }
    private static void resolveOther(ClockGame game,UUID poison,UUID protection) {
        ClockGame.PhysicalTask task=game.physicalTask();
        check(task!=null&&!task.role().equals("imp"),"only resolve non-Demon helper steps");
        if(task.targetCount()>0&&!task.submitted()) {
            List<UUID> targets;
            if(task.role().equals("poisoner")&&poison!=null)targets=List.of(poison);
            else if(task.role().equals("monk")&&protection!=null)targets=List.of(protection);
            else targets=game.players().stream().filter(id->!Set.of("monk","butler").contains(task.role())||!id.equals(task.actor())).limit(task.targetCount()).toList();
            check(game.submit(task.actor(),targets).startsWith("回复已"),"submit "+task.role());
        }
        String result=game.resolve(ST);
        if(!result.startsWith("步骤已"))result=game.resolve(ST,"说书人确认的私密信息");
        check(result.startsWith("步骤已"),"confirm "+task.role()+": "+result);
    }
    private static void firstNight(ClockGame game) {
        check(game.start(ST).startsWith("首夜"),"legal setup starts");
        int guard=0;
        while(game.physicalTask()!=null) {
            check(++guard<40,"first-night queue terminates");
            check(!game.physicalTask().role().equals("imp"),"first night has no physical Demon kill");
            resolveOther(game,null,null);
        }
        check(game.nightDeathIds().isEmpty(),"first night has no rule death");
        check(game.phase(ST,"day").startsWith("已进入白天"),"first dawn");
    }
    private static void toImp(ClockGame game,UUID poison,UUID protection) {
        check(game.phase(ST,"night").startsWith("白天无人"),"next night");
        int guard=0;
        while(game.physicalTask()!=null&&!game.physicalTask().role().equals("imp")) {
            check(++guard<40,"pre-Demon queue terminates");resolveOther(game,poison,protection);
        }
        check(game.physicalTask()!=null&&game.physicalTask().role().equals("imp"),"Demon's current step");
    }
    private static ClockGame successionGame() {
        ClockGame game=game("chef","empath","washerwoman","slayer","soldier","monk","mayor","scarlet_woman","poisoner","imp");
        firstNight(game);return game;
    }
    private static void suicide(ClockGame game,UUID demon) {
        ClockGame.PhysicalTask task=game.physicalTask();
        check(task.actor().equals(demon),"current Demon owns suicide action");
        check(game.submit(demon,List.of(demon)).startsWith("回复已"),"suicide submits self as target");
        check(game.isAlive(demon)&&game.nightDeathIds().isEmpty(),"submission alone does not kill or pass");
        check(game.physicalTask().key().equals(task.key())&&game.physicalTask().submitted(),"submitted target retains current step key");
        check(!game.impEntryPossible(demon,demon),"suicide is not room entry");
        check(game.resolve(demon).contains("只有说书人"),"Demon cannot confirm its own death");
        game.updateAutomation(50000,true);
        check(game.isAlive(demon)&&game.physicalTask().submitted(),"ticks cannot auto-confirm suicide");
        check(!ClockGame.acceptedResponse("submit",game.submit(demon,List.of(p(0)))),"suicide locks the only target");
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> publicSeat(ClockGame game,UUID player) {
        return ((List<Map<String,Object>>)game.publicView().get("seats")).stream().filter(row->row.get("uuid").equals(player.toString())).findFirst().orElseThrow();
    }
    private static void finishOtherNightSteps(ClockGame game) {
        int guard=0;
        while(game.physicalTask()!=null) {
            check(++guard<40,"remaining queue terminates");
            check(!game.physicalTask().role().equals("imp"),"no second Demon attack in the same night");
            resolveOther(game,null,null);
        }
    }
    private static void populationAndReadOnlyTask() {
        check(new ClockGame(ST,players(5)).players().size()==5,"minimum five accepted");
        ClockGame maximum=new ClockGame(ST,players(12));
        check(maximum.players().size()==12&&!maximum.players().contains(ST),"twelve players plus separate Storyteller accepted");
        boolean rejected=false;try{new ClockGame(ST,players(13));}catch(IllegalArgumentException expected){rejected=true;}
        check(rejected,"thirteenth role player rejected");
        check(maximum.physicalTask()==null&&!maximum.isAlive(ST)&&maximum.trueRole(ST).isEmpty(),"no task in preparation and Storyteller has no role");
        ClockGame game=game("monk","soldier","chef","empath","slayer","poisoner","imp");firstNight(game);
        toImp(game,p(1),p(2));UUID demon=p(6),victim=p(3);
        ClockGame.PhysicalTask before=game.physicalTask();
        check(before.current()&&!before.resolved()&&!before.submitted()&&before.targetCount()==1,"only unresolved current step snapshot");
        check(!game.impEntryPossible(demon,victim),"unsubmitted room entry rejected");
        check(game.submit(demon,List.of(victim)).startsWith("回复已"),"ordinary kill target submitted");
        ClockGame.PhysicalTask submitted=game.physicalTask();
        check(submitted.key().equals(before.key())&&submitted.targets().equals(List.of(victim)),"target remains scoped to same current step");
        check(game.impEntryPossible(demon,victim)&&!game.impEntryPossible(p(5),victim)&&!game.impEntryPossible(demon,p(4)),"entry only for exact actor and locked target");
        boolean immutable=false;try{submitted.targets().add(p(4));}catch(UnsupportedOperationException expected){immutable=true;}
        check(immutable,"task target list cannot mutate rule state");
        check(game.isAlive(victim)&&game.nightDeathIds().isEmpty(),"entry eligibility does not resolve death");
        check(game.resolve(ST).startsWith("步骤已")&&!game.isAlive(victim),"Storyteller confirmation applies one rule death");
        check(game.nightDeathIds().equals(Set.of(victim))&&game.executedDeathIds().isEmpty(),"night death is distinct from execution");
        check(Boolean.FALSE.equals(publicSeat(game,victim).get("dead")),"night death not public before dawn");
        immutable=false;try{game.nightDeathIds().clear();}catch(UnsupportedOperationException expected){immutable=true;}
        check(immutable&&game.nightDeathIds().contains(victim),"death query cannot alter rule state");
        finishOtherNightSteps(game);check(game.phase(ST,"day").startsWith("已进入白天"),"dawn after completed night");
        check(Boolean.TRUE.equals(publicSeat(game,victim).get("dead"))&&game.nightDeathIds().contains(victim),"dawn publishes death and preserves last night summary");
        toImp(game,p(1),p(2));check(game.nightDeathIds().isEmpty()&&!game.physicalTask().key().equals(before.key()),"new night clears death summary and invalidates previous task key");
        game.finish(ST,"good");check(game.physicalTask()==null&&!game.impEntryPossible(demon,p(4)),"settlement invalidates current physical task");
        check(!game.publicView().containsKey("physicalTask")&&!game.privateView(victim).containsKey("nightDeathIds"),"internal physical queries not added to player payload");
    }
    private static void succession() {
        ClockGame forced=successionGame();for(int i=0;i<5;i++)forced.mark(ST,p(i),"dead",true);
        toImp(forced,p(0),p(0));suicide(forced,p(9));
        check(forced.decisionChoices(ST).size()==1&&forced.decisionChoices(ST).getFirst().get("target").equals(p(7).toString()),"five alive before death forces healthy Scarlet Woman");
        check(!ClockGame.acceptedResponse("resolve",forced.resolve(ST,"pass:"+p(8)))&&forced.isAlive(p(9)),"cannot choose another living Minion when Scarlet Woman is forced");
        check(forced.resolve(ST,"pass:"+p(7)).startsWith("步骤已")&&!forced.isAlive(p(9))&&forced.trueRole(p(7)).equals("imp"),"confirmed forced succession at five alive");
        check(forced.nightDeathIds().equals(Set.of(p(9))),"only previous Demon died in succession");finishOtherNightSteps(forced);
        check(forced.privateView(p(7)).get("messages").toString().contains("现在是小恶魔"),"night successor privately notified immediately");

        for(UUID successor:List.of(p(7),p(8))) {
            ClockGame four=successionGame();for(int i=0;i<6;i++)four.mark(ST,p(i),"dead",true);
            toImp(four,p(0),null);suicide(four,p(9));
            check(four.decisionChoices(ST).size()==2,"four alive allows both living Minions");
            check(four.resolve(ST,"pass:"+successor).startsWith("步骤已")&&four.trueRole(successor).equals("imp"),"four alive may pass to Scarlet Woman or another Minion");finishOtherNightSteps(four);
        }
        for(String state:List.of("poisoned","drunk")) {
            ClockGame impaired=successionGame();impaired.mark(ST,p(7),state,true);toImp(impaired,p(0),p(0));suicide(impaired,p(9));
            check(impaired.decisionChoices(ST).size()==2,"impaired Scarlet Woman not forced: "+state);
            check(impaired.resolve(ST,"pass:"+p(7)).startsWith("步骤已")&&impaired.trueRole(p(7)).equals("imp")&&impaired.impairedPlayer(p(7)),"living impaired Scarlet Woman is legal recipient and remains impaired: "+state);
            finishOtherNightSteps(impaired);
        }
        ClockGame deadWoman=successionGame();deadWoman.mark(ST,p(7),"dead",true);toImp(deadWoman,p(0),p(0));suicide(deadWoman,p(9));
        check(deadWoman.decisionChoices(ST).size()==1&&deadWoman.decisionChoices(ST).getFirst().get("target").equals(p(8).toString()),"dead Scarlet Woman excluded from successor choices");
        check(!ClockGame.acceptedResponse("resolve",deadWoman.resolve(ST,"pass:"+p(7)))&&deadWoman.isAlive(p(9)),"dead Scarlet Woman cannot receive star pass");
        check(deadWoman.resolve(ST,"pass:"+p(8)).startsWith("步骤已")&&deadWoman.trueRole(p(8)).equals("imp"),"another living Minion succeeds");

        ClockGame none=game("chef","empath","soldier","poisoner","imp");firstNight(none);none.mark(ST,p(3),"dead",true);toImp(none,null,null);suicide(none,p(4));
        check(!ClockGame.acceptedResponse("resolve",none.resolve(ST)),"unconfirmed no-successor suicide still needs explicit Storyteller default");
        check(none.resolve(ST,"default").startsWith("步骤已")&&!none.isAlive(p(4)),"no living Minion leaves dead Demon without successor");
        check(none.players().stream().noneMatch(id->none.isAlive(id)&&none.trueRole(id).equals("imp"))&&!none.finished(),"victory remains Storyteller decision after no-successor death");
    }
    private static void ineffectiveSuicide() {
        for(String state:List.of("poisoned","drunk")) {
            ClockGame game=game("chef","empath","soldier","poisoner","imp");firstNight(game);
            if(state.equals("drunk"))game.mark(ST,p(4),state,true);
            toImp(game,state.equals("poisoned")?p(4):p(0),null);suicide(game,p(4));
            check(game.impairedPlayer(p(4)),"Demon impaired during its action: "+state);
            check(game.resolve(ST).startsWith("步骤已")&&game.isAlive(p(4))&&game.trueRole(p(3)).equals("poisoner")&&game.nightDeathIds().isEmpty(),"Storyteller confirms ineffective suicide without death or succession: "+state);
            check(!ClockGame.acceptedResponse("submit",game.submit(p(4),List.of(p(1)))),"ineffective suicide cannot be replaced by second kill");
            check(!game.privateView(p(4)).containsKey("impaired")&&!game.privateView(p(4)).get("messages").toString().contains("中毒"),"ineffective action message does not identify impairment");
            finishOtherNightSteps(game);
        }
        ClockGame protectedImp=game("monk","soldier","chef","empath","slayer","poisoner","imp");firstNight(protectedImp);toImp(protectedImp,p(2),p(6));
        check(protectedImp.demonProtected(p(6)),"Monk may protect the Demon");suicide(protectedImp,p(6));
        check(protectedImp.resolve(ST).startsWith("步骤已")&&protectedImp.isAlive(p(6))&&protectedImp.trueRole(p(5)).equals("poisoner")&&protectedImp.nightDeathIds().isEmpty(),"protected Demon suicide fails without succession only after Storyteller confirms");
        finishOtherNightSteps(protectedImp);protectedImp.phase(ST,"day");check(!protectedImp.demonProtected(p(6)),"Monk protection ends at dawn");
    }
    private static void mayorNoDeath() {
        for(String destination:List.of("dead","soldier","protected")) {
            ClockGame game=game("mayor","soldier","monk","chef","empath","poisoner","imp");firstNight(game);
            UUID redirect=destination.equals("dead")?p(3):destination.equals("soldier")?p(1):p(4);
            if(destination.equals("dead"))game.mark(ST,redirect,"dead",true);
            toImp(game,p(3),p(4));check(game.submit(p(6),List.of(p(0))).startsWith("回复已"),"Demon selects Mayor");
            check(game.resolve(ST).contains("明确裁定")&&game.isAlive(p(0)),"Mayor target remains alive awaiting Storyteller");
            check(game.resolve(ST,"kill:"+redirect).startsWith("步骤已")&&game.isAlive(p(0))&&game.nightDeathIds().isEmpty(),"Mayor redirect to "+destination+" causes no new death");
            finishOtherNightSteps(game);check(game.nightDeathIds().isEmpty(),"peaceful night determined after every applicable step");
        }
    }
    private static Map<String,String> decision(ClockGame game,String code,UUID target) {
        return game.decisionChoices(ST).stream().filter(c->c.get("id").endsWith(":"+code)&&c.get("target").equals(target==null?"":target.toString())).findFirst().orElseThrow();
    }
    private static String lockMayorDemon(ClockGame game,UUID mayor,UUID demon) {
        check(game.submit(demon,List.of(mayor)).startsWith("回复已"),"Mayor target submitted before redirect");
        String taskKey=game.physicalTask().key();Map<String,String> redirect=decision(game,"mayor_redirect",demon);
        check(!ClockGame.acceptedResponse("resolve",game.decide(demon,redirect.get("id"),demon)),"Demon cannot authorize Mayor redirect");
        check(ClockGame.acceptedResponse("resolve",game.decide(ST,redirect.get("id"),demon)),"Storyteller locks Mayor-to-Demon redirect");
        check(game.physicalTask()!=null&&game.physicalTask().actor().equals(demon)&&game.physicalTask().key().equals(taskKey)&&game.physicalTask().targets().equals(List.of(mayor)),"pending succession preserves physical task actor key and locked original choice");
        check(game.isAlive(mayor)&&game.isAlive(demon)&&game.nightDeathIds().isEmpty(),"redirect alone does not commit death before successor decision");
        check(!game.impEntryPossible(demon,mayor),"locked redirect cannot be re-approved as ordinary Mayor room entry");
        check(!Objects.equals(((Map<?,?>)game.workflow(ST).get("primaryAction")).get("action"),"finish")&&Boolean.TRUE.equals(game.workflow(ST).get("requiresChoice")),"pending succession blocks premature good-win workflow");
        check(!ClockGame.acceptedResponse("resolve",game.decide(ST,redirect.get("id"),demon)),"old Mayor choice cannot be replayed during successor step");
        check(!ClockGame.acceptedResponse("resolve",game.resolve(ST,"kill:"+p(0))),"pending redirect cannot change victim with another Mayor override");
        check(!ClockGame.acceptedResponse("phase",game.phase(ST,"day")),"pending succession prevents dawn");
        check(!ClockGame.acceptedResponse("submit",game.submit(demon,List.of(demon))),"Demon cannot replace locked Mayor choice during succession");
        check(!game.privateView(demon).get("messages").toString().contains("镇长替死"),"redirect reason is not disclosed to Demon");
        return taskKey;
    }
    private static void confirmedMayorPass(ClockGame game,UUID demon,UUID successor) {
        Map<String,String> pass=decision(game,"imp_pass",successor);
        check(!ClockGame.acceptedResponse("resolve",game.resolve(ST))&&game.isAlive(demon),"living successor requires explicit Storyteller selection");
        check(ClockGame.acceptedResponse("resolve",game.decide(ST,pass.get("id"),successor)),"Storyteller confirms lawful redirected-attack succession");
        check(!game.isAlive(demon)&&game.isAlive(successor)&&game.trueRole(successor).equals("imp"),"redirected Demon death and succession commit together");
        check(game.nightDeathIds().equals(Set.of(demon))&&game.executedDeathIds().isEmpty(),"only redirected Demon receives night death, not execution");
        check(!ClockGame.acceptedResponse("resolve",game.decide(ST,pass.get("id"),successor)),"confirmed succession cannot replay");
        check(game.privateView(successor).get("messages").toString().contains("现在是小恶魔"),"redirect successor is immediately notified privately");
        finishOtherNightSteps(game);
    }
    private static void mayorDemonSuccession() {
        ClockGame five=successionGame();for(int i=0;i<5;i++)five.mark(ST,p(i),"dead",true);
        toImp(five,p(0),p(0));lockMayorDemon(five,p(6),p(9));
        check(five.decisionChoices(ST).size()==1&&five.decisionChoices(ST).getFirst().get("target").equals(p(7).toString()),"Mayor bounce at five alive forces healthy Scarlet Woman");
        check(!ClockGame.acceptedResponse("resolve",five.resolve(ST,"pass:"+p(8)))&&five.isAlive(p(9)),"another Minion cannot bypass forced Scarlet succession");
        confirmedMayorPass(five,p(9),p(7));check(five.isAlive(p(6)),"Mayor survives redirected Demon death");
        check(five.phase(ST,"day").startsWith("已进入"),"dawn after redirected succession");toImp(five,p(0),p(0));
        check(five.physicalTask().actor().equals(p(7)),"successor first acts on following night");

        for(UUID successor:List.of(p(7),p(8))) {
            ClockGame four=successionGame();for(int i=0;i<6;i++)four.mark(ST,p(i),"dead",true);
            toImp(four,p(0),null);lockMayorDemon(four,p(6),p(9));
            check(four.decisionChoices(ST).size()==2,"Mayor bounce at four alive offers both living Minions");confirmedMayorPass(four,p(9),successor);
        }
        for(String state:List.of("poisoned","drunk"))for(UUID successor:List.of(p(7),p(8))) {
            ClockGame impaired=successionGame();impaired.mark(ST,p(7),state,true);toImp(impaired,p(0),p(0));lockMayorDemon(impaired,p(6),p(9));
            check(impaired.decisionChoices(ST).size()==2,"impaired Scarlet not forced on Mayor bounce: "+state);
            confirmedMayorPass(impaired,p(9),successor);
            if(successor.equals(p(7)))check(impaired.impairedPlayer(successor),"successor retains personal impairment: "+state);
        }
        ClockGame deadWoman=successionGame();deadWoman.mark(ST,p(7),"dead",true);toImp(deadWoman,p(0),p(0));lockMayorDemon(deadWoman,p(6),p(9));
        check(deadWoman.decisionChoices(ST).size()==1&&!ClockGame.acceptedResponse("resolve",deadWoman.resolve(ST,"pass:"+p(7))),"dead Scarlet excluded from redirected succession");confirmedMayorPass(deadWoman,p(9),p(8));

        ClockGame none=game("mayor","chef","soldier","spy","imp");firstNight(none);none.mark(ST,p(3),"dead",true);toImp(none,null,null);lockMayorDemon(none,p(0),p(4));
        check(none.decisionChoices(ST).size()==1&&none.decisionChoices(ST).getFirst().get("id").endsWith(":imp_default"),"no Minion offers explicit no-successor confirmation");
        check(!ClockGame.acceptedResponse("resolve",none.resolve(ST))&&none.isAlive(p(4)),"no-successor redirect still requires explicit confirmation");
        Map<String,String> noSuccessor=decision(none,"imp_default",null);check(ClockGame.acceptedResponse("resolve",none.decide(ST,noSuccessor.get("id"),null))&&!none.isAlive(p(4)),"Storyteller commits redirected death without successor");
        check(((Map<?,?>)none.workflow(ST).get("primaryAction")).get("target").equals("good")&&!none.finished(),"good-win confirmation appears only after no-successor decision");

        ClockGame protectedImp=successionGame();toImp(protectedImp,p(0),p(9));protectedImp.submit(p(9),List.of(p(6)));
        Map<String,String> protectedRedirect=decision(protectedImp,"mayor_redirect",p(9));check(ClockGame.acceptedResponse("resolve",protectedImp.decide(ST,protectedRedirect.get("id"),p(9)))&&protectedImp.isAlive(p(9))&&protectedImp.nightDeathIds().isEmpty(),"Mayor redirect to Monk-protected Imp consumes action without death or succession");finishOtherNightSteps(protectedImp);
        for(String state:List.of("poisoned","drunk")) {
            ClockGame badImp=successionGame();badImp.mark(ST,p(9),state,true);toImp(badImp,p(0),p(0));badImp.submit(p(9),List.of(p(6)));
            check(badImp.decisionChoices(ST).isEmpty()&&ClockGame.acceptedResponse("resolve",badImp.resolve(ST,"kill:"+p(9)))&&badImp.isAlive(p(9))&&badImp.isAlive(p(6))&&badImp.nightDeathIds().isEmpty(),"impaired Imp cannot invoke Mayor redirect or succession: "+state);finishOtherNightSteps(badImp);
        }
    }
    private static void storytellerCancellation() {
        ClockGame game=game("monk","soldier","chef","empath","slayer","poisoner","imp");firstNight(game);toImp(game,p(1),p(2));
        String key=game.physicalTask().key();
        check(!ClockGame.acceptedResponse("resolve",game.cancelPhysicalImp(ST,key)),"unsubmitted action cannot be canceled as a completed choice");
        game.submit(p(6),List.of(p(3)));
        check(!ClockGame.acceptedResponse("resolve",game.cancelPhysicalImp(p(6),key))&&game.impEntryPossible(p(6),p(3)),"Demon cannot impersonate Storyteller cancellation");
        check(!ClockGame.acceptedResponse("resolve",game.cancelPhysicalImp(ST,"previous-game:"+key))&&game.impEntryPossible(p(6),p(3)),"stale cancellation key cannot consume current action");
        check(ClockGame.acceptedResponse("resolve",game.cancelPhysicalImp(ST,key))&&game.isAlive(p(3))&&game.nightDeathIds().isEmpty(),"explicit exact-key Storyteller cancellation completes action without death");
        check(!ClockGame.acceptedResponse("submit",game.submit(p(6),List.of(p(4))))&&!ClockGame.acceptedResponse("resolve",game.cancelPhysicalImp(ST,key)),"cancel consumes choice and cannot replay on later step");
        check(game.privateView(p(6)).get("messages").toString().contains("已结束你的本次行动")&&!game.privateView(p(0)).get("messages").toString().contains("已结束你的本次行动"),"cancellation acknowledgement is private to action owner");
        finishOtherNightSteps(game);game.phase(ST,"day");toImp(game,p(1),p(2));game.submit(p(6),List.of(p(4)));
        check(!ClockGame.acceptedResponse("resolve",game.cancelPhysicalImp(ST,key))&&game.impEntryPossible(p(6),p(4)),"previous-night cancellation cannot affect a newly submitted attack");
    }
    private static void ghostAndExecution() {
        ClockGame ghost=game("chef","empath","soldier","poisoner","imp");firstNight(ghost);ghost.mark(ST,p(0),"dead",true);
        check(ghost.nominate(p(1),p(0)).startsWith("投票按"),"first ghost ballot");
        for(UUID player:List.of(p(1),p(2),p(3),p(4),p(0)))ghost.vote(player,false);
        check(ghost.closeVote(ST).startsWith("计票完成：0 票")&&Boolean.TRUE.equals(publicSeat(ghost,p(0)).get("ghostVote")),"final abstention preserves ghost vote");
        check(ghost.nominate(p(2),p(1)).startsWith("投票按"),"second ghost ballot");
        for(UUID player:List.of(p(2),p(3),p(4),p(0),p(1)))ghost.vote(player,player.equals(p(0)));
        check(Boolean.TRUE.equals(publicSeat(ghost,p(0)).get("ghostVote")),"submitted yes waits for final count");
        check(ghost.closeVote(ST).startsWith("计票完成：1 票")&&Boolean.FALSE.equals(publicSeat(ghost,p(0)).get("ghostVote")),"final counted yes consumes ghost vote");

        ClockGame execution=game("chef","empath","soldier","poisoner","imp");firstNight(execution);execution.nominate(p(0),p(1));
        for(UUID player:List.of(p(2),p(3),p(4),p(0),p(1)))execution.vote(player,true);
        execution.closeVote(ST);check(execution.isAlive(p(1))&&execution.executedDeathIds().isEmpty(),"votes and tally alone do not execute");
        check(execution.execute(p(0)).contains("只有说书人")&&execution.isAlive(p(1)),"player cannot confirm execution");
        check(execution.execute(ST).startsWith("处决完成")&&!execution.isAlive(p(1))&&execution.executedDeathIds().equals(Set.of(p(1)))&&execution.nightDeathIds().isEmpty(),"confirmed execution is tracked separately from night deaths");
        execution.mark(ST,p(1),"dead",false);check(execution.isAlive(p(1))&&execution.executedDeathIds().isEmpty(),"explicit Storyteller revival correction clears execution tracking");
        ClockGame virgin=game("virgin","chef","soldier","spy","imp");firstNight(virgin);virgin.nominate(p(1),p(0));
        check(virgin.executedDeathIds().equals(Set.of(p(1))),"Virgin-induced execution has same execution presentation identity");
        ClockGame slayer=game("slayer","chef","soldier","spy","imp");firstNight(slayer);slayer.slay(p(0),p(4));
        check(slayer.isAlive(p(4)),"Slayer awaits Storyteller confirmation");slayer.resolve(ST,"kill");
        check(!slayer.isAlive(p(4))&&slayer.executedDeathIds().isEmpty(),"Slayer death is not an execution");
    }
    public static void run() {
        populationAndReadOnlyTask();succession();ineffectiveSuicide();mayorNoDeath();mayorDemonSuccession();storytellerCancellation();ghostAndExecution();
        System.out.println("CLOCK_PHYSICAL_RULE_CHECKS="+checks);
    }
    private ClockPhysicalRuleTest() {}
}
