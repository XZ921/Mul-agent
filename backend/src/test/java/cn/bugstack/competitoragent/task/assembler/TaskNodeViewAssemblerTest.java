package cn.bugstack.competitoragent.task.assembler;

import cn.bugstack.competitoragent.model.dto.TaskNodeResponse;
import cn.bugstack.competitoragent.model.dto.TaskResponse;
import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.AnalysisTaskStatus;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.repository.AiCallAuditRecordRepository;
import cn.bugstack.competitoragent.repository.TaskPlanRepository;
import cn.bugstack.competitoragent.task.TaskRecoveryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskNodeViewAssemblerTest {

    private final AiCallAuditRecordRepository aiCallAuditRecordRepository = mock(AiCallAuditRecordRepository.class);
    private final TaskPlanRepository taskPlanRepository = mock(TaskPlanRepository.class);
    private final TaskRecoveryService taskRecoveryService = mock(TaskRecoveryService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private TaskNodeViewAssembler assembler;

    @BeforeEach
    void setUp() {
        when(taskRecoveryService.getTaskSnapshotOrRebuild(anyLong())).thenReturn(Optional.empty());
        assembler = new TaskNodeViewAssembler(
                aiCallAuditRecordRepository,
                taskPlanRepository,
                taskRecoveryService,
                objectMapper
        );
    }

    @Test
    void shouldExposeDraftReportCapabilityWhenWriterProducedDraftButReviewStoppedTask() {
        AnalysisTask task = AnalysisTask.builder()
                .id(56L)
                .status(AnalysisTaskStatus.STOPPED)
                .errorMessage("初审未通过且需要人工介入，请补充证据或调整策略后继续")
                .build();
        TaskNode writerNode = node("write_report", AgentType.WRITER, TaskNodeStatus.SUCCESS, 3);
        TaskNode reviewNode = node("quality_check", AgentType.REVIEWER, TaskNodeStatus.SUCCESS, 4);
        TaskNode rewriteNode = node("rewrite_report", AgentType.WRITER, TaskNodeStatus.SKIPPED, 5);
        rewriteNode.setErrorMessage("跳过修订：初审严重失败，需先人工补证据、调整搜索范围或重跑采集链路");

        TaskResponse response = assembler.toTaskResponse(task, List.of(writerNode, reviewNode, rewriteNode));

        assertThat(response.getCanViewReport()).isFalse();
        assertThat(response.getCanViewDraftReport()).isTrue();
    }

    @Test
    void shouldExposeNodeSourceUrlsFromRuntimeOutputAndPlannedConfig() {
        AnalysisTask task = AnalysisTask.builder()
                .id(57L)
                .status(AnalysisTaskStatus.RUNNING)
                .build();
        TaskNode node = node("extract_schema", AgentType.EXTRACTOR, TaskNodeStatus.SUCCESS, 1);
        node.setNodeConfig("""
                {
                  "sourceUrls": ["https://www.notion.so/product/ai"],
                  "competitorUrls": ["https://www.notion.so"]
                }
                """);
        node.setOutputData("""
                {
                  "sourceUrls": ["https://notion.so/product/ai"],
                  "results": [
                    {
                      "coverage": {
                        "coreFeatures": {
                          "sourceUrls": ["https://www.notion.so/help"]
                        }
                      }
                    }
                  ]
                }
                """);

        TaskNodeResponse response = assembler.toNodeResponse(task, node, List.of(node));

        assertThat(response.getSourceUrls()).containsExactly(
                "https://notion.so/product/ai",
                "https://www.notion.so/help",
                "https://www.notion.so/product/ai",
                "https://www.notion.so"
        );
    }

    @Test
    void shouldBuildCitationNodeConfigSummary() {
        AnalysisTask task = AnalysisTask.builder()
                .id(58L)
                .status(AnalysisTaskStatus.RUNNING)
                .build();
        TaskNode node = node("citation_check", AgentType.CITATION, TaskNodeStatus.PENDING, 2);
        node.setNodeConfig("""
                {
                  "sourceNode": "write_report",
                  "minCoverageRate": 0.85,
                  "trustPolicy": "official-first"
                }
                """);

        TaskNodeResponse response = assembler.toNodeResponse(task, node, List.of(node));

        assertThat(response.getConfigSummaryData()).isNotNull();
        assertThat(response.getConfigSummaryData().getSummaryText()).isEqualTo("引用核查：write_report，最低覆盖率 0.85");
        assertThat(response.getConfigSummaryData().getSourceNode()).isEqualTo("write_report");
        assertThat(response.getConfigSummaryData().getQualityPolicy()).isEqualTo("official-first");
    }

    private TaskNode node(String nodeName, AgentType agentType, TaskNodeStatus status, int executionOrder) {
        return TaskNode.builder()
                .taskId(56L)
                .nodeName(nodeName)
                .displayName(nodeName)
                .agentType(agentType)
                .dependsOn("[]")
                .required(true)
                .retryable(true)
                .maxRetries(3)
                .retryCount(0)
                .status(status)
                .executionOrder(executionOrder)
                .build();
    }
}
