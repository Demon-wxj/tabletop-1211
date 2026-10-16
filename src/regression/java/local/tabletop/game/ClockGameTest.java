package local.tabletop.game;

import java.util.*;

/** Executes with the project's plain-Java RegressionSuite; no game client or JUnit needed. */
public final class ClockGameTest {
    private static final UUID ST=UUID.fromString("00000000-0000-0000-0000-000000000100");
    private static UUID p(int n){return new UUID(0,n+1);}
    private static void check(boolean condition,String label){if(!condition)throw new AssertionError(label);}
    private static ClockGame game(String... ids) {
        List<UUID> people=new ArrayList<>();for(int i=0;i<ids.length;i++)people.add(p(i));
        ClockGame g=new ClockGame(ST,people);
        for(int i=0;i<ids.length;i++){String[] pair=ids[i].split(":");String assigned=g.assign(ST,p(i),pair[0],pair.length==2?pair[1]:pair[0]);check(assigned.startsWith("已分配"),"assign "+ids[i]+": "+assigned);}
        return g;
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> task(ClockGame g) {
        return g.nightTasks().stream().filter(m->Boolean.TRUE.equals(m.get("current"))).findFirst().orElse(null);
    }
    private static void firstNight(ClockGame g) {
        check(g.start(ST).startsWith("首夜"),"valid start");
        drain(g);check(g.phase(ST,"day").startsWith("已进入白天"),"dawn");
    }
    private static void drain(ClockGame g) {
        int guard=0;
        while(task(g)!=null) {
            check(++guard<60,"queue terminates");Map<String,Object> current=task(g);UUID actor=UUID.fromString((String)current.get("actor"));String id=(String)current.get("role");int count=(Integer)current.get("targetCount");
            if(count>0&&current.get("status").equals("awaiting_player")) {
                List<UUID> targets=g.players().stream().filter(x->!((id.equals("monk")||id.equals("butler")||id.equals("imp"))&&x.equals(actor))).limit(count).toList();
                check(g.submit(actor,targets).startsWith("回复已"),"night submit "+id);
            }
            String result=g.resolve(ST);
            if(!result.startsWith("步骤已")) {
                if(id.equals("imp"))result=g.resolve(ST,"default");
                else result=g.resolve(ST,"说书人核定的私密信息");
            }
            check(result.startsWith("步骤已"),"resolve "+id+": "+result);
        }
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> publicSeat(ClockGame g,UUID id) {
        return ((List<Map<String,Object>>)g.publicView().get("seats")).stream().filter(r->r.get("uuid").equals(id.toString())).findFirst().orElseThrow();
    }
    private static void ballot(ClockGame g,int yes) {
        @SuppressWarnings("unchecked") Map<String,Object> vote=(Map<String,Object>)g.publicView().get("vote");
        @SuppressWarnings("unchecked") List<String> order=(List<String>)vote.get("order");
        for(int i=0;i<order.size();i++)check(g.vote(UUID.fromString(order.get(i)),i<yes).startsWith(i<yes?"已举手":"已记录"),"sequential vote");
        check(g.closeVote(ST).startsWith("计票完成"),"close vote");
    }
    public static void run() {
        check(RoleCatalog.all().size()==22,"22 base characters");
        check(RoleCatalog.get("fortuneteller").id().equals("fortune_teller"),"aliases");
        check(RoleCatalog.get("slayer").name().equals("猎手"),"user-reference names");
        check(RoleCatalog.get("butler").otherNight()<RoleCatalog.get("undertaker").otherNight(),"electronic grimoire screenshot: Butler before Undertaker");
        int constructors=0;
        try{new ClockGame(ST,List.of(p(0)));}catch(IllegalArgumentException ex){constructors++;}
        try{new ClockGame(ST,List.of(p(0),p(1),p(2),p(3),p(3)));}catch(IllegalArgumentException ex){constructors++;}
        try{new ClockGame(ST,List.of(p(0),p(1),p(2),p(3),ST));}catch(IllegalArgumentException ex){constructors++;}
        check(constructors==3,"constructor validates players and host");
        int[][] expected={{3,0,1},{3,1,1},{5,0,1},{5,1,1},{5,2,1},{7,0,2},{7,1,2},{7,2,2}};
        List<String> town=RoleCatalog.all().stream().filter(r->r.type().equals("townsfolk")).map(RoleCatalog.Role::id).toList();
        List<String> outsiders=List.of("butler","saint");List<String> minions=List.of("poisoner","spy","scarlet_woman");
        boolean sawBaron=false,sawDrunk=false,sawFortune=false;
        for(int n=5;n<=12;n++)for(int seed=0;seed<64;seed++) {
            List<UUID> seats=new ArrayList<>();for(int i=0;i<n;i++)seats.add(p(i));ClockGame automatic=new ClockGame(ST,seats);
            check(automatic.autoAssign(p(0),seed).contains("只有说书人"),"auto-deal authority");
            check(automatic.setupAssignments(p(0)).isEmpty(),"setup snapshot authority");
            check(automatic.autoAssign(ST,seed).startsWith("已自动"),"auto-deal "+n);
            Map<UUID,List<String>> dealt=automatic.setupAssignments(ST);List<String> actual=dealt.values().stream().map(pair->pair.get(0)).toList(),apparent=dealt.values().stream().map(pair->pair.get(1)).toList();
            check(dealt.size()==n&&new HashSet<>(actual).size()==n&&new HashSet<>(apparent).size()==n,"unique real and apparent identities");
            int[] count=new int[4];for(String id:actual)switch(RoleCatalog.get(id).type()){case "townsfolk"->count[0]++;case "outsider"->count[1]++;case "minion"->count[2]++;case "demon"->count[3]++;}
            int[] baseline=expected[n-5];boolean hasBaron=actual.contains("baron");sawBaron|=hasBaron;sawDrunk|=actual.contains("drunk");sawFortune|=actual.contains("fortune_teller");
            check(count[0]==baseline[0]-(hasBaron?2:0)&&count[1]==baseline[1]+(hasBaron?2:0)&&count[2]==baseline[2]&&count[3]==1,"official population with Baron");
            for(List<String> pair:dealt.values())if(pair.get(0).equals("drunk"))check(RoleCatalog.get(pair.get(1)).type().equals("townsfolk")&&!actual.contains(pair.get(1)),"Drunk's unused Townsfolk token");
            @SuppressWarnings("unchecked") List<String> bluffs=(List<String>)automatic.privateView(ST).get("demonBluffs");
            check(bluffs.size()==3&&new HashSet<>(bluffs).size()==3,"three unique fixed demon bluffs");
            for(String id:bluffs)check(!actual.contains(id)&&!apparent.contains(id)&&!List.of("minion","demon").contains(RoleCatalog.get(id).type()),"bluff is absent good role");
            @SuppressWarnings("unchecked") List<Map<String,Object>> grimoire=(List<Map<String,Object>>)automatic.privateView(ST).get("grimoire");
            List<Map<String,Object>> herring=grimoire.stream().filter(row->Boolean.TRUE.equals(row.get("redHerring"))).toList();
            check(herring.size()==(actual.contains("fortune_teller")?1:0),"one fixed red herring when needed");
            for(var row:herring)check(!List.of("minion","demon").contains(RoleCatalog.get((String)row.get("role")).type()),"red herring is good");
            check(automatic.privateView(p(0)).get("shownRole").equals("")&&!automatic.privateView(p(0)).containsKey("demonBluffs"),"no preparation identity or bluff leak");
            ClockGame repeated=new ClockGame(ST,seats);repeated.autoAssign(ST,seed);check(repeated.setupAssignments(ST).equals(dealt),"seeded setup reproducible");
            check(automatic.start(ST).startsWith("首夜"),"every generated setup starts");
            check(automatic.autoAssign(ST,seed+1).contains("开局后")&&automatic.setupAssignments(ST).equals(dealt),"running deal cannot change");
        }
        check(sawBaron&&sawDrunk&&sawFortune,"special setup cases exercised");
        for(int n=5;n<=12;n++) {
            int[] counts=expected[n-5];List<String> setup=new ArrayList<>(town.subList(0,counts[0]));setup.addAll(outsiders.subList(0,counts[1]));setup.addAll(minions.subList(0,counts[2]));setup.add("imp");
            ClockGame supported=game(setup.toArray(String[]::new));check(supported.start(ST).startsWith("首夜"),"supported player count "+n);
        }

        ClockGame invalid=game("chef","empath","washerwoman","butler","imp");
        check(invalid.start(p(0)).contains("只有说书人"),"start permission");
        check(invalid.start(ST).contains("无法开局")&&!invalid.started(),"invalid category count");
        check(!invalid.start(ST).contains("外来者"),"failed start does not disclose category counts to table");
        check(invalid.assign(ST,p(0),"imp","imp").contains("不可重复"),"duplicate actual role");
        check(invalid.assign(ST,p(0),"drunk","saint").contains("镇民"),"drunk apparent must be Townsfolk");
        check(invalid.assign(p(0),p(0),"chef","chef").contains("只有说书人"),"assign permission");

        ClockGame baron=game("chef","butler","drunk:empath","baron","imp");
        firstNight(baron);check(baron.started(),"Baron +2 outsiders works at five");
        check(baron.privateView(p(2)).get("shownRole").equals("empath"),"Drunk gets apparent identity");
        check(!baron.privateView(p(2)).toString().contains("drunk"),"Drunk true identity never in player payload");
        check(!baron.publicView().toString().contains("baron"),"Baron not public");
        check(baron.nightTasks().stream().noneMatch(t->t.get("role").equals("demon_info")||t.get("role").equals("minion_info")),"five/six have no evil mutual recognition");
        check(baron.mark(p(0),p(1),"poisoned",true).contains("只有说书人"),"state permission");
        check(baron.finish(p(0),"evil").contains("只有说书人"),"winner permission");
        check(baron.suggestion(p(0)).contains("只有说书人"),"suggestion permission");
        check(!baron.privateView(p(40)).containsKey("shownRole"),"outsider spectator cannot get a role");

        ClockGame privacy=game("chef","empath","washerwoman","undertaker","monk","drunk:fortune_teller","scarlet_woman","imp");
        check(privacy.start(ST).startsWith("首夜"),"eight-player drunk setup");
        check(privacy.phase(ST,"day").contains("仍有未完成"),"cannot skip night");
        while(!task(privacy).get("actor").equals(p(5).toString()))check(privacy.resolve(ST).startsWith("步骤已"),"advance to Drunk simulated FT");
        check(privacy.privateView(p(5)).containsKey("pendingTask"),"Drunk receives apparent role task");
        check(!privacy.privateView(p(0)).containsKey("pendingTask"),"other players cannot see whose turn");
        check(!privacy.publicView().containsKey("nightTasks"),"night queue private");
        check(!privacy.privateView(p(5)).toString().contains("impaired"),"no impairment flag in own pending task");
        check(privacy.submit(p(0),List.of(p(1),p(2))).contains("没有需要你"),"wrong actor reply rejected");
        check(privacy.submit(p(5),List.of(p(1),p(1))).contains("不同"),"distinct targets");
        check(privacy.submit(p(5),List.of(p(1),p(30))).contains("本局座位"),"foreign target rejected");
        check(privacy.submit(p(5),List.of(p(1),p(7))).startsWith("回复已"),"Drunk submits choice");
        check(privacy.submit(p(5),List.of(p(2),p(3))).contains("已回复"),"repeat reply rejected");
        check(privacy.resolve(ST).contains("填写最终私密信息"),"Drunk never gets raw computed information");
        check(privacy.resolve(ST,"所选两人中是否有恶魔：否。").startsWith("步骤已"),"ST supplies Drunk result");
        check(privacy.privateView(p(5)).get("messages").toString().contains("否"),"private result delivered");
        check(!privacy.privateView(p(0)).get("messages").toString().contains("所选两人"),"other player does not receive result");
        check(privacy.mark(ST,p(0),"dead",true).startsWith("魔典"),"ST night death");
        check(publicSeat(privacy,p(0)).get("dead").equals(false),"night death hidden before dawn");
        check(privacy.phase(ST,"day").startsWith("已进入白天"),"dawn after queue");
        check(publicSeat(privacy,p(0)).get("dead").equals(true),"dawn publishes death");

        ClockGame voting=game("chef","empath","washerwoman","undertaker","slayer","scarlet_woman","imp");
        firstNight(voting);
        check(voting.nominate(p(0),p(1)).startsWith("投票按"),"nomination");
        check(voting.vote(p(1),true).startsWith("已举手"),"nominee can vote immediately without waiting for earlier seats");
        check(voting.closeVote(p(0)).contains("只有说书人"),"manual tally remains Storyteller-only");
        ballot(voting,4);check(voting.publicView().get("onBlock").equals(p(1).toString()),"ceil 7/2 threshold");
        check(voting.closeVote(ST).contains("没有待计票"),"repeat tally rejected");
        check(voting.nominate(p(0),p(2)).contains("已使用"),"one nomination per day");
        check(voting.nominate(p(2),p(1)).contains("已经被提名"),"one nomination received per day");
        check(voting.nominate(p(2),p(3)).startsWith("投票按"),"second nomination");
        ballot(voting,4);check(voting.publicView().get("onBlock").equals(""),"highest tie nobody on block");
        check(voting.nominate(p(4),p(5)).startsWith("投票按"),"third nomination");
        ballot(voting,5);check(voting.publicView().get("onBlock").equals(p(5).toString()),"higher vote resolves tie");
        check(voting.phase(ST,"night").contains("待处决"),"must execute candidate");
        check(voting.execute(ST).startsWith("处决完成"),"execution");
        check(voting.execute(ST).contains("只能在白天"),"one execution per day");
        drain(voting);check(voting.phase(ST,"day").startsWith("已进入白天"),"next day");
        check(voting.nominate(p(2),p(1)).startsWith("投票按"),"next-day nominations reset");

        ClockGame ghost=game("chef","empath","washerwoman","poisoner","imp");firstNight(ghost);
        ghost.mark(ST,p(0),"dead",true);
        check(ghost.nominate(p(0),p(1)).contains("死亡玩家"),"dead cannot nominate");
        check(ghost.nominate(p(1),p(0)).startsWith("投票按"),"dead can be nominated");
        for(UUID id:List.of(p(1),p(2),p(3),p(4),p(0)))ghost.vote(id,id.equals(p(0)));
        ghost.closeVote(ST);check(publicSeat(ghost,p(0)).get("ghostVote").equals(false),"yes consumes ghost vote");
        ghost.nominate(p(2),p(1));
        ghost.vote(p(2),false);ghost.vote(p(3),false);ghost.vote(p(4),false);
        check(ghost.vote(p(0),true).contains("已使用"),"ghost yes only once");
        check(ghost.vote(p(0),false).startsWith("已记录"),"spent ghost can decline");ghost.vote(p(1),false);ghost.closeVote(ST);

        ClockGame timed=game("chef","empath","washerwoman","poisoner","imp");firstNight(timed);
        timed.updateAutomation(100,true);timed.nominate(p(0),p(1));
        check(timed.voteTimeoutSeconds()==45,"default ballot is 45 seconds");
        check(timed.updateAutomation(999,true)==null&&timed.publicView().containsKey("vote"),"not closed before deadline");
        check(timed.updateAutomation(1000,true)==null&&timed.publicView().containsKey("vote"),"advisory timer never closes ballot");
        check(timed.closeVote(ST).startsWith("计票完成：0 票"),"only storyteller closes unclicked ballots as no");
        check(!timed.publicView().containsKey("vote"),"timed ballot removed once");
        check(timed.setVoteTimeout(p(0),15).contains("只有说书人"),"deadline configuration restricted");
        check(timed.setVoteTimeout(ST,14).contains("15–300"),"deadline lower bound");
        check(timed.setVoteTimeout(ST,301).contains("15–300"),"deadline upper bound");
        check(timed.setVoteTimeout(ST,15).startsWith("投票时限已"),"15 seconds allowed");
        timed.nominate(p(2),p(3));timed.vote(p(4),true);
        check(timed.updateAutomation(1100,false)==null,"offline pauses ballot");
        @SuppressWarnings("unchecked") Map<String,Object> paused=(Map<String,Object>)timed.publicView().get("vote");
        check(paused.get("remainingTicks").equals(200L)&&paused.get("paused").equals(true),"remaining deadline frozen while offline");
        timed.updateAutomation(9000,false);timed.updateAutomation(9001,true);
        check(timed.updateAutomation(9200,true)==null,"reconnect preserves remaining time");
        check(timed.updateAutomation(9201,true)==null&&timed.publicView().containsKey("vote"),"elapsed advisory leaves ballot open");
        check(timed.vote(p(0),true).startsWith("已举手"),"player can vote after advisory expires");
        check(timed.closeVote(ST).startsWith("计票完成：2 票"),"storyteller counts late votes and defaults unclicked to no");
        check(timed.updateAutomation(9300,true)==null,"deadline cannot settle twice");

        ClockGame cancel=game("chef","empath","washerwoman","poisoner","imp");firstNight(cancel);cancel.mark(ST,p(0),"dead",true);
        cancel.nominate(p(2),p(1));for(UUID id:List.of(p(2),p(3),p(4)))cancel.vote(id,false);
        cancel.vote(p(0),true);
        check(publicSeat(cancel,p(0)).get("ghostVote").equals(true),"ghost not spent before tally");
        check(cancel.unvote(p(0)).startsWith("已记录"),"ghost can cancel pending yes");
        check(publicSeat(cancel,p(0)).get("ghostVote").equals(true),"canceled ghost remains available");
        @SuppressWarnings("unchecked") Map<String,Object> ownVote=(Map<String,Object>)cancel.privateView(p(0)).get("vote");
        check(ownVote.get("myVote").equals(false)&&ownVote.get("mayVote").equals(true),"own changed ballot remains editable");
        @SuppressWarnings("unchecked") Map<String,Object> otherVote=(Map<String,Object>)cancel.privateView(p(1)).get("vote");
        check(!otherVote.containsKey("ballots")&&otherVote.get("myVote")==null,"other player's ballot remains private");
        cancel.vote(p(0),true);cancel.vote(p(1),false);
        check(cancel.updateAutomation(100,true)==null&&cancel.publicView().containsKey("vote"),"all replied still waits for storyteller");
        check(publicSeat(cancel,p(0)).get("ghostVote").equals(true),"all replied does not spend ghost before host tally");
        check(cancel.closeVote(ST).startsWith("计票完成：1 票"),"storyteller confirms completed vote");
        check(publicSeat(cancel,p(0)).get("ghostVote").equals(false),"final ghost yes consumes exactly once");
        check(cancel.unvote(p(0)).contains("不能返还"),"cannot refund ghost after completed tally");
        cancel.nominate(p(3),p(2));
        check(cancel.closeVote(ST).startsWith("计票完成：0 票"),"manual end defaults unanswered ballots to no");

        ClockGame logs=game("chef","empath","washerwoman","poisoner","imp");firstNight(logs);
        for(int i=0;i<100;i++)logs.mark(ST,p(0),"poisoned",i%2==0);
        @SuppressWarnings("unchecked") List<Map<String,Object>> full=(List<Map<String,Object>>)logs.privateView(ST).get("flowLog");
        check(full.size()>100,"full game log not limited to recent 60 announcements");
        for(int i=0;i<full.size();i++)check(full.get(i).get("sequence").equals(i+1L),"log ordered by monotonic sequence");
        check(full.stream().anyMatch(e->Boolean.TRUE.equals(e.get("secret"))&&e.get("kind").equals("night_result")),"private night result recorded");
        check(!logs.publicView().containsKey("flowLog")&&!logs.privateView(p(0)).containsKey("flowLog"),"full private history restricted during play");
        check(logs.setReview(ST,true).contains("结算后"),"cannot release secrets before finish");
        logs.finish(ST,"good");check(!logs.canReview(p(0)),"finished still requires Storyteller authorization");
        check(logs.setReview(p(0),true).contains("只有说书人"),"players cannot authorize own review");
        logs.setReview(ST,true);check(logs.canReview(p(0))&&logs.privateView(p(0)).containsKey("reviewGrimoire"),"authorized participant receives final identities");
        check(!logs.canReview(p(40))&&!logs.privateView(p(40)).containsKey("flowLog"),"spectator has no review entitlement");
        logs.setReview(ST,false);check(!logs.canReview(p(0))&&!logs.privateView(p(0)).containsKey("flowLog"),"review withdrawal revokes private history");

        ClockGame monk=game("monk","soldier","chef","poisoner","imp");firstNight(monk);monk.phase(ST,"night");
        check(task(monk).get("role").equals("poisoner"),"poison precedes Monk");
        monk.submit(p(3),List.of(p(2)));monk.resolve(ST);
        check(monk.submit(p(0),List.of(p(0))).contains("不能选择自己"),"Monk cannot protect self");
        monk.submit(p(0),List.of(p(2)));monk.resolve(ST);monk.submit(p(4),List.of(p(2)));monk.resolve(ST);drain(monk);monk.phase(ST,"day");
        check(publicSeat(monk,p(2)).get("dead").equals(false),"healthy Monk protection despite target poison");
        monk.phase(ST,"night");monk.submit(p(3),List.of(p(1)));monk.resolve(ST);monk.submit(p(0),List.of(p(2)));monk.resolve(ST);monk.submit(p(4),List.of(p(1)));monk.resolve(ST);drain(monk);monk.phase(ST,"day");
        check(publicSeat(monk,p(1)).get("dead").equals(true),"poisoned Soldier dies to Demon");
        check(!monk.privateView(p(1)).toString().contains("poisoned"),"poison not disclosed to player");

        ClockGame virgin=game("virgin","chef","empath","scarlet_woman","imp");firstNight(virgin);
        check(virgin.nominate(p(1),p(0)).startsWith("处决完成"),"Virgin executes actual Townsfolk immediately");
        check(virgin.phase().equals("night")&&publicSeat(virgin,p(1)).get("dead").equals(true),"Virgin ends daytime and publishes execution");
        ClockGame virginSpy=game("virgin","chef","empath","spy","imp");firstNight(virginSpy);
        check(virginSpy.nominate(p(3),p(0)).contains("正在处理"),"Spy registration stops automatic Virgin execution");
        check(virginSpy.resolve(ST,"continue").contains("开始普通"),"ST resolves Spy as actual Minion");

        ClockGame spy=game("chef","empath","washerwoman","spy","imp");firstNight(spy);
        check(spy.privateView(p(3)).containsKey("spyGrimoire"),"confirmed healthy Spy sees snapshot");
        check(!spy.privateView(p(0)).containsKey("spyGrimoire"),"Spy snapshot unavailable to other players");
        spy.mark(ST,p(3),"poisoned",true);spy.phase(ST,"night");
        // Imp acts before Spy, then poisoned Spy must receive an explicitly supplied alternative.
        spy.submit(p(4),List.of(p(0)));spy.resolve(ST);
        if(task(spy).get("role").equals("empath"))spy.resolve(ST,"你的存活邻居中有 1 名邪恶玩家。");
        check(task(spy).get("role").equals("spy"),"Spy comes after night information");
        check(spy.resolve(ST).contains("填写最终"),"poisoned Spy never gets actual grimoire");
        spy.resolve(ST,"说书人展示的替代魔典信息");check(!spy.privateView(p(3)).containsKey("spyGrimoire"),"poisoned Spy receives no new actual snapshot");

        ClockGame mayor=game("mayor","chef","empath","poisoner","imp");firstNight(mayor);mayor.phase(ST,"night");
        mayor.submit(p(3),List.of(p(1)));mayor.resolve(ST);mayor.submit(p(4),List.of(p(0)));
        check(mayor.resolve(ST).contains("明确裁定"),"Mayor requires explicit choice");
        check(mayor.resolve(ST,"kill:"+p(0)).contains("另一名"),"Mayor redirect cannot name same player");
        check(mayor.resolve(ST,"kill:"+p(1)).startsWith("步骤已"),"Mayor redirects death");drain(mayor);mayor.phase(ST,"day");
        check(publicSeat(mayor,p(0)).get("dead").equals(false)&&publicSeat(mayor,p(1)).get("dead").equals(true),"Mayor redirect applied privately then announced dawn");

        ClockGame pass=game("chef","empath","washerwoman","poisoner","imp");firstNight(pass);pass.phase(ST,"night");
        pass.submit(p(3),List.of(p(1)));pass.resolve(ST);pass.submit(p(4),List.of(p(4)));
        check(pass.resolve(ST).contains("指定接替者"),"Imp suicide requires selection");
        check(pass.resolve(ST,"pass:"+p(0)).contains("不合法"),"cannot pass to Townsfolk");
        check(pass.resolve(ST,"pass:"+p(3)).startsWith("步骤已"),"Imp passes to legal alive Minion");
        check(pass.privateView(p(3)).get("shownRole").equals("imp"),"new Imp learns its role at night");
        check(pass.nightTasks().stream().noneMatch(t->Boolean.TRUE.equals(t.get("current"))&&t.get("role").equals("imp")),"new Imp does not kill twice in same night");

        ClockGame claim=game("slayer","chef","empath","poisoner","imp");claim.start(ST);claim.submit(p(3),List.of(p(1)));claim.resolve(ST);drain(claim);claim.phase(ST,"day");
        check(claim.slay(p(1),p(4)).contains("真实身份"),"anyone can bluff a Slayer claim");
        check(publicSeat(claim,p(4)).get("dead").equals(false),"bluff has no real ability");
        claim.resolve(ST,"none");claim.slay(p(0),p(4));check(claim.isAlive(p(4)),"Slayer waits for Storyteller before death");claim.resolve(ST,"kill");check(publicSeat(claim,p(4)).get("dead").equals(true),"healthy real Slayer kills Demon");

        ClockGame transfer=game("chef","empath","washerwoman","undertaker","slayer","scarlet_woman","imp");firstNight(transfer);
        transfer.mark(ST,p(6),"dead",true);check(transfer.privateView(ST).get("grimoire").toString().contains("role=imp, shownRole=scarlet_woman"),"Scarlet Woman replacement recorded immediately at >=5");
        check(transfer.privateView(p(5)).get("shownRole").equals("scarlet_woman"),"day replacement identity learned at proper night step");
        transfer.phase(ST,"night");check(task(transfer).get("role").equals("scarlet_woman"),"Scarlet notice before Imp action");transfer.resolve(ST);
        check(transfer.privateView(p(5)).get("shownRole").equals("imp"),"private night identity notice");
        check(!transfer.publicView().toString().contains("scarlet_woman"),"replacement remains private");
        check(transfer.finish(ST,"good").startsWith("本局"),"host finishes");
        check(transfer.finished()&&transfer.nightTasks().isEmpty(),"finish clears tasks");
        check(transfer.finish(ST,"evil").contains("没有可结算"),"double finish prevented");
        check(transfer.mark(ST,p(0),"dead",true).contains("已结算"),"state frozen after finish");
        ClockGame reset=game("chef","empath","washerwoman","poisoner","imp");firstNight(reset);
        check(!reset.privateView(p(0)).get("messages").toString().equals("[]")&&publicSeat(reset,p(0)).get("dead").equals(false),"new instance fresh game works");
        ClockWorkflowTest.run();
        ClockPhysicalRuleTest.run();
        ClockDayConfirmationTest.run();
        ClockContinuousEffectTest.run();
        ClockSpyInformationTest.run();
        ClockInformationDraftTest.run();
    }
    public static void main(String[] args){run();System.out.println("ClockGame regression checks passed.");}
    private ClockGameTest() {}
}
