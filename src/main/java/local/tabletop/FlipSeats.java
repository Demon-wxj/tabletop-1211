package local.tabletop;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Fixed physical seats for the eight-colour Flip table. Leaving never shifts another player. */
public final class FlipSeats {
    public static final int LIMIT = 8;
    private static final int[] COLORS = {0xE53935,0x8D6E63,0xFDD835,0xEC80B0,0x42A5F5,0x66BB6A,0xAB47BC,0xECEFF1};
    private static final String[] NAMES = {"红色","棕色","黄色","粉色","蓝色","绿色","紫色","白色"};
    private final Map<UUID,Integer> seats = new LinkedHashMap<>();

    public synchronized int add(UUID player) {
        Objects.requireNonNull(player,"player");
        if (seats.containsKey(player)) return seats.get(player);
        for (int number=1;number<=LIMIT;number++) if (!seats.containsValue(number)) {
            seats.put(player,number); return number;
        }
        return 0;
    }
    public synchronized boolean choose(UUID player,int number) {
        if (player==null || !seats.containsKey(player) || number<1 || number>LIMIT) return false;
        if (seats.get(player)==number) return true;
        if (seats.containsValue(number)) return false;
        seats.put(player,number); return true;
    }
    public synchronized boolean remove(UUID player) { return seats.remove(player)!=null; }
    public synchronized int number(UUID player) { return seats.getOrDefault(player,0); }
    public synchronized List<UUID> ordered(Collection<UUID> players) {
        var result=new ArrayList<>(players);
        result.sort(Comparator.comparingInt(player->seats.getOrDefault(player,Integer.MAX_VALUE)));
        return List.copyOf(result);
    }
    public synchronized Map<UUID,Integer> snapshot() {
        var result=new LinkedHashMap<UUID,Integer>();
        for (UUID player:ordered(seats.keySet())) result.put(player,seats.get(player));
        return Collections.unmodifiableMap(result);
    }
    public static int color(int number) { return number>=1 && number<=LIMIT ? COLORS[number-1] : 0x90A4AE; }
    public static String colorName(int number) { return number>=1 && number<=LIMIT ? NAMES[number-1] : "未入座"; }
}
