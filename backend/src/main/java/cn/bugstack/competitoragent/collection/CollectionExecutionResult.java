package cn.bugstack.competitoragent.collection;

import cn.bugstack.competitoragent.source.SourceCandidate;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 最小采集执行结果。
 * 无论网页采集还是结构化 API 采集，都先收敛到同一份最小结果协议，便于 CollectorAgent 兼容映射。
 */
@Value
@Builder(toBuilder = true)
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class CollectionExecutionResult {

    String taskPackageKey;
    Integer targetIndex;
    String executorType;
    boolean success;
    String status;
    String resourceLocator;
    String title;
    String content;
    List<String> sourceUrls;
    List<SourceCandidate> discoveredCandidates;
    Integer discoveryDepth;
    Map<String, Object> structuredPayload;
    String errorMessage;
    String failureKind;
    List<String> qualitySignals;
    Double qualityScore;
    List<StructuredContentBlock> structuredBlocks;
    Instant collectedAt;
    Long durationMillis;
    Boolean reusedFromCheckpoint;
    String checkpointSource;

    /**
     * 统一收口 success / status / sourceUrls 的一致性。
     * 所有执行器结果、checkpoint 复用结果与兼容映射结果在落入正式 results 前都应先归一化。
     */
    public CollectionExecutionResult normalize() {
        String normalizedStatus = normalizeStatus(status, success);
        boolean normalizedSuccess = "SUCCESS".equals(normalizedStatus);
        List<String> normalizedSourceUrls = sourceUrls == null ? List.of() : sourceUrls;
        List<SourceCandidate> normalizedDiscoveredCandidates = discoveredCandidates == null ? List.of() : discoveredCandidates;
        Integer normalizedDiscoveryDepth = discoveryDepth == null ? 0 : Math.max(0, discoveryDepth);
        return this.toBuilder()
                .success(normalizedSuccess)
                .status(normalizedStatus)
                .sourceUrls(normalizedSourceUrls)
                .discoveredCandidates(normalizedDiscoveredCandidates)
                .discoveryDepth(normalizedDiscoveryDepth)
                .build();
    }

    private String normalizeStatus(String currentStatus, boolean currentSuccess) {
        if (currentStatus != null && !currentStatus.isBlank()) {
            return currentStatus.trim().toUpperCase(java.util.Locale.ROOT);
        }
        return currentSuccess ? "SUCCESS" : "FAILED";
    }
}
