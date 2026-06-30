package cn.bugstack.competitoragent.workflow;

import cn.bugstack.competitoragent.agent.collector.CollectorNodeConfig;
import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import cn.bugstack.competitoragent.source.SourceCandidate;
import cn.bugstack.competitoragent.source.SourceDiscoveryService;
import cn.bugstack.competitoragent.source.SourcePlan;
import cn.bugstack.competitoragent.task.definition.ExecutionPlanDefinition;
import cn.bugstack.competitoragent.workflow.coverage.AnalysisDimensionMappingCatalog;
import cn.bugstack.competitoragent.workflow.coverage.CoverageContractResolver;
import cn.bugstack.competitoragent.workflow.coverage.DimensionEvidencePlan;
import cn.bugstack.competitoragent.workflow.coverage.DimensionEvidencePlanFactory;
import cn.bugstack.competitoragent.workflow.coverage.FieldEvidenceQueryPlanner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowPlanFieldEvidencePlanTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void collectorNodeConfigShouldCarryDimensionEvidencePlan() throws Exception {
        AnalysisTask task = new AnalysisTask();
        task.setCompetitorNames(objectMapper.writeValueAsString(List.of("哔哩哔哩")));
        task.setCompetitorUrls(objectMapper.writeValueAsString(List.of("https://app.bilibili.com")));
        task.setSourceScope(objectMapper.writeValueAsString(List.of("官网", "产品文档")));
        task.setAnalysisDimensions(objectMapper.writeValueAsString(List.of("产品功能", "目标用户", "市场定位", "证据完整性")));
        task.setReportTemplate("标准版");

        SourceDiscoveryService sourceDiscoveryService = mock(SourceDiscoveryService.class);
        when(sourceDiscoveryService.discoverForPreview(any(), any(), any())).thenReturn(List.of(SourcePlan.builder()
                .sourceType("DOCS")
                .sourceFamilyKey("official")
                .sourceFamilyRole("PRIMARY_VERTICAL")
                .urls(List.of("https://app.bilibili.com"))
                .candidates(List.of(SourceCandidate.builder()
                        .url("https://app.bilibili.com")
                        .domain("app.bilibili.com")
                        .sourceType("OFFICIAL")
                        .build()))
                .build()));
        CollectorPlanTemplateFactory collectorPlanTemplateFactory = mock(CollectorPlanTemplateFactory.class);
        when(collectorPlanTemplateFactory.createCollectorNodeConfig(any(), any(), any(), any()))
                .thenReturn(CollectorNodeConfig.builder()
                        .competitorName("哔哩哔哩")
                        .competitorUrls(List.of("https://app.bilibili.com"))
                        .sourceType("DOCS")
                        .sourceCandidates(List.of())
                        .build());

        ExecutionPlanDefinitionBuilder builder = new ExecutionPlanDefinitionBuilder(
                null,
                sourceDiscoveryService,
                new cn.bugstack.competitoragent.source.SourceCandidateRanker(),
                objectMapper,
                collectorPlanTemplateFactory,
                new CoverageContractResolver(new AnalysisDimensionMappingCatalog()),
                new DimensionEvidencePlanFactory(new FieldEvidenceQueryPlanner())
        );

        ExecutionPlanDefinition definition = builder.build(task, true);
        ExecutionPlanDefinition.NodeDefinition collector = definition.getNodes().stream()
                .filter(node -> "COLLECTOR".equals(node.getAgentType()))
                .findFirst()
                .orElseThrow();
        CollectorNodeConfig config = objectMapper.readValue(collector.getNodeConfig(), CollectorNodeConfig.class);

        DimensionEvidencePlan fieldPlan = config.getDimensionEvidencePlan();
        assertThat(fieldPlan).isNotNull();
        assertThat(fieldPlan.findField("coreFeatures")).isPresent();
        assertThat(fieldPlan.findField("pricing")).isPresent();
        assertThat(fieldPlan.findField("pricing").orElseThrow().getPlannedQueries())
                .hasSizeGreaterThanOrEqualTo(5);
    }
}