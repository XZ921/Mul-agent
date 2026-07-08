package cn.bugstack.competitoragent.workflow;

import java.util.List;

/**
 * 采集证据是否足够进入首版抽取的结构化判断结果。
 * 这里只表达“能不能启动下游”，不代表最终报告已经满足质量封版线。
 */
public record CollectorEvidenceReadiness(
        boolean ready,
        boolean degraded,
        String reason,
        List<String> satisfiedFamilies,
        List<String> missingFamilies,
        List<String> auditFlags,
        List<String> sourceUrls) {
}
