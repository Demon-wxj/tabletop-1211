package local.tabletop.game;

import java.util.*;

/** Applied effects pause with their source; failed casts never acquire effects on recovery. */
public final class ClockContinuousEffectTest {
    private static final UUID ST=new UUID(16,999);
    private static int checks;
    private static UUID p(int n){return new UUID(16,n+1);}
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError("continuous effects: "+label);}
    private static ClockGame game(String... roles){
        var players=new ArrayList<UUID>();for(int i=0;i<roles.length;i++)players.add(p(i));var game=new ClockGame(ST,players);
        for(int i=0;i<roles.length;i++){String[] role=roles[i].split(":");check(game.assign(ST,p(i),role[0],role.length>1?role[1]:role[0]).startsWith("已分配"),"assign "+roles[i]);}
        check(game.start(ST).startsWith("首夜"),"start");return game;
    }
    private static ClockGame basic(){return game("monk","chef","soldier","empath","slayer","poisoner","imp");}
    private static void confirm(ClockGame game,UUID poison,UUID protection){
        var task=game.physicalTask();check(task!=null,"queued task exists");
        if(task.targetCount()>0&&!task.submitted()){
            List<UUID> targets=task.role().equals("poisoner")?List.of(poison):task.role().equals("monk")?List.of(protection):task.role().equals("imp")?List.of(p(2)):game.players().stream().filter(id->!id.equals(task.actor())).limit(task.targetCount()).toList();
            check(ClockGame.acceptedResponse("submit",game.submit(task.actor(),targets)),"submit "+task.role());
        }
        String result=game.resolve(ST);if(!ClockGame.acceptedResponse("resolve",result))result=game.resolve(ST,"说书人确认的最终信息");
        check(ClockGame.acceptedResponse("resolve",result),"confirm "+task.role());
    }
    private static void firstNight(ClockGame game){int guard=0;while(game.physicalTask()!=null){check(++guard<40,"first-night queue terminates");confirm(game,p(1),p(3));}check(ClockGame.acceptedResponse("phase",game.phase(ST,"day")),"first dawn");}
    private static void reach(ClockGame game,String role,UUID poison,UUID protection){
        int guard=0;while(game.physicalTask()!=null&&!game.physicalTask().role().equals(role)){check(++guard<40,"reach "+role);confirm(game,poison,protection);}
        check(game.physicalTask()!=null&&game.physicalTask().role().equals(role),"current role "+role);
    }
    private static void nextNight(ClockGame game){check(ClockGame.acceptedResponse("phase",game.phase(ST,"night")),"next dusk");}
    @SuppressWarnings("unchecked") private static boolean marked(ClockGame game,UUID player,String state){
        return Boolean.TRUE.equals(((List<Map<String,Object>>)game.privateView(ST).get("grimoire")).stream().filter(row->row.get("uuid").equals(player.toString())).findFirst().orElseThrow().get(state));
    }
    private static void protectionPauses(){
        for(String state:List.of("drunk","poisoned")){
            ClockGame game=basic();firstNight(game);nextNight(game);reach(game,"imp",p(1),p(3));
            check(game.demonProtected(p(3))&&marked(game,p(3),"safe"),"healthy Monk protection active before "+state);
            game.mark(ST,p(0),state,true);check(!game.demonProtected(p(3))&&!marked(game,p(3),"safe"),"source "+state+" suspends protection and visible safe token");
            game.mark(ST,p(0),state,false);check(game.demonProtected(p(3))&&marked(game,p(3),"safe"),"source recovery restores original protection after "+state);
            check(!ClockGame.acceptedResponse("submit",game.submit(p(0),List.of(p(4)))),"restored protection does not grant another Monk choice");
            game.mark(ST,p(0),"dead",true);check(!game.demonProtected(p(3))&&!marked(game,p(3),"safe"),"source death removes protection");
            game.mark(ST,p(0),"dead",false);check(!game.demonProtected(p(3)),"revived source does not restore a cleared protection");
        }
    }
    private static void poisonPauses(){
        for(String state:List.of("drunk","poisoned")){
            ClockGame game=basic();firstNight(game);check(game.impairedPlayer(p(1))&&marked(game,p(1),"poisoned"),"healthy first-night poison lasts into day");
            game.mark(ST,p(5),state,true);check(!game.impairedPlayer(p(1))&&!marked(game,p(1),"poisoned"),"source "+state+" suspends poison and grimoire token");
            game.mark(ST,p(5),state,false);check(game.impairedPlayer(p(1))&&marked(game,p(1),"poisoned"),"source recovery restores original poison after "+state);
            game.mark(ST,p(5),"dead",true);check(!game.impairedPlayer(p(1))&&!marked(game,p(1),"poisoned"),"source death clears poison");
            game.mark(ST,p(5),"dead",false);check(!game.impairedPlayer(p(1)),"source revival cannot revive cleared poison");
        }
        ClockGame manual=basic();firstNight(manual);manual.mark(ST,p(1),"poisoned",true);manual.mark(ST,p(5),"drunk",true);
        check(manual.impairedPlayer(p(1))&&marked(manual,p(1),"poisoned"),"independent target poison survives source suspension");
        manual.mark(ST,p(1),"poisoned",false);check(!manual.impairedPlayer(p(1)),"target clears when neither produced nor manual poison is effective");
        manual.mark(ST,p(5),"drunk",false);check(manual.impairedPlayer(p(1)),"clearing target's manual token did not erase stored produced poison");
        manual.mark(ST,p(5),"drunk",true);manual.mark(ST,p(5),"poisoned",true);manual.mark(ST,p(5),"drunk",false);
        check(!manual.impairedPlayer(p(1)),"remaining source poison still suspends effect after drunkenness clears");
        manual.mark(ST,p(5),"poisoned",false);check(manual.impairedPlayer(p(1)),"effect resumes when all source impairments clear");
    }
    private static void failedCastsStayFailed(){
        for(String state:List.of("drunk","poisoned")){
            ClockGame poison=basic();poison.mark(ST,p(5),state,true);reach(poison,"poisoner",p(1),p(3));confirm(poison,p(1),p(3));
            poison.mark(ST,p(5),state,false);check(!poison.impairedPlayer(p(1))&&!marked(poison,p(1),"poisoned"),"failed Poisoner cast never starts after source recovers: "+state);
            ClockGame monk=basic();firstNight(monk);nextNight(monk);reach(monk,"monk",p(1),p(3));monk.mark(ST,p(0),state,true);confirm(monk,p(1),p(3));
            monk.mark(ST,p(0),state,false);check(!monk.demonProtected(p(3))&&!marked(monk,p(3),"safe"),"failed Monk cast never starts after source recovers: "+state);
        }
        ClockGame drunk=game("drunk:monk","chef","soldier","empath","poisoner","imp");firstNight(drunk);nextNight(drunk);reach(drunk,"monk",p(1),p(3));confirm(drunk,p(1),p(3));drunk.mark(ST,p(0),"drunk",false);
        check(!drunk.demonProtected(p(3))&&!marked(drunk,p(3),"safe")&&drunk.impairedPlayer(p(0)),"actual Drunk remains powerless when manual drunkenness is cleared");
    }
    private static void dependentSourceHealth(){
        ClockGame game=basic();firstNight(game);nextNight(game);confirm(game,p(0),p(3));
        check(game.impairedPlayer(p(0)),"Poisoner initially impairs Monk before cast");game.mark(ST,p(5),"drunk",true);
        check(!game.impairedPlayer(p(0)),"pausing Poisoner makes Monk healthy for its step");confirm(game,p(0),p(3));
        check(game.demonProtected(p(3))&&marked(game,p(3),"safe"),"healthy Monk casts while Poisoner is suspended");
        game.mark(ST,p(5),"drunk",false);check(game.impairedPlayer(p(0))&&!game.demonProtected(p(3))&&!marked(game,p(3),"safe"),"resumed produced poison suspends already-applied Monk protection");
        game.mark(ST,p(5),"drunk",true);check(!game.impairedPlayer(p(0))&&game.demonProtected(p(3))&&marked(game,p(3),"safe"),"Monk protection resumes when its produced poison suspends again");
    }
    private static void selfPoisonAndExpiry(){
        ClockGame self=basic();reach(self,"poisoner",p(5),p(3));confirm(self,p(5),p(3));check(self.impairedPlayer(p(5))&&marked(self,p(5),"poisoned"),"Poisoner self-poison remains active without recursive source cancellation");
        self.mark(ST,p(5),"drunk",true);check(!marked(self,p(5),"poisoned")&&self.impairedPlayer(p(5)),"manual drunkenness pauses self-produced poison but remains an impairment");
        self.mark(ST,p(5),"drunk",false);check(self.impairedPlayer(p(5))&&marked(self,p(5),"poisoned"),"self-poison resumes when manual drunkenness clears");
        firstNight(self);nextNight(self);check(!self.impairedPlayer(p(5))&&!marked(self,p(5),"poisoned"),"self-poison expires at next dusk before the new choice");

        ClockGame expiry=basic();firstNight(expiry);nextNight(expiry);reach(expiry,"imp",p(1),p(3));check(expiry.demonProtected(p(3)),"night protection active before dawn");
        while(expiry.physicalTask()!=null)confirm(expiry,p(1),p(3));check(ClockGame.acceptedResponse("phase",expiry.phase(ST,"day")),"dawn after protected night");
        check(!expiry.demonProtected(p(3))&&!marked(expiry,p(3),"safe")&&expiry.impairedPlayer(p(1)),"Monk effect expires at dawn while Poisoner continues into day");
        nextNight(expiry);check(!expiry.impairedPlayer(p(1))&&!marked(expiry,p(1),"poisoned"),"Poisoner effect expires at next dusk");
    }
    private static void changedSourceClearsEffect(){
        ClockGame game=basic();firstNight(game);nextNight(game);reach(game,"imp",p(1),p(3));check(game.impairedPlayer(p(1)),"Poisoner effect active before starpass");
        game.submit(p(6),List.of(p(6)));check(ClockGame.acceptedResponse("resolve",game.resolve(ST,"pass:"+p(5))),"Poisoner becomes Imp via legitimate starpass");
        check(game.trueRole(p(5)).equals("imp")&&!game.impairedPlayer(p(1))&&!marked(game,p(1),"poisoned"),"source identity change clears Poisoner effect");
        game.mark(ST,p(5),"drunk",true);game.mark(ST,p(5),"drunk",false);check(!game.impairedPlayer(p(1)),"recovering changed source cannot restore its former role's poison");
    }
    public static void run(){protectionPauses();poisonPauses();failedCastsStayFailed();dependentSourceHealth();selfPoisonAndExpiry();changedSourceClearsEffect();System.out.println("CLOCK_CONTINUOUS_EFFECT_CHECKS="+checks);}
    private ClockContinuousEffectTest(){}
}
