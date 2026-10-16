package local.tabletop;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import local.tabletop.game.FlipGame;

public final class FlipSeatsTest {
    private static int checks;
    private static UUID player(int number){return new UUID(587,number);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
    public static void run(){
        checks=0;var seats=new FlipSeats();UUID host=player(0),second=player(1),third=player(2);
        check(seats.add(host)==1&&seats.add(second)==2&&seats.add(third)==3,"host and players receive lowest vacant seat");
        check(seats.choose(host,8)&&seats.choose(second,5),"host participates and can choose white eight");
        var before=seats.snapshot();
        check(!seats.choose(third,8)&&!seats.choose(third,9)&&!seats.choose(third,0)&&!seats.choose(player(20),1),"occupied, ninth, zero and foreign choices rejected");
        check(seats.snapshot().equals(before),"rejected choices preserve all ownership");
        check(seats.remove(third)&&seats.number(host)==8&&seats.number(second)==5,"leave releases one number without shifting survivors");
        UUID red=player(3);check(seats.add(red)==1,"new member fills lowest actual vacancy");
        check(seats.ordered(List.of(host,second,red)).equals(List.of(red,second,host)),"sparse 1,5,8 sort by colour rather than admission order");
        try{seats.snapshot().clear();throw new AssertionError("snapshot mutable");}catch(UnsupportedOperationException expected){checks++;}
        check(before.get(host)==8&&before.get(third)==3,"older snapshot remains stable after leave");
        for(int n=4;n<=8;n++)check(seats.add(player(n))>0,"remaining free seat admitted");
        check(seats.snapshot().size()==8&&seats.add(player(9))==0,"ninth player refused at a full eight-seat table");
        check(seats.add(host)==8&&seats.choose(host,8),"same member admission and seat choice idempotent");
        var ordered=seats.ordered(new ArrayList<>(seats.snapshot().keySet()));
        for(int number=1;number<=8;number++)check(seats.number(ordered.get(number-1))==number,"fixed clockwise slot "+number);
        FlipGame game=new FlipGame(ordered,581);
        check(game.players().equals(ordered)&&game.publicView().get("dealer").equals(red.toString()),"engine first round uses fixed red-one order");
        check(game.drainReveals().get(0).player().equals(red),"first initial card delivered to red one");
        int[] colors={0xE53935,0x8D6E63,0xFDD835,0xEC80B0,0x42A5F5,0x66BB6A,0xAB47BC,0xECEFF1};
        String[] names={"红色","棕色","黄色","粉色","蓝色","绿色","紫色","白色"};
        for(int number=1;number<=8;number++)check(FlipSeats.color(number)==colors[number-1]&&FlipSeats.colorName(number).equals(names[number-1]),"requested numbered colour "+number);
        System.out.println("FlipSeats checks: "+checks);
    }
    public static void main(String[] args){run();}
    private FlipSeatsTest(){}
}
