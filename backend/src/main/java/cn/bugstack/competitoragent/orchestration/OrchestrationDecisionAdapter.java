package cn.bugstack.competitoragent.orchestration;

import cn.bugstack.competitoragent.workflow.contract.RevisionDirective;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 兼容期修订指令适配器。
 * 它把历史 Reviewer 输出转换为新的 Orchestrator 决策，避免动态补图继续直接消费 orchestrationAction。
 */
@Component
public class OrchestrationDecisionAdapter {

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
                .inputRefs(buildInputRefs(triggerNodeName))
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
            case "SUPPLEMENT_EVIDENCE", "RERUN_NODE" -> "CURRENT_NODE_AND_DOWNSTREAM";
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
}
