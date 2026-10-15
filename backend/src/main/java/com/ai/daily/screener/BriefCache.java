package com.ai.daily.screener;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 大模型解读的进程内缓存。**与主查询的缓存分开两处**，这是有意的：
 * {@link CodeLookupService} 那份只缓存「未降级」的行情结果，塞进去会让
 * 「一次大模型超时」和「东财被限流」共用一个失效逻辑——两者的正确处置完全不同
 * （前者重试就行，后者要等冷却）。合在一起还会让主查询的缓冲区被解读文本占走。
 *
 * <h2>键里带快照时刻，而不是只带代码</h2>
 *
 * <p>键是 {@code 代码 + 底层数字的取数时刻}。这样「同一份快照重复点击不再计费」
 * 是**构造上成立**的，不需要任何额外的失效逻辑：换了快照，键就变了。
 * 只按代码缓存的话，隔天点开同一只票会拿到昨天的解读，而解读里的每个数字
 * 都来自昨天——两个日期对不上的页面比没有页面更糟（章程 §8）。
 *
 * <h2>容量</h2>
 *
 * <p>上限 {@value #MAX_ENTRIES} 条，超了就整体清空。**刻意不做 LRU**：
 * 这是个按需点击的页面，量级上一天也就几十条，写一个带链表淘汰的 LRU
 * 换来的收益是零，而多出来的那段代码要人读懂。清空这一个动作足够。
 */
final class BriefCache {

    /** 条数上限。见类注释：不做 LRU，超限整体清空。 */
    static final int MAX_ENTRIES = 200;

    private record Entry(AnalysisBrief brief, Instant at) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Duration ttl;

    BriefCache(Duration ttl) {
        this.ttl = ttl == null || ttl.isNegative() || ttl.isZero() ? Duration.ofMinutes(30) : ttl;
    }

    /**
     * 取一份还没过期的。**过期的直接返回空**——不返回过期数据再让调用方判断，
     * 那样每个调用点都要重写一遍这个判断。
     *
     * @param snapshotAt 底层数字的取数时刻，构成键的一部分
     */
    AnalysisBrief get(String code, String snapshotAt, Instant now) {
        Entry entry = entries.get(key(code, snapshotAt));
        if (entry == null) {
            return null;
        }
        if (Duration.between(entry.at(), now).compareTo(ttl) >= 0) {
            entries.remove(key(code, snapshotAt));
            return null;
        }
        return entry.brief();
    }

    /**
     * 存一份。**失败的解读也存**：未配置 key、模型超时这些都不是「过一秒就好」的状态，
     * 用户连点五次按钮就会打五次接口换回同一句话。真正修好后快照通常也变了，
     * 或者重启已经清空了这一份。
     */
    void put(String code, String snapshotAt, AnalysisBrief brief, Instant now) {
        if (entries.size() >= MAX_ENTRIES) {
            entries.clear();
        }
        entries.put(key(code, snapshotAt), new Entry(brief, now));
    }

    int size() {
        return entries.size();
    }

    void clear() {
        entries.clear();
    }

    private static String key(String code, String snapshotAt) {
        return code + '|' + snapshotAt;
    }
}
