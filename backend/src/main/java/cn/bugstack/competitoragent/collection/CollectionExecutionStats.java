package cn.bugstack.competitoragent.collection;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Collection 执行耗时统计。
 * 用于验证效率优化是否来自复用与并发，而不是减少页面数量。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CollectionExecutionStats {

    private Integer totalPackageCount;
    private Integer successCount;
    private Integer failedCount;
    private Integer prefetchedReuseCount;
    private Integer checkpointReuseCount;
    private Integer executorCallCount;
    private Integer configuredConcurrency;
    private Long elapsedMillis;
}
