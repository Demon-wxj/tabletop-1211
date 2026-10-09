package local.tabletop;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.BossBarS2CPacket;
import net.minecraft.text.Text;

/** Public turn information belongs to a single Flip table, never a Clock table. */
final class FlipTurnBar {
    private static final class TurnBar {
        final ServerBossBar bar;
        final Map<ServerPlayNetworkHandler, ServerPlayerEntity> connections = new IdentityHashMap<>();
        TurnBar() {
            bar = new ServerBossBar(Text.empty(), BossBar.Color.PURPLE, BossBar.Style.PROGRESS);
            bar.setPercent(1);
            bar.setDarkenSky(false);
            bar.setDragonMusic(false);
            bar.setThickenFog(false);
        }
    }
    private static final Map<Sessions.Session, TurnBar> BARS = new HashMap<>();

    private FlipTurnBar() { }

    static void update(Sessions.Session session, MinecraftServer server) {
        if (!session.type.equals("flip") || !session.running || session.flip == null) {
            clear(session);
            return;
        }
        TurnBar turnBar = BARS.computeIfAbsent(session, ignored -> new TurnBar());
        ServerBossBar bar = turnBar.bar;
        bar.setName(Text.literal(title(Sessions.flipPublicState(session), session.roster, !session.offline.isEmpty())));
        bar.setVisible(true);
        syncAudience(session, server, turnBar);
    }

    /** Role handoff can change the physical connection without changing the game turn. */
    static void syncAudience(Sessions.Session session, MinecraftServer server) {
        TurnBar bar = BARS.get(session);
        if (bar != null) syncAudience(session, server, bar);
    }

    private static void syncAudience(Sessions.Session session, MinecraftServer server, TurnBar turnBar) {
        ServerBossBar bar = turnBar.bar;
        Set<UUID> audience = session.audience();
        var connections = new IdentityHashMap<ServerPlayNetworkHandler, ServerPlayerEntity>();
        for (UUID id : audience) {
            var player = server.getPlayerManager().getPlayer(id);
            if (player != null && !session.offline.contains(id) && physical(player))
                connections.put(player.networkHandler, player);
        }
        // A handoff parks the old entity on a dummy handler. Remove the client bar
        // through its previously recorded physical handler when it leaves this table.
        for (var handler : turnBar.connections.keySet())
            if (!connections.containsKey(handler) && handler.isConnectionOpen())
                handler.sendPacket(BossBarS2CPacket.remove(bar.getUuid()));
        for (var player : new ArrayList<>(bar.getPlayers())) {
            if (!connections.containsValue(player))
                bar.removePlayer(player);
        }
        for (var player : connections.values()) bar.addPlayer(player);
        turnBar.connections.clear();
        turnBar.connections.putAll(connections);
    }

    private static boolean physical(ServerPlayerEntity player) {
        var handler = player.networkHandler;
        if (handler == null || handler.player != player || !handler.isConnectionOpen()) return false;
        // A review NPC's inert connection reports loopback port 0, including isOpen=true.
        // Real TCP peers have a nonzero port; integrated servers use a local address.
        return !(handler.getConnectionAddress() instanceof InetSocketAddress address) || address.getPort() != 0;
    }

    static String title(Map<String, Object> state, LinkedHashMap<UUID, String> roster, boolean offline) {
        int round = state.get("round") instanceof Number number ? number.intValue() : 0;
        String prefix = "七连翻 · 第 " + round + " 轮 · ";
        if (Boolean.TRUE.equals(state.get("finished"))) {
            String winner = player(state.get("winner"), roster, state);
            return "七连翻 · 对局结束 · " + (winner.isEmpty() ? "查看最终排名" : winner + "获胜");
        }
        boolean roundOver = Boolean.TRUE.equals(state.get("roundOver"));
        String actor = player(state.get("turn"), roster, state);
        if (offline) return prefix + (roundOver ? "轮末结算 · " : "") + "暂停：等待玩家重连"
                + (!roundOver && !actor.isEmpty() ? " · 当前 " + actor : "");
        if (roundOver) {
            String tied = Boolean.TRUE.equals(state.get("tiedAtThreshold")) ? "最高分并列 · " : "";
            return prefix + "轮末结算 · " + tied
                    + (Boolean.TRUE.equals(state.get("autoNext")) ? "等待下一轮" : "自动换轮已暂停");
        }
        boolean pending = "waiting_target".equals(state.get("flowState")) || state.get("pendingAction") instanceof Map<?, ?>;
        if (pending) {
            String owner = player(state.get("pendingOwner"), roster, state);
            if (!owner.isEmpty()) actor = owner;
            return prefix + (actor.isEmpty() ? "等待选择目标" : "轮到 " + actor + "选择目标");
        }
        String dealing = Boolean.TRUE.equals(state.get("initialDealing")) ? "初始发牌 · " : "";
        return prefix + dealing + (actor.isEmpty() ? "发牌处理中" : "轮到 " + actor + "翻牌或收手");
    }

    private static String player(Object value, LinkedHashMap<UUID, String> roster, Map<String,Object> state) {
        if (value == null) return "";
        UUID id;
        try { id = UUID.fromString(value.toString()); }
        catch (IllegalArgumentException invalid) { return ""; }
        int seat = 0;
        for (var entry : roster.entrySet()) {
            seat++;
            if (entry.getKey().equals(id)) {
                if (state.get("players") instanceof Map<?,?> players
                        && players.get(id.toString()) instanceof Map<?,?> row
                        && row.get("seat") instanceof Number fixed && fixed.intValue()>0) seat=fixed.intValue();
                return seat + " 号玩家（" + entry.getValue() + "）";
            }
        }
        return "";
    }

    static void disconnect(ServerPlayerEntity player) {
        for (var turnBar : BARS.values()) {
            turnBar.bar.removePlayer(player);
            turnBar.connections.entrySet().removeIf(entry -> entry.getValue() == player);
        }
    }

    static void clear(Sessions.Session session) {
        TurnBar bar = BARS.remove(session);
        if (bar != null) clear(bar);
    }

    private static void clear(TurnBar turnBar) {
        for (var handler : turnBar.connections.keySet())
            if (handler.isConnectionOpen()) handler.sendPacket(BossBarS2CPacket.remove(turnBar.bar.getUuid()));
        turnBar.bar.clearPlayers();
        turnBar.connections.clear();
    }

    static void clear() {
        for (var bar : BARS.values()) clear(bar);
        BARS.clear();
    }
}
