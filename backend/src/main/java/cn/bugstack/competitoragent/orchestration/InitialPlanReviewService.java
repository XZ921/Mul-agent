package cn.bugstack.competitoragent.orchestration;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 初始协作计划策略校验服务。
 * 它只判断计划能否安全映射到现有标准 DAG 模板，不负责创建或执行 WorkflowPlan。
 */
@Component
public class InitialPlanReviewService {

    private static final String STANDARD_TEMPLATE = "STANDARD_COMPETITOR_ANALYSIS_V1";
    private static final String UNMAPPED_TEMPLATE = "UNMAPPED";
    private static final int MAX_CHECKPOINT_COUNT = 2;
    private static final List<String> ALLOWED_AGENT_TYPES = List.of(
            "COLLECTOR", "EXTRACTOR", "ANALYZER", "WRITER", "REVIEWER"
    );
    private static final List<String> REQUIRED_AGENT_TYPES = ALLOWED_AGENT_TYPES;
    private static final List<String> REQUIRED_CHECKPOINTS = List.of("after_extract_schema", "quality_check_final");
    private static final List<String> POLICY_RULE_REFS = List.of(
            "agentRoleCoverage",
            "dagTemplateCompatibility",
            "checkpointDensity",
            "sourceUrlsOrEvidenceStateRequired"
    );

    /**
     * 校验协作计划是否能进入受控 Workflow 映射。
     * 每条规则只追加阻断原因，不抛异常，便于前端和 replay 展示完整诊断。
     */
    public InitialPlanReview review(CollaborationPlan plan) {
        if (plan == null) {
            return blocked("unknown", List.of("协作计划为空"), List.of("重新生成 CollaborationPlan"),
                    List.of(), EvidenceState.MISSING_SOURCE);
        }

        CollaborationPlan normalizedPlan = plan.normalized();
        List<String> blockedReasons = new ArrayList<>();
        List<String> requiredAdjustments = new ArrayList<>();

        validateAgentRoles(normalizedPlan, blockedReasons, requiredAdjustments);
        validateDagTemplateCompatibility(normalizedPlan, blockedReasons, requiredAdjustments);
        validateCheckpointDensity(normalizedPlan, blockedReasons, requiredAdjustments);
        validateEvidenceBoundary(normalizedPlan, blockedReasons, requiredAdjustments);

        boolean allowed = blockedReasons.isEmpty();
        return InitialPlanReview.builder()
                .reviewId("ipr-" + normalizedPlan.getPlanId())
                .planId(normalizedPlan.getPlanId())
                .allowed(allowed)
                .blockedReasons(blockedReasons)
                .requiredAdjustments(requiredAdjustments)
                .mappedWorkflowTemplate(allowed ? STANDARD_TEMPLATE : UNMAPPED_TEMPLATE)
                .policyRuleRefs(POLICY_RULE_REFS)
                .sourceUrls(normalizedPlan.getSourceUrls())
                .evidenceState(normalizedPlan.getEvidenceState())
                .build()
                .normalized();
    }

    private InitialPlanReview blocked(String planId,
                                      List<String> blockedReasons,
                                      List<String> requiredAdjustments,
                                      List<String> sourceUrls,
                                      EvidenceState evidenceState) {
        return InitialPlanReview.builder()
                .reviewId("ipr-" + planId)
                .planId(planId)
                .allowed(false)
                .blockedReasons(blockedReasons)
                .requiredAdjustments(requiredAdjustments)
                .mappedWorkflowTemplate(UNMAPPED_TEMPLATE)
                .policyRuleRefs(POLICY_RULE_REFS)
                .sourceUrls(sourceUrls)
                .evidenceState(evidenceState)
                .build()
                .normalized();
    }

    private void validateAgentRoles(CollaborationPlan plan,
                                    List<String> blockedReasons,
                                    List<String> requiredAdjustments) {
        Set<String> actualTypes = new LinkedHashSet<>();
        for (AgentRoleAssignment role : plan.getAgentRoleAssignments()) {
            String agentType = role.getAgentType();
            actualTypes.add(agentType);
            if (!ALLOWED_AGENT_TYPES.contains(agentType)) {
                blockedReasons.add("存在未登记 Agent 类型 " + agentType);
                requiredAdjustments.add("移除或替换未登记 Agent 类型 " + agentType);
            }
        }
        for (String requiredType : REQUIRED_AGENT_TYPES) {
            if (!actualTypes.contains(requiredType)) {
                blockedReasons.add("缺少必需角色 " + requiredType);
                requiredAdjustments.add("补齐必需角色 " + requiredType);
            }
        }
    }

    private void validateDagTemplateCompatibility(CollaborationPlan plan,
                                                  List<String> blockedReasons,
                                                  List<String> requiredAdjustments) {
        if (!"ORCHESTRATOR_FIRST".equals(plan.getPlanningMode())) {
            blockedReasons.add("planningMode 只能映射 ORCHESTRATOR_FIRST");
            requiredAdjustments.add("将 planningMode 调整为 ORCHESTRATOR_FIRST");
        }
    }

    private void validateCheckpointDensity(CollaborationPlan plan,
                                           List<String> blockedReasons,
                                           List<String> requiredAdjustments) {
        if (plan.getCheckpoints().size() > MAX_CHECKPOINT_COUNT) {
            blockedReasons.add("checkpoint 数量超过 P2 上限 2");
            requiredAdjustments.add("将 checkpoint 收敛到 after_extract_schema 和 quality_check_final");
        }
        if (!plan.getCheckpoints().containsAll(REQUIRED_CHECKPOINTS)) {
            blockedReasons.add("checkpoint 与标准 DAG 模板不兼容");
            requiredAdjustments.add("补齐标准 checkpoint after_extract_schema / quality_check_final");
        }
    }

    private void validateEvidenceBoundary(CollaborationPlan plan,
                                          List<String> blockedReasons,
                                          List<String> requiredAdjustments) {
        if (plan.getEvidenceState() == null) {
            blockedReasons.add("缺少 evidenceState");
            requiredAdjustments.add("补齐 evidenceState，缺少来源时必须显式写 MISSING_SOURCE");
            return;
        }
        if (plan.getSourceUrls().isEmpty() && plan.getEvidenceState() != EvidenceState.MISSING_SOURCE) {
            blockedReasons.add("缺少 sourceUrls 时 evidenceState 必须为 MISSING_SOURCE");
            requiredAdjustments.add("补齐 sourceUrls 或显式标记 MISSING_SOURCE");
        }
    }
}
