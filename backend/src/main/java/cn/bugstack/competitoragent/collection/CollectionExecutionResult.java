package cn.bugstack.competitoragent.collection;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 最小采集执行结果。
 * 无论网页采集还是结构化 API 采集，都先收敛到同一份最小结果协议，便于 CollectorAgent 兼容映射。
 */
@Value
@Builder
public class CollectionExecutionResult {

    String executorType;
    boolean success;
    String resourceLocator;
    String title;
    String content;
    List<String> sourceUrls;
    Map<String, Object> structuredPayload;
    String errorMessage;
    String failureKind;
    List<String> qualitySignals;
    Double qualityScore;
    List<StructuredContentBlock> structuredBlocks;
    Instant collectedAt;
    Long durationMillis;
}
