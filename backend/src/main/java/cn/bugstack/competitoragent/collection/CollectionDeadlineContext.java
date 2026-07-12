package cn.bugstack.competitoragent.collection;

/**
 * Collector 节点级共享 deadline 上下文。
 * 这里表达的是“整个 collector 节点还能不能继续启动新工作”，
 * 不是某个 executor、某次 HTTP 或单个 Playwright 调用的私有超时。
 *
 * canStartWork(...) 与 isExpired() 只看 hard deadline 本身；
 * drainGraceMillis 仅供 CollectorAgent 外层在超线后短暂回收已经启动的结果，
 * 内层 queue / batch / executor / Playwright 一律禁止用 grace 再启动新工作。
 */
public record CollectionDeadlineContext(
        Long hardDeadlineEpochMillis,
        Long drainGraceMillis,
        String degradationReason
) {

    public static CollectionDeadlineContext none() {
        return new CollectionDeadlineContext(Long.MAX_VALUE, 0L, null);
    }

    public static CollectionDeadlineContext hardDeadline(Long epochMillis, Long graceMillis) {
        long normalizedGraceMillis = Math.max(0L, graceMillis == null ? 0L : graceMillis);
        long normalizedEpochMillis = epochMillis == null ? Long.MAX_VALUE : epochMillis;
        return new CollectionDeadlineContext(normalizedEpochMillis, normalizedGraceMillis, "HARD_DEADLINE_REACHED");
    }

    public long remainingMillis() {
        if (hardDeadlineEpochMillis == null || hardDeadlineEpochMillis == Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, hardDeadlineEpochMillis - System.currentTimeMillis());
    }

    public boolean isExpired() {
        return remainingMillis() <= 0L;
    }

    public boolean canStartWork(long minStartBudgetMillis) {
        long remainingMillis = remainingMillis();
        return remainingMillis == Long.MAX_VALUE
                || remainingMillis >= Math.max(0L, minStartBudgetMillis);
    }
}
