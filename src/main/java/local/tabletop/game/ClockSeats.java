package local.tabletop.game;

import java.util.*;

/** Lobby seat ownership. Role and game state remain keyed by the player UUID. */
public final class ClockSeats {
    public static final int LIMIT=12;
    private static final int[] COLORS={0xE53935,0x8D6E63,0xFDD835,0xEC80B0,0x42A5F5,0x66BB6A,0xAB47BC,0xECEFF1,
        0xFFA726,0x26C6DA,0x90A4AE,0x5C6BC0};
    private static final String[] NAMES={"红色","棕色","黄色","粉色","蓝色","绿色","紫色","白色","橙色","青色","灰色","深蓝色"};
    private final Map<UUID,Integer> seats=new LinkedHashMap<>();

    /** Assign the first vacant number, retaining an existing player's number. Zero means full. */
    public synchronized int add(UUID player) {
        Objects.requireNonNull(player,"player");
        if(seats.containsKey(player))return seats.get(player);
        for(int number=1;number<=LIMIT;number++)if(!seats.containsValue(number)){seats.put(player,number);return number;}
        return 0;
    }
    /** A player can only move their own existing seat to a vacant number. */
    public synchronized boolean choose(UUID player,int number) {
        if(player==null||!seats.containsKey(player)||number<1||number>LIMIT)return false;
        if(seats.get(player)==number)return true;
        if(seats.containsValue(number))return false;
        seats.put(player,number);return true;
    }
    public synchronized boolean remove(UUID player){return seats.remove(player)!=null;}
    /** Transfer ownership without renumbering any other seat. */
    public synchronized boolean replace(UUID previous,UUID next) {
        if(previous==null||next==null||!seats.containsKey(previous))return false;
        if(previous.equals(next))return true;
        if(seats.containsKey(next))return false;
        int number=seats.remove(previous);seats.put(next,number);return true;
    }
    public synchronized int number(UUID player){return seats.getOrDefault(player,0);}
    public synchronized int maxNumber(){return seats.values().stream().mapToInt(Integer::intValue).max().orElse(0);}
    /** Unknown UUIDs keep their supplied order after the numbered players. */
    public synchronized List<UUID> ordered(Collection<UUID> players) {
        Objects.requireNonNull(players,"players");
        List<UUID> ordered=new ArrayList<>(players);
        ordered.sort(Comparator.comparingInt(player->seats.getOrDefault(player,Integer.MAX_VALUE)));
        return List.copyOf(ordered);
    }
    public synchronized Map<UUID,Integer> snapshot() {
        Map<UUID,Integer> copy=new LinkedHashMap<>();
        ordered(seats.keySet()).forEach(player->copy.put(player,seats.get(player)));
        return Collections.unmodifiableMap(copy);
    }
    public static int color(int number){return number>=1&&number<=LIMIT?COLORS[number-1]:0x90A4AE;}
    public static String colorName(int number){return number>=1&&number<=LIMIT?NAMES[number-1]:"灰色";}
}
