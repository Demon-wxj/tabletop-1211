package local.tabletop;

import local.tabletop.game.FlipGameTest;
import local.tabletop.game.FlipPresentationTest;
import local.tabletop.game.ClockGameTest;
import local.tabletop.game.ClockSeatsTest;

public final class RegressionSuite {
    public static void main(String[] args) {
        FlipGameTest.run();
        FlipPresentationTest.run();
        ClockGameTest.run();
        ClockSeatsTest.run();
        local.tabletop.game.ClockSimultaneousVoteTest.run();
        FlipSeatsTest.run();
        System.out.println("TABLETOP_REGRESSION_OK: pure rules and audience state; not a graphical game test");
    }
}
