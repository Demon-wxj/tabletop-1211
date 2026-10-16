package local.tabletop.game;

import local.tabletop.ReferenceLibrary;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Checks authoritative workflow facts needed by the phone, rather than rendering implementation. */
public final class FlipPresentationTest {
    private static final UUID A = new UUID(0, 1), B = new UUID(0, 2), C = new UUID(0, 3);
    private static final List<UUID> PLAYERS = List.of(A, B, C);
    private static int assertions;

    public static void main(String[] args) { run(); System.out.println("TABLETOP_FLIP_PRESENTATION_OK " + assertions); }
    public static void run() {
        assertions = 0;
        normalAndEmptyHandPermissions();
        directAndChainedTargetProgress();
        secondChanceInterruptProgress();
        scoreBankingAndSevenEnd();
        bustAndRoundReset();
        tieExtensionAndRanking();
        immutablePublicFacts();
        independentPublicBook();
        confirmedRevealSurvivesDrain();
        savedPairPublicFacts();
        forcedDrawRevealsKeepTheirOwnState();
    }
    @SuppressWarnings("unchecked") private static void confirmedRevealSurvivesDrain(){
        FlipGame game=scripted("number_2","number_3","number_4","number_2");
        Map<String,Object> first=(Map<String,Object>)game.publicView().get("lastReveal");
        eq(3L,first.get("sequence"),"initial deals expose only the last confirmed reveal");
        game.drainReveals();eq(first,game.publicView().get("lastReveal"),"render drain does not erase the public reveal");
        game.draw(A);Map<String,Object> bust=(Map<String,Object>)game.publicView().get("lastReveal");
        eq("number_2",bust.get("cardId"),"duplicate reveal is available even though not retained in hand");
        eq(A.toString(),bust.get("player"),"reveal belongs to actual recipient");
        eq(4L,bust.get("sequence"),"refresh never increments reveal sequence");
        eq(1,bust.get("round"),"reveal carries the actual round");
        eq("busted",bust.get("outcome"),"duplicate outcome is explicit for delayed presentation");
        eq(List.of("number_2"),bust.get("cards"),"bust reveal separates the scoring hand from its prop");
        eq(List.of("number_2","number_2"),bust.get("tableCards"),"duplicate remains in public table snapshot");
        eq(List.of("number_2","number_2"),row(game,A).get("tableCards"),"all players can inspect the complete busted pair");
        eq(0,bust.get("roundScore"),"bust reveal scores zero immediately");
        eq("busted",row(game,A).get("status"),"visual reveal leaves bust rule authoritative");
        try{bust.put("cardId","number_12");throw new AssertionError("mutable public reveal");}catch(UnsupportedOperationException expected){assertions++;}
    }
    @SuppressWarnings("unchecked") private static void savedPairPublicFacts() {
        FlipGame game=scripted("number_12","number_0","number_1","second_chance","number_12","number_12","number_12");
        game.draw(A);game.stay(B);game.stay(C);game.drainReveals();game.draw(A);
        List<FlipGame.Reveal> events=game.drainReveals();
        eq(1,events.size(),"save emits one confirmed draw and no replacement");
        FlipGame.Reveal saved=events.get(0);
        eq("saved",saved.outcome(),"saved duplicate is distinct from a bust");
        eq(List.of("second_chance","number_12","number_12"),saved.removedCards(),"event identifies protection and both removed copies");
        eq(List.of(),saved.cards(),"saved event shows actual retained hand");
        eq(List.of(),saved.tableCards(),"saved event shows actual table after removal");
        eq("active",saved.status(),"delayed save has no dead or busted badge");
        eq(0,saved.roundScore(),"saved number no longer contributes to score");
        eq(0,score(game,A).get("numberTotal"),"score explanation recomputes removed number");
        eq(0,row(game,A).get("numberCount"),"seven-card progress recomputes removed number");
        eq(7,row(game,A).get("remainingToSeven"),"removed pair cannot advance Flip Seven");
        eq(true,row(game,A).get("canDraw"),"empty saved hand still has a real draw choice");
        eq(false,row(game,A).get("canStay"),"empty saved hand cannot offer stay");
        eq(false,row(game,A).get("secondChance"),"consumed protection is no longer available");
        Map<String,Object> publicSaved=(Map<String,Object>)game.publicView().get("lastReveal");
        eq(saved.sequence(),publicSaved.get("sequence"),"public saved fact and animation event share sequence");
        eq(saved.removedCards(),publicSaved.get("removedCards"),"public saved fact shares exact removal list");
        expectUnsupported(() -> saved.removedCards().clear());
        expectUnsupported(() -> saved.tableCards().clear());
        expectUnsupported(() -> ((List<?>)publicSaved.get("cards")).clear());
        game.draw(A);
        eq("retained",((Map<?,?>)game.publicView().get("lastReveal")).get("outcome"),"same number after removal is a fresh retained card");
        eq("saved",publicSaved.get("outcome"),"later draws never mutate old public saved snapshot");
        eq(List.of(),saved.tableCards(),"later draws never refill old event table snapshot");
        game.draw(A);
        eq("busted",row(game,A).get("status"),"unprotected later actual duplicate remains a bust");
    }
    private static void forcedDrawRevealsKeepTheirOwnState() {
        FlipGame game=scripted("number_12","number_0","number_1","flip_three","second_chance","number_12","plus_2");
        game.drainReveals();game.draw(A);
        List<FlipGame.Reveal> action=game.drainReveals();
        eq(1,action.size(),"target choice does not pre-reveal the forced deck");
        eq("action",action.get(0).outcome(),"drawn Flip Three is a public action event");
        eq(List.of("number_12"),action.get(0).tableCards(),"pending action preserves pre-forced table");
        game.target(A,A);
        List<FlipGame.Reveal> events=game.drainReveals();
        eq(List.of("second_chance","number_12","plus_2"),events.stream().map(FlipGame.Reveal::cardId).toList(),"forced cards remain in exact confirmed draw order");
        eq(List.of(5L,6L,7L),events.stream().map(FlipGame.Reveal::sequence).toList(),"each forced event has its own monotonic sequence");
        eq(List.of("number_12","second_chance"),events.get(0).tableCards(),"first forced frame retains number plus available protection");
        eq(12,events.get(0).roundScore(),"first forced frame has its real score before save");
        eq("saved",events.get(1).outcome(),"second forced frame consumes the protection");
        eq(List.of(),events.get(1).tableCards(),"second forced frame removes both numbers before third frame");
        eq(0,events.get(1).roundScore(),"second forced frame has score zero after removal");
        eq(List.of("plus_2"),events.get(2).tableCards(),"third forced frame adds only its actual modifier");
        eq(2,events.get(2).roundScore(),"third forced frame and final authoritative score agree");
        eq("active",events.get(1).status(),"save is active even when later forced cards finish");
        eq(1,events.get(1).round(),"events are tied to the actual round");
        eq(B,game.turn(),"snapshot presentation cannot alter clockwise turn order");
        check(game.drainReveals().isEmpty(),"refresh drains each forced event exactly once");
        eq(List.of(),events.get(1).tableCards(),"draining or later frames do not mutate consumed pair snapshot");
    }
    private static FlipGame scripted(String... cards) { return new FlipGame(PLAYERS, 1, List.of(cards)); }
    @SuppressWarnings("unchecked") private static Map<String,Object> row(FlipGame game, UUID player) {
        return (Map<String,Object>) ((Map<?,?>) game.publicView().get("players")).get(player.toString());
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> score(FlipGame game, UUID player) { return (Map<String,Object>) row(game, player).get("scoreDetails"); }
    @SuppressWarnings("unchecked") private static Map<String,Object> pending(FlipGame game) { return (Map<String,Object>) game.publicView().get("pendingAction"); }
    private static void normalAndEmptyHandPermissions() {
        FlipGame game = scripted("number_1", "number_2", "number_3");
        eq(true, row(game,A).get("canDraw"), "current player can draw");
        eq(true, row(game,A).get("canStay"), "current card holder can stay");
        eq(false, row(game,B).get("canDraw"), "off-turn player cannot draw");
        eq(false, row(game,B).get("canStay"), "off-turn player cannot stay");
        eq(false, row(game,A).get("canSelectTarget"), "ordinary turn is not a target choice");
        game.stay(A);
        eq(false, row(game,A).get("canDraw"), "stayed player has no action");
        eq(true, row(game,B).get("canDraw"), "choice moves clockwise");
        FlipGame empty = scripted("freeze", "number_2", "number_3");
        empty.target(A,C);
        eq(true, row(empty,A).get("canDraw"), "action owner with empty hand must draw later");
        eq(false, row(empty,A).get("canStay"), "empty-hand stay must not be offered");
    }
    private static void directAndChainedTargetProgress() {
        FlipGame game = scripted("number_1", "number_2", "number_3", "flip_three", "freeze", "flip_three", "number_4", "number_5", "number_6", "number_7");
        game.draw(A);
        eq("direct", pending(game).get("stage"), "direct action has no deferred assignment stage");
        eq(A.toString(), pending(game).get("owner"), "drawer owns the initial action");
        eq(true, row(game,A).get("canSelectTarget"), "only drawer can select target");
        eq(false, row(game,A).get("canDraw"), "target decision blocks normal draw");
        game.target(A,B);
        eq(B.toString(), pending(game).get("owner"), "recipient owns newly drawn deferred actions");
        eq("assign_chain", pending(game).get("stage"), "deferred actions are labelled for assignment before execution");
        eq(1, pending(game).get("assignmentNumber"), "first pending action index");
        eq(2, pending(game).get("assignmentCount"), "complete pending chain count");
        eq(false, row(game,A).get("canSelectTarget"), "initial drawer cannot hijack recipient's chain");
        game.target(B,C);
        eq(2, pending(game).get("assignmentNumber"), "second target has accurate progress");
        eq(true, row(game,C).get("active"), "first freeze waits until all targets assigned");
        game.target(B,C);
        eq("frozen", row(game,C).get("status"), "actions apply in reveal order");
        check(game.publicView().get("pendingAction") == null, "invalidated later action does not leave a ghost pending UI");
        eq(B, game.turn(), "chain returns to original next turn");
    }
    private static void secondChanceInterruptProgress() {
        FlipGame game = scripted("second_chance", "number_2", "number_3", "flip_three", "second_chance", "number_4", "number_5");
        game.draw(A); game.target(A,A);
        eq("second_chance", pending(game).get("kind"), "excess chance pauses a forced draw");
        eq("during_flip_three", pending(game).get("stage"), "chance is distinguished from deferred action chain");
        eq(2, pending(game).get("forcedDrawRemaining"), "two forced cards remain after transfer");
        eq(List.of(B.toString(),C.toString()), pending(game).get("targets"), "only eligible recipients are offered");
        game.target(A,B);
        eq(List.of(4,5), row(game,A).get("numbers"), "remaining forced cards resume once");
        check(game.publicView().get("pendingAction") == null, "completed transfer clears pending progress");
    }
    private static void scoreBankingAndSevenEnd() {
        FlipGame game = scripted("number_3", "number_0", "number_1", "times_two", "plus_10", "number_4", "number_5", "number_6", "number_7", "number_8", "number_9");
        game.draw(A); game.stay(B); game.stay(C); game.draw(A);
        eq(3, score(game,A).get("numberTotal"), "number total is separate from modifiers");
        eq(2, score(game,A).get("multiplier"), "double is visible");
        eq(10, score(game,A).get("modifierTotal"), "addition is not multiplied");
        eq(16, score(game,A).get("afterTotal"), "live score is projected but not banked");
        eq(false, score(game,A).get("banked"), "live score cannot be mistaken for banked result");
        for (int i=0; i<6; i++) game.draw(A);
        eq("flip_seven", game.publicView().get("roundEndReason"), "seventh number states immediate end reason");
        eq(A.toString(), game.publicView().get("sevenPlayer"), "bonus owner is public");
        eq(42, score(game,A).get("numberTotal"), "all seven values summed");
        eq(15, score(game,A).get("sevenBonus"), "separate seven bonus");
        eq(109, score(game,A).get("roundScore"), "complete arithmetic matches authoritative banked score");
        eq(0, score(game,A).get("beforeTotal"), "prior total is preserved for round delta");
        eq(109, score(game,A).get("afterTotal"), "new total is shown exactly once");
        eq(true, score(game,A).get("banked"), "ended round is banked");
        for(UUID id:PLAYERS) { eq(false,row(game,id).get("canDraw"),"round result cannot offer draw"); eq(false,row(game,id).get("canStay"),"round result cannot offer stay"); }
    }
    private static void bustAndRoundReset() {
        FlipGame game=scripted("number_12","number_11","number_10","number_12","number_2","number_3","number_4");
        game.draw(A); game.stay(B); game.stay(C);
        eq(true, score(game,A).get("busted"), "bust explicitly explains zero");
        eq(12, score(game,A).get("numberTotal"), "busted effective original row still has original total");
        eq(0, score(game,A).get("roundScore"), "busted row banks no score");
        eq("no_active", game.publicView().get("roundEndReason"), "normal end reason");
        game.nextRound();
        check(game.publicView().get("roundEndReason")==null,"old end reason is cleared");
        check(game.publicView().get("sevenPlayer")==null,"old bonus owner is cleared");
        eq(11, score(game,B).get("beforeTotal"), "new round keeps prior banked total");
        eq(false, score(game,B).get("banked"), "new round projection is not already banked");
    }
    private static void tieExtensionAndRanking() {
        FlipGame game=scripted("number_2","number_2","number_0","number_3","number_4","number_5");
        setTotal(game,A,198); setTotal(game,B,198);
        game.stay(A); game.stay(B); game.stay(C);
        eq(true,game.publicView().get("tiedAtThreshold"),"inclusive 200 tie explicitly requires extension");
        eq(List.of(A.toString(),B.toString()),game.publicView().get("leaders"),"equal leaders listed");
        List<?> ranking=(List<?>)game.publicView().get("ranking");
        eq(1,((Map<?,?>)ranking.get(0)).get("rank"),"first leader rank");
        eq(1,((Map<?,?>)ranking.get(1)).get("rank"),"tied leader shares rank");
        eq(3,((Map<?,?>)ranking.get(2)).get("rank"),"trailing rank excludes two leaders");
        game.nextRound(); game.stay(B); game.stay(C); game.stay(A);
        eq(false,game.publicView().get("tiedAtThreshold"),"unique winner clears tie flag");
        eq(List.of(A.toString()),game.publicView().get("leaders"),"final unique leader");
        eq(A.toString(),game.publicView().get("winner"),"ranking leader is actual winner");
        eq(200,score(game,A).get("beforeTotal"),"extension delta shows previous 200");
        eq(205,score(game,A).get("afterTotal"),"extension delta shows banked 205");
    }
    private static void immutablePublicFacts() {
        FlipGame game=scripted("number_1","number_2","number_3","freeze");
        Map<String,Object> snapshot=game.publicView();
        Map<String,Object> before=score(game,A);
        expectUnsupported(() -> snapshot.put("round",9));
        expectUnsupported(() -> before.put("roundScore",999));
        expectUnsupported(() -> ((List<?>) snapshot.get("ranking")).clear());
        game.draw(A);
        expectUnsupported(() -> pending(game).put("owner",B.toString()));
        expectUnsupported(() -> ((List<?>) pending(game).get("targets")).clear());
        eq(false,before.get("banked"),"later actions do not mutate prior snapshot");
    }
    private static void independentPublicBook() {
        check(ReferenceLibrary.supports("flip_rules_book"),"independent physical book has a public route");
        ReferenceLibrary.Document book=ReferenceLibrary.get("flip_rules_book");
        eq("flip_rules_book",book.documentId(),"Flip book is not the Clocktower rules alias");
        for(String kind:List.of("flip_rules_book","rules_card","faq_book")) {
            var reference=ReferenceLibrary.get(kind);
            check(!reference.sections().isEmpty()&&reference.sections().size()<=8,"short public book remains navigable");
            eq((long)reference.sections().size(),reference.sections().stream().map(ReferenceLibrary.Section::id).distinct().count(),"chapters have unique navigation keys");
            for(var section:reference.sections()) {
                check(section.alignment().isEmpty(),"rules book has no hidden player allegiance");
                check(!section.summary().isBlank(),"each entry has a short answer");
                check(section.paragraphs().size()<=1,"no repeated long explanations");
                check(section.summary().length()+String.join("",section.paragraphs()).length()<=180,"entry fits a brief reading page");
            }
        }
        String flipText=bookText(book);
        for(String core:List.of("200","并列","重复数字","本轮0分","×2只乘数字","7种不同数字","15分","冻结","连翻三张","二次机会","没有行动倒计时","右键桌边摸牌或收手","对空右键选人","跨局保留"))
            check(flipText.contains(core),"concise Flip book retains core rule: "+core);
        String clockText=bookText(ReferenceLibrary.get("rules_card"));
        for(String core:List.of("本桌8名玩家＋1名说书人","只有活人能提名","向上取整","最高平票","幽灵支持票","说书人确认开门","走入房间攻击"))
            check(clockText.contains(core),"concise Clock book retains essential rule: "+core);
        expectUnsupported(() -> book.sections().clear());
        eq("rules_card",ReferenceLibrary.get("rules_card").documentId(),"brief card has its own reading route");
        var detailed=ReferenceLibrary.get("rules_book");
        check(detailed!=ReferenceLibrary.get("rules_card"),"detailed book does not expand the brief card");
        check(detailed.sections().size()>ReferenceLibrary.get("rules_card").sections().size(),"detailed flow has independent searchable chapters");
        eq((long)detailed.sections().size(),detailed.sections().stream().map(ReferenceLibrary.Section::id).distinct().count(),"detailed navigation keys are unique");
        check(new com.google.gson.Gson().toJson(detailed).length()<30000,"complete reference remains within the real 32767-character view limit");
        for(var section:detailed.sections())check(section.alignment().isEmpty()&&!section.summary().isBlank()&&!section.paragraphs().isEmpty(),"detailed flow remains a public reference");
        String detailText=bookText(detailed);
        for(String core:List.of("5–12","5/1/1/1","男爵","5–6人","首夜","后续夜","说书人确认","同时投票","向上取整","幽灵","中毒","继位","圣徒","镇长","胜负","复盘"))check(detailText.contains(core),"detailed flow covers: "+core);
    }
    private static String bookText(ReferenceLibrary.Document book) {
        return book.sections().stream().map(section->section.title()+section.summary()+String.join("",section.paragraphs())).reduce("",String::concat);
    }
    private static void setTotal(FlipGame game,UUID id,int total) {
        try { Field states=FlipGame.class.getDeclaredField("states");states.setAccessible(true);Object player=((Map<?,?>)states.get(game)).get(id);Field value=player.getClass().getDeclaredField("total");value.setAccessible(true);value.setInt(player,total); }
        catch(ReflectiveOperationException exception){throw new AssertionError(exception);}
    }
    private static void expectUnsupported(Runnable work){try{work.run();}catch(UnsupportedOperationException expected){assertions++;return;}throw new AssertionError("public snapshot was mutable");}
    private static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
    private static void eq(Object expected,Object actual,String message){assertions++;if(!java.util.Objects.equals(expected,actual))throw new AssertionError(message+": expected="+expected+", actual="+actual);}
}
