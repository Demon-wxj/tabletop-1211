package local.tabletop.game;

import java.util.*;

/** Seat edits preserve UUID-owned roles while the active ring follows chosen numbers. */
public final class ClockSeatsTest {
    private static final UUID ST=new UUID(81,100);
    private static int checks;
    private static UUID p(int n){return new UUID(81,n+1);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
    private static Map<UUID,Integer> numbers(int... values){Map<UUID,Integer> result=new LinkedHashMap<>();for(int index=0;index<values.length;index++)result.put(p(index),values[index]);return result;}
    private static List<UUID> people(int count){List<UUID> result=new ArrayList<>();for(int n=0;n<count;n++)result.add(p(n));return result;}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> rows(Map<String,Object> view,String key){return (List<Map<String,Object>>)view.get(key);}
    private static Map<String,Object> row(List<Map<String,Object>> rows,UUID player){return rows.stream().filter(value->value.get("uuid").equals(player.toString())).findFirst().orElseThrow();}
    private static Map<UUID,Integer> seatSnapshot(ClockGame game){Map<UUID,Integer> result=new LinkedHashMap<>();for(Map<String,Object> row:rows(game.publicView(),"seats"))result.put(UUID.fromString((String)row.get("uuid")),(Integer)row.get("seat"));return result;}
    private static void invalid(Map<UUID,Integer> numbers){try{new ClockGame(ST,people(5),numbers);}catch(IllegalArgumentException expected){checks++;return;}throw new AssertionError("invalid seat map accepted: "+numbers);}
    private static void immutable(Runnable operation){try{operation.run();}catch(UnsupportedOperationException expected){checks++;return;}throw new AssertionError("mutable seat snapshot");}
    private static Map<UUID,Map<String,Object>> hiddenWithoutNumbers(ClockGame game){Map<UUID,Map<String,Object>> result=new LinkedHashMap<>();for(Map<String,Object> original:rows(game.privateView(ST),"grimoire")){Map<String,Object> copy=new LinkedHashMap<>(original);copy.remove("seat");result.put(UUID.fromString((String)copy.get("uuid")),copy);}return result;}
    /** Follow the grimoire's actual confirmation action, including its drafted information text. */
    private static void confirmInformation(ClockGame game,String label){
        Map<?,?> primary=(Map<?,?>)game.workflow(ST).get("primaryAction");
        check(primary!=null&&"resolve".equals(primary.get("action")),label+" has an explicit Storyteller confirmation");
        check(primary.get("text") instanceof String,label+" carries confirmation text");
        String text=(String)primary.get("text");
        check(game.physicalTask().role().equals("spy")?text.isBlank():text.equals(game.privateView(ST).get("informationDraft")),label+" uses the actual draft or healthy Spy snapshot action");
        String result=game.resolve(ST,text);
        check(result.startsWith("步骤已"),label+": "+result);
    }

    private static void ownership() {
        ClockSeats seats=new ClockSeats();check(seats.number(p(0))==0&&seats.maxNumber()==0,"empty seat helper");
        check(!seats.choose(p(0),8)&&!seats.remove(p(0))&&!seats.replace(p(0),p(1)),"unknown player cannot own or transfer a seat");
        check(seats.add(p(0))==1&&seats.add(p(1))==2,"first vacant number assigned");
        check(seats.choose(p(0),8)&&seats.number(p(0))==8&&seats.maxNumber()==8,"choose sparse eight without compacting other seat");
        check(seats.add(p(2))==1,"freed first number reused");
        Map<UUID,Integer> before=seats.snapshot();
        check(!seats.choose(p(1),8)&&!seats.choose(p(1),0)&&!seats.choose(p(1),13)&&!seats.choose(p(40),3),"occupied, invalid and foreign choices rejected");
        check(seats.snapshot().equals(before),"all rejected choices leave ownership unchanged");
        check(seats.choose(p(1),2)&&seats.add(p(0))==8&&seats.snapshot().equals(before),"repeat choose/join idempotent");
        check(seats.ordered(List.of(p(0),p(1),p(2))).equals(List.of(p(2),p(1),p(0))),"ring sorted by explicit number");
        check(seats.ordered(List.of(p(40),p(1),p(41),p(2))).equals(List.of(p(2),p(1),p(40),p(41))),"unknown ids retain order after registered seats");
        immutable(()->before.clear());immutable(()->seats.ordered(List.of(p(0))).clear());
        check(!seats.replace(p(1),p(0))&&seats.snapshot().equals(before),"transfer to occupied UUID rejected");
        check(seats.replace(p(1),p(3))&&seats.number(p(3))==2&&seats.number(p(1))==0,"storyteller replacement keeps former seat number");
        check(seats.replace(p(3),p(3))&&seats.number(p(3))==2,"same UUID transfer idempotent");
        check(seats.remove(p(0))&&seats.number(p(0))==0&&seats.maxNumber()==2,"leave releases only chosen number");
        check(before.get(p(0))==8&&before.get(p(1))==2,"older ownership snapshot remains unchanged");
        for(int n=4;n<14;n++)check(seats.add(p(n))>0,"fill remaining table slots");
        check(seats.snapshot().size()==12&&seats.maxNumber()==12&&seats.add(p(30))==0,"twelve numbered players retained");
        check(seats.remove(p(8))&&seats.add(p(30))>0&&seats.snapshot().size()==12,"full table vacancy reused");
        int[] palette={0xE53935,0x8D6E63,0xFDD835,0xEC80B0,0x42A5F5,0x66BB6A,0xAB47BC,0xECEFF1};
        String[] names={"红色","棕色","黄色","粉色","蓝色","绿色","紫色","白色"};
        for(int n=1;n<=8;n++)check(ClockSeats.color(n)==palette[n-1]&&ClockSeats.colorName(n).equals(names[n-1]),"requested color for "+n);
        for(int n=9;n<=12;n++)check(ClockSeats.color(n)!=0&& !ClockSeats.colorName(n).isBlank(),"stable extra seat color "+n);
    }
    private static void sparseGame() {
        Map<UUID,Integer> explicit=numbers(8,1,5,12,3);
        ClockGame game=new ClockGame(ST,people(5),explicit);
        explicit.put(p(0),6);check(game.seatNumber(p(0))==8,"constructor copies explicit seat map");
        check(game.players().equals(List.of(p(1),p(4),p(2),p(0),p(3))),"game ring sorted by number with gaps");
        immutable(()->game.players().clear());check(game.seatNumber(p(50))==0,"unknown number unavailable");
        String[] roles={"chef","empath","soldier","spy","imp"};
        for(int n=0;n<roles.length;n++)check(game.assign(ST,p(n),roles[n],roles[n]).startsWith("已分配 "+game.seatNumber(p(n))+" 号"),"assignment text preserves explicit number");
        for(int n=0;n<5;n++){
            check(row(rows(game.publicView(),"seats"),p(n)).get("seat").equals(game.seatNumber(p(n))),"public sparse number "+n);
            check(row(rows(game.privateView(ST),"grimoire"),p(n)).get("seat").equals(game.seatNumber(p(n))),"grimoire sparse number "+n);
            check(row(rows(game.privateView(p(n)),"seats"),p(n)).get("seat").equals(game.seatNumber(p(n))),"private sparse number "+n);
        }
        check(game.start(ST).startsWith("首夜"),"legal five-player sparse game starts");
        check(game.physicalTask().role().equals("chef")&&game.privateView(ST).get("informationDraft").equals("相邻邪恶玩家共有 0 对。"),"Chef uses chosen ring, not join order or numeric adjacency");
        check(game.resolve(ST).contains("填写最终"),"Chef with Spy registration rejects an unspecified information confirmation");
        confirmInformation(game,"confirm Chef draft");
        check(game.physicalTask().role().equals("empath")&&game.privateView(ST).get("informationDraft").equals("你的两名存活邻居中有 2 名邪恶玩家。"),"Empath wraps across 12/1 and skips empty numbers");
        check(game.suggestion(ST).contains("12 号")&&game.suggestion(ST).contains("3 号"),"neighbor information text uses explicit numbers");
        check(game.resolve(ST).contains("填写最终"),"Empath with Spy neighbor requires final information confirmation");
        confirmInformation(game,"confirm Empath draft");
        check(game.physicalTask().role().equals("spy"),"confirm Empath before Spy");
        confirmInformation(game,"confirm Spy snapshot");
        @SuppressWarnings("unchecked") Map<String,Object> spy=(Map<String,Object>)game.privateView(p(3)).get("spyGrimoire");
        for(int n=0;n<5;n++)check(row(rows(spy,"seats"),p(n)).get("seat").equals(game.seatNumber(p(n))),"Spy true numbers retained "+n);
        check(game.phase(ST,"day").startsWith("已进入白天"),"sparse dawn");
        check(game.nominate(p(1),p(0)).startsWith("投票按"),"sparse nomination");
        @SuppressWarnings("unchecked") Map<String,Object> vote=(Map<String,Object>)game.publicView().get("vote");
        List<UUID> expected=List.of(p(3),p(1),p(4),p(2),p(0));
        check(vote.get("order").equals(expected.stream().map(UUID::toString).toList()),"ballot follows actual clockwise UUID ring");
        check(game.vote(p(0),true).startsWith("已举手"),"sparse final seat can vote immediately without prior responders");
        for(UUID voter:expected)check(game.vote(voter,false).startsWith("已记录"),"sequential sparse vote");
        check(game.closeVote(ST).startsWith("计票完成"),"sparse ballot closes normally");
        check(game.mark(ST,p(3),"dead",true).startsWith("魔典"),"mark neighbor dead");
        check(game.phase(ST,"night").contains("已进入"),"sparse second night");
        check(game.submit(p(4),List.of(p(2))).startsWith("回复已")&&game.resolve(ST).startsWith("步骤已"),"Demon choice remains UUID-owned; protected Soldier survives");
        check(game.physicalTask().role().equals("empath")&&game.privateView(ST).get("informationDraft").equals("你的两名存活邻居中有 1 名邪恶玩家。"),"Empath skips dead seat and empty numbers");
    }
    private static void validationAndReseat() {
        invalid(null);invalid(numbers(1,2,3,4));invalid(numbers(1,2,3,4,4));invalid(numbers(0,2,3,4,5));invalid(numbers(13,2,3,4,5));
        Map<UUID,Integer> missing=numbers(1,2,3,4,5);missing.put(p(0),null);invalid(missing);
        Map<UUID,Integer> extra=numbers(1,2,3,4,5);extra.put(p(20),6);invalid(extra);
        for(int count=5;count<=12;count++){
            ClockGame ordinary=new ClockGame(ST,people(count));for(int index=0;index<count;index++)check(ordinary.seatNumber(p(index))==index+1,"legacy constructor keeps number "+count+"/"+index);
        }
        ClockGame game=new ClockGame(ST,people(8));check(game.autoAssign(ST,47).startsWith("已自动"),"seeded prepared assignments");
        UUID good=game.players().stream().filter(player->!Set.of("minion","demon").contains(RoleCatalog.get(game.trueRole(player)).type())).findFirst().orElseThrow();
        check(game.mark(ST,good,"red_herring",true).startsWith("魔典"),"prepared red herring");
        check(game.mark(ST,p(0),"drunk",true).startsWith("魔典")&&game.mark(ST,p(1),"poisoned",true).startsWith("魔典"),"prepared intoxication marks");
        check(game.mark(ST,p(2),"used",true).startsWith("魔典")&&game.mark(ST,p(3),"note:保留这条备注",true).startsWith("魔典"),"prepared ability and note marks");
        Map<UUID,List<String>> assignments=game.setupAssignments(ST);Map<UUID,Map<String,Object>> hidden=hiddenWithoutNumbers(game);
        Object bluffs=game.privateView(ST).get("demonBluffs"),notes=game.privateView(ST).get("notes");
        Map<UUID,Integer> before=seatSnapshot(game),changed=numbers(12,8,1,7,5,2,3,4);
        check(!game.reseat(p(0),changed)&&!game.reseat(ST,null)&&!game.reseat(ST,numbers(1,2,3,4,5,6,7,7)),"only Storyteller can apply complete valid seat edit");
        check(seatSnapshot(game).equals(before)&&game.setupAssignments(ST).equals(assignments)&&hiddenWithoutNumbers(game).equals(hidden),"invalid reseat does not alter numbers or hidden state");
        check(game.reseat(ST,changed)&&seatSnapshot(game).equals(changed),"prepared choice updates explicit numbers");
        check(game.setupAssignments(ST).equals(assignments)&&hiddenWithoutNumbers(game).equals(hidden),"reseat retains every true/fake UUID role and status including red herring");
        check(game.privateView(ST).get("demonBluffs").equals(bluffs)&&game.privateView(ST).get("notes").equals(notes),"reseat retains chosen bluffs and notes");
        check(game.players().equals(List.of(p(2),p(5),p(6),p(7),p(4),p(3),p(1),p(0))),"prepared ring reordered only by chosen number");
        check(game.start(ST).startsWith("首夜"),"prepared edited game remains valid");
        Map<UUID,Integer> started=seatSnapshot(game);
        check(!game.reseat(ST,before)&&seatSnapshot(game).equals(started)&&game.setupAssignments(ST).equals(assignments),"numbers locked after game starts");
    }
    public static void run(){checks=0;ownership();sparseGame();validationAndReseat();System.out.println("ClockSeats checks: "+checks);}
    private ClockSeatsTest() {}
}
