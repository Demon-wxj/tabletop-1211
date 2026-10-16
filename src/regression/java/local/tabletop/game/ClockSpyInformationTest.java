package local.tabletop.game;

import java.util.*;

/** Spy information stays private, confirmed, and frozen at its capture time. */
public final class ClockSpyInformationTest {
    private static final UUID ST=new UUID(7,100);
    private static int checks;
    private static UUID p(int n){return new UUID(7,n+1);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
    private static ClockGame game(){
        String[] roles={"monk","slayer","fortune_teller","chef","soldier","drunk:washerwoman","butler","spy","imp"};
        ClockGame game=new ClockGame(ST,java.util.stream.IntStream.range(0,9).mapToObj(ClockSpyInformationTest::p).toList());
        for(int n=0;n<roles.length;n++){String[] pair=roles[n].split(":");check(game.assign(ST,p(n),pair[0],pair.length==2?pair[1]:pair[0]).startsWith("已分配"),"assign test roles");}
        check(game.mark(ST,p(4),"red_herring",true).contains("已更新"),"fix good red herring");
        check(game.start(ST).startsWith("首夜"),"start valid nine-player setup");return game;
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> snapshot(ClockGame game){return (Map<String,Object>)game.privateView(p(7)).get("spyGrimoire");}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> seats(Map<String,Object> snapshot){return (List<Map<String,Object>>)snapshot.get("seats");}
    private static Map<String,Object> row(Map<String,Object> snapshot,int n){return seats(snapshot).stream().filter(row->row.get("uuid").equals(p(n).toString())).findFirst().orElseThrow();}
    private static void toSpy(ClockGame game){
        int guard=0;
        while(game.physicalTask()!=null&&!game.physicalTask().role().equals("spy")){
            check(++guard<30,"reach Spy step");var task=game.physicalTask();
            if(task.targetCount()>0&&!task.submitted()){
                List<UUID> targets=task.role().equals("monk")||task.role().equals("butler")?List.of(p(4)):
                        task.role().equals("imp")?List.of(p(4)):List.of(p(0),p(4)).subList(0,task.targetCount());
                check(game.submit(task.actor(),targets).startsWith("回复已"),"submit queued target");
            }
            String result=game.resolve(ST);if(!result.startsWith("步骤已"))result=game.resolve(ST,"说书人核定信息");
            check(result.startsWith("步骤已"),"confirm step before Spy");
        }
        check(game.physicalTask()!=null&&game.physicalTask().role().equals("spy"),"Spy has current night step");
    }
    private static void immutable(Runnable mutation){try{mutation.run();}catch(UnsupportedOperationException expected){checks++;return;}throw new AssertionError("Spy snapshot was mutable");}
    public static void run(){
        checks=0;ClockGame game=game();
        game.mark(ST,p(2),"drunk",true);game.mark(ST,p(4),"poisoned",true);game.mark(ST,p(3),"dead",true);
        game.mark(ST,p(1),"used",true);game.mark(ST,p(4),"note:核对标记",true);
        toSpy(game);
        check(snapshot(game)==null,"no complete information before Storyteller confirmation");
        check(game.resolve(p(7)).contains("只有说书人"),"Spy cannot authorize own information");
        check(snapshot(game)==null,"invalid confirmation reveals nothing");
        check(game.resolve(ST).startsWith("步骤已"),"Storyteller confirms healthy Spy");
        Map<String,Object> first=snapshot(game);check(first!=null&&seats(first).size()==9,"confirmed snapshot includes every role seat");
        check(row(first,5).get("role").equals("drunk")&&row(first,5).get("shownRole").equals("washerwoman"),"true Drunk and fake Townsfolk visible together");
        check(row(first,5).get("drunk").equals(true),"true Drunk has intoxication status");
        check(row(first,3).get("dead").equals(true)&&row(first,3).get("ghostVote").equals(true),"dead player and unspent ghost vote captured");
        check(row(first,4).get("poisoned").equals(true)&&row(first,4).get("redHerring").equals(true),"poison and red herring captured");
        check(row(first,2).get("drunk").equals(true)&&row(first,1).get("used").equals(true),"manual intoxication and spent ability captured");
        check(row(first,4).get("note").equals("核对标记"),"grimoire reminder captured");
        check(first.get("master").equals(p(4).toString()),"Butler master captured after confirmation");
        check(row(first,7).get("alignment").equals("evil")&&row(first,5).get("alignment").equals("good"),"actual alignment independent from registration");
        for(int n=0;n<9;n++)if(n!=7)check(!game.privateView(p(n)).containsKey("spyGrimoire"),"other roles receive no Spy snapshot");
        check(!game.publicView().containsKey("spyGrimoire")&&!game.publicView().toString().contains("shownRole"),"public state contains no private grimoire");
        immutable(()->first.put("night",99));immutable(()->seats(first).clear());immutable(()->row(first,4).put("poisoned",false));
        game.mark(ST,p(4),"poisoned",false);game.mark(ST,p(4),"note:修改后的标记",true);
        check(row(first,4).get("poisoned").equals(true)&&row(first,4).get("note").equals("核对标记"),"later state changes do not rewrite delivered memory");
        check(game.phase(ST,"day").startsWith("已进入"),"dawn after first Spy result");game.mark(ST,p(2),"drunk",false);
        check(game.phase(ST,"night").contains("已进入"),"begin second night");toSpy(game);check(game.resolve(ST).startsWith("步骤已"),"second healthy confirmation");
        Map<String,Object> second=snapshot(game);check(second.get("night").equals(2)&&!second.equals(first),"next confirmed night replaces frozen snapshot");
        check(row(second,4).get("safe").equals(true)&&row(second,4).get("poisoned").equals(false),"confirmed Monk protection and current poison captured");
        check(row(second,4).get("dead").equals(false),"protected target survives Demon");

        for(String impairment:List.of("drunk","poisoned")){
            ClockGame impaired=game();impaired.mark(ST,p(7),impairment,true);toSpy(impaired);
            check(impaired.resolve(ST).contains("填写最终"),"impaired Spy requires explicit substitute");
            check(snapshot(impaired)==null,"impaired Spy cannot receive true grimoire");
            check(impaired.resolve(ST,"说书人提供的替代信息").startsWith("步骤已"),"confirm substituted information");
            check(snapshot(impaired)==null,"substitution leaves no actual snapshot");
        }
        ClockGame dead=game();dead.mark(ST,p(7),"dead",true);
        int guard=0;while(dead.physicalTask()!=null){check(++guard<30,"dead Spy night finishes");var task=dead.physicalTask();check(!task.role().equals("spy"),"dead Spy not woken for new ability");
            if(task.targetCount()>0)dead.submit(task.actor(),List.of(p(0),p(4)).subList(0,task.targetCount()));
            String result=dead.resolve(ST);if(!result.startsWith("步骤已"))result=dead.resolve(ST,"说书人核定信息");check(result.startsWith("步骤已"),"resolve remaining living steps");}
        check(snapshot(dead)==null,"dead Spy receives no fresh full information");
        System.out.println("CLOCK_SPY_INFORMATION_CHECKS="+checks);
    }
}
