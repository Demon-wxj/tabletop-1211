package local.tabletop.game;

import java.util.*;

/** Out-of-order ballots, live private preview and apparent-role choice privacy. */
public final class ClockSimultaneousVoteTest {
    private static final UUID ST=new UUID(0,81000);
    private static int checks;
    private static UUID p(int n){return new UUID(0,82000+n);}
    private static void check(boolean ok,String text){checks++;if(!ok)throw new AssertionError("simultaneous: "+text);}
    private static ClockGame game(String... ids){List<UUID> people=new ArrayList<>();for(int n=0;n<ids.length;n++)people.add(p(n));ClockGame game=new ClockGame(ST,people);for(int n=0;n<ids.length;n++){String[] pair=ids[n].split(":");check(game.assign(ST,p(n),pair[0],pair.length>1?pair[1]:pair[0]).startsWith("已分配"),"assign "+ids[n]);}check(game.start(ST).startsWith("首夜"),"start");return game;}
    private static void dawn(ClockGame game){int guard=0;while(game.physicalTask()!=null){check(++guard<40,"night terminates");var task=game.physicalTask();if(task.targetCount()>0){List<UUID> targets=game.players().stream().filter(id->!((task.role().equals("butler")||task.role().equals("monk"))&&id.equals(task.actor()))).limit(task.targetCount()).toList();check(game.submit(task.actor(),targets).startsWith("回复已"),"choose "+task.role());}String result=game.resolve(ST);if(!result.startsWith("步骤已"))result=game.resolve(ST,"说书人确认的私密信息");check(result.startsWith("步骤已"),"confirm "+task.role());}check(game.phase(ST,"day").startsWith("已进入"),"dawn");}
    @SuppressWarnings("unchecked") private static Map<String,Object> vote(ClockGame game,UUID viewer){return (Map<String,Object>)game.privateView(viewer).get("vote");}
    private static boolean ghost(ClockGame game,UUID id){return ((List<?>)game.publicView().get("seats")).stream().anyMatch(row->row instanceof Map<?,?> map&&id.toString().equals(map.get("uuid"))&&Boolean.TRUE.equals(map.get("ghostVote")));}
    public static void run(){
        check(ClockGame.acceptedResponse("resolve","房门已打开，等待夜刃命中。"),"physical door approval is a successful intermediate command acknowledgement");
        check(!ClockGame.acceptedResponse("resolve","已允许进入，等待夜刃命中；或取消本次行动。"),"repeated physical approval is still rejected");
        ClockGame game=game("chef","empath","washerwoman","undertaker","soldier","butler","scarlet_woman","imp");dawn(game);
        game.mark(ST,p(4),"dead",true);check(game.nominate(p(1),p(2)).startsWith("投票按同轮"),"simultaneous nomination");
        for(UUID id:game.players())check(Boolean.TRUE.equals(vote(game,id).get("mayVote")),"all seats immediately eligible");
        check(game.vote(p(5),true).startsWith("已举手"),"Butler may respond before master or next seat");
        check(((Number)vote(game,ST).get("supportCount")).intValue()==1&&((Number)vote(game,ST).get("effectiveCount")).intValue()==0,"Butler preview waits for master support");
        check(game.vote(p(7),true).startsWith("已举手")&&game.vote(p(4),true).startsWith("已举手"),"last seat and ghost can vote before earlier unclicked seats");
        check(ghost(game,p(4)),"preview does not spend ghost vote");
        check(game.vote(p(0),true).startsWith("已举手"),"master can support later");
        check(((Number)vote(game,ST).get("effectiveCount")).intValue()==4,"master later enables already submitted Butler vote");
        List<?> voters=(List<?>)vote(game,ST).get("voters");check(voters.size()==8&&voters.stream().anyMatch(row->row instanceof Map<?,?> m&&m.get("seat").equals(6)&&Boolean.TRUE.equals(m.get("counts"))),"private grimoire carries exact seat number and counted ballot");
        for(UUID id:game.players())check(!vote(game,id).containsKey("voters")&&!vote(game,id).containsKey("ballots")&&!vote(game,id).containsKey("effectiveCount"),"individual pending ballots and Butler effectiveness stay private");
        check(game.unvote(p(0)).startsWith("已记录")&&((Number)vote(game,ST).get("effectiveCount")).intValue()==2,"master change updates Butler preview");
        check(game.vote(p(0),true).startsWith("已举手")&&game.closeVote(ST).startsWith("计票完成：4 票"),"confirmed final tally matches live preview");
        check(!ghost(game,p(4))&&game.publicView().get("onBlock").equals(p(2).toString()),"ghost spent once only on tally and threshold candidate set");
        check(game.closeVote(ST).contains("没有待计票"),"no double tally");
        ClockGame fake=game("chef","empath","washerwoman","slayer","soldier","drunk:fortune_teller","poisoner","imp");
        while(!fake.physicalTask().actor().equals(p(5))){var t=fake.physicalTask();if(t.targetCount()>0)fake.submit(t.actor(),List.of(p(0)));String result=fake.resolve(ST);if(!result.startsWith("步骤已"))fake.resolve(ST,"说书人确认的私密信息");}
        check(fake.submit(p(5),List.of(p(1),p(7))).startsWith("回复已"),"Drunk apparent ability chooses two targets");
        Map<String,Object> task=fake.nightTasks().stream().filter(t->Boolean.TRUE.equals(t.get("current"))).findFirst().orElseThrow();
        check(task.get("role").equals("fortune_teller")&&task.get("trueRole").equals("drunk")&&Boolean.TRUE.equals(task.get("fakeRole"))&&task.get("targets").equals(List.of(p(1).toString(),p(7).toString())),"grimoire receives false-role ability and exact player targets");
        Map<?,?> pending=(Map<?,?>)fake.privateView(p(5)).get("pendingTask");check(!pending.containsKey("trueRole")&&!pending.containsKey("fakeRole")&&!pending.containsKey("impaired"),"apparent actor never receives real Drunk flags");
        check(!fake.publicView().containsKey("nightTasks")&&!fake.privateView(p(1)).containsKey("nightTasks"),"other players cannot read target choices");
        System.out.println("CLOCK_SIMULTANEOUS_CHECKS="+checks);
    }
    private ClockSimultaneousVoteTest(){}
}
