package local.tabletop.game;

import java.util.*;

/** Auto-written information is still private and needs an explicit ST send. */
public final class ClockInformationDraftTest {
    private static final UUID ST=new UUID(8,100);
    private static int checks;
    private static UUID p(int n){return new UUID(8,n+1);}
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;}
    private static ClockGame game(String... roles){
        ClockGame g=new ClockGame(ST,java.util.stream.IntStream.range(0,roles.length).mapToObj(ClockInformationDraftTest::p).toList());
        for(int n=0;n<roles.length;n++)check(g.assign(ST,p(n),roles[n],roles[n]).startsWith("已分配"),"assign draft scenario");
        check(g.start(ST).startsWith("首夜"),"start draft scenario");return g;
    }
    private static void toRole(ClockGame g,String role){int guard=0;while(g.physicalTask()!=null&&!g.physicalTask().role().equals(role)){
        check(++guard<20,"reach wanted information actor");var task=g.physicalTask();
        if(task.targetCount()>0)check(g.submit(task.actor(),List.of(p(1),p(2)).subList(0,task.targetCount())).startsWith("回复已"),"submit earlier task");
        String result=g.resolve(ST);if(!result.startsWith("步骤已"))result=g.resolve(ST,"主持确认的前序信息");check(result.startsWith("步骤已"),"confirm earlier task");}
        check(g.physicalTask()!=null&&g.physicalTask().role().equals(role),"requested information is current");}
    @SuppressWarnings("unchecked") private static Map<String,String> primary(ClockGame g){return (Map<String,String>)g.workflow(ST).get("primaryAction");}
    private static String draft(ClockGame g){return String.valueOf(g.privateView(ST).getOrDefault("informationDraft",""));}
    public static void run(){
        checks=0;
        for(String role:List.of("washerwoman","librarian","investigator")){
            ClockGame g=game(role,"chef","soldier","spy","imp");toRole(g,role);String first=draft(g);
            check(!first.isBlank(),"first-night information has a ready draft");
            for(int n=0;n<4;n++)check(draft(g).equals(first),"refresh preserves the same suggested pair and role");
            if(role.equals("librarian"))check(first.contains("没有外来者")||first.contains("无外来者"),"zero Outsiders automatically drafted");
            check(primary(g).get("action").equals("resolve")&&primary(g).get("text").equals(first),"main button already carries the text to confirm");
            check(g.physicalTask().role().equals(role),"reading draft does not advance the night");
            for(int n=0;n<5;n++)check(!g.privateView(p(n)).containsKey("informationDraft"),"draft never sent to ordinary role state");
            check(!g.publicView().containsKey("informationDraft"),"draft not public");
            check(g.resolve(p(0),first).contains("只有说书人"),"recipient cannot approve their own draft");
            check(g.resolve(ST,first).startsWith("步骤已"),"ST explicitly approves drafted information");
            check(g.privateView(p(0)).get("messages").toString().contains(first),"confirmed draft delivered to intended actor");
            for(int n=1;n<5;n++)check(!g.privateView(p(n)).get("messages").toString().contains(first),"confirmed draft not sent to other roles");
        }
        ClockGame fortune=game("fortune_teller","chef","soldier","spy","imp");toRole(fortune,"fortune_teller");
        check(draft(fortune).isBlank(),"target information is not drafted before the actor chooses");
        check(fortune.submit(p(0),List.of(p(1),p(4))).startsWith("回复已"),"Fortune Teller chooses Demon among two");
        String yes=draft(fortune);check(yes.contains("：是"),"Demon detection is drafted from submitted targets");
        check(primary(fortune).get("text").equals(yes),"targeted information uses ready main button");
        ClockGame spy=game("chef","soldier","slayer","spy","imp");toRole(spy,"spy");
        check(primary(spy).get("text").isBlank(),"healthy Spy does not replace the complete snapshot with a text override");
        check(spy.resolve(ST).startsWith("步骤已")&&spy.privateView(p(3)).containsKey("spyGrimoire"),"healthy confirmation sends actual grimoire");
        ClockGame poisoned=game("chef","soldier","slayer","spy","imp");poisoned.mark(ST,p(3),"poisoned",true);toRole(poisoned,"spy");
        String substitute=draft(poisoned);check(!substitute.isBlank(),"poisoned Spy has an automatic substitute draft");
        check(primary(poisoned).get("text").equals(substitute),"substitute still needs explicit confirmation");
        check(!poisoned.privateView(p(3)).containsKey("spyGrimoire"),"unconfirmed impaired Spy sees no actual snapshot");
        check(poisoned.resolve(ST).contains("填写最终"),"empty legacy impaired confirmation remains rejected");
        check(poisoned.resolve(ST,substitute).startsWith("步骤已"),"ST confirms substitute draft");
        check(!poisoned.privateView(p(3)).containsKey("spyGrimoire"),"substitute does not leak the true grimoire");
        System.out.println("CLOCK_INFORMATION_DRAFT_CHECKS="+checks);
    }
}
