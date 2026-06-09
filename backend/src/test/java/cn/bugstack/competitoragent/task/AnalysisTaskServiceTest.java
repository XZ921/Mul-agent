package cn.bugstack.competitoragent.task;

import cn.bugstack.competitoragent.common.BusinessException;
import cn.bugstack.competitoragent.common.ResultCode;
import cn.bugstack.competitoragent.event.TaskEventPublisher;
import cn.bugstack.competitoragent.governance.OrganizationQuotaPolicy;
import cn.bugstack.competitoragent.governance.QuotaDecision;
import cn.bugstack.competitoragent.model.dto.CreateTaskRequest;
import cn.bugstack.competitoragent.model.dto.TaskListPageResponse;
import cn.bugstack.competitoragent.model.dto.TaskResponse;
import cn.bugstack.competitoragent.model.dto.TaskNodeResponse;
import cn.bugstack.competitoragent.model.dto.UpdateNodeConfigRequest;
import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import cn.bugstack.competitoragent.model.entity.AiCallAuditRecord;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.entity.TaskPlan;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.AnalysisTaskStatus;
import cn.bugstack.competitoragent.model.enums.TaskNodeControlState;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.repository.AgentExecutionLogRepository;
import cn.bugstack.competitoragent.repository.AiCallAuditRecordRepository;
import cn.bugstack.competitoragent.repository.AnalysisTaskRepository;
import cn.bugstack.competitoragent.repository.CompetitorKnowledgeRepository;
import cn.bugstack.competitoragent.repository.EvidenceSourceRepository;
import cn.bugstack.competitoragent.repository.ReportRepository;
import cn.bugstack.competitoragent.repository.TaskPlanRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import cn.bugstack.competitoragent.search.SearchAuditSnapshot;
import cn.bugstack.competitoragent.task.TaskArtifactCleanupService;
import cn.bugstack.competitoragent.task.TaskQuotaCoordinator;
import cn.bugstack.competitoragent.task.assembler.TaskNodeViewAssembler;
import cn.bugstack.competitoragent.task.command.TaskDefinitionAppService;
import cn.bugstack.competitoragent.task.command.TaskRuntimeCommandAppService;
import cn.bugstack.competitoragent.task.query.TaskQueryAppService;
import cn.bugstack.competitoragent.workflow.CompensationGraphAssembler;
import cn.bugstack.competitoragent.workflow.DynamicTaskGraphService;
import cn.bugstack.competitoragent.workflow.NodeFailureCategory;
import cn.bugstack.competitoragent.workflow.TaskPlanVersioner;
import cn.bugstack.competitoragent.workflow.event.WorkflowEventOutboxService;
import cn.bugstack.competitoragent.workflow.event.WorkflowEventPublisher;
import cn.bugstack.competitoragent.workflow.WorkflowFactory;
import cn.bugstack.competitoragent.workflow.WorkflowPlan;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.doAnswer;
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
    private AiCallAuditRecordRepository aiCallAuditRecordRepository;

    @Mock
    private WorkflowFactory workflowFactory;

    @Mock
    private AnalysisTaskRunner taskRunner;

    @Mock
    private TaskRecoveryService taskRecoveryService;

    @Mock
    private TaskSnapshotCacheService taskSnapshotCacheService;

    @Mock
    private TaskEventPublisher taskEventPublisher;

    @Mock
    private WorkflowEventPublisher workflowEventPublisher;

    @Mock
    private WorkflowEventOutboxService workflowEventOutboxService;

    @Mock
    private DynamicTaskGraphService dynamicTaskGraphService;

    @Mock
    private TaskPlanRepository taskPlanRepository;

    @Mock
    private OrganizationQuotaPolicy organizationQuotaPolicy;

    @Mock
    private TaskArtifactCleanupService taskArtifactCleanupService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    private AnalysisTaskService taskService;

    @BeforeEach
    void setUp() {
        lenient().when(taskRecoveryService.getTaskSnapshotOrRebuild(any())).thenReturn(Optional.empty());
        DynamicTaskGraphService realDynamicTaskGraphService = new DynamicTaskGraphService(
                org.mockito.Mockito.mock(cn.bugstack.competitoragent.repository.TaskPlanRepository.class),
                new TaskPlanVersioner(objectMapper),
                new CompensationGraphAssembler(objectMapper));
        lenient().when(dynamicTaskGraphService.calculateAffectedNodes(any(), any()))
                .thenAnswer(invocation -> realDynamicTaskGraphService.calculateAffectedNodes(
                        invocation.getArgument(0),
                        invocation.getArgument(1)));
        TaskNodeViewAssembler assembler = new TaskNodeViewAssembler(
                aiCallAuditRecordRepository,
                taskPlanRepository,
                taskRecoveryService,
                objectMapper);
        TaskQuotaCoordinator taskQuotaCoordinator = new TaskQuotaCoordinator(organizationQuotaPolicy, objectMapper);
        TaskQueryAppService taskQueryAppService = new TaskQueryAppService(
                taskRepository,
                nodeRepository,
                assembler);
        TaskRuntimeCommandAppService taskRuntimeCommandAppService = new TaskRuntimeCommandAppService(
                taskRepository,
                nodeRepository,
                evidenceRepository,
                knowledgeRepository,
                reportRepository,
                logRepository,
                taskSnapshotCacheService,
                taskEventPublisher,
                taskRunner,
                workflowEventOutboxService,
                realDynamicTaskGraphService,
                taskRecoveryService,
                taskArtifactCleanupService,
                taskQuotaCoordinator,
                objectMapper);
        TaskDefinitionAppService taskDefinitionAppService = new TaskDefinitionAppService(
                taskRepository,
                nodeRepository,
                evidenceRepository,
                knowledgeRepository,
                reportRepository,
                logRepository,
                workflowFactory,
                taskSnapshotCacheService,
                taskEventPublisher,
                workflowEventPublisher,
                assembler,
                objectMapper,
                organizationQuotaPolicy,
                taskArtifactCleanupService,
                taskQuotaCoordinator);
        taskService = new AnalysisTaskService(
                taskQueryAppService,
                taskRuntimeCommandAppService,
                taskDefinitionAppService);
    }

    @Test
    void shouldExposePlanVersionMetadataInTaskAndNodeResponses() {
        AnalysisTask task = AnalysisTask.builder()
                .id(301L)
                .taskName("动态计划任务")
                .status(AnalysisTaskStatus.PENDING)
                .currentPlanVersionId(21L)
                .currentPlanVersion(3)
                .build();
        TaskNode node = TaskNode.builder()
                .id(401L)
                .taskId(301L)
                .nodeName("rewrite_revision_patch_v3")
                .displayName("动态改写节点")
                .agentType(AgentType.WRITER)
                .status(TaskNodeStatus.PENDING)
                .planVersionId(21L)
                .branchKey("root/review-2/review-3")
                .executionOrder(7)
                .build();

        when(taskRepository.findById(301L)).thenReturn(Optional.of(task));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(301L)).thenReturn(List.of(node));
        when(taskPlanRepository.findByTaskIdOrderByPlanVersionAsc(301L)).thenReturn(List.of(
                TaskPlan.builder().id(11L).taskId(301L).planVersion(1).branchKey("root").build(),
                TaskPlan.builder().id(21L).taskId(301L).planVersion(3).branchKey("root/review-2/review-3").build()
        ));

        TaskResponse response = taskService.getTask(301L);
        List<TaskNodeResponse> nodeResponses = taskService.getTaskNodes(301L);

        assertEquals(21L, response.getCurrentPlanVersionId());
        assertEquals(3, response.getCurrentPlanVersion());
        assertEquals(21L, nodeResponses.get(0).getPlanVersionId());
        assertEquals(3, nodeResponses.get(0).getPlanVersion());
        assertEquals("root/review-2/review-3", nodeResponses.get(0).getBranchKey());
    }

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
    void shouldBlockTaskCreationWithStructuredGovernanceDecisionWhenTaskQuotaExceeded() {
        // Task 5.8.c 要求任务创建链路在组织级并发不足时直接返回治理阻断结果，
        // 不能继续保存任务再把超并发误记成普通创建或执行失败。
        CreateTaskRequest request = new CreateTaskRequest();
        request.setTaskName("组织级治理阻断测试");
        request.setSubjectProduct("企业知识平台");
        request.setCompetitorNames(List.of("Notion AI"));
        request.setCompetitorUrls(List.of("https://www.notion.so"));
        request.setAnalysisDimensions(List.of("产品能力"));
        request.setSourceScope(List.of("官网"));

        when(organizationQuotaPolicy.checkAndReserve(any(), any(), any(), any(Integer.class), any()))
                .thenReturn(QuotaDecision.deny(
                        "BLOCKED_QUOTA_EXCEEDED",
                        "当前组织任务并发已达上限，请等待已有任务释放占位后再重试",
                        "default-organization",
                        "TASK",
                        "TASK_CONCURRENCY",
                        1,
                        0,
                        null,
                        List.of("https://ops.example.com/quota/task-concurrency")
                ));
        RuntimeException exception = assertThrows(RuntimeException.class, () -> taskService.createTask(request));

        assertEquals("GovernanceBlockException", exception.getClass().getSimpleName());
        Object decision = readAccessor(exception, "decision");
        assertEquals("BLOCKED_QUOTA_EXCEEDED", readAccessor(decision, "decisionCode"));
        assertEquals("TASK_CONCURRENCY", readAccessor(decision, "quotaKey"));
        assertEquals("当前组织任务并发已达上限，请等待已有任务释放占位后再重试",
                readAccessor(decision, "summary"));
        verify(taskRepository, never()).save(any(AnalysisTask.class));
    }

    @Test
    void shouldReserveTaskQuotaOnlyOnceWhenCreatingTaskThroughFacade() {
        CreateTaskRequest request = new CreateTaskRequest();
        request.setTaskName("单次配额预留测试");
        request.setSubjectProduct("企业知识平台");
        request.setCompetitorNames(List.of("Notion AI"));
        request.setCompetitorUrls(List.of("https://www.notion.so"));
        request.setAnalysisDimensions(List.of("产品能力"));
        request.setSourceScope(List.of("官网"));

        TaskNode createdNode = pendingNode(501L, "collect_sources_01_01", AgentType.COLLECTOR, "[]", 0);
        when(organizationQuotaPolicy.checkAndReserve(any(), any(), any(), any(Integer.class), any()))
                .thenReturn(QuotaDecision.allow(
                        "ALLOWED_RESERVED",
                        "quota ok",
                        "default-organization",
                        "TASK",
                        "TASK_CONCURRENCY",
                        1,
                        1,
                        "lease-1",
                        List.of("https://www.notion.so")
                ));
        when(taskRepository.save(any(AnalysisTask.class))).thenAnswer(invocation -> {
            AnalysisTask task = invocation.getArgument(0);
            if (task.getId() == null) {
                task.setId(501L);
            }
            return task;
        });
        when(workflowFactory.createWorkflow(any(AnalysisTask.class))).thenAnswer(invocation -> {
            AnalysisTask task = invocation.getArgument(0);
            task.setCurrentPlanVersionId(21L);
            task.setCurrentPlanVersion(1);
            return List.of(createdNode);
        });
        when(taskRepository.findById(501L)).thenReturn(Optional.of(AnalysisTask.builder()
                .id(501L)
                .taskName("单次配额预留测试")
                .subjectProduct("企业知识平台")
                .competitorNames("[\"Notion AI\"]")
                .competitorUrls("[\"https://www.notion.so\"]")
                .analysisDimensions("[\"产品能力\"]")
                .sourceScope("[\"官网\"]")
                .status(AnalysisTaskStatus.PENDING)
                .currentPlanVersionId(21L)
                .currentPlanVersion(1)
                .build()));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(501L)).thenReturn(List.of(createdNode));

        TaskResponse response = taskService.createTask(request);

        assertEquals(501L, response.getId());
        verify(organizationQuotaPolicy, times(1))
                .checkAndReserve(any(), any(), any(), any(Integer.class), any());
    }

    @Test
    void shouldReturnFormalTaskListPaginationSummaryAndAttentionItems() {
        AnalysisTask failedTask = listTask(201L, "Failed task", AnalysisTaskStatus.FAILED,
                LocalDateTime.of(2026, 6, 4, 10, 0),
                LocalDateTime.of(2026, 6, 4, 10, 4));
        AnalysisTask stoppedTask = listTask(202L, "Stopped task", AnalysisTaskStatus.STOPPED,
                LocalDateTime.of(2026, 6, 4, 9, 50),
                LocalDateTime.of(2026, 6, 4, 10, 3));
        AnalysisTask runningTask = listTask(203L, "Running task", AnalysisTaskStatus.RUNNING,
                LocalDateTime.of(2026, 6, 4, 9, 40),
                LocalDateTime.of(2026, 6, 4, 10, 2));
        AnalysisTask successTask = listTask(204L, "Success task", AnalysisTaskStatus.SUCCESS,
                LocalDateTime.of(2026, 6, 4, 9, 30),
                LocalDateTime.of(2026, 6, 4, 10, 1));

        List<AnalysisTask> matchedTasks = List.of(failedTask, stoppedTask, runningTask, successTask);
        when(taskRepository.findAllByOrderByCreatedAtDesc()).thenReturn(matchedTasks);
        when(taskRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 2)))
                .thenReturn(new PageImpl<>(List.of(failedTask, stoppedTask), PageRequest.of(0, 2), matchedTasks.size()));

        TaskNode stoppedNode = pendingNode(202L, "extract_schema", AgentType.EXTRACTOR, "[]", 0);
        stoppedNode.setStatus(TaskNodeStatus.PAUSED);
        stoppedNode.setInterventionReason("节点已由用户暂停，等待恢复");
        TaskNode runningNode = pendingNode(203L, "write_report", AgentType.WRITER, "[]", 0);
        runningNode.setStatus(TaskNodeStatus.RUNNING);

        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(201L))
                .thenReturn(List.of(failedNode(201L, "collect_sources_web", AgentType.COLLECTOR, "[]", 0)));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(202L)).thenReturn(List.of(stoppedNode));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(203L)).thenReturn(List.of(runningNode));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(204L))
                .thenReturn(List.of(successfulNode(204L, "write_report", AgentType.WRITER, "[]", 0)));

        TaskListPageResponse response = taskService.listTasks(null, 1, 2);

        assertEquals(1, response.getPageNum());
        assertEquals(2, response.getPageSize());
        assertEquals(4, response.getTotal());
        assertEquals(2, response.getTotalPages());
        assertEquals(List.of("Failed task", "Stopped task"),
                response.getItems().stream().map(TaskResponse::getTaskName).toList());
        assertEquals(List.of("Failed task", "Stopped task", "Running task"),
                response.getAttentionItems().stream().map(TaskResponse::getTaskName).toList());
        assertEquals(4, response.getSummary().getTotal());
        assertEquals(1, response.getSummary().getRunning());
        assertEquals(1, response.getSummary().getSuccess());
        assertEquals(1, response.getSummary().getFailed());
        assertEquals(1, response.getSummary().getStopped());
        assertEquals(50, response.getSummary().getAvgProgress());
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

        verify(taskRecoveryService, times(2)).applyCompensationIfRequired(any(TaskNode.class));
        verify(taskRecoveryService, never()).resetInterruptedNodes(any());
        verify(nodeRepository).saveAll(List.of(collectNode, extractNode, analyzeNode));
        verify(taskRepository).save(task);
        verify(taskRunner).runTask(taskId);
    }

    @Test
    void shouldMarkCompensatableNodeAsCompensatedWhenResumingTask() {
        Long taskId = 32L;
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .status(AnalysisTaskStatus.FAILED)
                .errorMessage("old failure")
                .build();

        TaskNode collectNode = successfulNode(taskId, "collect_sources_web", AgentType.COLLECTOR, "[]", 0);
        TaskNode reviewNode = failedNode(taskId, "quality_check", AgentType.REVIEWER, "[\"collect_sources_web\"]", 1);
        reviewNode.setStatus(TaskNodeStatus.WAITING_INTERVENTION);
        reviewNode.setFailureCategory(NodeFailureCategory.COMPENSATABLE);
        TaskNode rewriteNode = pendingNode(taskId, "rewrite_report", AgentType.WRITER, "[\"quality_check\"]", 2);

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId))
                .thenReturn(List.of(collectNode, reviewNode, rewriteNode));
        doAnswer(invocation -> {
            TaskNode targetNode = invocation.getArgument(0);
            targetNode.setStatus(TaskNodeStatus.COMPENSATED);
            targetNode.setErrorMessage("节点已通过补偿收口");
            return true;
        }).when(taskRecoveryService).applyCompensationIfRequired(reviewNode);

        taskService.resumeTask(taskId);

        assertEquals(TaskNodeStatus.COMPENSATED, reviewNode.getStatus());
        assertEquals(TaskNodeStatus.PENDING, rewriteNode.getStatus());
        assertEquals(AnalysisTaskStatus.PENDING, task.getStatus());
        verify(nodeRepository).saveAll(List.of(collectNode, reviewNode, rewriteNode));
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
                .taskQuotaReserved(true)
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
    void shouldExposeRedisProgressSnapshotInTaskDetailResponse() {
        Long taskId = 56_2L;
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .status(AnalysisTaskStatus.RUNNING)
                .taskQuotaReserved(true)
                .build();
        TaskNode runningNode = pendingNode(taskId, "analyze_competitors", AgentType.ANALYZER, "[]", 0);
        runningNode.setStatus(TaskNodeStatus.RUNNING);
        TaskProgressSnapshot snapshot = TaskProgressSnapshot.builder()
                .taskId(taskId)
                .taskStatus("RUNNING")
                .currentStage("数据分析")
                .completedNodes(2)
                .totalNodes(6)
                .activeNodeNames(List.of("analyze_competitors"))
                .updatedAt(java.time.LocalDateTime.of(2026, 6, 3, 12, 0, 0))
                .build();

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId)).thenReturn(List.of(runningNode));
        when(taskRecoveryService.getTaskSnapshotOrRebuild(taskId)).thenReturn(Optional.of(snapshot));

        TaskResponse response = taskService.getTask(taskId);

        assertEquals("数据分析", response.getCurrentStage());
        assertEquals(List.of("analyze_competitors"), response.getActiveNodeNames());
        assertEquals(2, response.getCompletedNodes());
        assertEquals(6, response.getTotalNodes());
        assertEquals("/api/task/562/events", response.getEventStreamPath());
        assertNotNull(response.getSnapshotUpdatedAt());
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
                .taskQuotaReserved(true)
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
        assertNotNull(response.getResumeAdvice());
        assertTrue(response.getResumeAdvice().contains("已完成节点"));
        assertNull(response.getRetryAdvice());
        assertNotNull(response.getReplayEntrySummary());
        assertTrue(response.getReplayEntrySummary().contains("节点追踪"));
    }

    @Test
    void shouldExposeFailedTaskRecoveryGuidance() {
        Long taskId = 56_3L;
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .status(AnalysisTaskStatus.FAILED)
                .errorMessage("报告生成阶段中断")
                .build();

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId)).thenReturn(List.of());

        TaskResponse response = taskService.getTask(taskId);

        assertEquals(Boolean.TRUE, response.getCanResume());
        assertEquals(Boolean.TRUE, response.getCanRetry());
        assertNotNull(response.getResumeAdvice());
        assertTrue(response.getResumeAdvice().contains("保留已完成节点"));
        assertNotNull(response.getRetryAdvice());
        assertTrue(response.getRetryAdvice().contains("从头重走"));
        assertNotNull(response.getReplayEntrySummary());
        assertTrue(response.getReplayEntrySummary().contains("高级诊断"));
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
    void shouldExposeGovernanceSemanticsInCollectorInsight() {
        Long taskId = 62L;
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .status(AnalysisTaskStatus.SUCCESS)
                .build();
        TaskNode collectorNode = TaskNode.builder()
                .id(2L)
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
                .nodeConfig("""
                        {
                          "competitorName": "Feishu",
                          "sourceType": "DOCS"
                        }
                        """)
                .outputData("""
                        {
                          "competitor": "Feishu",
                          "sourceType": "DOCS",
                          "taskRagContext": "检索查询：Feishu docs governance\\n缺口说明：企业权限细节公开资料仍不足",
                          "sourceCandidates": [
                            {
                              "url": "https://docs.feishu.cn",
                              "title": "Feishu Docs",
                              "sourceType": "DOCS",
                              "trustTier": "HIGH",
                              "trustTierLabel": "高可信",
                              "rankingReasons": ["来源可信度：高可信", "命中文档入口"],
                              "rankingSummary": "来源可信度：高可信；命中文档入口",
                              "selectionStage": "SELECTED",
                              "selectionReason": "VERIFIED_TARGET",
                              "selectionSummary": "已通过验证并进入正式采集"
                            }
                          ],
                          "selectedTargets": [
                            {
                              "url": "https://docs.feishu.cn",
                              "title": "Feishu Docs",
                              "selectionStage": "SELECTED",
                              "selectionReason": "VERIFIED_TARGET",
                              "targetSelectionSummary": "已通过验证并进入正式采集",
                              "selectionSummary": "已通过验证并进入正式采集",
                              "trustTier": "HIGH",
                              "trustTierLabel": "高可信",
                              "rankingReasons": ["来源可信度：高可信", "命中文档入口"],
                              "rankingSummary": "来源可信度：高可信；命中文档入口",
                              "hasPrefetchedPage": true
                            }
                          ]
                        }
                        """)
                .build();

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId)).thenReturn(List.of(collectorNode));

        List<TaskNodeResponse> responses = taskService.getTaskNodes(taskId);

        assertEquals(1, responses.size());
        assertEquals("高可信", responses.get(0).getCollectorInsight().getSourceCandidates().get(0).getTrustTierLabel());
        assertEquals("来源可信度：高可信；命中文档入口",
                responses.get(0).getCollectorInsight().getSourceCandidates().get(0).getRankingSummary());
        assertEquals("VERIFIED_TARGET",
                responses.get(0).getCollectorInsight().getSelectedTargets().get(0).getSelectionReason());
        assertEquals("已通过验证并进入正式采集",
                responses.get(0).getCollectorInsight().getSelectedTargets().get(0).getSelectionSummary());
        assertEquals(2,
                responses.get(0).getCollectorInsight().getSelectedTargets().get(0).getRankingReasons().size());
        assertTrue(responses.get(0).getCollectorInsight().getTaskRagContext().contains("企业权限细节公开资料仍不足"));
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
        assertNotNull(collectorResponse.getRerunActionSummary());
        assertTrue(collectorResponse.getRerunActionSummary().contains("上游输入仍然可复用"));
        assertNotNull(collectorResponse.getConfigRerunActionSummary());
        assertTrue(collectorResponse.getConfigRerunActionSummary().contains("扩大补源范围"));
        assertNotNull(collectorResponse.getImpactSummary());
        assertTrue(collectorResponse.getImpactSummary().contains("3 个节点"));
        assertNotNull(collectorResponse.getCheckpointSummary());
        assertTrue(collectorResponse.getCheckpointSummary().contains("可复用检查点"));
        assertNotNull(collectorResponse.getReplayEntrySummary());
        assertTrue(collectorResponse.getReplayEntrySummary().contains("高级诊断"));
    }

    @Test
    void shouldExposePauseResumeSkipTerminateCapabilitiesByNodeStatus() {
        Long taskId = 87L;
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .status(AnalysisTaskStatus.RUNNING)
                .taskQuotaReserved(true)
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
        assertNotNull(responses.get(1).getImpactSummary());
        assertTrue(responses.get(1).getImpactSummary().contains("恢复后会继续"));
        assertNotNull(responses.get(1).getReplayEntrySummary());
        assertTrue(responses.get(1).getReplayEntrySummary().contains("高级诊断"));

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
                .taskQuotaReserved(true)
                .build();

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        taskService.stopTask(taskId);

        assertEquals(AnalysisTaskStatus.STOPPED, task.getStatus());
        assertEquals("任务已由用户主动停止", task.getErrorMessage());
        assertTrue(task.getCompletedAt() != null);
        assertFalse(task.isTaskQuotaReserved());
        verify(taskRepository).save(task);
        verify(organizationQuotaPolicy).releaseReservation(any(), any(), any(), any(Integer.class), any());
        verify(taskRecoveryService).markStoppedNodes(taskId);
        verifyNoInteractions(taskRunner);
    }

    @Test
    void shouldEvictRuntimeSnapshotWhenDeletingTask() {
        Long taskId = 103L;
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .status(AnalysisTaskStatus.FAILED)
                .build();

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId)).thenReturn(List.of());

        taskService.deleteTask(taskId);

        verify(taskSnapshotCacheService).evictTaskRuntime(taskId);
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

    @Test
    void shouldExposePhase4RuntimeSummaryInTaskAndNodeResponses() {
        Long taskId = 104L;
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .status(AnalysisTaskStatus.FAILED)
                .build();

        TaskNode retryNode = pendingNode(taskId, "collect_sources_web", AgentType.COLLECTOR, "[]", 0);
        retryNode.setStatus(TaskNodeStatus.WAITING_RETRY);
        retryNode.setFailureCategory(NodeFailureCategory.TRANSIENT_INFRASTRUCTURE);
        retryNode.setRetryCount(1);
        retryNode.setLastAttemptAt(LocalDateTime.of(2026, 6, 5, 13, 40, 0));
        retryNode.setNextRetryAt(LocalDateTime.of(2026, 6, 5, 13, 40, 30));
        retryNode.setErrorMessage("HTTP timeout while collecting source page");

        TaskNode interventionNode = failedNode(taskId, "extract_schema", AgentType.EXTRACTOR, "[\"collect_sources_web\"]", 1);
        interventionNode.setStatus(TaskNodeStatus.WAITING_INTERVENTION);
        interventionNode.setFailureCategory(NodeFailureCategory.MANUAL_INTERVENTION_REQUIRED);
        interventionNode.setInterventionReason("需要人工确认抽取规则");

        TaskNode compensatedNode = successfulNode(taskId, "write_report", AgentType.WRITER, "[\"extract_schema\"]", 2);
        compensatedNode.setStatus(TaskNodeStatus.COMPENSATED);
        compensatedNode.setFailureCategory(NodeFailureCategory.COMPENSATABLE);

        TaskProgressSnapshot snapshot = TaskProgressSnapshot.builder()
                .taskId(taskId)
                .taskStatus("STOPPED")
                .currentStage("extract_schema：等待人工处理")
                .errorMessage("存在等待人工处理的节点，请确认后继续")
                .statusSummary("存在等待人工处理的节点")
                .totalNodes(3)
                .completedNodes(1)
                .waitingRetryNodeCount(1)
                .waitingInterventionNodeCount(1)
                .compensatedNodeCount(1)
                .activeNodeNames(List.of("collect_sources_web", "extract_schema"))
                .updatedAt(LocalDateTime.of(2026, 6, 5, 13, 41, 0))
                .build();

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId))
                .thenReturn(List.of(retryNode, interventionNode, compensatedNode));
        when(taskRecoveryService.getTaskSnapshotOrRebuild(taskId)).thenReturn(Optional.of(snapshot));

        TaskResponse response = taskService.getTask(taskId);
        List<TaskNodeResponse> nodeResponses = taskService.getTaskNodes(taskId);

        assertEquals(AnalysisTaskStatus.STOPPED, response.getStatus());
        assertEquals("存在等待人工处理的节点", response.getStatusSummary());
        assertEquals(1, response.getWaitingRetryNodeCount());
        assertEquals(1, response.getWaitingInterventionNodeCount());
        assertEquals(1, response.getCompensatedNodeCount());
        assertEquals(List.of("collect_sources_web", "extract_schema"), response.getActiveNodeNames());

        assertEquals(NodeFailureCategory.TRANSIENT_INFRASTRUCTURE, nodeResponses.get(0).getFailureCategory());
        assertEquals(LocalDateTime.of(2026, 6, 5, 13, 40, 0), nodeResponses.get(0).getLastAttemptAt());
        assertEquals(LocalDateTime.of(2026, 6, 5, 13, 40, 30), nodeResponses.get(0).getNextRetryAt());
        assertTrue(nodeResponses.get(0).getStatusSummary().contains("等待系统自动重试"));

        assertEquals(NodeFailureCategory.MANUAL_INTERVENTION_REQUIRED, nodeResponses.get(1).getFailureCategory());
        assertTrue(nodeResponses.get(1).getStatusSummary().contains("等待人工处理"));

        assertEquals(NodeFailureCategory.COMPENSATABLE, nodeResponses.get(2).getFailureCategory());
        assertTrue(nodeResponses.get(2).getStatusSummary().contains("补偿"));
    }

    @Test
    void shouldExposeReadableAiGovernanceSummaryInTaskNodeResponse() {
        Long taskId = 105L;
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .status(AnalysisTaskStatus.SUCCESS)
                .build();
        TaskNode writerNode = successfulNode(taskId, "write_report", AgentType.WRITER, "[\"analyze_competitors\"]", 0);

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId)).thenReturn(List.of(writerNode));
        when(aiCallAuditRecordRepository.findTopByTaskIdAndNodeNameOrderByCreatedAtDesc(taskId, "write_report"))
                .thenReturn(Optional.of(AiCallAuditRecord.builder()
                        .taskId(taskId)
                        .nodeName("write_report")
                        .providerKey("siliconflow")
                        .capability("CHAT")
                        .success(true)
                        .fallbackUsed(true)
                        .totalTokens(30)
                        .summary("主 Provider 超时，已切换到备用 Provider")
                        .build()));

        List<TaskNodeResponse> responses = taskService.getTaskNodes(taskId);

        assertEquals(1, responses.size());
        assertTrue(responses.get(0).getAiGovernanceSummary().contains("备用 Provider"));
        assertTrue(responses.get(0).getAiGovernanceSummary().contains("Token 总量=30"));
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

    private static AnalysisTask listTask(Long id,
                                         String taskName,
                                         AnalysisTaskStatus status,
                                         LocalDateTime createdAt,
                                         LocalDateTime updatedAt) {
        return AnalysisTask.builder()
                .id(id)
                .taskName(taskName)
                .subjectProduct("Workspace")
                .competitorNames("[\"Notion AI\"]")
                .status(status)
                .createdAt(createdAt)
                .updatedAt(updatedAt)
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

    /**
     * 测试阶段通过反射读取治理结果，确保 Red 阶段先暴露“结构化阻断结果缺失”的真实缺口，
     * 而不是因为直接依赖新异常类型导致测试无法编译。
     */
    private Object readAccessor(Object target, String accessorName) {
        Method method = ReflectionUtils.findMethod(target.getClass(),
                "get" + Character.toUpperCase(accessorName.charAt(0)) + accessorName.substring(1));
        assertNotNull(method, () -> "缺少访问器：" + target.getClass().getSimpleName() + "." + accessorName);
        ReflectionUtils.makeAccessible(method);
        return ReflectionUtils.invokeMethod(method, target);
    }
}
