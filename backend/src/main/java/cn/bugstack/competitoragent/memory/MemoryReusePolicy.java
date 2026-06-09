package cn.bugstack.competitoragent.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 统一定义记忆写回与复用的正式策略。
 * <p>
 * Task 5.4.c 的核心不是“能不能写回”，而是把
 * 1. 什么情况下允许写回；
 * 2. 应该写到哪一层；
 * 3. 版本来源如何标记；
 * 4. 在什么边界下失效；
 * 明确固化成一个可复用的策略对象，避免规则散落在调用方里。
 */
@Component
public class MemoryReusePolicy {

    /**
     * 基于写回请求给出正式策略决策。
     */
    public PolicyDecision decide(MemoryWritebackService.WritebackRequest request) {
        if (request == null) {
            return PolicyDecision.disabled("写回请求为空，无法建立治理边界。");
        }
        if (!hasTraceableSourceUrls(request.getSourceUrls())) {
            return PolicyDecision.disabled("缺少 sourceUrls，当前结论不可写回为正式记忆。");
        }
        if (!isWritebackQualityAccepted(request.getQualitySignal())) {
            return PolicyDecision.disabled("当前结论尚未达到可写回质量门槛。");
        }

        String versionSource = buildVersionSource(request.getPlanVersionId(), request.getBranchKey());
        String reuseReason = StringUtils.hasText(request.getReuseReason())
                ? request.getReuseReason()
                : "当前结论缺少正式复用说明。";

        if (isDomainKnowledgeCategory(request.getWritebackCategory())) {
            return PolicyDecision.builder()
                    .writebackEnabled(true)
                    .targetMemoryLayer("DOMAIN")
                    .versionSource(versionSource)
                    .invalidationScope("DOMAIN_REFRESH")
                    .invalidationReason("SOURCE_EVIDENCE_CHANGED")
                    .reuseReason(reuseReason)
                    .promoteToDomainKnowledge(StringUtils.hasText(request.getCompetitorName()))
                    .build();
        }

        return PolicyDecision.builder()
                .writebackEnabled(true)
                .targetMemoryLayer("SHORT_TERM")
                .versionSource(versionSource)
                .invalidationScope("TASK_RERUN")
                .invalidationReason("PLAN_VERSION_CHANGED")
                .reuseReason(reuseReason)
                .promoteToDomainKnowledge(false)
                .build();
    }

    /**
     * 版本来源统一编码为“任务 RAG + 计划版本 + 分支”的稳定格式，
     * 避免上游各自拼接导致后续无法按同一规则解释。
     */
    public String buildVersionSource(Long planVersionId, String branchKey) {
        long safePlanVersionId = planVersionId == null ? 0L : planVersionId;
        String safeBranchKey = StringUtils.hasText(branchKey) ? branchKey.trim() : "default";
        return "TASK_RAG@PLAN-" + safePlanVersionId + ":" + safeBranchKey;
    }

    private boolean hasTraceableSourceUrls(List<String> sourceUrls) {
        return sourceUrls != null && sourceUrls.stream().anyMatch(StringUtils::hasText);
    }

    /**
     * 只有已经被核实、可追溯的结论才允许写回，
     * 避免把草稿、猜测或缺证据摘要沉淀成下一轮任务的“旧记忆”。
     */
    private boolean isWritebackQualityAccepted(String qualitySignal) {
        if (!StringUtils.hasText(qualitySignal)) {
            return false;
        }
        String normalized = qualitySignal.trim().toUpperCase();
        return "VERIFIED".equals(normalized) || "TRACEABLE".equals(normalized);
    }

    private boolean isDomainKnowledgeCategory(String writebackCategory) {
        if (!StringUtils.hasText(writebackCategory)) {
            return false;
        }
        String normalized = writebackCategory.trim().toUpperCase();
        return "VERIFIED_DOMAIN_KNOWLEDGE".equals(normalized)
                || "EVIDENCE_BACKED_DOMAIN_KNOWLEDGE".equals(normalized);
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PolicyDecision {
        private boolean writebackEnabled;
        private String targetMemoryLayer;
        private String versionSource;
        private String invalidationScope;
        private String invalidationReason;
        private String reuseReason;
        private boolean promoteToDomainKnowledge;

        public static PolicyDecision disabled(String reuseReason) {
            return PolicyDecision.builder()
                    .writebackEnabled(false)
                    .targetMemoryLayer("NONE")
                    .versionSource("UNSPECIFIED")
                    .invalidationScope("MANUAL_REVIEW")
                    .invalidationReason("NOT_ELIGIBLE")
                    .reuseReason(reuseReason)
                    .promoteToDomainKnowledge(false)
                    .build();
        }
    }
}
