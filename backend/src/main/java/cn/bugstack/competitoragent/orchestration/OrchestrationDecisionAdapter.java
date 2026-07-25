package cn.bugstack.competitoragent.orchestration;

import cn.bugstack.competitoragent.workflow.contract.RevisionDirective;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 兼容期修订指令适配器。
 * 它把历史 Reviewer 输出转换为新的 Orchestrator 决策，避免动态补图继续直接消费 orchestrationAction。
 */
@Component
public class OrchestrationDecisionAdapter {

    /**
     * 将一轮 Reviewer 的全部结构化诊断归一为唯一 candidate decision。
     * Reviewer 的自然语言、分数和 requiresHumanIntervention 只保留为质量事实；
     * 只有明确的 MANUAL_REVIEW 指令或自动动作缺少安全必填字段时才形成 Java 硬停点。
     */
    public OrchestrationDecision fromReviewCycle(OrchestrationContext rawContext) {
        OrchestrationContext context = rawContext == null ? null : rawContext.normalized();
        if (context == null || context.getTaskId() == null) {
            return manualDecision(null, "unknown_reviewer", 0, "Reviewer 决策上下文缺少 taskId");
        }
        List<RevisionDirective> directives = context.getLegacyRevisionDirectives().stream()
                .filter(java.util.Objects::nonNull)
                .map(RevisionDirective::normalized)
                .sorted(Comparator
                        .comparingInt(this::actionRank)
                        .thenComparing(this::stableDirectiveKey))
                .toList();

        if (!directives.isEmpty()) {
            RevisionDirective selected = directives.get(0);
            String validationError = validateAutomaticDirective(selected);
            if (validationError != null) {
                return reviewCycleManualDecision(context, validationError);
            }
            return fromRevisionDirective(context.getTaskId(), context.getTriggerNodeName(), selected, 0)
                    .toBuilder()
                    .decisionId(reviewCycleDecisionId(context))
                    .decisionOrigin(OrchestrationDecisionOrigin.RULE_ONLY)
                    .build()
                    .normalized();
        }
        if (context.isPassed() && !context.getSourceUrls().isEmpty()) {
            return OrchestrationDecision.builder()
                    .decisionId(reviewCycleDecisionId(context))
                    .taskId(context.getTaskId())
                    .triggerNodeName(context.getTriggerNodeName())
                    .decisionOrigin(OrchestrationDecisionOrigin.RULE_ONLY)
                    .decisionType("NO_ACTION")
                    .actionType("NO_ACTION")
                    .targetNode(context.getTriggerNodeName())
                    .affectedScope("CURRENT_NODE_ONLY")
                    .reason("Reviewer 已通过且结论具备可追溯 sourceUrls。")
                    .priority("LOW")
                    .inputRefs(buildInputRefs(context.getTriggerNodeName()))
                    .sourceUrls(context.getSourceUrls())
                    .evidenceState(context.getEvidenceState())
                    .build()
                    .normalized();
        }
        return reviewCycleManualDecision(context, context.isPassed()
                        ? "Reviewer 通过结论缺少 sourceUrls，禁止直接收口"
                        : "Reviewer 未通过但没有完整的结构化修订诊断");
    }

    private OrchestrationDecision reviewCycleManualDecision(OrchestrationContext context, String reason) {
        return manualDecision(context.getTaskId(), context.getTriggerNodeName(), 0, reason)
                .toBuilder()
                .decisionId(reviewCycleDecisionId(context))
                .decisionOrigin(OrchestrationDecisionOrigin.RULE_ONLY)
                .build()
                .normalized();
    }

    public OrchestrationDecision fromRevisionDirective(Long taskId,
                                                       String triggerNodeName,
                                                       RevisionDirective directive,
                                                       int index) {
        RevisionDirective normalized = directive == null ? null : directive.normalized();
        if (normalized == null) {
            return manualDecision(taskId, triggerNodeName, index, "空修订指令需要人工确认");
        }
        List<String> sourceUrls = normalized.getSourceUrls() == null ? List.of() : normalized.getSourceUrls();
        EvidenceState evidenceState = sourceUrls.isEmpty() ? EvidenceState.MISSING_SOURCE : EvidenceState.FULL_SOURCE;
        return OrchestrationDecision.builder()
                .decisionId("od-" + taskId + "-" + triggerNodeName + "-" + index)
                .taskId(taskId)
                .triggerNodeName(triggerNodeName)
                .decisionOrigin(OrchestrationDecisionOrigin.LEGACY_ADAPTER)
                .decisionType(resolveDecisionType(normalized))
                .actionType(normalized.getActionType())
                .targetNode(resolveTargetNode(normalized))
                .affectedScope(resolveAffectedScope(normalized))
                .priority(resolvePriority(normalized, evidenceState))
                .targetSection(normalized.getTargetSection())
                .reason(normalized.getSummary())
                .requiresHumanIntervention(false)
                .requiresConfirmation(false)
                .confidence(resolveConfidence(normalized))
                .suggestedQueries(normalized.getSearchQueries())
                .inputRefs(buildInputRefs(triggerNodeName, normalized))
                .sourceUrls(sourceUrls)
                .evidenceState(evidenceState)
                .build()
                .normalized();
    }

    private OrchestrationDecision manualDecision(Long taskId, String triggerNodeName, int index, String reason) {
        return OrchestrationDecision.builder()
                .decisionId("od-" + taskId + "-" + triggerNodeName + "-" + index)
                .taskId(taskId)
                .triggerNodeName(triggerNodeName)
                .decisionOrigin(OrchestrationDecisionOrigin.LEGACY_ADAPTER)
                .decisionType("WAIT_FOR_HUMAN")
                .actionType("MANUAL_REVIEW")
                .targetNode("quality_check_final")
                .affectedScope("CURRENT_NODE_ONLY")
                .reason(reason)
                .priority("HIGH")
                .requiresHumanIntervention(true)
                .requiresConfirmation(true)
                .confidence(0.10d)
                .inputRefs(buildInputRefs(triggerNodeName))
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .build()
                .normalized();
    }

    private String resolveDecisionType(RevisionDirective directive) {
        return switch (directive.getActionType()) {
            case "SUPPLEMENT_EVIDENCE" -> "APPEND_DYNAMIC_BRANCH";
            case "RERUN_NODE" -> "RERUN_NODE";
            case "REWRITE_SECTION", "REWRITE_CLAIM" -> "REWRITE_ONLY";
            case "MANUAL_REVIEW" -> "WAIT_FOR_HUMAN";
            default -> "WAIT_FOR_HUMAN";
        };
    }

    private String resolveTargetNode(RevisionDirective directive) {
        if (directive.getTargetNode() != null && !directive.getTargetNode().isBlank()) {
            return directive.getTargetNode();
        }
        return switch (directive.getActionType()) {
            case "SUPPLEMENT_EVIDENCE" -> "collect_sources";
            case "RERUN_NODE" -> "extract_schema";
            case "REWRITE_SECTION", "REWRITE_CLAIM" -> "rewrite_report";
            default -> "quality_check_final";
        };
    }

    private String resolveAffectedScope(RevisionDirective directive) {
        return switch (directive.getActionType()) {
            case "SUPPLEMENT_EVIDENCE" -> "CURRENT_NODE_AND_DOWNSTREAM";
            case "RERUN_NODE" -> "CURRENT_NODE_ONLY";
            case "REWRITE_SECTION", "REWRITE_CLAIM" -> "CURRENT_NODE_ONLY";
            default -> "CURRENT_NODE_ONLY";
        };
    }

    private String resolvePriority(RevisionDirective directive, EvidenceState evidenceState) {
        if (directive.getPriority() != null && !directive.getPriority().isBlank()) {
            return directive.getPriority();
        }
        if (EvidenceState.MISSING_SOURCE == evidenceState && !"SUPPLEMENT_EVIDENCE".equals(directive.getActionType())) {
            return "HIGH";
        }
        if ("RERUN_NODE".equals(directive.getActionType())) {
            return "HIGH";
        }
        return "MEDIUM";
    }

    private double resolveConfidence(RevisionDirective directive) {
        return switch (directive.getActionType()) {
            case "SUPPLEMENT_EVIDENCE" -> 0.85d;
            case "RERUN_NODE" -> 0.70d;
            case "REWRITE_SECTION", "REWRITE_CLAIM" -> 0.78d;
            default -> 0.40d;
        };
    }

    /**
     * P1 不回溯改造 QualityDiagnosis 主键，因此先用触发节点生成稳定 inputRefs，
     * 后续等诊断对象具备正式 ID 后再替换为权威引用。
     */
    private Map<String, Object> buildInputRefs(String triggerNodeName) {
        Map<String, Object> refs = new LinkedHashMap<>();
        refs.put("qualityDiagnosisIds", List.of());
        refs.put("agentSuggestionIds", List.of());
        refs.put("triggerNodeName", triggerNodeName);
        return refs;
    }

    private Map<String, Object> buildInputRefs(String triggerNodeName, RevisionDirective directive) {
        Map<String, Object> refs = buildInputRefs(triggerNodeName);
        refs.put("competitor", directive.getCompetitor());
        refs.put("targetField", directive.getTargetField());
        refs.put("requiredSourceType", directive.getRequiredSourceType());
        refs.put("gapKey", directive.getGapKey());
        refs.put("sourceSnapshot", directive.getSourceUrls());
        return refs;
    }

    private int actionRank(RevisionDirective directive) {
        return switch (directive.getActionType()) {
            case "MANUAL_REVIEW" -> 0;
            case "SUPPLEMENT_EVIDENCE" -> 1;
            case "RERUN_NODE" -> 2;
            case "REWRITE_SECTION", "REWRITE_CLAIM" -> 3;
            default -> 4;
        };
    }

    private String stableDirectiveKey(RevisionDirective directive) {
        return String.join("|",
                safe(directive.getCompetitor()),
                safe(directive.getTargetField()),
                safe(directive.getRequiredSourceType()),
                safe(directive.getTargetNode()));
    }

    private String validateAutomaticDirective(RevisionDirective directive) {
        if ("MANUAL_REVIEW".equals(directive.getActionType())) {
            return directive.getSummary();
        }
        if (directive.getSourceUrls().isEmpty()) {
            return "自动 Reviewer 动作缺少 sourceUrls，禁止执行";
        }
        if ("SUPPLEMENT_EVIDENCE".equals(directive.getActionType())
                && (isBlank(directive.getCompetitor())
                || isBlank(directive.getTargetField())
                || isBlank(directive.getRequiredSourceType())
                || isBlank(directive.getGapKey()))) {
            return "补采诊断缺少 competitor、targetField、requiredSourceType 或 gapKey";
        }
        if ("RERUN_NODE".equals(directive.getActionType())
                && (!"extract_schema".equals(directive.getTargetNode())
                || isBlank(directive.getTargetField())
                || isBlank(directive.getRequiredSourceType())
                || isBlank(directive.getGapKey()))) {
            return "RERUN_NODE 必须命中 extract_schema 白名单并携带 targetField、requiredSourceType 和 gapKey";
        }
        return null;
    }

    private String reviewCycleDecisionId(OrchestrationContext context) {
        return reviewCycleDecisionId(context.getTaskId(), context.getTriggerNodeName());
    }

    private String reviewCycleDecisionId(Long taskId, String triggerNodeName) {
        return "od-" + taskId + "-" + safe(triggerNodeName) + "-review-cycle";
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
