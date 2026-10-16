package local.tabletop.game;

import java.util.*;

/** User's manual Storyteller confirmations, including the Drunk's apparent actions. */
public final class ClockDayConfirmationTest {
    private static final UUID ST=new UUID(8,999);
    private static UUID p(int n){return new UUID(8,n+1);}
    private static int checks;
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;}
    private static ClockGame game(String... roles){var ids=new ArrayList<UUID>();for(int i=0;i<roles.length;i++)ids.add(p(i));var g=new ClockGame(ST,ids);for(int i=0;i<roles.length;i++){String[] r=roles[i].split(":");check(g.assign(ST,p(i),r[0],r.length>1?r[1]:r[0]).startsWith("已分配"),"assignment "+roles[i]);}check(g.start(ST).startsWith("首夜"),"start");return g;}
    private static void drain(ClockGame g,boolean first){int guard=0;while(g.physicalTask()!=null){check(++guard<50,"task loop");var t=g.physicalTask();if(t.targetCount()>0&&!t.submitted()){var targets=g.players().stream().filter(id->!id.equals(t.actor())).filter(id->!t.role().equals("poisoner")||id.equals(p(1))).limit(t.targetCount()).toList();check(g.submit(t.actor(),targets).startsWith("回复已"),"submit");}String result=g.resolve(ST);if(!ClockGame.acceptedResponse("resolve",result))result=g.resolve(ST,"说书人选择的假信息");check(ClockGame.acceptedResponse("resolve",result),"resolve "+t.role()+": "+result);}if(first)check(g.phase(ST,"day").startsWith("已进入"),"dawn");}
    public static void run(){
        ClockGame healthy=game("slayer","chef","empath","poisoner","imp");drain(healthy,true);
        check(healthy.slay(p(0),p(4)).contains("等待说书人"),"Slayer request waits");check(healthy.isAlive(p(4)),"Demon alive before confirmation");
        check(healthy.decisionChoices(ST).size()==1&&healthy.decisionChoices(ST).getFirst().get("id").endsWith(":kill"),"only valid kill offered");
        check(!ClockGame.acceptedResponse("resolve",healthy.resolve(ST,"none")),"cannot suppress healthy Demon hit");check(!ClockGame.acceptedResponse("resolve",healthy.resolve(p(0),"kill")),"player cannot approve own hit");
        check(healthy.resolve(ST,"kill").contains("已确认")&&!healthy.isAlive(p(4)),"ST confirms Demon death");check(healthy.slay(p(0),p(2)).contains("已经使用"),"once per game");
        for(boolean poisoned:List.of(false,true)){
            var g=game("slayer","chef","empath","poisoner","imp");drain(g,true);if(poisoned)g.mark(ST,p(0),"poisoned",true);
            UUID target=poisoned?p(4):p(2);g.slay(p(0),target);check(g.isAlive(target),"no effect before ST");check(g.decisionChoices(ST).size()==1&&g.decisionChoices(ST).getFirst().get("id").endsWith(":none"),"no effect choice only");check(!ClockGame.acceptedResponse("resolve",g.resolve(ST,"kill")),"illegal kill denied");check(g.resolve(ST).contains("已确认")&&g.isAlive(target),"confirm no effect");check(g.slay(p(0),target).contains("已经使用"),"bad/miss consumes one use");
        }
        var fake=game("drunk:slayer","chef","empath","soldier","poisoner","imp");drain(fake,true);
        check(fake.privateView(p(0)).get("shownRole").equals("slayer")&&fake.privateView(p(0)).get("roleName").equals("猎手"),"Drunk only sees apparent identity");
        fake.slay(p(0),p(5));check(fake.isAlive(p(5)),"Drunk hit no premature death");check(fake.resolve(ST).contains("已确认")&&fake.isAlive(p(5)),"Drunk Slayer does not kill");check(fake.slay(p(0),p(5)).contains("已经使用"),"Drunk Slayer appears spent");
        var virgin=game("drunk:virgin","chef","empath","soldier","poisoner","imp");drain(virgin,true);virgin.nominate(p(2),p(0));check(virgin.phase().equals("day")&&virgin.isAlive(p(2))&&virgin.publicView().containsKey("vote"),"Drunk Virgin fails but normal vote proceeds");
        var monk=game("drunk:monk","chef","empath","soldier","poisoner","imp");drain(monk,true);monk.phase(ST,"night");while(!monk.physicalTask().role().equals("monk")){var t=monk.physicalTask();monk.submit(t.actor(),List.of(p(1)));monk.resolve(ST);}monk.submit(p(0),List.of(p(2)));monk.resolve(ST);check(!monk.demonProtected(p(2)),"Drunk Monk's choice has no protection");
        var information=game("drunk:chef","washerwoman","empath","soldier","poisoner","imp");int guard=0;while(!information.physicalTask().role().equals("chef")){check(++guard<20,"reach Drunk Chef");var t=information.physicalTask();if(t.targetCount()>0)information.submit(t.actor(),List.of(p(1)));String result=information.resolve(ST);if(!ClockGame.acceptedResponse("resolve",result))information.resolve(ST,"说书人的最终信息");}
        check(!ClockGame.acceptedResponse("resolve",information.resolve(ST)),"Drunk information requires explicit final text");check(information.physicalTask().actor().equals(p(0)),"Drunk has apparent night step");check(!information.privateView(p(0)).containsKey("suggestion")&&!information.privateView(p(0)).containsKey("grimoire"),"no true ST information leaked");information.resolve(ST,"相邻邪恶：4对");check(information.privateView(p(0)).get("messages").toString().contains("4对"),"ST fake information delivered privately");
        var repeated=game("chef","soldier","empath","spy","imp");drain(repeated,true);
        check(ClockGame.acceptedResponse("slay",repeated.slay(p(0),p(4))),"first bluff request accepted");String previous=repeated.decisionChoices(ST).getFirst().get("id");
        check(ClockGame.acceptedResponse("resolve",repeated.decide(ST,previous,null))&&repeated.isAlive(p(4)),"first bluff confirmed without killing Demon");
        check(ClockGame.acceptedResponse("slay",repeated.slay(p(0),p(4))),"same actor may repeat the same bluff target");String current=repeated.decisionChoices(ST).getFirst().get("id");
        check(!current.equals(previous),"each repeated daytime request has a distinct confirmation key");
        check(!ClockGame.acceptedResponse("resolve",repeated.decide(ST,previous,null)),"previous claim's confirmation cannot settle a new claim");
        check(repeated.decisionChoices(ST).getFirst().get("id").equals(current)&&!Boolean.TRUE.equals(repeated.privateView(p(1)).get("canNominate")),"stale confirmation leaves new claim pending");
        check(ClockGame.acceptedResponse("resolve",repeated.decide(ST,current,null))&&Boolean.TRUE.equals(repeated.privateView(p(1)).get("canNominate")),"fresh confirmation settles only the current claim");
        System.out.println("CLOCK_DAY_CONFIRMATION_CHECKS="+checks);
    }
}
