package local.tabletop.game;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Pure rules regression; does not assert that Minecraft or multiplayer was tested. */
public final class FlipGameTest {
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final List<UUID> PLAYERS = List.of(A, B, C);

    public static void main(String[] args) { run(); System.out.println("FlipGame rules regression passed"); }

    public static void run() {
        compositionAndValidation();
        scoringAndSeven();
        secondChanceNoReplacement();
        secondChanceRecalculatesScore();
        initialSecondChancePairRemoval();
        secondChanceReacquiredInsideThree();
        savedThreeKeepsDeferredAction();
        secondChanceTransfer();
        secondChanceInsideThree();
        secondChanceTransferInsideThree();
        resolvedActionCardLifecycle();
        unassignableSecondChanceAndFreeze();
        deferredDiscardOnBust();
        deferredAssignmentBeforeExecution();
        nestedThreeTurnOrder();
        sevenStopsThreeImmediately();
        sevenBanksAllActivePlayers();
        initialFreezeBeforeFirstCard();
        initialThreeBeforeFirstCard();
        modifierOnlyStay();
        roundsRotateAndClear();
        scoreThresholdAndTieExtension();
        automaticDelayPauseAndReconnect();
        manualAdvanceCancelsAutomation();
        automaticTieExtensionAndFinalResult();
        automationValidationAndPendingTarget();
        completeGamesAndCardConservation();
        manualPacingAndEffects();
    }

    private static void manualPacingAndEffects(){
        FlipGame game=scripted("number_12","number_11","number_10","number_12");
        game.updateAutomation(60000,true);
        eq(A,game.turn(),"long wait preserves current player's choice");
        expect(IllegalArgumentException.class,()->game.draw(B));
        game.draw(A);
        eq(List.of(new FlipGame.Effect(A,"bust")),game.drainEffects(),"duplicate emits one cosmetic bust for its actual recipient");
        check(game.drainEffects().isEmpty(),"repeated refresh cannot replay cosmetic effects");
        game.stay(B);game.stay(C);game.updateAutomation(120000,true);
        eq(1,game.publicView().get("round"),"finished round waits indefinitely for host by default");
        game.nextRound();eq(2,game.publicView().get("round"),"host explicitly starts next round");
        FlipGame electric=scripted("flip_three","number_12","number_11","number_10","number_9");
        check(electric.drainEffects().isEmpty(),"drawing action card does not strike before target selected");
        electric.target(A,C);
        eq(List.of(new FlipGame.Effect(C,"lightning")),electric.drainEffects(),"Flip Three emits electricity only at applied target");
        FlipGame shield=scripted("number_12","number_11","number_10","second_chance","number_2","number_3","number_12");
        shield.draw(A);shield.draw(B);shield.draw(C);shield.draw(A);
        eq(List.of(new FlipGame.Effect(A,"shield_break")),shield.drainEffects(),"protection consumed once with fracture instead of explosion");
        FlipGame freeze=scripted("freeze","number_12","number_11");freeze.target(A,B);
        eq(List.of(new FlipGame.Effect(B,"freeze")),freeze.drainEffects(),"freeze uses its actual recipient");
    }

    private static FlipGame scripted(String... cards) { return new FlipGame(PLAYERS, 91L, List.of(cards)); }

    private static void compositionAndValidation() {
        expect(IllegalArgumentException.class, () -> new FlipGame(List.of(A, B), 1L));
        expect(IllegalArgumentException.class, () -> new FlipGame(List.of(A, A, C), 1L));
        expect(IllegalArgumentException.class, () -> scripted("number_0", "number_0"));
        List<String> fullDeck = new ArrayList<>();
        for (int number = 0; number <= 12; number++) {
            for (int copy = 0; copy < Math.max(1, number); copy++) fullDeck.add("number_" + number);
        }
        for (int amount = 2; amount <= 10; amount += 2) fullDeck.add("plus_" + amount);
        fullDeck.add("times_two");
        for (int copy = 0; copy < 3; copy++) fullDeck.addAll(List.of("freeze", "flip_three", "second_chance"));
        eq(94, fullDeck.size(), "standard deck has 94 cards");
        FlipGame game = new FlipGame(PLAYERS, 3L, fullDeck);
        eq(A, game.turn(), "initial deal finishes before choices");
        eq(List.of(0), row(game, A).get("numbers"), "zero is a number card");
        eq(91, number(game.publicView(), "deckRemaining"), "three initial cards dealt");
        conserved(game);
        String before = game.publicView().toString();
        expect(IllegalArgumentException.class, () -> game.draw(C));
        expect(IllegalStateException.class, game::nextRound);
        eq(before, game.publicView().toString(), "invalid actions do not change state");
        expect(UnsupportedOperationException.class, () -> game.players().clear());
    }

    private static void scoringAndSeven() {
        FlipGame game = scripted("number_3", "number_0", "number_1", "times_two", "plus_10",
                "number_4", "number_5", "number_6", "number_7", "number_8", "number_9");
        game.draw(A);
        game.stay(B);
        game.stay(C);
        for (int i = 0; i < 7; i++) game.draw(A);
        check(game.roundOver(), "seven distinct numbers ends everyone immediately");
        eq(109, number(row(game, A), "roundScore"), "(3+4+5+6+7+8+9)*2 +10 +15");
        eq(109, number(row(game, A), "total"), "round score banked once");
        eq(0, number(row(game, B), "total"), "zero held successfully");
        eq(1, number(row(game, C), "total"), "others bank their cards on flip seven");
        check(game.turn() == null, "completed round has no turn");
        String before = game.publicView().toString();
        expect(IllegalStateException.class, () -> game.draw(A));
        eq(before, game.publicView().toString(), "cannot score an ended round twice");
        conserved(game);
    }

    private static void secondChanceNoReplacement() {
        FlipGame game = scripted("number_12", "number_0", "number_1", "second_chance",
                "number_12", "number_12", "number_12", "number_2", "number_3", "number_4");
        game.draw(A);
        game.stay(B);
        game.stay(C);
        game.drainReveals();
        int oldDeck = number(game.publicView(), "deckRemaining");
        game.draw(A);
        eq(1, game.drainReveals().size(), "protection consumes duplicate without replacement draw");
        eq(oldDeck - 1, number(game.publicView(), "deckRemaining"), "only one card consumed");
        eq(List.of(), row(game, A).get("cards"), "protection removes both matching number cards from the hand");
        eq(List.of(), row(game, A).get("tableCards"), "saved pair is removed from the public table too");
        eq(List.of(), row(game, A).get("numbers"), "removed value no longer causes a false duplicate");
        eq(3, number(game.publicView(), "discardCount"), "protection plus old and new matching cards each discarded once");
        eq(0, number(row(game, A), "roundScore"), "removed number no longer scores");
        check((Boolean) row(game, A).get("active"), "saved player is still active");
        eq("active", row(game, A).get("status"), "save does not mark the player busted");
        check(!(Boolean) row(game, A).get("secondChance"), "protection consumed exactly once");
        check(!(Boolean) row(game, A).get("canStay"), "empty saved hand must draw before staying");
        String saved = game.publicView().toString();
        expect(IllegalStateException.class, () -> game.stay(A));
        eq(saved, game.publicView().toString(), "invalid empty-hand stay cannot change saved state");
        eq(A, game.turn(), "only active player acts again on next normal turn");
        game.draw(A);
        check(!game.roundOver(), "same number can return after both saved copies were removed");
        eq(List.of(12), row(game, A).get("numbers"), "new copy becomes the player's only twelve");
        game.draw(A);
        check(game.roundOver(), "next actual duplicate without new protection busts");
        eq(0, number(row(game, A), "roundScore"), "bust scores zero");
        eq(1, number(row(game, A), "spentOnTableCount"), "bust duplicate is withheld from recycling");
        eq(List.of("number_12"), row(game, A).get("cards"), "duplicate stays outside the scoring hand");
        eq(List.of("number_12", "number_12"), row(game, A).get("tableCards"), "both busted copies remain publicly displayable");
        conserved(game);
        game.nextRound();
        eq(7, number(game.publicView(), "discardCount"), "saved pair, protection and old table each recycled once");
        eq(List.of("number_4"), row(game, A).get("tableCards"), "next round removes old duplicate and spent protection");
        eq(0, number(row(game, A), "spentOnTableCount"), "next round clears public spent props");
        eq("active", row(game, A).get("status"), "new round clears old bust status");
        conserved(game);
    }

    private static void secondChanceRecalculatesScore() {
        FlipGame game = scripted("number_12", "number_0", "number_1", "times_two", "plus_10",
                "number_3", "second_chance", "number_12");
        game.draw(A); game.stay(B); game.stay(C);
        game.draw(A); game.draw(A); game.draw(A);
        eq(40, number(row(game, A), "roundScore"), "pre-save score includes both numbers before applying modifiers");
        game.draw(A);
        eq(List.of("times_two", "plus_10", "number_3"), row(game, A).get("cards"), "save preserves unrelated number and modifiers");
        eq(List.of(3), row(game, A).get("numbers"), "matching value removed from number set");
        eq(16, number(row(game, A), "roundScore"), "removed twelve is excluded before doubling and adding ten");
        game.stay(A);
        eq(16, number(row(game, A), "total"), "round banks recalculated score exactly once");
        expect(IllegalStateException.class, () -> game.draw(A));
        eq(16, number(row(game, A), "total"), "ended saved round cannot score twice");
        conserved(game);
    }

    private static void initialSecondChancePairRemoval() {
        FlipGame game = scripted("flip_three", "number_12", "second_chance", "number_12", "number_0", "number_1");
        game.target(A, C);
        eq(List.of(), row(game, C).get("cards"), "save during initial forced deal removes the complete matching pair");
        eq(C, game.turn(), "initial recipient still receives their initial-position choice");
        check((Boolean) game.publicView().get("initialDealing"), "initial dealing resumes after saved forced batch");
        expect(IllegalStateException.class, () -> game.stay(C));
        game.draw(C);
        eq(List.of(1), row(game, C).get("numbers"), "empty saved recipient must draw one actual initial-choice card");
        check(!(Boolean) game.publicView().get("initialDealing"), "initial dealing finishes after that choice");
        eq(A, game.turn(), "normal dealer rotation remains intact");
        eq(4, number(game.publicView(), "discardCount"), "saved three cards and completed Flip Three discarded once");
        conserved(game);

        FlipGame busted = scripted("flip_three", "number_12", "number_12", "number_0");
        busted.target(A, C);
        eq("busted", row(busted, C).get("status"), "unprotected initial forced duplicate busts its actual recipient");
        eq(List.of("number_12", "number_12"), row(busted, C).get("tableCards"), "initial bust duplicate is also public until round change");
        eq(List.of(0), row(busted, B).get("numbers"), "initial batch stops before third draw then deals next player");
        eq(A, busted.turn(), "initial bust recipient is skipped in subsequent choices");
        conserved(busted);
    }

    private static void secondChanceReacquiredInsideThree() {
        FlipGame game = scripted("number_12", "number_0", "number_1", "second_chance", "flip_three",
                "number_12", "second_chance", "number_12", "number_12", "number_12", "number_12");
        game.draw(A); game.stay(B); game.stay(C); game.draw(A);
        game.drainEffects(); game.target(A, A);
        eq(List.of(new FlipGame.Effect(A, "lightning"), new FlipGame.Effect(A, "shield_break")), game.drainEffects(), "one forced duplicate consumes exactly one protection");
        eq(List.of(12), row(game, A).get("numbers"), "third forced number is fresh after first saved pair removal");
        check((Boolean) row(game, A).get("secondChance"), "second forced card can supply a genuinely new protection");
        game.draw(A);
        eq(List.of(new FlipGame.Effect(A, "shield_break")), game.drainEffects(), "new protection saves the later actual duplicate once");
        eq(List.of(), row(game, A).get("cards"), "second save removes both matching cards and new protection");
        eq(7, number(game.publicView(), "discardCount"), "two saved triples and one Flip Three are conserved");
        game.draw(A);
        check(game.drainEffects().isEmpty(), "fresh number never reuses a consumed protection");
        game.draw(A);
        eq(List.of(new FlipGame.Effect(A, "bust")), game.drainEffects(), "next duplicate correctly busts without either consumed protection");
        eq(List.of("number_12", "number_12"), row(game, A).get("tableCards"), "only the later unprotected pair remains on table");
        conserved(game);
    }

    private static void savedThreeKeepsDeferredAction() {
        FlipGame game = scripted("number_12", "number_0", "number_1", "second_chance", "flip_three", "freeze", "number_12", "plus_2");
        game.draw(A); game.stay(B); game.stay(C); game.draw(A); game.target(A, A);
        eq("freeze", game.publicView().get("pendingKind"), "saved recipient still assigns a previously deferred action");
        eq(List.of("plus_2"), row(game, A).get("cards"), "forced draw continues to its third card after a save");
        eq(2, number(row(game, A), "roundScore"), "post-save forced modifier scores without the removed number");
        game.target(A, A);
        eq("frozen", row(game, A).get("status"), "deferred freeze still applies once after a save");
        eq(List.of("plus_2", "freeze"), row(game, A).get("tableCards"), "resolved freeze is public but removed saved pair is absent");
        eq(2, number(row(game, A), "total"), "saved batch banks its actual remaining score");
        eq(0, number(game.publicView(), "inFlightCount"), "saved action chain leaves no staged card");
        conserved(game);
    }

    private static void secondChanceTransfer() {
        FlipGame game = scripted("second_chance", "number_0", "number_1", "second_chance");
        check(game.publicView().get("pendingKind") == null, "first protection is automatically retained, not targeted");
        expect(IllegalStateException.class, () -> game.target(A, B));
        check((Boolean) row(game, A).get("secondChance") && !(Boolean) row(game, B).get("secondChance"), "first protection cannot be voluntarily gifted");
        game.draw(A);
        eq("second_chance", game.publicView().get("pendingKind"), "second protection must transfer");
        expect(IllegalArgumentException.class, () -> game.target(A, A));
        expect(IllegalArgumentException.class, () -> game.target(B, C));
        expect(IllegalStateException.class, () -> game.draw(A));
        game.target(A, B);
        check((Boolean) row(game, A).get("secondChance"), "owner retains original protection");
        check((Boolean) row(game, B).get("secondChance"), "chosen player receives extra protection");
        eq(B, game.turn(), "transfer does not skip next normal player");
        conserved(game);
    }

    private static void secondChanceInsideThree() {
        FlipGame game = scripted("number_12", "number_0", "number_1", "flip_three", "second_chance", "number_12", "plus_2");
        game.drainReveals();
        game.draw(A);
        game.target(A, A);
        eq(4, game.drainReveals().size(), "original action and exactly three forced cards revealed");
        check((Boolean) row(game, A).get("active"), "new protection works inside same flip three");
        eq(2, number(row(game, A), "roundScore"), "modifier scores after both matching numbers are removed during forced three");
        check(!(Boolean) row(game, A).get("secondChance"), "protection consumed");
        eq(B, game.turn(), "forced draw returns to original clockwise successor");
        conserved(game);
    }

    private static void deferredDiscardOnBust() {
        FlipGame game = scripted("number_12", "number_0", "number_1", "flip_three", "freeze", "number_12", "number_2");
        game.drainReveals();
        game.draw(A);
        game.target(A, A);
        eq(3, game.drainReveals().size(), "bust stops forced sequence before its third card");
        eq("busted", row(game, A).get("status"), "duplicate busts owner");
        check(game.publicView().get("pendingKind") == null, "deferred freeze discarded on bust");
        check((Boolean) row(game, B).get("active"), "discarded action cannot freeze another player");
        eq(88, number(game.publicView(), "deckRemaining"), "unflipped third card remains in deck");
        eq(0, number(game.publicView(), "inFlightCount"), "aborted batch clears all its staged actions");
        conserved(game);
    }

    private static void secondChanceTransferInsideThree() {
        FlipGame game = scripted("second_chance", "number_0", "number_1", "flip_three", "second_chance", "number_2", "number_3");
        game.draw(A);
        game.target(A, A);
        eq("second_chance", game.publicView().get("pendingKind"), "duplicate protection interrupts forced sequence for immediate donation");
        eq(List.of(), row(game, A).get("numbers"), "remaining two cards not dealt until donation resolved");
        game.target(A, B);
        eq(List.of(2, 3), row(game, A).get("numbers"), "forced sequence resumes with exactly two remaining cards");
        check((Boolean) row(game, B).get("secondChance"), "recipient protection retained");
        eq(B, game.turn(), "donation inside three preserves normal turn order");
        conserved(game);
    }

    private static void unassignableSecondChanceAndFreeze() {
        FlipGame game = scripted("second_chance", "number_0", "number_1", "plus_2", "second_chance", "freeze");
        game.draw(A);
        game.stay(B);
        game.stay(C);
        game.draw(A);
        check(game.publicView().get("pendingKind") == null, "extra protection discarded when no other player is active");
        eq(List.of("second_chance", "plus_2"), row(game, A).get("cards"), "only original protection remains");
        game.draw(A);
        eq(List.of(A.toString()), game.publicView().get("pendingTargets"), "last active player must use freeze on self");
        expect(IllegalArgumentException.class, () -> game.target(A, B));
        game.target(A, A);
        eq("frozen", row(game, A).get("status"), "protection cannot prevent freeze");
        eq(2, number(row(game, A), "total"), "freeze banks modifier-only points");
        check(game.roundOver(), "last active player frozen ends the round");
        conserved(game);
    }

    private static void resolvedActionCardLifecycle() {
        FlipGame direct = scripted("number_12", "number_0", "number_1", "freeze",
                "number_2", "number_3", "number_4");
        direct.draw(A);
        eq(1, number(direct.publicView(), "inFlightCount"), "unassigned freeze remains a pending physical card");
        direct.target(A, B);
        eq(List.of("number_0"), row(direct, B).get("cards"), "resolved freeze leaves the recipient's public card row");
        eq(List.of("number_0", "freeze"), row(direct, B).get("tableCards"), "resolved freeze remains an explicit public table prop");
        eq("frozen", row(direct, B).get("status"), "hiding resolved freeze does not undo its effect");
        eq(1, number(row(direct, B), "spentOnTableCount"), "resolved freeze remains out of recycling for this round");
        eq(0, number(direct.publicView(), "discardCount"), "visual removal does not prematurely recycle freeze");
        eq(0, number(direct.publicView(), "inFlightCount"), "freeze completes once after selecting its target");
        String before = direct.publicView().toString();
        expect(IllegalStateException.class, () -> direct.target(A, B));
        eq(before, direct.publicView().toString(), "repeated target cannot discard or apply a resolved action twice");
        conserved(direct);
        direct.stay(C); direct.stay(A); direct.nextRound();
        eq(4, number(direct.publicView(), "discardCount"), "three old numbers and resolved freeze recycled once at round change");
        for (UUID player : PLAYERS)
            eq(0, number(row(direct, player), "spentOnTableCount"), "round change clears the old resolved actions");
        eq(List.of("number_2"), row(direct, B).get("tableCards"), "next round table row contains no old resolved freeze");
        expect(UnsupportedOperationException.class, () -> ((List<?>) row(direct, B).get("tableCards")).clear());
        conserved(direct);

        FlipGame chain = scripted("number_12", "number_0", "number_1", "flip_three",
                "freeze", "flip_three", "plus_2", "number_2", "number_3", "number_4");
        chain.draw(A); chain.target(A, A); chain.target(A, B); chain.target(A, C);
        eq(List.of("number_12", "plus_2"), row(chain, A).get("cards"), "resolved original and deferred actions do not remain in owner row");
        eq(List.of("number_0"), row(chain, B).get("cards"), "resolved chained freeze leaves the target row");
        eq(List.of("number_0", "freeze"), row(chain, B).get("tableCards"), "completed chained freeze remains on the public table only");
        eq(List.of("number_1", "number_2", "number_3", "number_4"), row(chain, C).get("cards"), "nested flip three leaves only its actual received cards");
        eq(1, number(row(chain, B), "spentOnTableCount"), "chained freeze held once until round change");
        eq(2, number(chain.publicView(), "discardCount"), "both completed flip three cards discarded once");
        eq(0, number(chain.publicView(), "inFlightCount"), "completed chain releases all pending cards");
        conserved(chain);
        chain.stay(C); chain.stay(A); chain.nextRound();
        eq(10, number(chain.publicView(), "discardCount"), "completed actions are not duplicated during next-round cleanup");
        conserved(chain);

        FlipGame protection = scripted("number_12", "number_0", "number_1", "second_chance", "number_12");
        protection.draw(A);
        eq(List.of("number_12", "second_chance"), row(protection, A).get("cards"), "unused protection remains visible and usable");
        protection.stay(B); protection.stay(C); protection.draw(A);
        eq(List.of(), row(protection, A).get("cards"), "consumed protection and matching pair leave the public row");
        eq(List.of(), row(protection, A).get("tableCards"), "protection and matching pair also leave the table and enter discard");
        check(!(Boolean) row(protection, A).get("secondChance"), "consumed protection is no longer reported as available");
        eq(3, number(protection.publicView(), "discardCount"), "protection, original number and duplicate each discarded exactly once");
        conserved(protection);
    }

    private static void deferredAssignmentBeforeExecution() {
        FlipGame game = scripted("number_12", "number_0", "number_1", "flip_three", "freeze", "flip_three", "plus_2");
        game.draw(A);
        game.target(A, A);
        eq("freeze", game.publicView().get("pendingKind"), "three draws finish before deferred freeze selection");
        eq(14, number(row(game, A), "roundScore"), "all three dealt before freeze action");
        game.target(A, B);
        check((Boolean) row(game, B).get("active"), "first action waits for all deferred assignments");
        eq("flip_three", game.publicView().get("pendingKind"), "deferred actions assigned in draw order");
        int oldDeck = number(game.publicView(), "deckRemaining");
        game.target(A, B);
        eq("frozen", row(game, B).get("status"), "first assigned freeze applied");
        eq(oldDeck, number(game.publicView(), "deckRemaining"), "second action discarded for now-inactive target");
        eq(C, game.turn(), "normal turn skips frozen successor");
        conserved(game);
    }

    private static void nestedThreeTurnOrder() {
        FlipGame game = scripted("number_12", "number_0", "number_1", "flip_three", "freeze", "flip_three", "plus_2",
                "number_2", "number_3", "number_4");
        game.draw(A);
        game.target(A, A);
        game.target(A, B);
        game.target(A, C);
        eq(List.of(1, 2, 3, 4), row(game, C).get("numbers"), "nested forced draw targets assigned player");
        eq(C, game.turn(), "normal turn resumes after ORIGINAL recipient, not nested recipient");
        eq(10, number(row(game, C), "roundScore"), "nested three points accounted");
        eq(0, number(game.publicView(), "inFlightCount"), "nested batches fully resolved");
        conserved(game);
    }

    private static void sevenStopsThreeImmediately() {
        FlipGame game = scripted("number_2", "number_0", "number_1", "number_3", "number_4", "number_5", "number_6",
                "number_7", "flip_three", "number_8", "freeze", "plus_2");
        game.draw(A);
        game.stay(B);
        game.stay(C);
        for (int i = 0; i < 4; i++) game.draw(A);
        game.draw(A);
        game.drainReveals();
        int oldDeck = number(game.publicView(), "deckRemaining");
        game.target(A, A);
        eq(1, game.drainReveals().size(), "seven stops after first forced draw");
        eq(oldDeck - 1, number(game.publicView(), "deckRemaining"), "remaining two forced cards not dealt");
        eq(50, number(row(game, A), "roundScore"), "2 through 8 plus seven bonus");
        check(game.roundOver(), "seven ends round immediately");
        conserved(game);
    }

    private static void initialFreezeBeforeFirstCard() {
        FlipGame game = scripted("freeze", "number_12", "number_11");
        eq(A, game.turn(), "initial action pauses dealer immediately");
        game.target(A, C);
        eq("frozen", row(game, C).get("status"), "initial action can target an undealt player");
        eq(List.of(), row(game, C).get("numbers"), "frozen player does not receive their initial number");
        eq(List.of(12), row(game, B).get("numbers"), "initial dealing resumes to next active player");
        eq(A, game.turn(), "normal round starts after first deal");
        expect(IllegalStateException.class, () -> game.stay(A));
        game.draw(A);
        eq(List.of(11), row(game, A).get("numbers"), "player who gave action away must draw before staying");
        eq(B, game.turn(), "normal rotation retained");
        conserved(game);
    }

    private static void sevenBanksAllActivePlayers() {
        FlipGame game = scripted("number_0", "number_12", "number_11",
                "number_1", "number_11", "number_12",
                "number_2", "number_10", "number_10",
                "number_3", "number_9", "number_9",
                "number_4", "number_8", "number_8",
                "number_5", "number_7", "number_6", "number_6");
        for (int turn = 0; turn < 16; turn++) game.draw(game.turn());
        check(game.roundOver(), "seventh number ends round without requiring others to stay");
        eq(36, number(row(game, A), "total"), "zero counts toward flip seven bonus");
        eq(57, number(row(game, B), "total"), "another active player's cards bank automatically");
        eq(56, number(row(game, C), "total"), "third active player's cards bank automatically");
        conserved(game);
    }

    private static void initialThreeBeforeFirstCard() {
        FlipGame game = scripted("flip_three", "number_2", "number_3", "number_4", "number_5", "number_6");
        game.target(A, C);
        eq(List.of(2, 3, 4), row(game, C).get("numbers"), "initial flip three is resolved immediately");
        eq(List.of(5), row(game, B).get("numbers"), "initial deal resumes after action owner");
        eq(C, game.turn(), "already-dealt player can decide whether to take initial-position card");
        check((Boolean) game.publicView().get("initialDealing"), "optional initial-position choice still in initial deal");
        game.stay(C);
        eq(A, game.turn(), "normal turns start after everyone has initial opportunity");
        game.draw(A);
        eq(List.of(6), row(game, A).get("numbers"), "extra initial card was optional");
        conserved(game);
    }

    private static void modifierOnlyStay() {
        FlipGame game = scripted("plus_8", "times_two", "second_chance");
        game.stay(A);
        game.stay(B);
        game.stay(C);
        eq(8, number(row(game, A), "total"), "modifier alone scores its value");
        eq(0, number(row(game, B), "total"), "times two alone scores zero");
        eq(0, number(row(game, C), "total"), "action alone scores zero");
        conserved(game);
    }

    private static void roundsRotateAndClear() {
        FlipGame game = scripted("number_12", "number_11", "number_10", "number_12", "number_2", "number_3", "number_4");
        game.draw(A);
        game.stay(B);
        game.stay(C);
        game.nextRound();
        eq(2, game.publicView().get("round"), "round advances");
        eq(B.toString(), game.publicView().get("dealer"), "dealer moves clockwise");
        eq(B, game.turn(), "new dealer is first normal chooser");
        eq(List.of(4), row(game, A).get("numbers"), "old busted hand removed");
        eq(0, number(row(game, A), "spentOnTableCount"), "old bust duplicate reclaimed only on round reset");
        eq(11, number(row(game, B), "total"), "total score survives round reset");
        eq(4, number(game.publicView(), "discardCount"), "old hand cards are discarded instead of immediately reshuffled");
        eq(87, number(game.publicView(), "deckRemaining"), "remaining deck used for new initial deal");
        conserved(game);
        FlipGame nextMatch = scripted("number_1", "number_2", "number_3");
        for (UUID player : PLAYERS) eq(0, number(row(nextMatch, player), "total"), "new match clears all totals");
    }

    private static void scoreThresholdAndTieExtension() {
        FlipGame tied = scripted("number_2", "number_2", "number_0", "number_3", "number_4", "number_5");
        // This checkpoint fixture isolates end-of-match scoring without needing
        // hundreds of unrelated random deals to create a 200-point tie.
        setTotal(tied, A, 198);
        setTotal(tied, B, 198);
        tied.stay(A);
        tied.stay(B);
        tied.stay(C);
        eq(200, number(row(tied, A), "total"), "winning threshold is inclusive");
        check(!tied.finished(), "equal leaders at 200 extend the game");
        tied.nextRound();
        // New initial order is B,C,A: B adds 3, C adds 4, A adds 5.
        tied.stay(B);
        tied.stay(C);
        tied.stay(A);
        check(tied.finished(), "one leader after extension wins");
        eq(A.toString(), tied.publicView().get("winner"), "highest cumulative total wins");
        expect(IllegalStateException.class, tied::nextRound);
        conserved(tied);
        FlipGame under = scripted("number_1", "number_0", "number_2");
        setTotal(under, A, 198);
        under.stay(A);
        under.stay(B);
        under.stay(C);
        check(!under.finished(), "199 points does not end match");
        FlipGame higher = scripted("number_2", "number_11", "number_12");
        setTotal(higher, A, 198);
        setTotal(higher, B, 190);
        setTotal(higher, C, 185);
        higher.stay(A);
        check(!higher.finished(), "first player reaching 200 in a round does not terminate other players");
        higher.stay(B);
        higher.stay(C);
        eq(B.toString(), higher.publicView().get("winner"), "highest score wins even when another player first crosses target");
    }

    private static void automaticDelayPauseAndReconnect() {
        FlipGame game = scripted("number_12", "number_11", "number_10", "number_2", "number_3", "number_4");
        check(!game.autoNext(), "host-controlled advancement defaults off");
        String waiting=game.publicView().toString();game.updateAutomation(0,true);game.updateAutomation(1,true);
        eq(waiting,game.publicView().toString(),"elapsed ticks cannot make player decisions");
        game.setAutoNext(true);
        check(game.updateAutomation(10, true) == null, "active round is never automatically completed");
        for (UUID player : PLAYERS) game.stay(player);
        eq("round_results", game.publicView().get("flowState"), "ended round keeps score display state");
        check(game.updateAutomation(100, true) == null, "first tick schedules rather than skips result display");
        eq(160L, game.publicView().get("autoNextAtTick"), "default result display lasts sixty ticks");
        eq(60L, game.publicView().get("autoNextInTicks"), "public countdown is available");
        check(game.updateAutomation(159, true) == null, "next round cannot start before deadline");
        eq(1, game.publicView().get("round"), "old table still shown before deadline");
        eq(List.of("number_12"), row(game, A).get("cards"), "old scoring row stays visible");
        game.setAutoNext(false);
        check(game.updateAutomation(999, true) == null, "paused automatic mode never advances");
        check(game.publicView().get("autoNextAtTick") == null, "pause cancels old countdown");
        game.setAutoNext(true);
        game.updateAutomation(1000, true);
        eq(1060L, game.publicView().get("autoNextAtTick"), "resume grants full result display delay");
        game.updateAutomation(1030, false);
        check(game.publicView().get("autoNextAtTick") == null, "disconnect cancels countdown");
        check((Boolean) game.publicView().get("autoNextWaitingForOnline"), "public state explains waiting for reconnect");
        game.updateAutomation(5000, true);
        eq(5060L, game.publicView().get("autoNextAtTick"), "reconnect never jumps immediately to next round");
        check(game.updateAutomation(5059, true) == null, "reconnect result display delay is respected");
        check(game.updateAutomation(5060, true) != null, "deadline advances one round");
        eq(2, game.publicView().get("round"), "automatic advancement starts second round");
        eq(B.toString(), game.publicView().get("dealer"), "automatic advancement preserves dealer rotation");
        eq(12, number(row(game, A), "total"), "automatic advancement preserves banked score");
        String once = game.publicView().toString();
        check(game.updateAutomation(5060, true) == null, "repeat tick cannot advance twice");
        eq(once, game.publicView().toString(), "repeat tick leaves new round unchanged");
        conserved(game);
    }

    private static void manualAdvanceCancelsAutomation() {
        FlipGame game = scripted("number_12", "number_11", "number_10", "number_2", "number_3", "number_4");
        game.setAutoNext(true);
        for (UUID player : PLAYERS) game.stay(player);
        game.updateAutomation(20, true);
        game.nextRound();
        check(game.publicView().get("autoNextAtTick") == null, "manual next round cancels scheduled countdown");
        check(game.updateAutomation(80, true) == null, "old deadline cannot skip a live second round");
        eq(2, game.publicView().get("round"), "manual advancement happens once");
        game.setAutoNext(false);
        for (int guard = 0; !game.roundOver() && guard < 100; guard++) {
            if (game.publicView().get("pendingKind") != null) {
                @SuppressWarnings("unchecked") List<String> allowed = (List<String>) game.publicView().get("pendingTargets");
                game.target(game.turn(), UUID.fromString(allowed.get(0)));
            } else if (((List<?>) row(game, game.turn()).get("cards")).isEmpty()) game.draw(game.turn());
            else game.stay(game.turn());
        }
        check(game.roundOver(), "second round completed for manual mode checkpoint");
        game.updateAutomation(800, true);
        eq(2, game.publicView().get("round"), "manual mode keeps ended round awaiting host");
        game.nextRound();
        eq(3, game.publicView().get("round"), "host can advance while automation is paused");
        conserved(game);
    }

    private static void automaticTieExtensionAndFinalResult() {
        FlipGame game = scripted("number_2", "number_2", "number_0", "number_3", "number_4", "number_5");
        game.setAutoNext(true);
        setTotal(game, A, 198); setTotal(game, B, 198);
        for (UUID player : PLAYERS) game.stay(player);
        check(!game.finished(), "tied two hundred point result remains extendable");
        game.updateAutomation(100, true);
        game.updateAutomation(160, true);
        eq(2, game.publicView().get("round"), "automatic next round resolves official tie extension");
        game.stay(B); game.stay(C); game.stay(A);
        check(game.finished(), "tie extension determines unique winner");
        eq("finished", game.publicView().get("flowState"), "final score has its own terminal flow state");
        game.updateAutomation(161, true);
        check(game.publicView().get("autoNextAtTick") == null, "finished match schedules no next round");
        String finished = game.publicView().toString();
        check(game.updateAutomation(100000, true) == null, "final match remains visible indefinitely");
        eq(finished, game.publicView().toString(), "final score is not cleared or double counted by tick loop");
        expect(IllegalStateException.class, game::nextRound);
        conserved(game);
    }

    private static void automationValidationAndPendingTarget() {
        FlipGame game = scripted("freeze", "number_12", "number_11");
        game.setAutoNext(true);
        game.updateAutomation(10, true);
        eq("waiting_target", game.publicView().get("flowState"), "pending action exposes explicit flow state");
        String pending = game.publicView().toString();
        check(game.updateAutomation(1000, true) == null, "automatic flow never chooses a pending target");
        eq(pending, game.publicView().toString(), "pending action unchanged by long elapsed time");
        expect(IllegalArgumentException.class, () -> game.updateAutomation(-1, true));
        expect(IllegalArgumentException.class, () -> game.updateAutomation(999, true));
        expect(IllegalArgumentException.class, () -> game.setAutoNextDelayTicks(0));
        expect(IllegalArgumentException.class, () -> game.setAutoNextDelayTicks(1201));
        eq(pending, game.publicView().toString(), "invalid automation input leaves state intact");
        game.setAutoNextDelayTicks(20);
        game.target(A, C);
        game.draw(A); game.stay(B); game.stay(A);
        game.updateAutomation(1001, true);
        eq(1021L, game.publicView().get("autoNextAtTick"), "configured server tick delay applies");
        game.setAutoNextDelayTicks(40);
        check(game.publicView().get("autoNextAtTick") == null, "delay change invalidates old deadline");
        game.updateAutomation(1002, true);
        eq(1042L, game.publicView().get("autoNextAtTick"), "delay change reschedules using full configured delay");
        conserved(game);
    }

    private static void completeGamesAndCardConservation() {
        boolean recycled = false;
        boolean recycledWithBustOnTable = false;
        for (int seed = 0; seed < 24; seed++) {
            FlipGame game = new FlipGame(PLAYERS, seed);
            int actions = 0;
            while (!game.finished() && actions++ < 12000) {
                conserved(game);
                int beforeDeck = number(game.publicView(), "deckRemaining");
                boolean hasBust = PLAYERS.stream().anyMatch(id -> row(game, id).get("status").equals("busted"));
                if (game.roundOver()) {
                    game.nextRound();
                } else if (game.publicView().get("pendingKind") != null) {
                    @SuppressWarnings("unchecked") List<String> targets = (List<String>) game.publicView().get("pendingTargets");
                    UUID owner = game.turn();
                    UUID selected = targets.contains(owner.toString()) ? owner : UUID.fromString(targets.get(0));
                    game.target(owner, selected);
                } else {
                    UUID player = game.turn();
                    // Aggressive policy exercises busts and batches; occasional
                    // staying also covers skipped players and stable score banking.
                    if (number(row(game, player), "roundScore") >= 36 && seed % 3 == 0) game.stay(player);
                    else game.draw(player);
                }
                if (number(game.publicView(), "deckRemaining") > beforeDeck) {
                    recycled = true;
                    if (hasBust && !game.roundOver()) recycledWithBustOnTable = true;
                }
                conserved(game);
                game.drainReveals();
            }
            check(game.finished(), "seed " + seed + " completes a full multi-round match");
            check(game.publicView().get("winner") != null, "completed match has unique winner");
            int best = PLAYERS.stream().mapToInt(id -> number(row(game, id), "total")).max().orElseThrow();
            check(best >= 200, "completed match reaches official target");
            eq(1L, PLAYERS.stream().filter(id -> number(row(game, id), "total") == best).count(), "single highest total wins");
        }
        check(recycled, "simulated matches exercise deck recycling");
        check(recycledWithBustOnTable, "recycling also exercised while a busting player's cards remain on table");
    }

    private static void setTotal(FlipGame game, UUID player, int score) {
        try {
            Field states = FlipGame.class.getDeclaredField("states");
            states.setAccessible(true);
            Object data = ((Map<?, ?>) states.get(game)).get(player);
            Field total = data.getClass().getDeclaredField("total");
            total.setAccessible(true);
            total.setInt(data, score);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("checkpoint fixture could not load total", error);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> row(FlipGame game, UUID player) {
        Map<String, Object> rows = (Map<String, Object>) game.publicView().get("players");
        return (Map<String, Object>) rows.get(player.toString());
    }
    private static int number(Map<String, Object> data, String key) { return ((Number) data.get(key)).intValue(); }
    private static void conserved(FlipGame game) {
        int count = number(game.publicView(), "deckRemaining") + number(game.publicView(), "discardCount")
                + number(game.publicView(), "inFlightCount");
        for (UUID player : PLAYERS)
            count += ((List<?>) row(game, player).get("cards")).size() + number(row(game, player), "spentOnTableCount");
        eq(94, count, "every physical card is in deck, discard, player row or pending action");
    }
    private static void check(boolean condition, String explanation) {
        if (!condition) throw new AssertionError(explanation);
    }
    private static void eq(Object expected, Object actual, String explanation) {
        if (!expected.equals(actual)) throw new AssertionError(explanation + ": expected " + expected + ", actual " + actual);
    }
    private static void expect(Class<? extends Throwable> type, Runnable operation) {
        try { operation.run(); }
        catch (Throwable error) {
            if (type.isInstance(error)) return;
            throw new AssertionError("expected " + type.getSimpleName() + " but got " + error, error);
        }
        throw new AssertionError("expected " + type.getSimpleName());
    }
}
