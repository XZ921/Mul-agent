package cn.bugstack.competitoragent.task.assembler;

import cn.bugstack.competitoragent.model.dto.TaskPlanPreviewNodeResponse;
import cn.bugstack.competitoragent.task.definition.TaskDefinition;
import cn.bugstack.competitoragent.workflow.WorkflowPlan;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TaskPlanPreviewAssemblerTest {

    private final TaskPlanPreviewAssembler assembler = new TaskPlanPreviewAssembler(new ObjectMapper());

    @Test
    void shouldBuildCitationPreviewConfigSummary() {
        TaskDefinition definition = TaskDefinition.builder()
                .taskName("citation preview")
                .subjectProduct("企业级 RAG")
                .sourceUrls(List.of("https://www.notion.so"))
                .build();
        WorkflowPlan plan = WorkflowPlan.builder()
                .contractType("TASK_PLAN_PREVIEW_V1")
                .goal("围绕企业级 RAG 展开竞品研究")
                .stages(List.of(WorkflowPlan.WorkflowPlanStage.builder()
                        .stageCode("DELIVER")
                        .title("输出与复核")
                        .summary("生成报告并执行引用核查")
                        .detail("Writer 之后先 Citation，再 Reviewer")
                        .sourceUrls(List.of("https://www.notion.so"))
                        .build()))
                .nodes(List.of(WorkflowPlan.WorkflowPlanNode.builder()
                        .nodeName("citation_check")
                        .displayName("报告引用核查")
                        .agentType("CITATION")
                        .dependsOn(List.of("write_report"))
                        .required(true)
                        .executionOrder(4)
                        .nodeConfig("""
                                {
                                  "sourceNode": "write_report",
                                  "minCoverageRate": 0.85,
                                  "trustPolicy": "official-first"
                                }
                                """)
                        .summary("在质量初审前确认报告结论可回指来源")
                        .sourceUrls(List.of("https://www.notion.so"))
                        .build()))
                .build();

        TaskPlanPreviewNodeResponse node = assembler.toPreviewResponse(definition, plan).getNodes().get(0);

        assertThat(node.getConfigSummaryData()).isNotNull();
        assertThat(node.getConfigSummaryData().getSummaryText()).isEqualTo("核查 write_report 引用覆盖，最低覆盖率 0.85");
        assertThat(node.getConfigSummaryData().getSourceNode()).isEqualTo("write_report");
        assertThat(node.getConfigSummaryData().getQualityPolicy()).isEqualTo("official-first");
    }
}
