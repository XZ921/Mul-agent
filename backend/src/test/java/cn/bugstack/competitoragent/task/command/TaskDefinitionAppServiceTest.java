package cn.bugstack.competitoragent.task.command;

import cn.bugstack.competitoragent.governance.OrganizationQuotaPolicy;
import cn.bugstack.competitoragent.governance.QuotaDecision;
import cn.bugstack.competitoragent.model.dto.CreateTaskRequest;
import cn.bugstack.competitoragent.model.dto.TaskNodeResponse;
import cn.bugstack.competitoragent.model.dto.TaskResponse;
import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.AnalysisTaskStatus;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.repository.AgentExecutionLogRepository;
import cn.bugstack.competitoragent.repository.AiCallAuditRecordRepository;
import cn.bugstack.competitoragent.repository.AnalysisTaskRepository;
import cn.bugstack.competitoragent.repository.CompetitorKnowledgeRepository;
import cn.bugstack.competitoragent.repository.EvidenceSourceRepository;
import cn.bugstack.competitoragent.repository.ReportRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import cn.bugstack.competitoragent.repository.TaskPlanRepository;
import cn.bugstack.competitoragent.task.TaskArtifactCleanupService;
import cn.bugstack.competitoragent.task.TaskProgressSnapshot;
import cn.bugstack.competitoragent.task.TaskQuotaCoordinator;
import cn.bugstack.competitoragent.task.TaskRecoveryService;
import cn.bugstack.competitoragent.task.TaskSnapshotCacheService;
import cn.bugstack.competitoragent.task.assembler.TaskNodeViewAssembler;
import cn.bugstack.competitoragent.workflow.WorkflowFactory;
import cn.bugstack.competitoragent.workflow.WorkflowPlan;
import cn.bugstack.competitoragent.workflow.event.WorkflowEventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskDefinitionAppServiceTest {

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
    private TaskSnapshotCacheService taskSnapshotCacheService;

    @Mock
    private cn.bugstack.competitoragent.event.TaskEventPublisher taskEventPublisher;

    @Mock
    private WorkflowEventPublisher workflowEventPublisher;

    @Mock
    private TaskRecoveryService taskRecoveryService;

    @Mock
    private TaskPlanRepository taskPlanRepository;

    @Mock
    private OrganizationQuotaPolicy organizationQuotaPolicy;

    @Mock
    private TaskArtifactCleanupService taskArtifactCleanupService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    private TaskDefinitionAppService taskDefinitionAppService;
    private TaskQuotaCoordinator taskQuotaCoordinator;

    @BeforeEach
    void setUp() {
        lenient().when(taskRecoveryService.getTaskSnapshotOrRebuild(any())).thenReturn(Optional.empty());
        taskQuotaCoordinator = new TaskQuotaCoordinator(organizationQuotaPolicy, objectMapper);
        TaskNodeViewAssembler assembler = new TaskNodeViewAssembler(
                aiCallAuditRecordRepository,
                taskPlanRepository,
                taskRecoveryService,
                objectMapper);
        taskDefinitionAppService = new TaskDefinitionAppService(
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
    }

    @Test
    void shouldCreateTaskAndReturnCreatedTaskResponse() {
        CreateTaskRequest request = buildRequest();
        TaskNode createdNode = pendingNode(501L, "collect_sources_01_01");
        when(organizationQuotaPolicy.checkAndReserve(any(), any(), any(), any(Integer.class), any()))
                .thenReturn(QuotaDecision.allow(
                        "ALLOWED",
                        "quota ok",
                        "default-organization",
                        "TASK",
                        "TASK_CONCURRENCY",
                        1,
                        1,
                        "lease-1",
                        List.of("https://www.notion.so/product/ai")));
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
        when(taskRepository.findById(501L)).thenAnswer(invocation -> Optional.of(AnalysisTask.builder()
                .id(501L)
                .taskName("AI 知识库竞品分析")
                .subjectProduct("企业级知识库")
                .competitorNames("[\"Notion AI\"]")
                .competitorUrls("[\"https://www.notion.so/product/ai\"]")
                .analysisDimensions("[\"产品功能\",\"价格策略\"]")
                .sourceScope("[\"官网\",\"产品文档\"]")
                .status(AnalysisTaskStatus.PENDING)
                .currentPlanVersionId(21L)
                .currentPlanVersion(1)
                .build()));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(501L)).thenReturn(List.of(createdNode));

        TaskResponse response = taskDefinitionAppService.createTask(request);

        assertEquals(501L, response.getId());
        assertEquals("AI 知识库竞品分析", response.getTaskName());
        assertEquals(AnalysisTaskStatus.PENDING, response.getStatus());
        assertEquals(21L, response.getCurrentPlanVersionId());
        verify(organizationQuotaPolicy, times(1))
                .checkAndReserve(any(), any(), any(), any(Integer.class), any());
        verify(workflowFactory).createWorkflow(any(AnalysisTask.class));
        verify(taskSnapshotCacheService).saveTaskSnapshot(any(TaskProgressSnapshot.class));
        verify(taskEventPublisher).publishTaskSnapshot(any(TaskProgressSnapshot.class));
        verify(workflowEventPublisher).publishTaskCreated(any(AnalysisTask.class));
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

        List<TaskNodeResponse> responses = taskDefinitionAppService.previewWorkflow(buildRequest());

        assertEquals(1, responses.size());
        assertEquals("collect_sources_01_01", responses.get(0).getNodeName());
        assertEquals(TaskNodeStatus.PENDING, responses.get(0).getStatus());
        assertFalse(Boolean.TRUE.equals(responses.get(0).getCanPause()));
        verify(workflowFactory, times(1)).buildPreviewPlan(any(AnalysisTask.class));
        verify(workflowFactory, never()).buildPlan(any(AnalysisTask.class));
    }

    @Test
    void shouldBlockTaskCreationWithStructuredGovernanceDecisionWhenTaskQuotaExceeded() {
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
                        List.of("https://ops.example.com/quota/task-concurrency")));

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> taskDefinitionAppService.createTask(buildRequest()));

        assertEquals("GovernanceBlockException", exception.getClass().getSimpleName());
        Object decision = readAccessor(exception, "decision");
        assertEquals("BLOCKED_QUOTA_EXCEEDED", readAccessor(decision, "decisionCode"));
        assertEquals("TASK_CONCURRENCY", readAccessor(decision, "quotaKey"));
        verify(taskRepository, never()).save(any(AnalysisTask.class));
    }

    @Test
    void shouldDeleteTaskAndEvictRuntimeSnapshot() {
        Long taskId = 103L;
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .status(AnalysisTaskStatus.FAILED)
                .taskQuotaReserved(true)
                .build();
        List<TaskNode> nodes = List.of(pendingNode(taskId, "collect_sources_01_01"));

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId)).thenReturn(nodes);

        taskDefinitionAppService.deleteTask(taskId);

        assertFalse(task.isTaskQuotaReserved());
        verify(taskArtifactCleanupService).cleanupTaskArtifacts(taskId);
        verify(organizationQuotaPolicy).releaseReservation(any(), any(), any(), any(Integer.class), any());
        verify(nodeRepository).deleteAll(nodes);
        verify(taskRepository).delete(task);
        verify(taskSnapshotCacheService).evictTaskRuntime(taskId);
    }

    private static CreateTaskRequest buildRequest() {
        CreateTaskRequest request = new CreateTaskRequest();
        request.setTaskName("AI 知识库竞品分析");
        request.setSubjectProduct("企业级知识库");
        request.setCompetitorNames(List.of("Notion AI"));
        request.setCompetitorUrls(List.of("https://www.notion.so/product/ai"));
        request.setAnalysisDimensions(List.of("产品功能", "价格策略"));
        request.setSourceScope(List.of("官网", "产品文档"));
        return request;
    }

    private static TaskNode pendingNode(Long taskId, String nodeName) {
        return TaskNode.builder()
                .taskId(taskId)
                .nodeName(nodeName)
                .displayName(nodeName)
                .agentType(AgentType.COLLECTOR)
                .dependsOn("[]")
                .executionOrder(0)
                .status(TaskNodeStatus.PENDING)
                .retryCount(0)
                .required(true)
                .retryable(true)
                .maxRetries(3)
                .build();
    }

    private Object readAccessor(Object target, String accessorName) {
        Method method = ReflectionUtils.findMethod(target.getClass(),
                "get" + Character.toUpperCase(accessorName.charAt(0)) + accessorName.substring(1));
        assertNotNull(method, () -> "缺少访问器：" + target.getClass().getSimpleName() + "." + accessorName);
        ReflectionUtils.makeAccessible(method);
        return ReflectionUtils.invokeMethod(method, target);
    }
}
