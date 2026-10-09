package local.tabletop.game;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;

/** Trouble Brewing only. Chinese descriptions are concise, original summaries.
 * Relative ranks follow the user's official electronic-grimoire screenshots;
 * these are not the generic cross-script Script Tool indices or physical-sheet order. */
public final class RoleCatalog {
    public record Role(String id, String name, String type, String ability, int firstNight, int otherNight) {}
    private static final Map<String, Role> ROLES = new LinkedHashMap<>();
    static {
        add("washerwoman", "洗衣妇", "townsfolk", "仅首夜，得知两名玩家中有一人是某个指定镇民。", 4, 0);
        add("librarian", "图书管理员", "townsfolk", "仅首夜，得知两名玩家中有一人是某个指定外来者，或得知没有外来者在场。", 5, 0);
        add("investigator", "调查员", "townsfolk", "仅首夜，得知两名玩家中有一人是某个指定爪牙。", 6, 0);
        add("chef", "厨师", "townsfolk", "仅首夜，得知座位相邻的邪恶玩家共有几对。连续三名邪恶玩家形成两对。", 7, 0);
        add("empath", "共情者", "townsfolk", "每晚得知自己两侧最近的存活邻居中有几人是邪恶阵营。", 8, 6);
        add("fortune_teller", "占卜师", "townsfolk", "每晚选择两名玩家，得知其中是否有恶魔。一名固定善良玩家也会对你显示为恶魔。", 9, 7);
        add("undertaker", "送葬者", "townsfolk", "除首夜外，若当天有人因处决死亡，得知其角色。", 0, 9);
        add("monk", "僧侣", "townsfolk", "除首夜外，每晚选择另一名玩家，使其当晚免受恶魔能力影响。", 0, 2);
        add("ravenkeeper", "守鸦人", "townsfolk", "若在夜间死亡，当晚选择一名玩家并得知其角色。", 0, 5);
        add("virgin", "贞洁者", "townsfolk", "第一次被提名时，若提名者被判定为镇民，提名者立即被处决。第一次提名后能力即用尽。", 0, 0);
        add("slayer", "猎手", "townsfolk", "每局一次，在白天公开选择一名玩家；若其被判定为恶魔，该玩家死亡。", 0, 0);
        add("soldier", "士兵", "townsfolk", "免受恶魔能力影响；仍然可以被处决。", 0, 0);
        add("mayor", "镇长", "townsfolk", "仅有三名玩家存活且白天无人被处决时，善良获胜。若你在夜间将死亡，说书人可以让另一名玩家代替。", 0, 0);
        add("butler", "管家", "outsider", "每晚选择另一名玩家作为主人；白天只有主人举手投票时，你才能投票。", 10, 8);
        add("drunk", "酒鬼", "outsider", "你不知道自己是酒鬼，以为自己是某个镇民；该镇民能力实际上无效，信息由说书人处理。", 0, 0);
        add("recluse", "陌客", "outsider", "你的能力可能使你被登记为邪恶阵营、爪牙或恶魔，即使你已经死亡。", 0, 0);
        add("saint", "圣徒", "outsider", "若因处决而死亡，你的阵营落败。", 0, 0);
        add("poisoner", "投毒者", "minion", "每晚选择一名玩家，使其在当晚及接下来的白天中毒；中毒者不知情且能力失效。", 3, 1);
        add("spy", "间谍", "minion", "每晚经说书人确认查看全桌真实身份与状态；你可能被登记为善良阵营、镇民或外来者，即使已死。", 11, 10);
        add("scarlet_woman", "红唇女郎", "minion", "恶魔死亡时，若包含恶魔在内至少有五名玩家存活，你成为恶魔。", 0, 3);
        add("baron", "男爵", "minion", "初始配置增加两名外来者，同时减少两名镇民。", 0, 0);
        add("imp", "小恶魔", "demon", "除首夜外，每晚选择一名玩家使其死亡；若通过此能力杀死自己，一名存活爪牙成为小恶魔。", 0, 4);
    }
    private static void add(String id, String name, String type, String ability, int first, int other) {
        ROLES.put(id, new Role(id, name, type, ability, first, other));
    }
    public static Collection<Role> all() { return Collections.unmodifiableCollection(ROLES.values()); }
    public static Role get(String id) {
        if (id == null) return null;
        String key = id.toLowerCase(Locale.ROOT).trim();
        if (key.equals("fortuneteller")) key = "fortune_teller";
        if (key.equals("scarletwoman")) key = "scarlet_woman";
        return ROLES.get(key);
    }
    private RoleCatalog() {}
}
