package cn.bugstack.competitoragent.workflow.coverage;

import cn.bugstack.competitoragent.agent.AgentContext;
import cn.bugstack.competitoragent.model.entity.TaskPlan;
import cn.bugstack.competitoragent.repository.TaskPlanRepository;
import cn.bugstack.competitoragent.workflow.WorkflowPlan;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CoverageContractProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TaskPlanRepository taskPlanRepository = mock(TaskPlanRepository.class);
    private final CoverageContractResolver fallbackResolver =
            new CoverageContractResolver(new AnalysisDimensionMappingCatalog());
    private final CoverageContractProvider provider =
            new CoverageContractProvider(taskPlanRepository, objectMapper, fallbackResolver);

    @Test
    void shouldLoadContractFromActivePlanSnapshot() throws Exception {
        CoverageContract contract = CoverageContract.builder()
                .taskMode("CAPABILITY_INTRO")
                .contractVersion("coverage-capability_intro-v1")
                .source("PLANNER")
                .fields(List.of(CoverageFieldContract.builder()
                        .field("pricing")
                        .status(CoverageFieldStatus.OUT_OF_SCOPE)
                        .blockingLevel(CoverageBlockingLevel.NONE)
                        .build()))
                .build();
        WorkflowPlan plan = WorkflowPlan.builder()
                .coverageContract(contract)
                .nodes(List.of())
                .stages(List.of())
                .build();
        when(taskPlanRepository.findFirstByTaskIdAndActiveTrueOrderByPlanVersionDesc(66L))
                .thenReturn(Optional.of(TaskPlan.builder()
                        .taskId(66L)
                        .planVersion(1)
                        .planSnapshot(objectMapper.writeValueAsString(plan))
                        .build()));

        CoverageContract resolved = provider.resolve(AgentContext.builder()
                .taskId(66L)
                .analysisDimensions("[\"开放平台\",\"开发者生态\",\"产品功能\"]")
                .sourceScope("[\"官网\",\"产品文档\"]")
                .build());

        assertThat(resolved.getTaskMode()).isEqualTo("CAPABILITY_INTRO");
        assertThat(resolved.findField("pricing").orElseThrow().getStatus())
                .isEqualTo(CoverageFieldStatus.OUT_OF_SCOPE);
    }

    @Test
    void shouldFallbackToResolverWhenPlanSnapshotMissing() {
        when(taskPlanRepository.findFirstByTaskIdAndActiveTrueOrderByPlanVersionDesc(67L))
                .thenReturn(Optional.empty());

        CoverageContract resolved = provider.resolve(AgentContext.builder()
                .taskId(67L)
                .analysisDimensions("[\"定价策略\"]")
                .sourceScope("[\"官网\"]")
                .build());

        assertThat(resolved.findField("pricing").orElseThrow().getStatus())
                .isEqualTo(CoverageFieldStatus.REQUIRED);
    }
}
