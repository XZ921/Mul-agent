package cn.bugstack.competitoragent.model.dto;

import cn.bugstack.competitoragent.collection.CollectionAuditSnapshot;
import cn.bugstack.competitoragent.collection.CollectionExecutionResult;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * collection 审计轻量摘要。
 * 供 insight / replay / runtime event 主路径优先消费，避免下游反复解析完整审计快照。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class CollectionAuditSummary {

    private Integer totalPackages;
    private Integer successCount;
    private Integer failedCount;
    private Integer reusedCount;
    private String status;
    private String recoveryCheckpoint;
    private List<String> sourceUrls;

    /**
     * 从正式审计快照提取轻量摘要。
     * 显式兜底空列表与空计数，确保 runtime / insight / replay 不再因为 null 分叉。
     */
    public static CollectionAuditSummary from(CollectionAuditSnapshot snapshot) {
        if (snapshot == null) {
            return CollectionAuditSummary.builder()
                    .totalPackages(0)
                    .successCount(0)
                    .failedCount(0)
                    .reusedCount(0)
                    .sourceUrls(List.of())
                    .build();
        }
        List<CollectionExecutionResult> results = snapshot.getResults() == null ? List.of() : snapshot.getResults();
        int successCount = 0;
        int failedCount = 0;
        int reusedCount = 0;
        for (CollectionExecutionResult result : results) {
            if (result == null) {
                continue;
            }
            if (result.isSuccess()) {
                successCount++;
            } else {
                failedCount++;
            }
            if (Boolean.TRUE.equals(result.getReusedFromCheckpoint())) {
                reusedCount++;
            }
        }
        return CollectionAuditSummary.builder()
                .totalPackages(results.size())
                .successCount(successCount)
                .failedCount(failedCount)
                .reusedCount(reusedCount)
                .status(snapshot.getStatus())
                .recoveryCheckpoint(snapshot.getRecoveryCheckpoint())
                .sourceUrls(snapshot.getSourceUrls() == null ? List.of() : snapshot.getSourceUrls())
                .build();
    }
}
