package cn.bugstack.competitoragent.orchestration;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 规则优先的协作计划服务。
 * P2 第一版只生成固定角色和受控 checkpoint，不调用外部 LLM 生成自由 DAG。
 */
@Component
public class CollaborationPlanService {

    private static final List<String> CHECKPOINTS = List.of("after_extract_schema", "quality_check_final");

    /**
     * 根据协作目标生成标准协作计划。
     * sourceUrls 和 evidenceState 从目标继承，确保计划层仍能追溯输入来源边界。
     */
    public CollaborationPlan createPlan(CollaborationGoal goal) {
        CollaborationGoal normalizedGoal = goal == null
                ? CollaborationGoal.builder().build().normalized()
                : goal.normalized();
        return CollaborationPlan.builder()
                .planId("cp-task-" + normalizedGoal.getTaskId() + "-v1")
                .goalId(normalizedGoal.getGoalId())
                .taskId(normalizedGoal.getTaskId())
                .planningMode("ORCHESTRATOR_FIRST")
                .agentRoleAssignments(List.of(
                        role("role-collector-01",
                                "COLLECTOR",
                                "采集竞品官网、文档、定价页和公开资料证据。",
                                List.of("EvidenceFragment", "CollectionAudit"),
                                List.of(),
                                "sourceUrls must not be empty or evidence gap must be explicit",
                                normalizedGoal),
                        role("role-extractor-01",
                                "EXTRACTOR",
                                "抽取结构化字段、evidenceCoverage，并在证据不足时输出 AgentSuggestion。",
                                List.of("ExtractResult", "AgentSuggestion"),
                                List.of("role-collector-01"),
                                "evidenceCoverage must cover requested dimensions",
                                normalizedGoal),
                        role("role-analyzer-01",
                                "ANALYZER",
                                "基于抽取结果完成维度化分析，禁止脱离 sourceUrls 输出事实。",
                                List.of("AnalysisResult"),
                                List.of("role-extractor-01"),
                                "analysis must reference extract evidence boundaries",
                                normalizedGoal),
                        role("role-writer-01",
                                "WRITER",
                                "把分析结果组织为可审计报告草稿，保留证据来源。",
                                List.of("ReportDraft"),
                                List.of("role-analyzer-01"),
                                "report draft must keep sourceUrls for factual claims",
                                normalizedGoal),
                        role("role-reviewer-01",
                                "REVIEWER",
                                "对最终报告做质量审查，并把缺口交由 Orchestrator 决策。",
                                List.of("QualityReview", "RevisionDirective"),
                                List.of("role-writer-01"),
                                "quality review must produce explicit diagnosis for gaps",
                                normalizedGoal)))
                .checkpoints(CHECKPOINTS)
                .sourceUrls(normalizedGoal.getSourceUrls())
                .evidenceState(normalizedGoal.getEvidenceState())
                .build()
                .normalized();
    }

    private AgentRoleAssignment role(String roleId,
                                     String agentType,
                                     String mission,
                                     List<String> expectedOutputs,
                                     List<String> dependsOn,
                                     String qualityGate,
                                     CollaborationGoal goal) {
        return AgentRoleAssignment.builder()
                .roleId(roleId)
                .agentType(agentType)
                .mission(mission)
                .expectedOutputs(expectedOutputs)
                .dependsOn(dependsOn)
                .qualityGate(qualityGate)
                .sourceUrls(goal.getSourceUrls())
                .evidenceState(goal.getEvidenceState())
                .build()
                .normalized();
    }
}
