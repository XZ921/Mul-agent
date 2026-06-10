package cn.bugstack.competitoragent.task.command;

import cn.bugstack.competitoragent.common.BusinessException;
import cn.bugstack.competitoragent.common.ResultCode;
import cn.bugstack.competitoragent.event.TaskEventPublisher;
import cn.bugstack.competitoragent.governance.OrganizationQuotaPolicy;
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
import cn.bugstack.competitoragent.task.AnalysisTaskRunner;
import cn.bugstack.competitoragent.task.TaskProgressSnapshot;
import cn.bugstack.competitoragent.task.TaskQuotaCoordinator;
import cn.bugstack.competitoragent.task.TaskRecoveryService;
import cn.bugstack.competitoragent.task.TaskSnapshotCacheService;
import cn.bugstack.competitoragent.task.application.cleanup.TaskArtifactCleanupCoordinator;
import cn.bugstack.competitoragent.workflow.CompensationGraphAssembler;
import cn.bugstack.competitoragent.workflow.DynamicTaskGraphService;
import cn.bugstack.competitoragent.workflow.TaskPlanVersioner;
import cn.bugstack.competitoragent.workflow.event.WorkflowEventOutboxService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskRuntimeCommandAppServiceTest {

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
    private TaskSnapshotCacheService taskSnapshotCacheService;

    @Mock
    private TaskEventPublisher taskEventPublisher;

    @Mock
    private AnalysisTaskRunner taskRunner;

    @Mock
    private WorkflowEventOutboxService workflowEventOutboxService;

    @Mock
    private TaskRecoveryService taskRecoveryService;

    @Mock
    private TaskArtifactCleanupCoordinator taskArtifactCleanupCoordinator;

    @Mock
    private OrganizationQuotaPolicy organizationQuotaPolicy;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    private DynamicTaskGraphService dynamicTaskGraphService;

    private TaskRuntimeCommandAppService taskRuntimeCommandAppService;

    @BeforeEach
    void setUp() {
        dynamicTaskGraphService = new DynamicTaskGraphService(
                org.mockito.Mockito.mock(cn.bugstack.competitoragent.repository.TaskPlanRepository.class),
                new TaskPlanVersioner(objectMapper),
                new CompensationGraphAssembler(objectMapper));
        TaskQuotaCoordinator taskQuotaCoordinator = new TaskQuotaCoordinator(organizationQuotaPolicy, objectMapper);
        taskRuntimeCommandAppService = new TaskRuntimeCommandAppService(
                taskRepository,
                nodeRepository,
                taskSnapshotCacheService,
                taskEventPublisher,
                taskRunner,
                workflowEventOutboxService,
                dynamicTaskGraphService,
                taskRecoveryService,
                taskArtifactCleanupCoordinator,
                taskQuotaCoordinator,
                objectMapper);
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
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId)).thenReturn(List.of(node));

        taskRuntimeCommandAppService.pauseNode(taskId, "collect_sources_web");

        assertEquals(TaskNodeStatus.PAUSED, node.getStatus());
        assertEquals(TaskNodeControlState.NONE, node.getControlState());
        assertEquals("节点已由用户暂停，等待恢复", node.getInterventionReason());
        verify(nodeRepository).save(node);
        verify(taskSnapshotCacheService).saveTaskSnapshot(any(TaskProgressSnapshot.class));
        verify(taskEventPublisher).publishNodeStatusEvent(taskId, node, "NODE_PAUSED");
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

        taskRuntimeCommandAppService.resumeNode(taskId, "extract_schema");

        assertEquals(TaskNodeStatus.PENDING, node.getStatus());
        assertEquals(TaskNodeControlState.NONE, node.getControlState());
        assertNull(node.getErrorMessage());
        assertNull(node.getInterventionReason());
        assertEquals(AnalysisTaskStatus.PENDING, task.getStatus());
        verify(nodeRepository).save(node);
        verify(taskRepository).save(task);
        verify(workflowEventOutboxService).assertWorkflowIngressReady();
        verify(taskRunner).runTask(taskId);
        verify(taskEventPublisher).publishNodeStatusEvent(taskId, node, "NODE_RESUMED");
    }

    @Test
    void shouldKeepTaskStoppedWhenOtherPausedNodesStillExistAfterManualResume() {
        Long taskId = 361L;
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

        taskRuntimeCommandAppService.resumeNode(taskId, "extract_schema");

        assertEquals(TaskNodeStatus.PENDING, resumedNode.getStatus());
        assertEquals(AnalysisTaskStatus.STOPPED, task.getStatus());
        assertTrue(task.getErrorMessage().contains("暂停"));
        verify(nodeRepository).save(resumedNode);
        verify(taskRepository, never()).save(task);
        verifyNoInteractions(taskRunner);
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

        taskRuntimeCommandAppService.skipNode(taskId, "extract_schema");

        assertEquals(TaskNodeStatus.SKIPPED, node.getStatus());
        assertEquals("节点已由用户手动跳过", node.getErrorMessage());
        assertEquals("节点已由用户手动跳过", node.getInterventionReason());
        assertNotNull(node.getStartedAt());
        assertNotNull(node.getCompletedAt());
        assertEquals(AnalysisTaskStatus.PENDING, task.getStatus());
        verify(nodeRepository).save(node);
        verify(taskRepository).save(task);
        verify(workflowEventOutboxService).assertWorkflowIngressReady();
        verify(taskRunner).runTask(taskId);
        verify(taskEventPublisher).publishNodeStatusEvent(taskId, node, "NODE_SKIPPED");
    }

    @Test
    void shouldTerminatePendingNodeAndContinueTask() {
        Long taskId = 381L;
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .status(AnalysisTaskStatus.STOPPED)
                .build();
        TaskNode node = pendingNode(taskId, "extract_schema", AgentType.EXTRACTOR, "[]", 0);

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(nodeRepository.findByTaskIdAndNodeName(taskId, "extract_schema")).thenReturn(Optional.of(node));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId)).thenReturn(List.of(node));

        taskRuntimeCommandAppService.terminateNode(taskId, "extract_schema");

        assertEquals(TaskNodeStatus.SKIPPED, node.getStatus());
        assertEquals("节点已由用户强制终止", node.getErrorMessage());
        assertEquals("节点已由用户强制终止", node.getInterventionReason());
        assertEquals(AnalysisTaskStatus.PENDING, task.getStatus());
        verify(nodeRepository).save(node);
        verify(taskRepository).save(task);
        verify(workflowEventOutboxService).assertWorkflowIngressReady();
        verify(taskRunner).runTask(taskId);
        verify(taskEventPublisher).publishNodeStatusEvent(taskId, node, "NODE_TERMINATED");
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
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId)).thenReturn(List.of(node));

        taskRuntimeCommandAppService.terminateNode(taskId, "extract_schema");

        assertEquals(TaskNodeStatus.RUNNING, node.getStatus());
        assertEquals(TaskNodeControlState.TERMINATE_REQUESTED, node.getControlState());
        assertTrue(node.getInterventionReason().contains("终止请求"));
        verify(nodeRepository).save(node);
        verify(taskEventPublisher).publishNodeStatusEvent(taskId, node, "NODE_TERMINATE_REQUESTED");
        verifyNoInteractions(taskRunner);
    }

    @Test
    void shouldExecuteFailedTaskWithFullResetAndRunTask() {
        Long taskId = 101L;
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .status(AnalysisTaskStatus.FAILED)
                .errorMessage("boom")
                .completedAt(LocalDateTime.now())
                .build();
        TaskNode collectNode = successfulNode(taskId, "collect_sources_web", AgentType.COLLECTOR, "[]", 0);
        TaskNode extractNode = failedNode(taskId, "extract_schema", AgentType.EXTRACTOR, "[\"collect_sources_web\"]", 1);

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId)).thenReturn(List.of(collectNode, extractNode));

        taskRuntimeCommandAppService.executeTask(taskId);

        assertPendingCleared(collectNode);
        assertPendingCleared(extractNode);
        assertEquals(AnalysisTaskStatus.PENDING, task.getStatus());
        assertNull(task.getErrorMessage());
        assertNull(task.getCompletedAt());
        verify(taskArtifactCleanupCoordinator).cleanupTaskArtifacts(taskId);
        verify(taskSnapshotCacheService).evictTaskRuntime(taskId);
        verify(nodeRepository).saveAll(List.of(collectNode, extractNode));
        verify(taskRepository).save(task);
        verify(workflowEventOutboxService).assertWorkflowIngressReady();
        verify(taskRunner).runTask(taskId);
    }

    @Test
    void shouldRetryFailedTaskWithFullResetWithoutStartingRunner() {
        Long taskId = 102L;
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .status(AnalysisTaskStatus.FAILED)
                .errorMessage("boom")
                .completedAt(LocalDateTime.now())
                .build();
        TaskNode collectNode = successfulNode(taskId, "collect_sources_web", AgentType.COLLECTOR, "[]", 0);
        TaskNode extractNode = failedNode(taskId, "extract_schema", AgentType.EXTRACTOR, "[\"collect_sources_web\"]", 1);

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId)).thenReturn(List.of(collectNode, extractNode));

        taskRuntimeCommandAppService.retryTask(taskId);

        assertPendingCleared(collectNode);
        assertPendingCleared(extractNode);
        assertEquals(AnalysisTaskStatus.PENDING, task.getStatus());
        assertNull(task.getErrorMessage());
        assertNull(task.getCompletedAt());
        verify(taskArtifactCleanupCoordinator).cleanupTaskArtifacts(taskId);
        verify(taskSnapshotCacheService).evictTaskRuntime(taskId);
        verify(nodeRepository).saveAll(List.of(collectNode, extractNode));
        verify(taskRepository).save(task);
        verifyNoInteractions(taskRunner, workflowEventOutboxService);
    }

    @Test
    void shouldPropagateCleanupFailureInSameUseCase() {
        Long taskId = 1002L;
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .status(AnalysisTaskStatus.FAILED)
                .build();

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        doThrow(new IllegalStateException("cleanup failed"))
                .when(taskArtifactCleanupCoordinator).cleanupTaskArtifacts(taskId);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> taskRuntimeCommandAppService.retryTask(taskId));

        assertEquals("cleanup failed", exception.getMessage());
        verify(taskArtifactCleanupCoordinator).cleanupTaskArtifacts(taskId);
        verify(nodeRepository, never()).findByTaskIdOrderByExecutionOrderAsc(taskId);
        verify(taskRepository, never()).save(task);
        verifyNoInteractions(taskRunner, workflowEventOutboxService);
    }

    @Test
    void shouldRerunOnlyAffectedBranchFromSpecifiedNode() {
        Long taskId = 103L;
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

        taskRuntimeCommandAppService.rerunFromNode(taskId, "collect_sources_web");

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
        verify(taskArtifactCleanupCoordinator).cleanupNodeArtifacts(taskId, "collect_sources_web");
        verify(taskRepository).save(task);
        verify(nodeRepository).saveAll(List.of(collectWeb, extractSchema, analyze, write));
        verify(workflowEventOutboxService).assertWorkflowIngressReady();
        verify(taskRunner).runTask(taskId);
    }

    @Test
    void shouldDelegateNodeCleanupToCleanupCoordinatorForRewriteBranchRerun() {
        Long taskId = 1103L;
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

        taskRuntimeCommandAppService.rerunFromNode(taskId, "rewrite_report");

        assertPendingCleared(rewriteReport);
        assertPendingCleared(finalReview);
        assertEquals(TaskNodeStatus.SUCCESS, writeReport.getStatus());
        assertEquals(TaskNodeStatus.SUCCESS, qualityCheck.getStatus());
        verify(taskArtifactCleanupCoordinator).cleanupNodeArtifacts(taskId, "rewrite_report");
        verify(taskRunner).runTask(taskId);
    }

    @Test
    void shouldResumeOnlyIncompleteNodesAndKeepSuccessfulCheckpoints() {
        Long taskId = 104L;
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

        taskRuntimeCommandAppService.resumeTask(taskId);

        assertEquals(TaskNodeStatus.SUCCESS, collectNode.getStatus());
        assertEquals("{\"node\":\"collect_sources_web\"}", collectNode.getOutputData());
        assertEquals(1, collectNode.getRetryCount());
        assertPendingCleared(extractNode);
        assertPendingCleared(analyzeNode);
        assertEquals(AnalysisTaskStatus.PENDING, task.getStatus());
        assertNull(task.getErrorMessage());
        assertNull(task.getCompletedAt());
        verify(nodeRepository).saveAll(List.of(collectNode, extractNode, analyzeNode));
        verify(taskRepository).save(task);
        verify(taskRunner).runTask(taskId);
        verify(taskRecoveryService, never()).resetInterruptedNodes(taskId);
    }

    @Test
    void shouldWriteSearchAuditCheckpointBackToCollectorConfigWhenRerunningCollectorNode() throws Exception {
        Long taskId = 105L;
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

        taskRuntimeCommandAppService.rerunFromNode(taskId, "collect_sources_web");

        JsonNode updatedConfig = objectMapper.readTree(collectorNode.getNodeConfig());
        assertTrue(updatedConfig.has("searchAuditCheckpoint"));
        assertEquals("SELECT_TARGETS",
                updatedConfig.path("searchAuditCheckpoint").path("executionTrace").path("recoveryCheckpoint").asText());
    }

    @Test
    void shouldUpdateNodeConfigBeforeRerunFromSpecifiedNode() throws Exception {
        Long taskId = 106L;
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

        taskRuntimeCommandAppService.updateNodeConfigAndRerun(taskId, "collect_sources_web",
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
        Long taskId = 107L;
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .status(AnalysisTaskStatus.RUNNING)
                .build();
        TaskNode runningNode = pendingNode(taskId, "collect_sources_web", AgentType.COLLECTOR, "[]", 0);
        runningNode.setStatus(TaskNodeStatus.RUNNING);

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId)).thenReturn(List.of(runningNode));

        taskRuntimeCommandAppService.stopTask(taskId);

        assertEquals(AnalysisTaskStatus.STOPPED, task.getStatus());
        assertNotNull(task.getCompletedAt());
        verify(taskRepository).save(task);
        verify(taskRecoveryService).markStoppedNodes(taskId);
        verify(taskEventPublisher).publishTaskStatusEvent(
                taskId,
                AnalysisTaskStatus.STOPPED,
                task.getErrorMessage(),
                task.getErrorMessage());
        verifyNoInteractions(taskRunner);
    }

    @Test
    void shouldRejectResumeForSuccessfulTask() {
        Long taskId = 108L;
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .status(AnalysisTaskStatus.SUCCESS)
                .build();

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> taskRuntimeCommandAppService.resumeTask(taskId));

        assertSame(ResultCode.TASK_STATUS_INVALID, exception.getResultCode());
        verifyNoInteractions(nodeRepository, taskRunner, taskRecoveryService);
    }

    @Test
    void shouldMarkCompensatableNodeAsCompensatedWhenResumingTask() {
        Long taskId = 109L;
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .status(AnalysisTaskStatus.FAILED)
                .errorMessage("old failure")
                .build();
        TaskNode collectNode = successfulNode(taskId, "collect_sources_web", AgentType.COLLECTOR, "[]", 0);
        TaskNode reviewNode = failedNode(taskId, "quality_check", AgentType.REVIEWER, "[\"collect_sources_web\"]", 1);
        reviewNode.setStatus(TaskNodeStatus.WAITING_INTERVENTION);
        TaskNode rewriteNode = pendingNode(taskId, "rewrite_report", AgentType.WRITER, "[\"quality_check\"]", 2);

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId))
                .thenReturn(List.of(collectNode, reviewNode, rewriteNode));
        doAnswer(invocation -> {
            TaskNode targetNode = invocation.getArgument(0);
            targetNode.setStatus(TaskNodeStatus.COMPENSATED);
            targetNode.setErrorMessage("compensated");
            return true;
        }).when(taskRecoveryService).applyCompensationIfRequired(reviewNode);

        taskRuntimeCommandAppService.resumeTask(taskId);

        assertEquals(TaskNodeStatus.COMPENSATED, reviewNode.getStatus());
        assertEquals(TaskNodeStatus.PENDING, rewriteNode.getStatus());
        assertEquals(AnalysisTaskStatus.PENDING, task.getStatus());
        verify(nodeRepository).saveAll(List.of(collectNode, reviewNode, rewriteNode));
        verify(taskRunner).runTask(taskId);
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

    @SuppressWarnings("unused")
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
