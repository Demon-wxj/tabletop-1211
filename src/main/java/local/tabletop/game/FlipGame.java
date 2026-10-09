package local.tabletop.game;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/** Server-owned Flip 7 ruleset 3.1, with the table's custom second-chance pair removal. */
public final class FlipGame {
    public static final int WINNING_SCORE = 200;
    public static final int DEFAULT_AUTO_NEXT_DELAY_TICKS = 60;
    /** A confirmed draw, including its immutable public row before any later forced draw. */
    public record Reveal(UUID player, String cardId, String label, long sequence, int round,
                         String outcome, List<String> cards, List<String> tableCards,
                         List<String> removedCards, String status, int roundScore) {
        public Reveal {
            cards = List.copyOf(cards);
            tableCards = List.copyOf(tableCards);
            removedCards = List.copyOf(removedCards);
        }
        /** Retains the old constructor for consumers that supply cosmetic reveals themselves. */
        public Reveal(UUID player, String cardId, String label) {
            this(player, cardId, label, 0L, 0, "unknown", List.of(), List.of(), List.of(), "active", 0);
        }
    }

    private enum Kind { NUMBER, PLUS, DOUBLE, FREEZE, FLIP_THREE, SECOND_CHANCE }
    private record Card(String id, String label, Kind kind, int value) { }

    private static final class Player {
        final UUID id;
        final List<Card> cards = new ArrayList<>();
        final Set<Integer> numbers = new LinkedHashSet<>();
        // A busting duplicate or resolved Freeze is a public table prop until
        // the round ends, but is not part of the player's scoring hand.
        final List<Card> spentOnTable = new ArrayList<>();
        int total;
        boolean active = true;
        boolean busted;
        boolean received;
        String status = "active";
        Player(UUID id) { this.id = id; }
    }

    private interface Task { void execute(FlipGame game); }
    private record InitialOffer(UUID player) implements Task {
        public void execute(FlipGame game) {
            Player state = game.state(player);
            if (!state.active) return;
            if (state.received) {
                // A preceding Flip Three may already have dealt this player
                // cards. They now have the same Hit/Stay choice as in live play.
                game.normalTurn = player;
                game.initialChoice = true;
            } else {
                game.receive(player, null);
            }
        }
    }
    private record InitialFinished() implements Task {
        public void execute(FlipGame game) {
            game.initialDealing = false;
            game.chooseNextTurn(game.dealerIndex - 1);
        }
    }
    private record TurnFinished(int index) implements Task {
        public void execute(FlipGame game) { game.chooseNextTurn(index); }
    }
    private static final class Batch {
        final UUID owner;
        final Card original;
        int remaining = 3;
        final List<Card> deferred = new ArrayList<>();
        final List<AssignedAction> assigned = new ArrayList<>();
        Batch(UUID owner, Card original) { this.owner = owner; this.original = original; }
    }
    private record ForcedDraw(Batch batch) implements Task {
        public void execute(FlipGame game) {
            if (!game.state(batch.owner).active) {
                game.discardFlight(batch.original);
                for (Card action : batch.deferred) game.discardFlight(action);
                return;
            }
            if (batch.remaining == 0) {
                game.discardFlight(batch.original);
                game.tasks.addFirst(new AssignBatch(batch, 0));
                return;
            }
            batch.remaining--;
            game.tasks.addFirst(this);
            game.receive(batch.owner, batch);
        }
    }
    private record AssignBatch(Batch batch, int index) implements Task {
        public void execute(FlipGame game) {
            if (index == batch.deferred.size()) {
                // Assign ALL deferred actions before applying any of them.
                // Therefore two actions can validly target the same player;
                // a later Flip Three is discarded if an earlier Freeze made
                // that assigned recipient inactive.
                for (int i = batch.assigned.size() - 1; i >= 0; i--)
                    game.tasks.addFirst(batch.assigned.get(i));
                return;
            }
            Card action = batch.deferred.get(index);
            game.pending = new Pending(batch.owner, action, game.activeIds(), "assign_chain", index + 1, batch.deferred.size(), 0, chosen -> {
                batch.assigned.add(new AssignedAction(action, chosen));
                game.tasks.addFirst(new AssignBatch(batch, index + 1));
            });
        }
    }
    private record AssignedAction(Card card, UUID target) implements Task {
        public void execute(FlipGame game) {
            Player recipient = game.state(target);
            if (!recipient.active) {
                game.discardFlight(card);
                return;
            }
            if (card.kind == Kind.FREEZE) {
                if (!game.inFlight.remove(card))
                    throw new IllegalStateException("行动牌已经结算，不能重复结算");
                recipient.spentOnTable.add(card);
                recipient.active = false;
                recipient.status = "frozen";
                game.effect(target,"freeze");
            } else if (card.kind == Kind.FLIP_THREE) {
                game.effect(target,"lightning");
                game.tasks.addFirst(new ForcedDraw(new Batch(target, card)));
            } else {
                throw new IllegalStateException("无效的待执行行动牌");
            }
        }
    }
    private interface TargetSelection { void selected(UUID player); }
    private record Pending(UUID owner, Card card, List<UUID> targets, String stage,
                           int assignmentNumber, int assignmentCount, int forcedDrawRemaining,
                           TargetSelection selection) { }

    private final List<UUID> players;
    private final Map<UUID, Player> states = new LinkedHashMap<>();
    private final Random random;
    private final Deque<Card> deck = new ArrayDeque<>();
    private final List<Card> discard = new ArrayList<>();
    private final List<Card> inFlight = new ArrayList<>();
    private final Deque<Task> tasks = new ArrayDeque<>();
    private final List<Reveal> reveals = new ArrayList<>();
    public record Effect(UUID player,String kind) {}
    private final Deque<Effect> effects = new ArrayDeque<>();
    private void effect(UUID player,String kind){if(effects.size()==64)effects.removeFirst();effects.addLast(new Effect(player,kind));}
    public List<Effect> drainEffects(){List<Effect> result=List.copyOf(effects);effects.clear();return result;}
    private Reveal lastReveal;
    private long revealSequence;
    private Pending pending;
    private UUID normalTurn;
    private boolean initialChoice;
    private boolean initialDealing;
    private int dealerIndex;
    private int round = 1;
    private boolean roundOver;
    private boolean finished;
    private UUID winner;
    private UUID sevenPlayer;
    private String roundEndReason;
    private boolean autoNext = false;
    private boolean automationOnline = true;
    private int autoNextDelayTicks = DEFAULT_AUTO_NEXT_DELAY_TICKS;
    private long lastAutomationTick = -1;
    private long autoNextAtTick = -1;

    public FlipGame(List<UUID> players, long seed) { this(players, seed, List.of()); }

    /** Deterministic valid deck prefix, package-private for regression tests. */
    FlipGame(List<UUID> players, long seed, List<String> prefix) {
        if (players == null || players.size() < 3 || players.size() > 18)
            throw new IllegalArgumentException("七连翻单副牌支持3至18名玩家");
        if (players.stream().anyMatch(Objects::isNull) || new HashSet<>(players).size() != players.size())
            throw new IllegalArgumentException("玩家必须非空且不能重复");
        this.players = List.copyOf(players);
        this.random = new Random(seed);
        for (UUID player : players) states.put(player, new Player(player));
        List<Card> remaining = standardDeck();
        Collections.shuffle(remaining, random);
        for (String id : prefix) {
            int found = -1;
            for (int i = 0; i < remaining.size(); i++) {
                if (remaining.get(i).id.equals(id)) { found = i; break; }
            }
            if (found < 0) throw new IllegalArgumentException("预置牌超出标准牌组：" + id);
            deck.addLast(remaining.remove(found));
        }
        deck.addAll(remaining);
        beginRound();
    }

    public List<UUID> players() { return players; }
    public UUID turn() { return pending == null ? normalTurn : pending.owner; }
    public boolean finished() { return finished; }
    public boolean roundOver() { return roundOver; }
    public boolean autoNext() { return autoNext; }

    /** Pauses automatic round advancement, without choosing players' actions. */
    public String setAutoNext(boolean enabled) {
        if (autoNext != enabled) autoNextAtTick = -1;
        autoNext = enabled;
        return enabled ? "自动换轮已开启，轮末计分展示后推进" : "自动换轮已暂停，主持人可手动开始下一轮";
    }

    /** A server tick lasts 50 ms under normal conditions. */
    public void setAutoNextDelayTicks(int delayTicks) {
        if (delayTicks < 1 || delayTicks > 1200)
            throw new IllegalArgumentException("自动换轮延迟应为1至1200个服务器刻");
        if (autoNextDelayTicks != delayTicks) autoNextAtTick = -1;
        autoNextDelayTicks = delayTicks;
    }

    /**
     * Call from the authoritative server's monotonic tick loop. A non-null
     * result means one next round began and its table needs rendering/refresh.
     * The result remains visible for the full configured delay. Disconnection
     * cancels the countdown; reconnection begins a fresh result-display delay.
     * This never starts a new match or selects a pending action target.
     */
    public String updateAutomation(long serverTick, boolean everyoneOnline) {
        if (serverTick < 0 || serverTick < lastAutomationTick || serverTick > Long.MAX_VALUE - 1200)
            throw new IllegalArgumentException("自动流程需要递增的有效服务器刻");
        lastAutomationTick = serverTick;
        automationOnline = everyoneOnline;
        if (!autoNext || !everyoneOnline || !roundOver || finished) {
            autoNextAtTick = -1;
            return null;
        }
        if (autoNextAtTick < 0) {
            autoNextAtTick = serverTick + autoNextDelayTicks;
            return null;
        }
        if (serverTick < autoNextAtTick) return null;
        return nextRound();
    }

    public String draw(UUID player) {
        requireTurn(player);
        boolean wasInitial = initialChoice;
        normalTurn = null;
        initialChoice = false;
        if (!wasInitial) tasks.addFirst(new TurnFinished(players.indexOf(player)));
        int before = reveals.size();
        receive(player, null);
        pump();
        Reveal reveal = reveals.get(before);
        String result = "翻开了「" + reveal.label + "」";
        if (state(player).busted) result += "，重复数字爆牌，本轮0分";
        else if (reveal.outcome.equals("saved")) result += "，保命牌已消耗，新旧两张重复数字牌均已移除";
        if (pending != null) result += "，请获得行动牌的玩家选择目标";
        if (roundOver) result += "；本轮结束" + (finished ? "，对局已决出胜者" : "");
        return result;
    }

    public String stay(UUID player) {
        requireTurn(player);
        Player state = state(player);
        if (state.cards.isEmpty()) throw new IllegalStateException("没有牌时不能停牌，请先翻牌");
        boolean wasInitial = initialChoice;
        normalTurn = null;
        initialChoice = false;
        state.active = false;
        state.status = "stayed";
        if (!wasInitial) tasks.addFirst(new TurnFinished(players.indexOf(player)));
        pump();
        return "已停牌，本轮保留" + score(state) + "分" + (roundOver ? "；本轮结束" : "");
    }

    public String target(UUID chooser, UUID target) {
        state(chooser);
        state(target);
        requireOpen();
        if (pending == null) throw new IllegalStateException("当前没有需要选择目标的行动牌");
        if (!pending.owner.equals(chooser)) throw new IllegalArgumentException("只有获得该行动牌的玩家可以选择目标");
        if (!pending.targets.contains(target) || !state(target).active)
            throw new IllegalArgumentException("目标必须是本行动允许的活跃玩家");
        Pending selected = pending;
        pending = null;
        selected.selection.selected(target);
        pump();
        return "已指定「" + selected.card.label + "」的目标" + (roundOver ? "；本轮结束" : "");
    }

    public String nextRound() {
        if (finished) throw new IllegalStateException("对局已结束，请创建新对局");
        if (!roundOver) throw new IllegalStateException("当前轮次尚未结束");
        autoNextAtTick = -1;
        for (Player player : states.values()) {
            discard.addAll(player.cards);
            discard.addAll(player.spentOnTable);
            player.cards.clear();
            player.spentOnTable.clear();
            player.numbers.clear();
            player.active = true;
            player.busted = false;
            player.received = false;
            player.status = "active";
        }
        dealerIndex = (dealerIndex + 1) % players.size();
        round++;
        roundOver = false;
        beginRound();
        return "第" + round + "轮开始，庄家已轮换";
    }

    public List<Reveal> drainReveals() {
        List<Reveal> result = List.copyOf(reveals);
        reveals.clear();
        return result;
    }

    public Map<String, Object> publicView() {
        Map<String, Object> view = new LinkedHashMap<>();
        if (lastReveal != null) {
            Map<String, Object> reveal = new LinkedHashMap<>();
            reveal.put("player", lastReveal.player().toString());
            reveal.put("cardId", lastReveal.cardId()); reveal.put("label", lastReveal.label());
            reveal.put("sequence", lastReveal.sequence()); reveal.put("round", lastReveal.round());
            reveal.put("outcome", lastReveal.outcome()); reveal.put("cards", lastReveal.cards());
            reveal.put("tableCards", lastReveal.tableCards()); reveal.put("removedCards", lastReveal.removedCards());
            reveal.put("status", lastReveal.status()); reveal.put("roundScore", lastReveal.roundScore());
            view.put("lastReveal", Collections.unmodifiableMap(reveal));
        }
        view.put("round", round);
        view.put("dealer", players.get(dealerIndex).toString());
        view.put("turn", turn() == null ? null : turn().toString());
        view.put("initialDealing", initialDealing);
        view.put("roundOver", roundOver);
        view.put("finished", finished);
        view.put("flowState", finished ? "finished" : roundOver ? "round_results" : pending != null ? "waiting_target" : "playing");
        view.put("autoNext", autoNext);
        view.put("autoNextDelayTicks", autoNextDelayTicks);
        view.put("autoNextAtTick", autoNextAtTick < 0 ? null : autoNextAtTick);
        view.put("autoNextInTicks", autoNextAtTick < 0 ? null : Math.max(0L, autoNextAtTick - lastAutomationTick));
        view.put("autoNextWaitingForOnline", autoNext && roundOver && !finished && !automationOnline);
        view.put("winner", winner == null ? null : winner.toString());
        view.put("sevenPlayer", sevenPlayer == null ? null : sevenPlayer.toString());
        view.put("roundEndReason", roundEndReason);
        int best = states.values().stream().mapToInt(player -> player.total).max().orElse(0);
        List<String> leaders = states.values().stream().filter(player -> player.total == best).map(player -> player.id.toString()).toList();
        view.put("leaders", leaders);
        view.put("tiedAtThreshold", roundOver && !finished && best >= WINNING_SCORE && leaders.size() > 1);
        view.put("flowTitle", flowTitle());
        view.put("flowInstruction", flowInstruction());
        view.put("winningScore", WINNING_SCORE);
        view.put("deckRemaining", deck.size());
        view.put("discardCount", discard.size());
        view.put("inFlightCount", inFlight.size());
        view.put("pendingOwner", pending == null ? null : pending.owner.toString());
        view.put("pendingKind", pending == null ? null : pending.card.id);
        view.put("pendingTargets", pending == null ? List.of() : pending.targets.stream().map(UUID::toString).toList());
        view.put("pendingAction", pendingAction());
        Map<String, Object> publicPlayers = new LinkedHashMap<>();
        for (Player player : states.values()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("numbers", List.copyOf(player.numbers));
            data.put("cards", player.cards.stream().map(Card::id).toList());
            data.put("tableCards", tableCards(player));
            data.put("cardLabels", player.cards.stream().map(Card::label).toList());
            data.put("total", player.total);
            data.put("roundScore", score(player));
            data.put("numberCount", player.numbers.size());
            data.put("remainingToSeven", Math.max(0, 7 - player.numbers.size()));
            data.put("active", player.active);
            data.put("status", player.status);
            data.put("secondChance", chance(player) != null);
            data.put("spentOnTableCount", player.spentOnTable.size());
            boolean normalChoice = !roundOver && !finished && pending == null && player.id.equals(normalTurn) && player.active;
            data.put("canDraw", normalChoice);
            data.put("canStay", normalChoice && !player.cards.isEmpty());
            data.put("canSelectTarget", !roundOver && !finished && pending != null && player.id.equals(pending.owner));
            data.put("scoreDetails", scoreDetails(player));
            publicPlayers.put(player.id.toString(), Collections.unmodifiableMap(data));
        }
        view.put("players", Collections.unmodifiableMap(publicPlayers));
        List<Player> ranked = new ArrayList<>(states.values());
        ranked.sort((a, b) -> Integer.compare(b.total, a.total));
        List<Map<String, Object>> ranking = new ArrayList<>();
        int rank = 0, previous = -1;
        for (int index = 0; index < ranked.size(); index++) {
            Player player = ranked.get(index);
            if (player.total != previous) rank = index + 1;
            previous = player.total;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("player", player.id.toString()); row.put("rank", rank);
            row.put("total", player.total); row.put("roundScore", score(player)); row.put("status", player.status);
            ranking.add(Collections.unmodifiableMap(row));
        }
        view.put("ranking", List.copyOf(ranking));
        return Collections.unmodifiableMap(view);
    }

    private String flowTitle() {
        if (finished) return "对局结束 · 最高总分获胜";
        if (roundOver) {
            int best = states.values().stream().mapToInt(player -> player.total).max().orElse(0);
            long leaders = states.values().stream().filter(player -> player.total == best).count();
            return "第" + round + "轮结算" + (best >= WINNING_SCORE && leaders > 1 ? " · 最高分平分，继续加轮" : "");
        }
        if (pending != null) return "待办 · " + pending.card.label + "选择目标";
        return initialDealing ? "初次发牌 · 已有牌者选择翻或停" : "第" + round + "轮 · 翻牌或收手";
    }

    private String flowInstruction() {
        if (finished) return "本轮分数已计入总分。最终排名保留在桌面，主持人可准备下一局。";
        if (roundOver) return sevenPlayer != null
                ? "有人集齐七种数字，立即结束全桌本轮；未爆牌者计分，集齐者另加15分。"
                : "本轮已无活跃玩家，所有得分已一次性计入总分；爆牌者本轮0分。";
        if (pending != null) return pendingInstruction();
        return "只有当前行动玩家选择翻牌或收手。没有牌时必须先翻牌；其他玩家等待桌边轮转。";
    }

    private Map<String, Object> pendingAction() {
        if (pending == null) return null;
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("kind", pending.card.id); action.put("title", pending.card.label);
        action.put("owner", pending.owner.toString()); action.put("targets", pending.targets.stream().map(UUID::toString).toList());
        action.put("stage", pending.stage); action.put("assignmentNumber", pending.assignmentNumber);
        action.put("assignmentCount", pending.assignmentCount); action.put("forcedDrawRemaining", pending.forcedDrawRemaining);
        action.put("instruction", pendingInstruction());
        return Collections.unmodifiableMap(action);
    }

    private String pendingInstruction() {
        String explanation = switch (pending.card.kind) {
            case FREEZE -> "选一名活跃玩家冻结，他保留当前分数并退出本轮；可以选自己。";
            case FLIP_THREE -> "选一名活跃玩家连翻三张；爆牌或集齐七种数字会提前停止，可以选自己。";
            case SECOND_CHANCE -> "这张多余的二次机会必须赠给另一名活跃且没有保护的玩家。";
            default -> throw new IllegalStateException("非行动牌不能等待目标");
        };
        if (pending.stage.equals("assign_chain"))
            explanation = "连翻后的行动目标 " + pending.assignmentNumber + "/" + pending.assignmentCount + "：" + explanation
                    + " 全部目标选完后，再按翻出顺序执行行动。";
        if (pending.stage.equals("during_flip_three"))
            explanation += pending.forcedDrawRemaining > 0
                    ? " 赠送后继续余下" + pending.forcedDrawRemaining + "张强制翻牌。"
                    : " 赠送后处理本次三张里的暂存行动，再恢复正常轮转。";
        if (pending.targets.size() == 1) explanation += " 当前只有一个合法目标，确认该玩家即可。";
        return explanation;
    }

    private Map<String, Object> scoreDetails(Player player) {
        int numberTotal = player.numbers.stream().mapToInt(Integer::intValue).sum();
        int additions = player.cards.stream().filter(card -> card.kind == Kind.PLUS).mapToInt(Card::value).sum();
        int multiplier = player.cards.stream().anyMatch(card -> card.kind == Kind.DOUBLE) ? 2 : 1;
        int sevenBonus = player.numbers.size() == 7 ? 15 : 0;
        int result = score(player);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("numberTotal", numberTotal); details.put("multiplier", multiplier); details.put("modifierTotal", additions);
        details.put("sevenBonus", sevenBonus); details.put("roundScore", result); details.put("busted", player.busted);
        details.put("beforeTotal", roundOver ? player.total - result : player.total);
        details.put("afterTotal", roundOver ? player.total : player.total + result);
        details.put("banked", roundOver);
        return Collections.unmodifiableMap(details);
    }

    private void beginRound() {
        sevenPlayer = null;
        roundEndReason = null;
        initialDealing = true;
        initialChoice = false;
        normalTurn = null;
        pending = null;
        tasks.clear();
        for (int i = 0; i < players.size(); i++)
            tasks.addLast(new InitialOffer(players.get((dealerIndex + i) % players.size())));
        tasks.addLast(new InitialFinished());
        pump();
    }

    private void pump() {
        while (!roundOver && pending == null && normalTurn == null && !tasks.isEmpty()) {
            if (activeIds().isEmpty()) { finishRound(); break; }
            tasks.removeFirst().execute(this);
        }
        if (!roundOver && activeIds().isEmpty()) finishRound();
        if (!roundOver && pending == null && normalTurn == null && tasks.isEmpty())
            throw new IllegalStateException("轮次缺少下一步操作");
    }

    private void receive(UUID playerId, Batch batch) {
        Player player = state(playerId);
        if (!player.active) throw new IllegalStateException("非活跃玩家不能接收新牌");
        Card card = takeCard();
        player.received = true;
        String outcome = "retained";
        List<String> removedCards = List.of();
        switch (card.kind) {
            case NUMBER -> {
                if (player.numbers.contains(card.value)) {
                    Card protection = chance(player);
                    if (protection != null) {
                        Card previous = player.cards.stream().filter(held -> held.kind == Kind.NUMBER && held.value == card.value)
                                .findFirst().orElseThrow(() -> new IllegalStateException("重复数字缺少原有牌"));
                        player.cards.remove(protection);
                        player.cards.remove(previous);
                        player.numbers.remove(card.value);
                        discard.add(protection);
                        discard.add(previous);
                        discard.add(card);
                        outcome = "saved";
                        removedCards = List.of(protection.id, previous.id, card.id);
                        effect(playerId,"shield_break");
                    } else {
                        player.spentOnTable.add(card);
                        player.active = false;
                        player.busted = true;
                        player.status = "busted";
                        outcome = "busted";
                        effect(playerId,"bust");
                    }
                } else {
                    player.cards.add(card);
                    player.numbers.add(card.value);
                    if (player.numbers.size() == 7) { sevenPlayer = playerId; finishRound(); }
                }
            }
            case PLUS, DOUBLE -> player.cards.add(card);
            case SECOND_CHANCE -> {
                if (chance(player) == null) {
                    player.cards.add(card);
                } else {
                    List<UUID> eligible = players.stream().filter(id -> !id.equals(playerId))
                            .filter(id -> state(id).active && chance(state(id)) == null).toList();
                    if (eligible.isEmpty()) {
                        discard.add(card);
                        outcome = "discarded";
                    } else {
                        inFlight.add(card);
                        outcome = "transfer";
                        pending = new Pending(playerId, card, eligible, batch == null ? "direct" : "during_flip_three", 0, 0, batch == null ? 0 : batch.remaining, chosen -> {
                            inFlight.remove(card);
                            state(chosen).cards.add(card);
                            state(chosen).received = true;
                        });
                    }
                }
            }
            case FREEZE, FLIP_THREE -> {
                inFlight.add(card);
                outcome = "action";
                if (batch != null) {
                    batch.deferred.add(card);
                } else {
                    pending = new Pending(playerId, card, activeIds(), "direct", 0, 0, 0, chosen ->
                            tasks.addFirst(new AssignedAction(card, chosen)));
                }
            }
        }
        lastReveal = new Reveal(playerId, card.id, card.label, ++revealSequence, round, outcome,
                player.cards.stream().map(Card::id).toList(), tableCards(player), removedCards,
                player.status, score(player));
        reveals.add(lastReveal);
    }

    private List<String> tableCards(Player player) {
        List<Card> cards = new ArrayList<>(player.cards);
        cards.addAll(player.spentOnTable);
        return cards.stream().map(Card::id).toList();
    }

    private Card takeCard() {
        if (deck.isEmpty()) {
            if (discard.isEmpty()) throw new IllegalStateException("没有可回收弃牌；本轮场上牌不能洗回牌堆，请减少人数或使用更多牌副");
            Collections.shuffle(discard, random);
            deck.addAll(discard);
            discard.clear();
        }
        return deck.removeFirst();
    }

    private void discardFlight(Card card) {
        if (!inFlight.remove(card)) throw new IllegalStateException("行动牌已经结算，不能重复结算");
        discard.add(card);
    }

    private void chooseNextTurn(int after) {
        if (roundOver) return;
        for (int offset = 1; offset <= players.size(); offset++) {
            UUID candidate = players.get(Math.floorMod(after + offset, players.size()));
            if (state(candidate).active) {
                normalTurn = candidate;
                initialChoice = false;
                return;
            }
        }
        finishRound();
    }

    private void finishRound() {
        if (roundOver) return;
        roundOver = true;
        roundEndReason = sevenPlayer == null ? "no_active" : "flip_seven";
        autoNextAtTick = -1;
        initialDealing = false;
        initialChoice = false;
        normalTurn = null;
        pending = null;
        tasks.clear();
        discard.addAll(inFlight);
        inFlight.clear();
        int best = -1;
        int bestCount = 0;
        UUID leader = null;
        for (Player player : states.values()) {
            player.total += score(player);
            player.active = false;
            if (player.status.equals("active")) player.status = "round_end";
            if (player.total > best) { best = player.total; bestCount = 1; leader = player.id; }
            else if (player.total == best) bestCount++;
        }
        if (best >= WINNING_SCORE && bestCount == 1) {
            finished = true;
            winner = leader;
        }
    }

    private int score(Player player) {
        if (player.busted) return 0;
        int numberTotal = player.numbers.stream().mapToInt(Integer::intValue).sum();
        int additions = 0;
        int multiplier = 1;
        for (Card card : player.cards) {
            if (card.kind == Kind.PLUS) additions += card.value;
            if (card.kind == Kind.DOUBLE) multiplier = 2;
        }
        return numberTotal * multiplier + additions + (player.numbers.size() == 7 ? 15 : 0);
    }

    private Card chance(Player player) {
        for (Card card : player.cards) if (card.kind == Kind.SECOND_CHANCE) return card;
        return null;
    }

    private List<UUID> activeIds() { return players.stream().filter(id -> state(id).active).toList(); }
    private Player state(UUID player) {
        Player state = states.get(player);
        if (state == null) throw new IllegalArgumentException("玩家不在本局中");
        return state;
    }
    private void requireOpen() {
        if (finished) throw new IllegalStateException("对局已经结束");
        if (roundOver) throw new IllegalStateException("本轮已结束，请开始下一轮");
    }
    private void requireTurn(UUID player) {
        state(player);
        requireOpen();
        if (pending != null) throw new IllegalStateException("请先选择行动牌目标");
        if (!player.equals(normalTurn)) throw new IllegalArgumentException("尚未轮到该玩家");
        if (!state(player).active) throw new IllegalStateException("该玩家本轮已经离场");
    }

    private static List<Card> standardDeck() {
        List<Card> result = new ArrayList<>(94);
        for (int number = 0; number <= 12; number++) {
            for (int copy = 0; copy < Math.max(1, number); copy++)
                result.add(new Card("number_" + number, Integer.toString(number), Kind.NUMBER, number));
        }
        for (int addition = 2; addition <= 10; addition += 2)
            result.add(new Card("plus_" + addition, "+" + addition, Kind.PLUS, addition));
        result.add(new Card("times_two", "×2", Kind.DOUBLE, 0));
        for (int copy = 0; copy < 3; copy++) {
            result.add(new Card("freeze", "冻结", Kind.FREEZE, 0));
            result.add(new Card("flip_three", "连翻三张", Kind.FLIP_THREE, 0));
            result.add(new Card("second_chance", "二次机会", Kind.SECOND_CHANCE, 0));
        }
        return result;
    }
}
