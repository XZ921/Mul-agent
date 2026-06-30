package cn.bugstack.competitoragent.search;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.util.List;

/**
 * 证据修复计划。
 * 记录质量门禁或字段覆盖检查产生的补采查询、候选 URL 和最终提升结果，
 * 供审计 metadata 与再入闭环稳定消费。
 */
@Value
@Builder(toBuilder = true)
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class EvidenceRepairPlan {

    EvidenceRepairState state;
    String reason;
    String sourceUrl;
    List<String> repairQueries;
    List<String> candidateUrls;
    List<String> promotedUrls;

    /**
     * 判断 repair 是否已经真正完成。
     * 只有证据被提升，或字段路径已经闭环，才算完成。
     */
    public boolean isComplete() {
        return state == EvidenceRepairState.REPAIR_EVIDENCE_PROMOTED
                || state == EvidenceRepairState.REPAIR_FIELD_PATH_COMPLETED;
    }

    /**
     * 回填验证通过的候选 URL。
     * 没有 verified URL 时仍保持 QUERY_PROPOSED，说明 repair 还停留在查询建议阶段。
     */
    public EvidenceRepairPlan verifyCandidates(List<String> verifiedUrls) {
        List<String> normalizedUrls = immutableList(verifiedUrls);
        if (normalizedUrls.isEmpty()) {
            return this.toBuilder()
                    .state(EvidenceRepairState.REPAIR_QUERY_PROPOSED)
                    .candidateUrls(List.of())
                    .build();
        }
        return this.toBuilder()
                .state(EvidenceRepairState.REPAIR_CANDIDATE_VERIFIED)
                .candidateUrls(normalizedUrls)
                .build();
    }

    /**
     * 提升 URL 为正式证据。
     * 没有 promoted URL 时标记失败，避免空提升被误读为 repair 成功。
     */
    public EvidenceRepairPlan promoteEvidence(List<String> promotedUrls) {
        List<String> normalizedUrls = immutableList(promotedUrls);
        if (normalizedUrls.isEmpty()) {
            return this.toBuilder()
                    .state(EvidenceRepairState.REPAIR_FAILED)
                    .promotedUrls(List.of())
                    .build();
        }
        return this.toBuilder()
                .state(EvidenceRepairState.REPAIR_EVIDENCE_PROMOTED)
                .promotedUrls(normalizedUrls)
                .build();
    }

    private List<String> immutableList(List<String> urls) {
        return urls == null || urls.isEmpty() ? List.of() : List.copyOf(urls);
    }
}
