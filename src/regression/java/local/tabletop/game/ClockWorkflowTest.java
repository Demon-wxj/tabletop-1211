package local.tabletop.game;

import com.google.gson.Gson;
import java.util.*;

/** Meaningful rule/permission checks for the structured Storyteller workflow. */
public final class ClockWorkflowTest {
    private static final UUID ST=new UUID(0,9000);
    private static int assertions;
    private static UUID p(int seat){return new UUID(0,1000+seat);}
    private static void check(boolean value,String label){assertions++;if(!value)throw new AssertionError("workflow: "+label);}
    private static ClockGame game(String... roles){List<UUID> players=new ArrayList<>();for(int i=0;i<roles.length;i++)players.add(p(i));ClockGame g=new ClockGame(ST,players);for(int i=0;i<roles.length;i++)check(g.assign(ST,p(i),roles[i],roles[i]).startsWith("已分配"),"assign "+roles[i]);return g;}
    private static Map<String,Object> task(ClockGame g){return g.nightTasks().stream().filter(t->Boolean.TRUE.equals(t.get("current"))).findFirst().orElse(null);}
    private static void firstNight(ClockGame g){check(g.start(ST).startsWith("首夜"),"start");while(task(g)!=null){var t=task(g);UUID actor=UUID.fromString((String)t.get("actor"));int count=(Integer)t.get("targetCount");if(count>0){String role=(String)t.get("role");List<UUID> targets=g.players().stream().filter(id->!((role.equals("monk")||role.equals("butler"))&&id.equals(actor))).limit(count).toList();check(g.submit(actor,targets).startsWith("回复已"),"first-night targets");}String result=g.resolve(ST);if(!result.startsWith("步骤已"))result=g.resolve(ST,"说书人确认的最终私密信息");check(result.startsWith("步骤已"),"first-night resolution");}check(g.phase(ST,"day").startsWith("已进入"),"dawn");}
    @SuppressWarnings("unchecked") private static Map<String,String> primary(ClockGame g){return (Map<String,String>)g.workflow(ST).get("primaryAction");}
    private static Map<String,String> choice(ClockGame g,String suffix,UUID target){return g.decisionChoices(ST).stream().filter(c->c.get("id").endsWith(":"+suffix)&&c.get("target").equals(target==null?"":target.toString())).findFirst().orElseThrow();}
    private static boolean privatelyDead(ClockGame g,UUID id){return ((List<?>)g.privateView(ST).get("grimoire")).stream().anyMatch(row->row instanceof Map<?,?> m&&m.get("uuid").equals(id.toString())&&Boolean.TRUE.equals(m.get("dead")));}
    public static void run(){
        ClockGame empty=new ClockGame(ST,List.of(p(0),p(1),p(2),p(3),p(4)));
        check(primary(empty).get("action").equals("autoassign")&&Boolean.TRUE.equals(empty.workflow(ST).get("canAdvance")),"incomplete setup offers valid auto-deal");
        check(empty.workflow(p(0)).isEmpty()&&empty.decisionChoices(p(0)).isEmpty(),"non-Storyteller cannot read checklist or choices");
        check(!empty.privateView(p(0)).containsKey("workflow")&&!empty.publicView().containsKey("seatActions"),"public/prepared participant payload does not leak checklist");
        check(!ClockGame.acceptedResponse("phase",empty.phase(ST,"day")),"blocked phase text is a rejected acknowledgement");
        for(String operation:List.of("phase","nominate","vote","unvote","closevote","execute","slay","mark","finish","submit","resolve"))check(!ClockGame.acceptedResponse(operation,"只有说书人可以操作。"),"permission rejection remains failure for "+operation);
        check(!ClockGame.acceptedResponse("unvote","你本轮没有待取消的支持票。")&&!ClockGame.acceptedResponse("resolve","玩家尚未回复，不能代替其选择目标。"),"no-op and unfinished selection are not successful feedback");
        ClockGame feedback=game("chef","empath","soldier","poisoner","imp");check(feedback.start(ST).startsWith("首夜"),"feedback case starts");
        check(Boolean.FALSE.equals(feedback.workflow(ST).get("canAdvance"))&&primary(feedback).get("enabled").equals("false")&&primary(feedback).get("reason").contains("私密选择")&&feedback.workflow(ST).get("waitingFor").equals(p(3).toString()),"waiting selection has disabled primary with accurate owner and reason");
        check(!ClockGame.acceptedResponse("submit",feedback.submit(p(0),List.of(p(1))))&&!ClockGame.acceptedResponse("resolve",feedback.resolve(ST)),"wrong actor submit and early confirmation rejected");
        check(ClockGame.acceptedResponse("submit",feedback.submit(p(3),List.of(p(1))))&&((Map<?,?>)feedback.privateView(p(3)).get("pendingTask")).get("status").equals("awaiting_confirmation"),"accepted selection waits for actual Storyteller confirmation");
        check(!ClockGame.acceptedResponse("submit",feedback.submit(p(3),List.of(p(2))))&&Boolean.TRUE.equals(feedback.workflow(ST).get("canAdvance")),"duplicate selection rejected while primary becomes available");
        check(ClockGame.acceptedResponse("resolve",feedback.resolve(ST)),"successful resolve acknowledged");
        ClockGame canceled=game("chef","empath","soldier","poisoner","imp");canceled.start(ST);check(ClockGame.acceptedResponse("finish",canceled.finish(ST,"good")),"actual settlement accepted");
        check(!ClockGame.acceptedResponse("submit",canceled.submit(p(3),List.of(p(1))))&&!canceled.privateView(p(3)).containsKey("pendingTask"),"settled game rejects pending nighttime choice and hides stale task");

        ClockGame mayor=game("mayor","soldier","empath","poisoner","imp");
        check(primary(mayor).get("action").equals("start"),"legal role bag offers first night");firstNight(mayor);mayor.phase(ST,"night");
        mayor.submit(p(3),List.of(p(2)));mayor.resolve(ST);mayor.submit(p(4),List.of(p(0)));
        check(Boolean.TRUE.equals(mayor.workflow(ST).get("requiresChoice"))&&mayor.decisionChoices(ST).size()==5,"Mayor offers default plus every other player");
        Map<String,String> redirect=choice(mayor,"mayor_redirect",p(1));
        check(mayor.decide(p(0),redirect.get("id"),p(1)).contains("只有说书人"),"player cannot submit private decision");
        check(mayor.decide(ST,redirect.get("id"),p(0)).contains("不合法"),"forged Mayor self-redirect rejected");
        check(mayor.decide(ST,redirect.get("id"),p(1)).startsWith("步骤已"),"click redirects to chosen seat");
        check(!privatelyDead(mayor,p(0))&&!privatelyDead(mayor,p(1)),"healthy Soldier consumes redirected attack without death");
        check(mayor.decide(ST,redirect.get("id"),p(1)).contains("已失效"),"replayed previous task decision rejected");
        Map<String,String> empathy=choice(mayor,"empath_0",null);
        check(mayor.decide(ST,empathy.get("id"),null).startsWith("步骤已"),"poisoned information character accepts deliberate numeric choice");
        check(mayor.privateView(p(2)).get("messages").toString().contains("0 名邪恶玩家")&&!mayor.privateView(p(2)).containsKey("decisionChoices"),"only intended information is delivered without poison or choice labels");
        check(primary(mayor).get("action").equals("phase")&&primary(mayor).get("target").equals("day"),"completed night offers dawn only");
        ClockGame fresh=game("mayor","soldier","empath","poisoner","imp");firstNight(fresh);fresh.phase(ST,"night");fresh.submit(p(3),List.of(p(2)));fresh.resolve(ST);fresh.submit(p(4),List.of(p(0)));
        check(fresh.decide(ST,redirect.get("id"),p(1)).contains("已失效"),"cached button from other game with identical UUIDs rejected");

        ClockGame pass=game("chef","empath","washerwoman","slayer","soldier","scarlet_woman","imp");firstNight(pass);pass.phase(ST,"night");pass.submit(p(6),List.of(p(6)));
        check(pass.decisionChoices(ST).size()==1&&pass.decisionChoices(ST).getFirst().get("target").equals(p(5).toString()),"healthy Scarlet Woman is forced at five-or-more before death");
        Map<String,String> successor=choice(pass,"imp_pass",p(5));check(pass.decide(ST,successor.get("id"),p(0)).contains("不合法"),"forged Townsfolk successor rejected");
        check(pass.decide(ST,successor.get("id"),p(5)).startsWith("步骤已"),"legal successor button applies transformation");
        check(pass.privateView(p(5)).get("shownRole").equals("imp")&&privatelyDead(pass,p(6)),"successor privately notified and previous Demon dead");
        check(pass.nightTasks().stream().noneMatch(t->Boolean.TRUE.equals(t.get("current"))&&t.get("role").equals("imp")),"successor does not kill twice in one night");
        check(!pass.publicView().containsKey("workflow")&&!pass.privateView(p(6)).containsKey("seatActions"),"night succession choices and editor actions stay private");

        ClockGame virgin=game("virgin","chef","empath","spy","imp");firstNight(virgin);virgin.nominate(p(3),p(0));
        check(virgin.decisionChoices(ST).size()==2&&virgin.workflow(ST).get("title").equals("白天角色裁定")&&Boolean.TRUE.equals(virgin.workflow(ST).get("requiresChoice"))&&Boolean.TRUE.equals(virgin.workflow(ST).get("canAdvance")),"Spy/Virgin exposes enabled explicit registration choices");
        check(Boolean.FALSE.equals(virgin.privateView(p(1)).get("canClaimSlayer")),"pending registration hides competing public Slayer claim");
        Map<String,String> ordinary=choice(virgin,"continue",null);check(virgin.decide(ST,ordinary.get("id"),null).contains("开始普通"),"registration choice continues ordinary vote");
        check(virgin.publicView().containsKey("vote")&&primary(virgin).get("action").equals("closevote"),"vote is reflected by phase-specific workflow");
        check(Boolean.FALSE.equals(virgin.privateView(p(1)).get("canClaimSlayer")),"active vote hides competing Slayer claim without revealing real roles");
        check(virgin.decide(ST,ordinary.get("id"),null).contains("已失效"),"completed daytime registration choice cannot repeat");
        check(ClockGame.acceptedResponse("closevote",virgin.closeVote(ST)),"successful count accepted");check(Boolean.TRUE.equals(virgin.privateView(p(1)).get("canClaimSlayer")),"ordinary live Townsfolk can bluff a public Slayer claim");virgin.finish(ST,"good");check(primary(virgin).get("action").equals("reset")&&((Map<?,?>)virgin.privateView(ST).get("seatActions")).isEmpty(),"finished game only offers fresh-game reset and no editor actions");
        List<UUID> maximum=new ArrayList<>();for(int i=0;i<12;i++)maximum.add(p(i));ClockGame full=new ClockGame(ST,maximum);check(full.autoAssign(ST,711).startsWith("已自动"),"maximum-player setup");
        String largest=new Gson().toJson(full.privateView(ST));check(largest.length()<32767,"maximum-player preparation state stays below protocol character limit before outer envelope");
        check(((Map<?,?>)full.privateView(ST).get("seatActions")).size()==12,"all maximum-player seat editors exist only in private view");
        System.out.println("CLOCK_WORKFLOW_V2_CHECKS="+assertions);
    }
    private ClockWorkflowTest(){}
}
