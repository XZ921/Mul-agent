package cn.bugstack.competitoragent.task;

import cn.bugstack.competitoragent.common.BusinessException;
import cn.bugstack.competitoragent.common.ResultCode;
import cn.bugstack.competitoragent.model.dto.CreateTaskRequest;
import cn.bugstack.competitoragent.model.dto.TaskResponse;
import cn.bugstack.competitoragent.model.dto.TaskNodeResponse;
import cn.bugstack.competitoragent.model.dto.UpdateNodeConfigRequest;
import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.AnalysisTaskStatus;
import cn.bugstack.competitoragent.model.enums.TaskNodeControlState;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.repository.AgentExecutionLogRepository;
import cn.bugstack.competitoragent.repository.AnalysisTaskRepository;
import cn.bugstack.competitoragent.repository.CompetitorKnowledgeRepository;
import cn.bugstack.competitoragent.repository.EvidenceSourceRepository;
import cn.bugstack.competitoragent.repository.ReportRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import cn.bugstack.competitoragent.search.SearchAuditSnapshot;
import cn.bugstack.competitoragent.workflow.WorkflowFactory;
import cn.bugstack.competitoragent.workflow.WorkflowPlan;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalysisTaskServiceTest {

    @Mock
    private AnalysisTaskRepository taskRepository;

    @Mock
    private TaskNodeRepository nodeRepository;

    @Mock
    private EvidenceSourceRepository evidenceRepository;

    @Mock
    private CompetitorKnowledgeRepository knowledgeRepository;

    @Mock
    private ReportRepository reportRepository;

    @Mock
    private AgentExecutionLogRepository logRepository;

    @Mock
    private WorkflowFactory workflowFactory;

    @Mock
    private AnalysisTaskRunner taskRunner;

    @Mock
    private TaskRecoveryService taskRecoveryService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private AnalysisTaskService taskService;

    @Test
    void shouldUsePreviewPlanInsteadOfLiveWorkflowBuildWhenPreviewingTask() {
        WorkflowPlan previewPlan = WorkflowPlan.builder()
                .nodes(List.of(WorkflowPlan.WorkflowPlanNode.builder()
                        .nodeName("collect_sources_01_01")
                        .displayName("Notion AI - DOCS采集")
                        .agentType(AgentType.COLLECTOR.name())
                        .dependsOn(List.of())
                        .nodeConfig("{\"competitorName\":\"Notion AI\",\"sourceType\":\"DOCS\"}")
                        .executionOrder(0)
                        .build()))
                .build();
        when(workflowFactory.buildPreviewPlan(any(AnalysisTask.class))).thenReturn(previewPlan);

        CreateTaskRequest request = new CreateTaskRequest();
        request.setTaskName("预览任务");
        request.setSubjectProduct("本方产品");
        request.setCompetitorNames(List.of("Notion AI"));
        request.setCompetitorUrls(List.of("https://www.notion.so"));
        request.setAnalysisDimensions(List.of("产品功能"));
        request.setSourceScope(List.of("官网", "产品文档"));

        List<TaskNodeResponse> responses = taskService.previewWorkflow(request);

        assertEquals(1, responses.size());
        verify(workflowFactory, times(1)).buildPreviewPlan(any(AnalysisTask.class));
        verify(workflowFactory, never()).buildPlan(any(AnalysisTask.class));
    }

    @Test
    void shouldRerunOnlyAffectedBranchFromSpecifiedNode() {
        Long taskId = 11L;
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .status(AnalysisTaskStatus.FAILED)
                .errorMessage("boom")
                .build();

        TaskNode collectWeb = successfulNode(taskId, "collect_sources_web", AgentType.COLLECTOR, "[]", 0);
        TaskNode collectDocs = successfulNode(taskId, "collect_sources_docs", AgentType.COLLECTOR, "[]", 1);
        TaskNode extractSchema = successfulNode(taskId, "extract_schema", AgentType.EXTRACTOR, "[\"collect_sources_web\"]", 2);
        TaskNode analyze = successfulNode(taskId, "analyze_competitors", AgentType.ANALYZER, "[\"extract_schema\"]", 3);
        TaskNode write = successfulNode(taskId, "write_report", AgentType.WRITER, "[\"analyze_competitors\"]", 4);

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId))
                .thenReturn(List.of(collectWeb, collectDocs, extractSchema, analyze, write));

        taskService.rerunFromNode(taskId, "collect_sources_web");

        assertPendingCleared(collectWeb);
        assertPendingCleared(extractSchema);
        assertPendingCleared(analyze);
        assertPendingCleared(write);

        assertEquals(TaskNodeStatus.SUCCESS, collectDocs.getStatus());
        assertEquals("{\"node\":\"collect_sources_docs\"}", collectDocs.getOutputData());
        assertEquals(1, collectDocs.getRetryCount());

        assertEquals(AnalysisTaskStatus.PENDING, task.getStatus());
        assertNull(task.getErrorMessage());
        assertNull(task.getCompletedAt());

        verify(evidenceRepository).deleteByTaskIdAndEvidenceIdStartingWith(taskId, "T0011-COLLECT_SOURCES_WEB-");
        verify(knowledgeRepository).deleteByTaskId(taskId);
        verify(reportRepository).deleteByTaskId(taskId);
        verify(taskRunner).runTask(taskId);

        ArgumentCaptor<List<TaskNode>> savedNodesCaptor = ArgumentCaptor.forClass(List.class);
        verify(nodeRepository).saveAll(savedNodesCaptor.capture());
        List<TaskNode> savedNodes = savedNodesCaptor.getValue();
        assertEquals(4, savedNodes.size());
        assertTrue(savedNodes.stream().anyMatch(node -> "collect_sources_web".equals(node.getNodeName())));
        assertTrue(savedNodes.stream().noneMatch(node -> "collect_sources_docs".equals(node.getNodeName())));
        verify(taskRepository).save(task);
    }

    @Test
    void shouldKeepExistingReportWhenRerunningRewriteBranch() {
        Long taskId = 21L;
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .status(AnalysisTaskStatus.FAILED)
                .build();

        TaskNode writeReport = successfulNode(taskId, "write_report", AgentType.WRITER, "[\"analyze_competitors\"]", 0);
        TaskNode qualityCheck = successfulNode(taskId, "quality_check", AgentType.REVIEWER, "[\"write_report\"]", 1);
        TaskNode rewriteReport = failedNode(taskId, "rewrite_report", AgentType.WRITER, "[\"quality_check\"]", 2);
        TaskNode finalReview = failedNode(taskId, "quality_check_final", AgentType.REVIEWER, "[\"rewrite_report\"]", 3);

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId))
                .thenReturn(List.of(writeReport, qualityCheck, rewriteReport, finalReview));

        taskService.rerunFromNode(taskId, "rewrite_report");

        assertPendingCleared(rewriteReport);
        assertPendingCleared(finalReview);
        assertEquals(TaskNodeStatus.SUCCESS, writeReport.getStatus());
        assertEquals(TaskNodeStatus.SUCCESS, qualityCheck.getStatus());

        verify(reportRepository, never()).deleteByTaskId(taskId);
        verify(knowledgeRepository, never()).deleteByTaskId(taskId);
        verify(evidenceRepository, never()).deleteByTaskIdAndEvidenceIdStartingWith(any(), any());
        verify(taskRunner).runTask(taskId);
    }

    @Test
    void shouldResumeOnlyIncompleteNodesAndKeepSuccessfulCheckpoints() {
        Long taskId = 31L;
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .status(AnalysisTaskStatus.FAILED)
                .errorMessage("old failure")
                .build();

        TaskNode collectNode = successfulNode(taskId, "collect_sources_web", AgentType.COLLECTOR, "[]", 0);
        TaskNode extractNode = failedNode(taskId, "extract_schema", AgentType.EXTRACTOR, "[\"collect_sources_web\"]", 1);
        TaskNode analyzeNode = failedNode(taskId, "analyze_competitors", AgentType.ANALYZER, "[\"extract_schema\"]", 2);
        analyzeNode.setStatus(TaskNodeStatus.SKIPPED);

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId))
                .thenReturn(List.of(collectNode, extractNode, analyzeNode));

        taskService.resumeTask(taskId);

        assertEquals(TaskNodeStatus.SUCCESS, collectNode.getStatus());
        assertEquals("{\"node\":\"collect_sources_web\"}", collectNode.getOutputData());
        assertEquals(1, collectNode.getRetryCount());

        assertPendingCleared(extractNode);
        assertPendingCleared(analyzeNode);

        assertEquals(AnalysisTaskStatus.PENDING, task.getStatus());
        assertNull(task.getErrorMessage());
        assertNull(task.getCompletedAt());

        verifyNoInteractions(taskRecoveryService);
        verify(nodeRepository).saveAll(List.of(collectNode, extractNode, analyzeNode));
        verify(taskRepository).save(task);
        verify(taskRunner).runTask(taskId);
    }

    @Test
    void shouldWriteSearchAuditCheckpointBackToCollectorConfigWhenRerunningCollectorNode() throws Exception {
        Long taskId = 71L;
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .status(AnalysisTaskStatus.FAILED)
                .build();

        TaskNode collectorNode = successfulNode(taskId, "collect_sources_web", AgentType.COLLECTOR, "[]", 0);
        collectorNode.setNodeConfig("""
                {"competitorName":"Feishu","sourceType":"DOCS"}
                """);
        collectorNode.setOutputData("""
                {
                  "searchAudit": {
                    "executionTrace": {
                      "traceVersion": "v1",
                      "recoveryCheckpoint": "SELECT_TARGETS"
                    },
                    "sourceCandidates": [
                      {
                        "url": "https://docs.example.com",
                        "sourceType": "DOCS",
                        "selectionStage": "SELECTED"
                      }
                    ]
                  }
                }
                """);

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId)).thenReturn(List.of(collectorNode));

        taskService.rerunFromNode(taskId, "collect_sources_web");

        JsonNode updatedConfig = objectMapper.readTree(collectorNode.getNodeConfig());
        assertTrue(updatedConfig.has("searchAuditCheckpoint"));
        assertEquals("SELECT_TARGETS",
                updatedConfig.path("searchAuditCheckpoint").path("executionTrace").path("recoveryCheckpoint").asText());
    }

    @Test
    void shouldPausePendingNode() {
        Long taskId = 35L;
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .status(AnalysisTaskStatus.RUNNING)
                .build();
        TaskNode node = pendingNode(taskId, "collect_sources_web", AgentType.COLLECTOR, "[]", 0);

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(nodeRepository.findByTaskIdAndNodeName(taskId, "collect_sources_web")).thenReturn(Optional.of(node));

        taskService.pauseNode(taskId, "collect_sources_web");

        assertEquals(TaskNodeStatus.PAUSED, node.getStatus());
        assertEquals(TaskNodeControlState.NONE, node.getControlState());
        assertEquals("节点已由用户暂停，等待恢复", node.getInterventionReason());
        verify(nodeRepository).save(node);
        verifyNoInteractions(taskRunner);
    }

    @Test
    void shouldResumePausedNodeAndContinueTask() {
        Long taskId = 36L;
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .status(AnalysisTaskStatus.STOPPED)
                .errorMessage("存在已暂停节点，等待人工恢复")
                .build();
        TaskNode node = pendingNode(taskId, "extract_schema", AgentType.EXTRACTOR, "[]", 0);
        node.setStatus(TaskNodeStatus.PAUSED);
        node.setErrorMessage("节点已由用户暂停，等待恢复");
        node.setInterventionReason("节点已由用户暂停，等待恢复");

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(nodeRepository.findByTaskIdAndNodeName(taskId, "extract_schema")).thenReturn(Optional.of(node));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId)).thenReturn(List.of(node));

        taskService.resumeNode(taskId, "extract_schema");

        assertEquals(TaskNodeStatus.PENDING, node.getStatus());
        assertEquals(TaskNodeControlState.NONE, node.getControlState());
        assertNull(node.getErrorMessage());
        assertNull(node.getInterventionReason());
        assertEquals(AnalysisTaskStatus.PENDING, task.getStatus());
        verify(nodeRepository).save(node);
        verify(taskRepository).save(task);
        verify(taskRunner).runTask(taskId);
    }

    @Test
    void shouldKeepTaskStoppedWhenOtherPausedNodesStillExistAfterManualResume() {
        Long taskId = 36_1L;
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .status(AnalysisTaskStatus.STOPPED)
                .errorMessage("存在已暂停节点，等待人工恢复")
                .build();
        TaskNode resumedNode = pendingNode(taskId, "extract_schema", AgentType.EXTRACTOR, "[]", 0);
        resumedNode.setStatus(TaskNodeStatus.PAUSED);
        resumedNode.setErrorMessage("节点已由用户暂停，等待恢复");
        resumedNode.setInterventionReason("节点已由用户暂停，等待恢复");
        TaskNode stillPausedNode = pendingNode(taskId, "write_report", AgentType.WRITER, "[\"extract_schema\"]", 1);
        stillPausedNode.setStatus(TaskNodeStatus.PAUSED);
        stillPausedNode.setErrorMessage("节点已由用户暂停，等待恢复");
        stillPausedNode.setInterventionReason("节点已由用户暂停，等待恢复");

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(nodeRepository.findByTaskIdAndNodeName(taskId, "extract_schema")).thenReturn(Optional.of(resumedNode));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId)).thenReturn(List.of(resumedNode, stillPausedNode));

        taskService.resumeNode(taskId, "extract_schema");

        assertEquals(TaskNodeStatus.PENDING, resumedNode.getStatus());
        assertEquals(AnalysisTaskStatus.STOPPED, task.getStatus());
        assertTrue(task.getErrorMessage().contains("暂停"));
        verify(nodeRepository).save(resumedNode);
        verify(taskRepository, never()).save(task);
        verifyNoInteractions(taskRunner);
    }

    @Test
    void shouldDeriveStoppedTaskStatusFromPausedNodesInTaskDetail() {
        Long taskId = 56_1L;
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .status(AnalysisTaskStatus.FAILED)
                .errorMessage("旧失败信息")
                .build();
        TaskNode pausedNode = pendingNode(taskId, "extract_schema", AgentType.EXTRACTOR, "[]", 0);
        pausedNode.setStatus(TaskNodeStatus.PAUSED);
        pausedNode.setInterventionReason("节点已由用户暂停，等待恢复");
        TaskNode downstream = pendingNode(taskId, "analyze_competitors", AgentType.ANALYZER, "[\"extract_schema\"]", 1);

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId)).thenReturn(List.of(pausedNode, downstream));

        TaskResponse response = taskService.getTask(taskId);

        assertEquals(AnalysisTaskStatus.STOPPED, response.getStatus());
        assertTrue(response.getErrorMessage().contains("暂停"));
        assertEquals(Boolean.TRUE, response.getCanResume());
    }

    @Test
    void shouldSkipPausedNodeAndContinueTask() {
        Long taskId = 37L;
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .status(AnalysisTaskStatus.STOPPED)
                .build();
        TaskNode node = pendingNode(taskId, "extract_schema", AgentType.EXTRACTOR, "[]", 0);
        node.setStatus(TaskNodeStatus.PAUSED);

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(nodeRepository.findByTaskIdAndNodeName(taskId, "extract_schema")).thenReturn(Optional.of(node));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId)).thenReturn(List.of(node));

        taskService.skipNode(taskId, "extract_schema");

        assertEquals(TaskNodeStatus.SKIPPED, node.getStatus());
        assertEquals("节点已由用户手动跳过", node.getErrorMessage());
        assertEquals("节点已由用户手动跳过", node.getInterventionReason());
        assertEquals(AnalysisTaskStatus.PENDING, task.getStatus());
        verify(nodeRepository).save(node);
        verify(taskRepository).save(task);
        verify(taskRunner).runTask(taskId);
    }

    @Test
    void shouldRequestCooperativeTerminationForRunningNode() {
        Long taskId = 38L;
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .status(AnalysisTaskStatus.RUNNING)
                .build();
        TaskNode node = pendingNode(taskId, "extract_schema", AgentType.EXTRACTOR, "[]", 0);
        node.setStatus(TaskNodeStatus.RUNNING);

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(nodeRepository.findByTaskIdAndNodeName(taskId, "extract_schema")).thenReturn(Optional.of(node));

        taskService.terminateNode(taskId, "extract_schema");

        assertEquals(TaskNodeStatus.RUNNING, node.getStatus());
        assertEquals(TaskNodeControlState.TERMINATE_REQUESTED, node.getControlState());
        assertTrue(node.getInterventionReason().contains("终止请求"));
        verify(nodeRepository).save(node);
        verifyNoInteractions(taskRunner);
    }

    @Test
    void shouldInvokeRecoveryServiceBeforeResumeWhenRunningNodeExists() {
        Long taskId = 41L;
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .status(AnalysisTaskStatus.FAILED)
                .build();

        TaskNode interruptedNode = TaskNode.builder()
                .taskId(taskId)
                .nodeName("extract_schema")
                .displayName("extract_schema")
                .agentType(AgentType.EXTRACTOR)
                .dependsOn("[\"collect_sources_web\"]")
                .executionOrder(1)
                .status(TaskNodeStatus.RUNNING)
                .inputData("{\"partial\":true}")
                .outputData("{\"partial\":true}")
                .errorMessage("interrupted")
                .retryCount(2)
                .build();

        TaskNode resumedNode = failedNode(taskId, "extract_schema", AgentType.EXTRACTOR, "[\"collect_sources_web\"]", 1);

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId))
                .thenReturn(List.of(interruptedNode))
                .thenReturn(List.of(resumedNode));

        taskService.resumeTask(taskId);

        verify(taskRecoveryService).resetInterruptedNodes(taskId);
        assertPendingCleared(resumedNode);
        verify(nodeRepository).saveAll(List.of(resumedNode));
        verify(taskRunner).runTask(taskId);
    }

    @Test
    void shouldRejectResumeForSuccessfulTask() {
        Long taskId = 51L;
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .status(AnalysisTaskStatus.SUCCESS)
                .build();

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        BusinessException exception = assertThrows(BusinessException.class, () -> taskService.resumeTask(taskId));

        assertSame(ResultCode.TASK_STATUS_INVALID, exception.getResultCode());
        verifyNoInteractions(nodeRepository, taskRunner, taskRecoveryService);
    }

    @Test
    void shouldExposeStoppedTaskInterventionCapabilities() {
        Long taskId = 56L;
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .status(AnalysisTaskStatus.STOPPED)
                .build();

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId)).thenReturn(List.of());

        TaskResponse response = taskService.getTask(taskId);

        assertEquals(Boolean.FALSE, response.getCanExecute());
        assertEquals(Boolean.TRUE, response.getCanResume());
        assertEquals(Boolean.FALSE, response.getCanRetry());
        assertEquals(Boolean.FALSE, response.getCanStop());
        assertTrue(response.getInterventionSummary().contains("恢复执行"));
        assertTrue(response.getInterventionSummary().contains("恢复对应节点"));
    }

    @Test
    void shouldBuildReadableCollectorOutputSummaryForTaskNodes() {
        Long taskId = 61L;
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .status(AnalysisTaskStatus.FAILED)
                .build();
        TaskNode collectorNode = TaskNode.builder()
                .id(1L)
                .taskId(taskId)
                .nodeName("collect_sources_01_01")
                .displayName("Feishu - DOCS采集")
                .agentType(AgentType.COLLECTOR)
                .dependsOn("[]")
                .required(true)
                .retryable(true)
                .maxRetries(3)
                .retryCount(0)
                .status(TaskNodeStatus.SUCCESS)
                .executionOrder(0)
                .outputData("""
                        {
                          "competitor": "Feishu",
                          "sourceType": "DOCS",
                          "selectedTargets": [{"url":"https://docs.example.com"}],
                          "successCollected": 1,
                          "totalCollected": 2,
                          "searchExecutionTrace": {
                            "supplementMethod": "BROWSER",
                            "degradationReason": "SEARCH_TIMEOUT_AFTER_SUPPLEMENT"
                          },
                          "searchProgress": {
                            "status": "DEGRADED"
                          }
                        }
                        """)
                .build();

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId)).thenReturn(List.of(collectorNode));

        List<TaskNodeResponse> responses = taskService.getTaskNodes(taskId);

        assertEquals(1, responses.size());
        assertTrue(responses.get(0).getOutputSummary().contains("选中 1 条"));
        assertTrue(responses.get(0).getOutputSummary().contains("补源方式=BROWSER"));
        assertTrue(responses.get(0).getOutputSummary().contains("进度状态=DEGRADED"));
        assertTrue(responses.get(0).getOutputSummary().contains("降级原因=SEARCH_TIMEOUT_AFTER_SUPPLEMENT"));
    }

    @Test
    void shouldBuildReadableCollectorConfigSummaryForPreviewAndTaskNodeList() {
        Long taskId = 81L;
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .status(AnalysisTaskStatus.FAILED)
                .build();
        TaskNode collectorNode = TaskNode.builder()
                .id(9L)
                .taskId(taskId)
                .nodeName("collect_sources_01_01")
                .displayName("Notion AI - DOCS采集")
                .agentType(AgentType.COLLECTOR)
                .dependsOn("[]")
                .required(true)
                .retryable(true)
                .maxRetries(3)
                .retryCount(0)
                .status(TaskNodeStatus.PENDING)
                .executionOrder(0)
                .nodeConfig("""
                        {
                          "competitorName": "Notion AI",
                          "sourceType": "DOCS",
                          "sourceScope": ["官网", "产品文档", "定价页面"],
                          "competitorUrls": ["https://www.notion.so/product/ai"],
                          "sourceCandidates": [{"url":"https://www.notion.so/help"}],
                          "searchMode": "HYBRID",
                          "searchQueries": ["Notion AI documentation", "Notion AI pricing"],
                          "browserSearchEnabled": true
                        }
                        """)
                .build();

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId)).thenReturn(List.of(collectorNode));

        List<TaskNodeResponse> responses = taskService.getTaskNodes(taskId);

        assertEquals(1, responses.size());
        assertTrue(responses.get(0).getConfigSummary().contains("Notion AI"));
        assertTrue(responses.get(0).getConfigSummary().contains("文档采集"));
        assertTrue(responses.get(0).getConfigSummary().contains("搜索模式：混合"));
        assertTrue(responses.get(0).getConfigSummary().contains("候选 1 条"));
        assertTrue(responses.get(0).getConfigSummary().contains("Query 2 条"));
        assertTrue(responses.get(0).getConfigSummary().contains("浏览器补源：开启"));
    }

    @Test
    void shouldExposeNodeLevelInterventionCapabilitiesAndImpactScope() {
        Long taskId = 86L;
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .status(AnalysisTaskStatus.FAILED)
                .build();
        TaskNode collectorNode = successfulNode(taskId, "collect_sources_web", AgentType.COLLECTOR, "[]", 0);
        collectorNode.setNodeConfig("""
                {"competitorName":"Feishu","sourceType":"DOCS"}
                """);
        collectorNode.setOutputData("""
                {
                  "searchAudit": {
                    "executionTrace": {
                      "traceVersion": "v1",
                      "recoveryCheckpoint": "SELECT_TARGETS"
                    }
                  }
                }
                """);
        TaskNode extractNode = failedNode(taskId, "extract_schema", AgentType.EXTRACTOR, "[\"collect_sources_web\"]", 1);
        TaskNode writeNode = failedNode(taskId, "write_report", AgentType.WRITER, "[\"extract_schema\"]", 2);

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId))
                .thenReturn(List.of(collectorNode, extractNode, writeNode));

        List<TaskNodeResponse> responses = taskService.getTaskNodes(taskId);
        TaskNodeResponse collectorResponse = responses.get(0);

        assertEquals(Boolean.TRUE, collectorResponse.getCanRerun());
        assertEquals(Boolean.TRUE, collectorResponse.getCanUpdateConfigAndRerun());
        assertEquals(Boolean.TRUE, collectorResponse.getCanReuseCheckpoint());
        assertEquals(Boolean.FALSE, collectorResponse.getCanPause());
        assertEquals(Boolean.FALSE, collectorResponse.getCanResumeNode());
        assertEquals(Boolean.FALSE, collectorResponse.getCanSkip());
        assertEquals(Boolean.FALSE, collectorResponse.getCanTerminate());
        assertEquals(3, collectorResponse.getAffectedNodeCount());
        assertEquals(List.of("collect_sources_web", "extract_schema", "write_report"),
                collectorResponse.getAffectedNodeNames());
        assertTrue(collectorResponse.getInterventionSummary().contains("2 个下游节点"));
        assertTrue(collectorResponse.getInterventionSummary().contains("搜索检查点"));
    }

    @Test
    void shouldExposePauseResumeSkipTerminateCapabilitiesByNodeStatus() {
        Long taskId = 87L;
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .status(AnalysisTaskStatus.RUNNING)
                .build();
        TaskNode pendingNode = pendingNode(taskId, "collect_sources_web", AgentType.COLLECTOR, "[]", 0);
        TaskNode pausedNode = pendingNode(taskId, "extract_schema", AgentType.EXTRACTOR, "[\"collect_sources_web\"]", 1);
        pausedNode.setStatus(TaskNodeStatus.PAUSED);
        pausedNode.setInterventionReason("节点已由用户暂停，等待恢复");
        TaskNode runningNode = pendingNode(taskId, "write_report", AgentType.WRITER, "[\"extract_schema\"]", 2);
        runningNode.setStatus(TaskNodeStatus.RUNNING);

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId))
                .thenReturn(List.of(pendingNode, pausedNode, runningNode));

        List<TaskNodeResponse> responses = taskService.getTaskNodes(taskId);

        assertEquals(Boolean.TRUE, responses.get(0).getCanPause());
        assertEquals(Boolean.TRUE, responses.get(0).getCanSkip());
        assertEquals(Boolean.TRUE, responses.get(0).getCanTerminate());
        assertEquals(Boolean.FALSE, responses.get(0).getCanResumeNode());

        assertEquals(Boolean.TRUE, responses.get(1).getCanResumeNode());
        assertEquals(Boolean.TRUE, responses.get(1).getCanSkip());
        assertEquals(Boolean.TRUE, responses.get(1).getCanTerminate());
        assertTrue(responses.get(1).getInterventionSummary().contains("已暂停"));

        assertEquals(Boolean.TRUE, responses.get(2).getCanTerminate());
        assertTrue(responses.get(2).getInterventionSummary().contains("协作式终止请求"));
    }

    @Test
    void shouldUpdateNodeConfigBeforeRerunFromSpecifiedNode() throws Exception {
        Long taskId = 91L;
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .status(AnalysisTaskStatus.FAILED)
                .build();

        TaskNode collectorNode = successfulNode(taskId, "collect_sources_web", AgentType.COLLECTOR, "[]", 0);
        collectorNode.setNodeConfig("""
                {"competitorName":"Feishu","sourceType":"DOCS","browserSearchEnabled":false}
                """);
        TaskNode extractNode = successfulNode(taskId, "extract_schema", AgentType.EXTRACTOR, "[\"collect_sources_web\"]", 1);

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId))
                .thenReturn(List.of(collectorNode, extractNode));

        taskService.updateNodeConfigAndRerun(taskId, "collect_sources_web",
                UpdateNodeConfigRequest.builder()
                        .nodeConfig("""
                                {"competitorName":"Feishu","sourceType":"DOCS","browserSearchEnabled":true}
                                """)
                        .build());

        JsonNode updatedConfig = objectMapper.readTree(collectorNode.getNodeConfig());
        assertTrue(updatedConfig.path("browserSearchEnabled").asBoolean());
        assertPendingCleared(collectorNode);
        assertPendingCleared(extractNode);
        verify(nodeRepository).save(collectorNode);
        verify(taskRunner).runTask(taskId);
    }

    @Test
    void shouldMarkRunningTaskStoppedAndDelegateNodeStopHandling() {
        Long taskId = 101L;
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .status(AnalysisTaskStatus.RUNNING)
                .build();

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        taskService.stopTask(taskId);

        assertEquals(AnalysisTaskStatus.STOPPED, task.getStatus());
        assertEquals("任务已由用户主动停止", task.getErrorMessage());
        assertTrue(task.getCompletedAt() != null);
        verify(taskRepository).save(task);
        verify(taskRecoveryService).markStoppedNodes(taskId);
        verifyNoInteractions(taskRunner);
    }

    @Test
    void shouldKeepManualStoppedTaskStatusEvenWhenRunningNodesRemainInSnapshot() {
        Long taskId = 102L;
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .status(AnalysisTaskStatus.STOPPED)
                .errorMessage("任务已由用户主动停止")
                .build();
        TaskNode runningNode = pendingNode(taskId, "collect_sources_web", AgentType.COLLECTOR, "[]", 0);
        runningNode.setStatus(TaskNodeStatus.RUNNING);
        TaskNode pendingNode = pendingNode(taskId, "extract_schema", AgentType.EXTRACTOR, "[\"collect_sources_web\"]", 1);

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId)).thenReturn(List.of(runningNode, pendingNode));

        TaskResponse response = taskService.getTask(taskId);

        assertEquals(AnalysisTaskStatus.STOPPED, response.getStatus());
        assertEquals(Boolean.FALSE, response.getCanStop());
        assertEquals(Boolean.TRUE, response.getCanResume());
        assertEquals(Boolean.FALSE, response.getCanExecute());
        assertTrue(response.getInterventionSummary().contains("恢复"));
    }

    private static TaskNode successfulNode(Long taskId, String nodeName, AgentType agentType, String dependsOn, int order) {
        return TaskNode.builder()
                .taskId(taskId)
                .nodeName(nodeName)
                .displayName(nodeName)
                .agentType(agentType)
                .dependsOn(dependsOn)
                .executionOrder(order)
                .status(TaskNodeStatus.SUCCESS)
                .inputData("{\"input\":true}")
                .outputData("{\"node\":\"" + nodeName + "\"}")
                .errorMessage("old error")
                .retryCount(1)
                .build();
    }

    private static TaskNode failedNode(Long taskId, String nodeName, AgentType agentType, String dependsOn, int order) {
        return TaskNode.builder()
                .taskId(taskId)
                .nodeName(nodeName)
                .displayName(nodeName)
                .agentType(agentType)
                .dependsOn(dependsOn)
                .executionOrder(order)
                .status(TaskNodeStatus.FAILED)
                .inputData("{\"input\":true}")
                .outputData("{\"node\":\"" + nodeName + "\"}")
                .errorMessage("failed")
                .retryCount(2)
                .build();
    }

    private static TaskNode pendingNode(Long taskId, String nodeName, AgentType agentType, String dependsOn, int order) {
        return TaskNode.builder()
                .taskId(taskId)
                .nodeName(nodeName)
                .displayName(nodeName)
                .agentType(agentType)
                .dependsOn(dependsOn)
                .executionOrder(order)
                .status(TaskNodeStatus.PENDING)
                .retryCount(0)
                .build();
    }

    private static void assertPendingCleared(TaskNode node) {
        assertEquals(TaskNodeStatus.PENDING, node.getStatus());
        assertNull(node.getInputData());
        assertNull(node.getOutputData());
        assertNull(node.getErrorMessage());
        assertNull(node.getStartedAt());
        assertNull(node.getCompletedAt());
        assertEquals(0, node.getRetryCount());
    }
}
