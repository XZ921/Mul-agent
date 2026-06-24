package cn.bugstack.competitoragent.orchestration;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CollaborationPlanServiceTest {

    private final CollaborationPlanService service = new CollaborationPlanService();

    @Test
    void shouldCreateStandardRolePlanWithoutGeneratingFreeDagNodes() {
        CollaborationGoal goal = CollaborationGoal.builder()
                .goalId("cg-task-88")
                .taskId(88L)
                .subject("企业级 RAG 知识库竞品分析")
                .competitors(List.of("Notion AI", "Glean"))
                .analysisDimensions(List.of("pricing", "security"))
                .deliverableType("COMPETITOR_REPORT")
                .depth("STANDARD")
                .budget(Map.of("maxSearchQueries", 20, "maxModelCalls", 12, "maxAutoDecisions", 5))
                .constraints(Map.of("requireSourceUrls", true, "allowDynamicBranch", true))
                .sourceUrls(List.of("https://www.notion.so"))
                .build()
                .normalized();

        CollaborationPlan plan = service.createPlan(goal);

        assertThat(plan.getPlanId()).isEqualTo("cp-task-88-v1");
        assertThat(plan.getPlanningMode()).isEqualTo("ORCHESTRATOR_FIRST");
        assertThat(plan.getAgentRoleAssignments()).extracting(AgentRoleAssignment::getAgentType)
                .containsExactlyInAnyOrder("COLLECTOR", "EXTRACTOR", "ANALYZER", "WRITER", "REVIEWER");
        assertThat(plan.getCheckpoints()).containsExactly("after_extract_schema", "quality_check_final");
        assertThat(plan.getAgentRoleAssignments())
                .allSatisfy(role -> assertThat(role.getQualityGate()).isNotBlank());
        assertThat(plan.getSourceUrls()).containsExactly("https://www.notion.so");
        assertThat(plan.getEvidenceState()).isEqualTo(EvidenceState.FULL_SOURCE);
    }
}
