package cn.bugstack.competitoragent.task;

import cn.bugstack.competitoragent.model.dto.RecoveryCheckpointResponse;
import cn.bugstack.competitoragent.model.dto.TaskRecoveryAdvice;
import cn.bugstack.competitoragent.model.dto.TaskReplayResponse;
import cn.bugstack.competitoragent.model.entity.AgentExecutionLog;
import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import cn.bugstack.competitoragent.model.entity.MemorySnapshot;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.entity.TaskNodeExecutionAttempt;
import cn.bugstack.competitoragent.model.entity.TaskPlan;
import cn.bugstack.competitoragent.model.entity.TaskWorkflowEvent;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.AnalysisTaskStatus;
import cn.bugstack.competitoragent.model.enums.TaskNodeControlState;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.repository.AgentExecutionLogRepository;
import cn.bugstack.competitoragent.repository.AnalysisTaskRepository;
import cn.bugstack.competitoragent.repository.MemorySnapshotRepository;
import cn.bugstack.competitoragent.repository.TaskNodeExecutionAttemptRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import cn.bugstack.competitoragent.repository.TaskPlanRepository;
import cn.bugstack.competitoragent.repository.TaskWorkflowEventRepository;
import cn.bugstack.competitoragent.governance.OrganizationQuotaPolicy;
import cn.bugstack.competitoragent.workflow.DynamicTaskGraphService;
import cn.bugstack.competitoragent.workflow.NodeFailureCategory;
import cn.bugstack.competitoragent.workflow.RecoveryEngine;
import cn.bugstack.competitoragent.workflow.event.WorkflowEventOutboxService;
import cn.bugstack.competitoragent.workflow.event.WorkflowEventType;
import cn.bugstack.competitoragent.model.dto.SearchAuditSummary;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Task 5.6.e 回放投影测试基线。
 * <p>
 * 这个测试只关心两个当前任务完成标志：
 * 1. 任务 / 节点 / 审计主链路回放仍能稳定投影；
 * 2. 回放响应已经显式预留对话与导出接入点，供后续 Task 5.7 / 5.9 扩展。
 */
class TaskReplayProjectionServiceTest {

    @Test
    void shouldExposeOrchestrationDecisionEventsInReplayTimeline() {
        TaskPlanRepository taskPlanRepository = mock(TaskPlanRepository.class);
        TaskWorkflowEventRepository taskWorkflowEventRepository = mock(TaskWorkflowEventRepository.class);
        TaskNodeRepository taskNodeRepository = mock(TaskNodeRepository.class);
        TaskNodeExecutionAttemptRepository taskNodeExecutionAttemptRepository = mock(TaskNodeExecutionAttemptRepository.class);
        MemorySnapshotRepository memorySnapshotRepository = mock(MemorySnapshotRepository.class);
        AgentExecutionLogRepository agentExecutionLogRepository = mock(AgentExecutionLogRepository.class);
        RecoveryCheckpointService recoveryCheckpointService = mock(RecoveryCheckpointService.class);
        TaskRecoveryService taskRecoveryService = new TaskRecoveryService(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null) {
            @Override
            public TaskRecoveryAdvice buildRecoveryAdvice(Long taskId) {
                return TaskRecoveryAdvice.builder()
                        .recommendedAction("OBSERVE_ONLY")
                        .summary("none")
                        .blockingNodeNames(List.of())
                        .resumeSupported(false)
                        .sourceUrls(List.of())
                        .build();
            }
        };

        LocalDateTime baseTime = LocalDateTime.of(2026, 6, 23, 15, 0, 0);
        TaskPlan activePlan = TaskPlan.builder()
                .id(12L)
                .taskId(42L)
                .planVersion(3)
                .branchKey("root/review-3")
                .active(true)
                .createdAt(baseTime.minusMinutes(10))
                .build();
        TaskWorkflowEvent orchestrationEvent = TaskWorkflowEvent.builder()
                .id(101L)
                .eventId("evt-orchestration-101")
                .taskId(42L)
                .nodeName("quality_check_final")
                .planVersionId(12L)
                .branchKey("root/review-3")
                .eventType(WorkflowEventType.ORCHESTRATION_DECISION_RECORDED)
                .deliveryStatus(TaskWorkflowEvent.STATUS_CONSUMED)
                .topic("task.orchestration")
                .tag("orchestration_decision_recorded")
                .payload("{\"summary\":\"Orchestrator 已生成运行期编排决策\"}")
                .sourceUrls("[\"https://www.notion.so/pricing\"]")
                .createdAt(baseTime.minusMinutes(2))
                .updatedAt(baseTime.minusMinutes(2))
                .build();
        TaskWorkflowEvent collaborationEvent = TaskWorkflowEvent.builder()
                .id(102L)
                .eventId("evt-collaboration-102")
                .taskId(42L)
                .nodeName("collaboration_plan")
                .planVersionId(12L)
                .branchKey("root/review-3")
                .eventType(WorkflowEventType.COLLABORATION_PLAN_RECORDED)
                .deliveryStatus(TaskWorkflowEvent.STATUS_CONSUMED)
                .topic("task.collaboration")
                .tag("collaboration_plan_recorded")
                .payload("{\"summary\":\"协作计划已记录：cp-task-42-v1\"}")
                .sourceUrls("[\"https://www.notion.so\"]")
                .createdAt(baseTime.minusMinutes(3))
                .updatedAt(baseTime.minusMinutes(3))
                .build();

        when(taskPlanRepository.findByTaskIdOrderByPlanVersionAsc(42L)).thenReturn(List.of(activePlan));
        when(taskPlanRepository.findFirstByTaskIdAndActiveTrueOrderByPlanVersionDesc(42L)).thenReturn(Optional.of(activePlan));
        when(taskWorkflowEventRepository.findAll()).thenReturn(List.of(collaborationEvent, orchestrationEvent));
        when(taskNodeRepository.findByTaskIdOrderByExecutionOrderAsc(42L)).thenReturn(List.of());
        when(taskNodeExecutionAttemptRepository.findAll()).thenReturn(List.of());
        when(memorySnapshotRepository.findByTaskIdOrderByIdDesc(42L)).thenReturn(List.of());
        when(agentExecutionLogRepository.findByTaskIdOrderByCreatedAtAsc(42L)).thenReturn(List.of());
        when(recoveryCheckpointService.listTaskCheckpoints(42L)).thenReturn(List.of());
        when(taskRecoveryService.buildRecoveryAdvice(42L)).thenReturn(TaskRecoveryAdvice.builder()
                .recommendedAction("OBSERVE_ONLY")
                .summary("none")
                .blockingNodeNames(List.of())
                .resumeSupported(false)
                .sourceUrls(List.of())
                .build());

        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        TaskReplayProjectionService service = new TaskReplayProjectionService(
                taskPlanRepository,
                taskWorkflowEventRepository,
                taskNodeRepository,
                taskNodeExecutionAttemptRepository,
                memorySnapshotRepository,
                agentExecutionLogRepository,
                recoveryCheckpointService,
                taskRecoveryService,
                objectMapper
        );

        TaskReplayResponse replayResponse = service.getTaskReplay(42L);

        assertThat(replayResponse.getTimeline())
                .anySatisfy(event -> {
                    assertThat(event.getEventType()).isEqualTo("ORCHESTRATION_DECISION_RECORDED");
                    assertThat(event.getSummary()).contains("Orchestrator 已生成运行期编排决策");
                });
        assertThat(replayResponse.getTimeline())
                .anySatisfy(event -> {
                    assertThat(event.getEventType()).isEqualTo("COLLABORATION_PLAN_RECORDED");
                    assertThat(event.getSummary()).contains("协作计划");
                });
        assertThat(replayResponse.getSourceUrls()).contains("https://www.notion.so/pricing");
        assertThat(replayResponse.getSourceUrls()).contains("https://www.notion.so");
    }

    @Test
    void shouldProjectAnalyzerOrchestrationDecisionTrace() {
        TaskPlanRepository taskPlanRepository = mock(TaskPlanRepository.class);
        TaskWorkflowEventRepository taskWorkflowEventRepository = mock(TaskWorkflowEventRepository.class);
        TaskNodeRepository taskNodeRepository = mock(TaskNodeRepository.class);
        TaskNodeExecutionAttemptRepository taskNodeExecutionAttemptRepository = mock(TaskNodeExecutionAttemptRepository.class);
        MemorySnapshotRepository memorySnapshotRepository = mock(MemorySnapshotRepository.class);
        AgentExecutionLogRepository agentExecutionLogRepository = mock(AgentExecutionLogRepository.class);
        RecoveryCheckpointService recoveryCheckpointService = mock(RecoveryCheckpointService.class);
        TaskRecoveryService taskRecoveryService = mock(TaskRecoveryService.class);

        TaskWorkflowEvent event = TaskWorkflowEvent.builder()
                .taskId(99L)
                .nodeName("analyze_competitors")
                .eventType(WorkflowEventType.ORCHESTRATION_DECISION_RECORDED)
                .payload("""
                        {
                          "decisionId": "od-99-analyze_competitors-human",
                          "triggerNodeName": "analyze_competitors",
                          "decisionType": "WAIT_FOR_HUMAN",
                          "sourceUrls": [],
                          "evidenceState": "MISSING_SOURCE"
                        }
                        """)
                .sourceUrls("[]")
                .createdAt(LocalDateTime.of(2026, 6, 24, 19, 0))
                .build();

        when(taskPlanRepository.findByTaskIdOrderByPlanVersionAsc(99L)).thenReturn(List.of());
        when(taskPlanRepository.findFirstByTaskIdAndActiveTrueOrderByPlanVersionDesc(99L)).thenReturn(Optional.empty());
        when(taskWorkflowEventRepository.findAll()).thenReturn(List.of(event));
        when(taskNodeRepository.findByTaskIdOrderByExecutionOrderAsc(99L)).thenReturn(List.of());
        when(taskNodeExecutionAttemptRepository.findAll()).thenReturn(List.of());
        when(memorySnapshotRepository.findByTaskIdOrderByIdDesc(99L)).thenReturn(List.of());
        when(agentExecutionLogRepository.findByTaskIdOrderByCreatedAtAsc(99L)).thenReturn(List.of());
        when(recoveryCheckpointService.listTaskCheckpoints(99L)).thenReturn(List.of());
        when(taskRecoveryService.buildRecoveryAdvice(99L)).thenReturn(TaskRecoveryAdvice.builder()
                .recommendedAction("OBSERVE_ONLY")
                .summary("none")
                .blockingNodeNames(List.of())
                .resumeSupported(false)
                .sourceUrls(List.of())
                .build());

        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        TaskReplayProjectionService projectionService = new TaskReplayProjectionService(
                taskPlanRepository,
                taskWorkflowEventRepository,
                taskNodeRepository,
                taskNodeExecutionAttemptRepository,
                memorySnapshotRepository,
                agentExecutionLogRepository,
                recoveryCheckpointService,
                taskRecoveryService,
                objectMapper
        );

        TaskReplayResponse replay = projectionService.getTaskReplay(99L);

        assertThat(replay.getTimeline())
                .anySatisfy(item -> {
                    assertThat(item.getNodeName()).isEqualTo("analyze_competitors");
                    assertThat(item.getEventType()).isEqualTo("ORCHESTRATION_DECISION_RECORDED");
                });
    }

    @Test
    void shouldProjectWriterOrchestrationDecisionTrace() {
        TaskPlanRepository taskPlanRepository = mock(TaskPlanRepository.class);
        TaskWorkflowEventRepository taskWorkflowEventRepository = mock(TaskWorkflowEventRepository.class);
        TaskNodeRepository taskNodeRepository = mock(TaskNodeRepository.class);
        TaskNodeExecutionAttemptRepository taskNodeExecutionAttemptRepository = mock(TaskNodeExecutionAttemptRepository.class);
        MemorySnapshotRepository memorySnapshotRepository = mock(MemorySnapshotRepository.class);
        AgentExecutionLogRepository agentExecutionLogRepository = mock(AgentExecutionLogRepository.class);
        RecoveryCheckpointService recoveryCheckpointService = mock(RecoveryCheckpointService.class);
        TaskRecoveryService taskRecoveryService = mock(TaskRecoveryService.class);

        TaskWorkflowEvent event = TaskWorkflowEvent.builder()
                .taskId(99L)
                .nodeName("write_report")
                .eventType(WorkflowEventType.ORCHESTRATION_DECISION_RECORDED)
                .payload("""
                        {
                          "decisionId": "od-99-write_report-human",
                          "triggerNodeName": "write_report",
                          "decisionType": "WAIT_FOR_HUMAN",
                          "sourceUrls": [],
                          "evidenceState": "MISSING_SOURCE"
                        }
                        """)
                .sourceUrls("[]")
                .createdAt(LocalDateTime.of(2026, 6, 24, 19, 30))
                .build();

        when(taskPlanRepository.findByTaskIdOrderByPlanVersionAsc(99L)).thenReturn(List.of());
        when(taskPlanRepository.findFirstByTaskIdAndActiveTrueOrderByPlanVersionDesc(99L)).thenReturn(Optional.empty());
        when(taskWorkflowEventRepository.findAll()).thenReturn(List.of(event));
        when(taskNodeRepository.findByTaskIdOrderByExecutionOrderAsc(99L)).thenReturn(List.of());
        when(taskNodeExecutionAttemptRepository.findAll()).thenReturn(List.of());
        when(memorySnapshotRepository.findByTaskIdOrderByIdDesc(99L)).thenReturn(List.of());
        when(agentExecutionLogRepository.findByTaskIdOrderByCreatedAtAsc(99L)).thenReturn(List.of());
        when(recoveryCheckpointService.listTaskCheckpoints(99L)).thenReturn(List.of());
        when(taskRecoveryService.buildRecoveryAdvice(99L)).thenReturn(TaskRecoveryAdvice.builder()
                .recommendedAction("OBSERVE_ONLY")
                .summary("none")
                .blockingNodeNames(List.of())
                .resumeSupported(false)
                .sourceUrls(List.of())
                .build());

        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        TaskReplayProjectionService projectionService = new TaskReplayProjectionService(
                taskPlanRepository,
                taskWorkflowEventRepository,
                taskNodeRepository,
                taskNodeExecutionAttemptRepository,
                memorySnapshotRepository,
                agentExecutionLogRepository,
                recoveryCheckpointService,
                taskRecoveryService,
                objectMapper
        );

        TaskReplayResponse replay = projectionService.getTaskReplay(99L);

        assertThat(replay.getTimeline())
                .anySatisfy(item -> {
                    assertThat(item.getNodeName()).isEqualTo("write_report");
                    assertThat(item.getEventType()).isEqualTo("ORCHESTRATION_DECISION_RECORDED");
                });
    }

    @Test
    void shouldProjectStructuredOrchestrationDecisionIntoReplayMainPath() {
        TaskPlanRepository taskPlanRepository = mock(TaskPlanRepository.class);
        TaskWorkflowEventRepository taskWorkflowEventRepository = mock(TaskWorkflowEventRepository.class);
        TaskNodeRepository taskNodeRepository = mock(TaskNodeRepository.class);
        TaskNodeExecutionAttemptRepository taskNodeExecutionAttemptRepository = mock(TaskNodeExecutionAttemptRepository.class);
        MemorySnapshotRepository memorySnapshotRepository = mock(MemorySnapshotRepository.class);
        AgentExecutionLogRepository agentExecutionLogRepository = mock(AgentExecutionLogRepository.class);
        RecoveryCheckpointService recoveryCheckpointService = mock(RecoveryCheckpointService.class);
        TaskRecoveryService taskRecoveryService = mock(TaskRecoveryService.class);

        TaskWorkflowEvent decisionEvent = TaskWorkflowEvent.builder()
                .id(3301L)
                .eventId("evt-review-3301")
                .taskId(120L)
                .nodeName("quality_check_final")
                .eventType(WorkflowEventType.ORCHESTRATION_DECISION_RECORDED)
                .payload("""
                        {
                          "decision": {
                            "decisionId": "od-120-review",
                            "triggerNodeName": "quality_check_final",
                            "decisionType": "WAIT_FOR_HUMAN",
                            "actionType": "MANUAL_REVIEW",
                            "reason": "终审阻塞，等待人工补证",
                            "evidenceState": "MISSING_SOURCE",
                            "sourceUrls": ["https://docs.example.com/replay-gap"]
                          }
                        }
                        """)
                .sourceUrls("[\"https://docs.example.com/replay-gap\"]")
                .createdAt(LocalDateTime.of(2026, 6, 26, 18, 0))
                .build();

        when(taskPlanRepository.findByTaskIdOrderByPlanVersionAsc(120L)).thenReturn(List.of());
        when(taskPlanRepository.findFirstByTaskIdAndActiveTrueOrderByPlanVersionDesc(120L)).thenReturn(Optional.empty());
        when(taskWorkflowEventRepository.findAll()).thenReturn(List.of(decisionEvent));
        when(taskNodeRepository.findByTaskIdOrderByExecutionOrderAsc(120L)).thenReturn(List.of());
        when(taskNodeExecutionAttemptRepository.findAll()).thenReturn(List.of());
        when(memorySnapshotRepository.findByTaskIdOrderByIdDesc(120L)).thenReturn(List.of());
        when(agentExecutionLogRepository.findByTaskIdOrderByCreatedAtAsc(120L)).thenReturn(List.of());
        when(recoveryCheckpointService.listTaskCheckpoints(120L)).thenReturn(List.of());

        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        TaskReplayProjectionService projectionService = new TaskReplayProjectionService(
                taskPlanRepository,
                taskWorkflowEventRepository,
                taskNodeRepository,
                taskNodeExecutionAttemptRepository,
                memorySnapshotRepository,
                agentExecutionLogRepository,
                recoveryCheckpointService,
                taskRecoveryService,
                objectMapper
        );

        TaskReplayResponse replay = projectionService.getTaskReplay(120L);
        JsonNode payload = objectMapper.valueToTree(replay);

        assertThat(payload.at("/latestOrchestrationDecision/decisionType").asText()).isEqualTo("WAIT_FOR_HUMAN");
        assertThat(payload.at("/latestOrchestrationDecision/evidenceState").asText()).isEqualTo("MISSING_SOURCE");
        assertThat(payload.at("/timeline/0/orchestrationDecision/decisionId").asText()).isEqualTo("od-120-review");
        assertThat(payload.at("/timeline/0/summary").asText()).contains("WAIT_FOR_HUMAN");
    }

    @Test
    void shouldProjectCitationDecisionTraceAndBackfillTaskLevelSourceUrls() {
        TaskPlanRepository taskPlanRepository = mock(TaskPlanRepository.class);
        TaskWorkflowEventRepository taskWorkflowEventRepository = mock(TaskWorkflowEventRepository.class);
        TaskNodeRepository taskNodeRepository = mock(TaskNodeRepository.class);
        TaskNodeExecutionAttemptRepository taskNodeExecutionAttemptRepository = mock(TaskNodeExecutionAttemptRepository.class);
        MemorySnapshotRepository memorySnapshotRepository = mock(MemorySnapshotRepository.class);
        AgentExecutionLogRepository agentExecutionLogRepository = mock(AgentExecutionLogRepository.class);
        RecoveryCheckpointService recoveryCheckpointService = mock(RecoveryCheckpointService.class);
        TaskRecoveryService taskRecoveryService = mock(TaskRecoveryService.class);

        TaskWorkflowEvent taskCreatedEvent = TaskWorkflowEvent.builder()
                .id(2801L)
                .eventId("evt-task-created-2801")
                .taskId(77L)
                .eventType(WorkflowEventType.TASK_CREATED)
                .sourceUrls("[\"https://www.notion.so/product/ai\",\"https://www.notion.so/pricing\"]")
                .createdAt(LocalDateTime.of(2026, 6, 25, 14, 0))
                .build();
        TaskWorkflowEvent citationDecisionEvent = TaskWorkflowEvent.builder()
                .id(2802L)
                .eventId("evt-citation-decision-2802")
                .taskId(77L)
                .nodeName("citation_check")
                .eventType(WorkflowEventType.ORCHESTRATION_DECISION_RECORDED)
                .payload("""
                        {
                          "summary": "Citation Agent 发现引用缺口，需人工介入",
                          "decisionType": "WAIT_FOR_HUMAN",
                          "evidenceState": "MISSING_SOURCE"
                        }
                        """)
                .sourceUrls("[\"https://www.notion.so/product/ai\"]")
                .createdAt(LocalDateTime.of(2026, 6, 25, 14, 5))
                .build();
        TaskNode citationNode = TaskNode.builder()
                .id(2803L)
                .taskId(77L)
                .nodeName("citation_check")
                .displayName("报告引用核查")
                .agentType(AgentType.CITATION)
                .status(TaskNodeStatus.WAITING_INTERVENTION)
                .outputData("""
                        {
                          "citationRiskSeverity": "ERROR",
                          "citationEvidenceState": "MISSING_SOURCE"
                        }
                        """)
                .build();

        when(taskPlanRepository.findByTaskIdOrderByPlanVersionAsc(77L)).thenReturn(List.of());
        when(taskPlanRepository.findFirstByTaskIdAndActiveTrueOrderByPlanVersionDesc(77L)).thenReturn(Optional.empty());
        when(taskWorkflowEventRepository.findAll()).thenReturn(List.of(taskCreatedEvent, citationDecisionEvent));
        when(taskNodeRepository.findByTaskIdOrderByExecutionOrderAsc(77L)).thenReturn(List.of(citationNode));
        when(taskNodeExecutionAttemptRepository.findAll()).thenReturn(List.of());
        when(memorySnapshotRepository.findByTaskIdOrderByIdDesc(77L)).thenReturn(List.of());
        when(agentExecutionLogRepository.findByTaskIdOrderByCreatedAtAsc(77L)).thenReturn(List.of());
        when(recoveryCheckpointService.listTaskCheckpoints(77L)).thenReturn(List.of());
        when(taskRecoveryService.buildRecoveryAdvice(77L)).thenReturn(TaskRecoveryAdvice.builder()
                .recommendedAction("OBSERVE_ONLY")
                .summary("none")
                .blockingNodeNames(List.of())
                .resumeSupported(false)
                .sourceUrls(List.of())
                .build());

        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        TaskReplayProjectionService service = new TaskReplayProjectionService(
                taskPlanRepository,
                taskWorkflowEventRepository,
                taskNodeRepository,
                taskNodeExecutionAttemptRepository,
                memorySnapshotRepository,
                agentExecutionLogRepository,
                recoveryCheckpointService,
                taskRecoveryService,
                objectMapper
        );

        TaskReplayResponse replayResponse = service.getTaskReplay(77L);

        assertThat(replayResponse.getTimeline())
                .anySatisfy(event -> {
                    assertThat(event.getNodeName()).isEqualTo("citation_check");
                    assertThat(event.getEventType()).isEqualTo("ORCHESTRATION_DECISION_RECORDED");
                    assertThat(event.getSummary()).contains("Citation Agent");
                    assertThat(event.getSourceUrls()).contains("https://www.notion.so/product/ai");
                });
        assertThat(replayResponse.getNodeSummaries()).hasSize(1);
        assertThat(replayResponse.getNodeSummaries().get(0).getNodeName()).isEqualTo("citation_check");
        assertThat(replayResponse.getNodeSummaries().get(0).getSourceUrls())
                .containsExactly("https://www.notion.so/product/ai", "https://www.notion.so/pricing");
    }

    @Test
    void shouldExposePlannedSourceUrlsInNodeSummariesBeforeNodeExecution() {
        TaskPlanRepository taskPlanRepository = mock(TaskPlanRepository.class);
        TaskWorkflowEventRepository taskWorkflowEventRepository = mock(TaskWorkflowEventRepository.class);
        TaskNodeRepository taskNodeRepository = mock(TaskNodeRepository.class);
        TaskNodeExecutionAttemptRepository taskNodeExecutionAttemptRepository = mock(TaskNodeExecutionAttemptRepository.class);
        MemorySnapshotRepository memorySnapshotRepository = mock(MemorySnapshotRepository.class);
        AgentExecutionLogRepository agentExecutionLogRepository = mock(AgentExecutionLogRepository.class);
        RecoveryCheckpointService recoveryCheckpointService = mock(RecoveryCheckpointService.class);
        TaskRecoveryService taskRecoveryService = mock(TaskRecoveryService.class);

        TaskNode pendingExtractor = TaskNode.builder()
                .id(501L)
                .taskId(42L)
                .nodeName("extract_schema")
                .displayName("结构化抽取")
                .agentType(AgentType.EXTRACTOR)
                .status(TaskNodeStatus.PENDING)
                .nodeConfig("""
                        {
                          "sourceUrls":["https://www.notion.so/product/ai"],
                          "competitorUrls":["https://www.notion.so"]
                        }
                        """)
                .build();

        when(taskPlanRepository.findByTaskIdOrderByPlanVersionAsc(42L)).thenReturn(List.of());
        when(taskPlanRepository.findFirstByTaskIdAndActiveTrueOrderByPlanVersionDesc(42L)).thenReturn(Optional.empty());
        when(taskWorkflowEventRepository.findAll()).thenReturn(List.of());
        when(taskNodeRepository.findByTaskIdOrderByExecutionOrderAsc(42L)).thenReturn(List.of(pendingExtractor));
        when(taskNodeExecutionAttemptRepository.findAll()).thenReturn(List.of());
        when(memorySnapshotRepository.findByTaskIdOrderByIdDesc(42L)).thenReturn(List.of());
        when(agentExecutionLogRepository.findByTaskIdOrderByCreatedAtAsc(42L)).thenReturn(List.of());
        when(recoveryCheckpointService.listTaskCheckpoints(42L)).thenReturn(List.of());
        when(taskRecoveryService.buildRecoveryAdvice(42L)).thenReturn(TaskRecoveryAdvice.builder()
                .recommendedAction("OBSERVE_ONLY")
                .summary("none")
                .blockingNodeNames(List.of())
                .resumeSupported(false)
                .sourceUrls(List.of())
                .build());

        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        TaskReplayProjectionService service = new TaskReplayProjectionService(
                taskPlanRepository,
                taskWorkflowEventRepository,
                taskNodeRepository,
                taskNodeExecutionAttemptRepository,
                memorySnapshotRepository,
                agentExecutionLogRepository,
                recoveryCheckpointService,
                taskRecoveryService,
                objectMapper
        );

        TaskReplayResponse replayResponse = service.getTaskReplay(42L);

        assertThat(replayResponse.getNodeSummaries()).hasSize(1);
        assertThat(replayResponse.getNodeSummaries().get(0).getSourceUrls())
                .containsExactly("https://www.notion.so/product/ai", "https://www.notion.so");
        assertThat(replayResponse.getSourceUrls())
                .containsExactly("https://www.notion.so/product/ai", "https://www.notion.so");
    }

    @Test
    void shouldBackfillTaskLevelSourceUrlsToDownstreamSourceConsumerNodeSummaries() {
        TaskPlanRepository taskPlanRepository = mock(TaskPlanRepository.class);
        TaskWorkflowEventRepository taskWorkflowEventRepository = mock(TaskWorkflowEventRepository.class);
        TaskNodeRepository taskNodeRepository = mock(TaskNodeRepository.class);
        TaskNodeExecutionAttemptRepository taskNodeExecutionAttemptRepository = mock(TaskNodeExecutionAttemptRepository.class);
        MemorySnapshotRepository memorySnapshotRepository = mock(MemorySnapshotRepository.class);
        AgentExecutionLogRepository agentExecutionLogRepository = mock(AgentExecutionLogRepository.class);
        RecoveryCheckpointService recoveryCheckpointService = mock(RecoveryCheckpointService.class);
        TaskRecoveryService taskRecoveryService = mock(TaskRecoveryService.class);

        TaskWorkflowEvent taskCreatedEvent = TaskWorkflowEvent.builder()
                .id(1801L)
                .eventId("evt-task-created-1801")
                .taskId(42L)
                .eventType(WorkflowEventType.TASK_CREATED)
                .sourceUrls("[\"https://www.notion.so/product/ai\",\"https://www.notion.so\"]")
                .createdAt(LocalDateTime.of(2026, 6, 24, 17, 0))
                .build();
        TaskNode pendingExtractor = TaskNode.builder()
                .id(502L)
                .taskId(42L)
                .nodeName("extract_schema")
                .displayName("结构化抽取")
                .agentType(AgentType.EXTRACTOR)
                .status(TaskNodeStatus.PENDING)
                .build();

        when(taskPlanRepository.findByTaskIdOrderByPlanVersionAsc(42L)).thenReturn(List.of());
        when(taskPlanRepository.findFirstByTaskIdAndActiveTrueOrderByPlanVersionDesc(42L)).thenReturn(Optional.empty());
        when(taskWorkflowEventRepository.findAll()).thenReturn(List.of(taskCreatedEvent));
        when(taskNodeRepository.findByTaskIdOrderByExecutionOrderAsc(42L)).thenReturn(List.of(pendingExtractor));
        when(taskNodeExecutionAttemptRepository.findAll()).thenReturn(List.of());
        when(memorySnapshotRepository.findByTaskIdOrderByIdDesc(42L)).thenReturn(List.of());
        when(agentExecutionLogRepository.findByTaskIdOrderByCreatedAtAsc(42L)).thenReturn(List.of());
        when(recoveryCheckpointService.listTaskCheckpoints(42L)).thenReturn(List.of());
        when(taskRecoveryService.buildRecoveryAdvice(42L)).thenReturn(TaskRecoveryAdvice.builder()
                .recommendedAction("OBSERVE_ONLY")
                .summary("none")
                .blockingNodeNames(List.of())
                .resumeSupported(false)
                .sourceUrls(List.of())
                .build());

        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        TaskReplayProjectionService service = new TaskReplayProjectionService(
                taskPlanRepository,
                taskWorkflowEventRepository,
                taskNodeRepository,
                taskNodeExecutionAttemptRepository,
                memorySnapshotRepository,
                agentExecutionLogRepository,
                recoveryCheckpointService,
                taskRecoveryService,
                objectMapper
        );

        TaskReplayResponse replayResponse = service.getTaskReplay(42L);

        assertThat(replayResponse.getNodeSummaries()).hasSize(1);
        assertThat(replayResponse.getNodeSummaries().get(0).getSourceUrls())
                .containsExactly("https://www.notion.so/product/ai", "https://www.notion.so");
    }

    @Test
    void shouldExposeSearchReplaySnapshotFromCollectorAuditOutput() {
        TaskPlanRepository taskPlanRepository = mock(TaskPlanRepository.class);
        TaskWorkflowEventRepository taskWorkflowEventRepository = mock(TaskWorkflowEventRepository.class);
        TaskNodeRepository taskNodeRepository = mock(TaskNodeRepository.class);
        TaskNodeExecutionAttemptRepository taskNodeExecutionAttemptRepository = mock(TaskNodeExecutionAttemptRepository.class);
        MemorySnapshotRepository memorySnapshotRepository = mock(MemorySnapshotRepository.class);
        AgentExecutionLogRepository agentExecutionLogRepository = mock(AgentExecutionLogRepository.class);
        RecoveryCheckpointService recoveryCheckpointService = mock(RecoveryCheckpointService.class);
        TaskRecoveryService taskRecoveryService = mock(TaskRecoveryService.class);

        when(taskPlanRepository.findByTaskIdOrderByPlanVersionAsc(42L)).thenReturn(List.of());
        when(taskPlanRepository.findFirstByTaskIdAndActiveTrueOrderByPlanVersionDesc(42L)).thenReturn(Optional.empty());
        when(taskWorkflowEventRepository.findAll()).thenReturn(List.of());
        when(taskNodeRepository.findByTaskIdOrderByExecutionOrderAsc(42L)).thenReturn(List.of(TaskNode.builder()
                .id(1L)
                .taskId(42L)
                .nodeName("collect_sources_docs")
                .displayName("collect_sources_docs")
                .agentType(AgentType.COLLECTOR)
                .status(TaskNodeStatus.SUCCESS)
                .planVersionId(31L)
                .branchKey("root")
                .outputData("""
                        {
                          "searchAudit":{
                            "executionTrace":{"traceVersion":"v1","recoveryCheckpoint":"SELECT_TARGETS"},
                            "selectedTargets":[{"candidate":{"url":"https://docs.notion.so/reference"}}],
                            "sourceUrls":["https://docs.notion.so/reference"]
                          },
                          "collectionAudit":{
                            "summary":{
                              "totalPackages":1,
                              "successCount":1,
                              "status":"SUCCESS",
                              "recoveryCheckpoint":"collect_sources_docs#001",
                              "sourceUrls":["https://docs.notion.so/reference/raw"]
                            },
                            "status":"SUCCESS",
                            "replayTimeline":[{"taskPackageKey":"collect_sources_docs#001","targetIndex":1,"status":"SUCCESS","executorType":"WEB_PAGE","sourceUrls":["https://docs.notion.so/reference/raw"]}],
                            "recoveryCheckpoint":"collect_sources_docs#001",
                            "sourceUrls":["https://docs.notion.so/reference/raw"]
                          },
                          "selectedTargets":[{"url":"https://docs.notion.so/reference","title":"Reference"}],
                          "sourceUrls":["https://docs.notion.so/reference"]
                        }
                        """)
                .build()));
        when(taskNodeExecutionAttemptRepository.findAll()).thenReturn(List.of());
        when(memorySnapshotRepository.findByTaskIdOrderByIdDesc(42L)).thenReturn(List.of());
        when(agentExecutionLogRepository.findByTaskIdOrderByCreatedAtAsc(42L)).thenReturn(List.of());
        when(recoveryCheckpointService.listTaskCheckpoints(42L)).thenReturn(List.of());
        when(taskRecoveryService.buildRecoveryAdvice(42L)).thenReturn(TaskRecoveryAdvice.builder()
                .recommendedAction("OBSERVE_ONLY")
                .summary("none")
                .blockingNodeNames(List.of())
                .resumeSupported(false)
                .sourceUrls(List.of())
                .build());

        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        TaskReplayProjectionService service = new TaskReplayProjectionService(
                taskPlanRepository,
                taskWorkflowEventRepository,
                taskNodeRepository,
                taskNodeExecutionAttemptRepository,
                memorySnapshotRepository,
                agentExecutionLogRepository,
                recoveryCheckpointService,
                taskRecoveryService,
                objectMapper
        );

        TaskReplayResponse response = service.getTaskReplay(42L);

        assertTrue(objectMapper.valueToTree(response).has("searchReplays"));
        assertTrue(objectMapper.valueToTree(response).has("collectionReplays"));
        assertEquals("collect_sources_docs", objectMapper.valueToTree(response).at("/searchReplays/0/nodeName").asText());
        assertEquals("SELECT_TARGETS",
                objectMapper.valueToTree(response).at("/searchReplays/0/searchAudit/executionTrace/recoveryCheckpoint").asText());
        assertEquals(1,
                objectMapper.valueToTree(response).at("/searchReplays/0/searchAuditSummary/selectedCount").asInt());
        assertEquals("collect_sources_docs",
                objectMapper.valueToTree(response).at("/collectionReplays/0/nodeName").asText());
        assertEquals("SUCCESS",
                objectMapper.valueToTree(response).at("/collectionReplays/0/collectionAuditSummary/status").asText());
        assertEquals("https://docs.notion.so/reference/raw",
                objectMapper.valueToTree(response).at("/sourceUrls/1").asText());
    }

    @Test
    void shouldExposeSearchReplayTimelineAndDiscardedCandidates() {
        TaskPlanRepository taskPlanRepository = mock(TaskPlanRepository.class);
        TaskWorkflowEventRepository taskWorkflowEventRepository = mock(TaskWorkflowEventRepository.class);
        TaskNodeRepository taskNodeRepository = mock(TaskNodeRepository.class);
        TaskNodeExecutionAttemptRepository taskNodeExecutionAttemptRepository = mock(TaskNodeExecutionAttemptRepository.class);
        MemorySnapshotRepository memorySnapshotRepository = mock(MemorySnapshotRepository.class);
        AgentExecutionLogRepository agentExecutionLogRepository = mock(AgentExecutionLogRepository.class);
        RecoveryCheckpointService recoveryCheckpointService = mock(RecoveryCheckpointService.class);
        TaskRecoveryService taskRecoveryService = mock(TaskRecoveryService.class);

        TaskNode collectNode = TaskNode.builder()
                .id(1L)
                .taskId(42L)
                .nodeName("collect_sources_docs")
                .displayName("collect_sources_docs")
                .agentType(AgentType.COLLECTOR)
                .status(TaskNodeStatus.SUCCESS)
                .planVersionId(31L)
                .branchKey("root")
                .outputData("""
                        {
                          "searchAudit":{
                            "executionTrace":{"recoveryCheckpoint":"SELECT_TARGETS"},
                            "attemptedTargets":[{"candidate":{"url":"https://docs.example.com/reference"}}],
                            "discardedCandidates":[{"url":"https://www.example.com/login","selectionReason":"LOW_SIGNAL_UTILITY_PAGE"}],
                            "replayTimeline":[{"stepCode":"SELECT_TARGETS","status":"SUCCESS","sourceUrls":["https://docs.example.com/reference"]}],
                            "sourceUrls":["https://docs.example.com/reference"]
                          },
                          "sourceUrls":["https://docs.example.com/reference"]
                        }
                        """)
                .build();

        when(taskPlanRepository.findByTaskIdOrderByPlanVersionAsc(42L)).thenReturn(List.of());
        when(taskPlanRepository.findFirstByTaskIdAndActiveTrueOrderByPlanVersionDesc(42L)).thenReturn(Optional.empty());
        when(taskWorkflowEventRepository.findAll()).thenReturn(List.of());
        when(taskNodeRepository.findByTaskIdOrderByExecutionOrderAsc(42L)).thenReturn(List.of(collectNode));
        when(taskNodeExecutionAttemptRepository.findAll()).thenReturn(List.of());
        when(memorySnapshotRepository.findByTaskIdOrderByIdDesc(42L)).thenReturn(List.of());
        when(agentExecutionLogRepository.findByTaskIdOrderByCreatedAtAsc(42L)).thenReturn(List.of());
        when(recoveryCheckpointService.listTaskCheckpoints(42L)).thenReturn(List.of());
        when(taskRecoveryService.buildRecoveryAdvice(42L)).thenReturn(TaskRecoveryAdvice.builder()
                .recommendedAction("OBSERVE_ONLY")
                .summary("none")
                .blockingNodeNames(List.of())
                .resumeSupported(false)
                .sourceUrls(List.of())
                .build());

        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        TaskReplayProjectionService service = new TaskReplayProjectionService(
                taskPlanRepository,
                taskWorkflowEventRepository,
                taskNodeRepository,
                taskNodeExecutionAttemptRepository,
                memorySnapshotRepository,
                agentExecutionLogRepository,
                recoveryCheckpointService,
                taskRecoveryService,
                objectMapper
        );

        TaskReplayResponse response = service.getTaskReplay(42L);
        JsonNode payload = objectMapper.valueToTree(response);

        assertThat(payload.at("/searchReplays/0/searchAudit/attemptedTargets")).hasSize(1);
        assertThat(payload.at("/searchReplays/0/searchAudit/discardedCandidates")).hasSize(1);
        assertThat(payload.at("/searchReplays/0/attemptedTargets")).hasSize(1);
        assertThat(payload.at("/searchReplays/0/discardedCandidates")).hasSize(1);
        assertThat(payload.at("/searchReplays/0/timeline/0/stepCode").asText()).isEqualTo("SELECT_TARGETS");
    }

    @Test
    void shouldExposeCollectionAuditSourceUrlsAndFastFailSignalsForRssReplay() {
        TaskPlanRepository taskPlanRepository = mock(TaskPlanRepository.class);
        TaskWorkflowEventRepository taskWorkflowEventRepository = mock(TaskWorkflowEventRepository.class);
        TaskNodeRepository taskNodeRepository = mock(TaskNodeRepository.class);
        TaskNodeExecutionAttemptRepository taskNodeExecutionAttemptRepository = mock(TaskNodeExecutionAttemptRepository.class);
        MemorySnapshotRepository memorySnapshotRepository = mock(MemorySnapshotRepository.class);
        AgentExecutionLogRepository agentExecutionLogRepository = mock(AgentExecutionLogRepository.class);
        RecoveryCheckpointService recoveryCheckpointService = mock(RecoveryCheckpointService.class);
        TaskRecoveryService taskRecoveryService = mock(TaskRecoveryService.class);

        when(taskPlanRepository.findByTaskIdOrderByPlanVersionAsc(84L)).thenReturn(List.of());
        when(taskPlanRepository.findFirstByTaskIdAndActiveTrueOrderByPlanVersionDesc(84L)).thenReturn(Optional.empty());
        when(taskWorkflowEventRepository.findAll()).thenReturn(List.of());
        when(taskNodeRepository.findByTaskIdOrderByExecutionOrderAsc(84L)).thenReturn(List.of(TaskNode.builder()
                .id(11L)
                .taskId(84L)
                .nodeName("collect_sources_news")
                .displayName("collect_sources_news")
                .agentType(AgentType.COLLECTOR)
                .status(TaskNodeStatus.FAILED)
                .planVersionId(51L)
                .branchKey("root")
                .outputData("""
                        {
                          "collectionStatus":"FAILED",
                          "sourceUrls":["https://blog.example.com/feed.xml","https://blog.example.com/agent-launch"],
                          "collectionAudit":{
                            "summary":{
                              "totalPackages":1,
                              "successCount":0,
                              "failedCount":1,
                              "reusedCount":0,
                              "status":"FAILED",
                              "recoveryCheckpoint":"collect_sources_news#001",
                              "sourceUrls":["https://blog.example.com/feed.xml","https://blog.example.com/agent-launch"]
                            },
                            "status":"FAILED",
                            "results":[
                              {
                                "taskPackageKey":"collect_sources_news#001",
                                "targetIndex":1,
                                "executorType":"API_DATA",
                                "success":false,
                                "status":"FAILED",
                                "resourceLocator":"rss://feed/YWNtZQ",
                                "failureKind":"RUNTIME_FAILURE",
                                "qualitySignals":["TOOL_UNAVAILABLE_FAST_FAIL"],
                                "sourceUrls":["https://blog.example.com/feed.xml","https://blog.example.com/agent-launch"]
                              }
                            ],
                            "replayTimeline":[
                              {
                                "taskPackageKey":"collect_sources_news#001",
                                "targetIndex":1,
                                "status":"FAILED",
                                "executorType":"API_DATA",
                                "resourceLocator":"rss://feed/YWNtZQ",
                                "errorMessage":"rss disabled",
                                "sourceUrls":["https://blog.example.com/feed.xml","https://blog.example.com/agent-launch"]
                              }
                            ],
                            "recoveryCheckpoint":"collect_sources_news#001",
                            "sourceUrls":["https://blog.example.com/feed.xml","https://blog.example.com/agent-launch"]
                          }
                        }
                        """)
                .build()));
        when(taskNodeExecutionAttemptRepository.findAll()).thenReturn(List.of());
        when(memorySnapshotRepository.findByTaskIdOrderByIdDesc(84L)).thenReturn(List.of());
        when(agentExecutionLogRepository.findByTaskIdOrderByCreatedAtAsc(84L)).thenReturn(List.of());
        when(recoveryCheckpointService.listTaskCheckpoints(84L)).thenReturn(List.of());
        when(taskRecoveryService.buildRecoveryAdvice(84L)).thenReturn(TaskRecoveryAdvice.builder()
                .recommendedAction("OBSERVE_ONLY")
                .summary("none")
                .blockingNodeNames(List.of())
                .resumeSupported(false)
                .sourceUrls(List.of())
                .build());

        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        TaskReplayProjectionService service = new TaskReplayProjectionService(
                taskPlanRepository,
                taskWorkflowEventRepository,
                taskNodeRepository,
                taskNodeExecutionAttemptRepository,
                memorySnapshotRepository,
                agentExecutionLogRepository,
                recoveryCheckpointService,
                taskRecoveryService,
                objectMapper
        );

        TaskReplayResponse response = service.getTaskReplay(84L);
        JsonNode payload = objectMapper.valueToTree(response);

        assertThat(payload.at("/collectionReplays/0/collectionAudit/sourceUrls")).hasSize(2);
        assertThat(payload.at("/collectionReplays/0/sourceUrls")).hasSize(2);
        assertThat(payload.at("/collectionReplays/0/collectionAudit/results/0/qualitySignals/0").asText())
                .isEqualTo("TOOL_UNAVAILABLE_FAST_FAIL");
    }

    @Test
    void shouldProjectMainReplayChainAndReserveConversationAndExportEntryPoints() {
        TaskPlanRepository taskPlanRepository = mock(TaskPlanRepository.class);
        TaskWorkflowEventRepository taskWorkflowEventRepository = mock(TaskWorkflowEventRepository.class);
        TaskNodeRepository taskNodeRepository = mock(TaskNodeRepository.class);
        TaskNodeExecutionAttemptRepository taskNodeExecutionAttemptRepository = mock(TaskNodeExecutionAttemptRepository.class);
        MemorySnapshotRepository memorySnapshotRepository = mock(MemorySnapshotRepository.class);
        AgentExecutionLogRepository agentExecutionLogRepository = mock(AgentExecutionLogRepository.class);
        AnalysisTaskRepository analysisTaskRepository = mock(AnalysisTaskRepository.class);
        RecoveryCheckpointService recoveryCheckpointService = mock(RecoveryCheckpointService.class);
        AnalysisTaskRunner taskRunner = mock(AnalysisTaskRunner.class);
        TaskSnapshotCacheService taskSnapshotCacheService = mock(TaskSnapshotCacheService.class);
        WorkflowEventOutboxService workflowEventOutboxService = mock(WorkflowEventOutboxService.class);
        DynamicTaskGraphService dynamicTaskGraphService = mock(DynamicTaskGraphService.class);

        LocalDateTime baseTime = LocalDateTime.of(2026, 6, 8, 16, 0, 0);
        AnalysisTask task = AnalysisTask.builder()
                .id(42L)
                .status(AnalysisTaskStatus.STOPPED)
                .currentPlanVersionId(12L)
                .currentPlanVersion(3)
                .build();
        TaskPlan activePlan = TaskPlan.builder()
                .id(12L)
                .taskId(42L)
                .planVersion(3)
                .parentPlanId(8L)
                .branchKey("root/review-3")
                .triggerNodeName("quality_check")
                .planType("DYNAMIC_BACKFLOW")
                .active(true)
                .planSnapshot("{\"nodes\":[]}")
                .createdAt(baseTime.minusMinutes(15))
                .build();
        TaskWorkflowEvent workflowEvent = TaskWorkflowEvent.builder()
                .id(101L)
                .eventId("evt-101")
                .taskId(42L)
                .nodeName("collect_sources")
                .planVersionId(12L)
                .branchKey("root/review-3")
                .eventType(WorkflowEventType.NODE_COMPLETED)
                .deliveryStatus(TaskWorkflowEvent.STATUS_CONSUMED)
                .topic("task.replay")
                .tag("node_completed")
                .payload("{\"summary\":\"采集节点已完成\"}")
                .sourceUrls("[\"https://example.com/event\"]")
                .createdAt(baseTime.minusMinutes(10))
                .updatedAt(baseTime.minusMinutes(10))
                .build();
        TaskNode collectNode = TaskNode.builder()
                .id(201L)
                .taskId(42L)
                .nodeName("collect_sources")
                .displayName("信息采集")
                .agentType(AgentType.COLLECTOR)
                .status(TaskNodeStatus.SUCCESS)
                .controlState(TaskNodeControlState.NONE)
                .executionOrder(0)
                .planVersionId(12L)
                .branchKey("root/review-3")
                .build();
        TaskNode reviewNode = TaskNode.builder()
                .id(202L)
                .taskId(42L)
                .nodeName("quality_check")
                .displayName("质量复核")
                .agentType(AgentType.REVIEWER)
                .status(TaskNodeStatus.WAITING_INTERVENTION)
                .controlState(TaskNodeControlState.NONE)
                .failureCategory(NodeFailureCategory.MANUAL_INTERVENTION_REQUIRED)
                .errorMessage("需要人工判断证据冲突")
                .executionOrder(1)
                .planVersionId(12L)
                .branchKey("root/review-3")
                .build();
        TaskNodeExecutionAttempt reviewAttempt = TaskNodeExecutionAttempt.builder()
                .id(301L)
                .taskId(42L)
                .nodeId(202L)
                .nodeName("quality_check")
                .attemptNo(2)
                .idempotencyKey("attempt-202-2")
                .resultStatus(TaskNodeStatus.WAITING_INTERVENTION)
                .failureCategory(NodeFailureCategory.MANUAL_INTERVENTION_REQUIRED)
                .errorSummary("证据存在冲突，需要人工确认")
                .createdAt(baseTime.minusMinutes(5))
                .build();
        MemorySnapshot memorySnapshot = MemorySnapshot.builder()
                .id(401L)
                .taskId(42L)
                .planVersionId(12L)
                .branchKey("root/review-3")
                .nodeName("quality_check")
                .snapshotType("TASK_RAG")
                .memoryLayer("SHORT_TERM")
                .summary("已定位到互相矛盾的证据")
                .gapSummary("仍缺少官方定价页面")
                .sourceUrls(List.of("https://example.com/memory"))
                .issueFlags(List.of("pricing-gap"))
                .versionSource("PLAN_12")
                .invalidationScope("MANUAL_REVIEW")
                .invalidationReason("NOT_EVALUATED")
                .contextPayload("{\"summary\":\"需要补证\"}")
                .createdAt(baseTime.minusMinutes(4))
                .updatedAt(baseTime.minusMinutes(4))
                .build();
        AgentExecutionLog executionLog = AgentExecutionLog.builder()
                .id(501L)
                .taskId(42L)
                .nodeId(202L)
                .agentType(AgentType.REVIEWER)
                .agentName("quality-reviewer")
                .status(TaskNodeStatus.WAITING_INTERVENTION)
                .reasoningSummary("建议人工确认后再决定是否继续收集")
                .inputData("{\"node\":\"quality_check\"}")
                .outputData("{\"decision\":\"manual_intervention\"}")
                .promptUsed("prompt")
                .durationMs(1200L)
                .tokenUsage("{\"total\":120}")
                .needsHumanIntervention(true)
                .createdAt(baseTime.minusMinutes(3))
                .build();
        RecoveryCheckpointResponse checkpoint = RecoveryCheckpointResponse.builder()
                .id(601L)
                .taskId(42L)
                .planVersionId(12L)
                .planVersion(3)
                .checkpointKey("checkpoint-collect-1")
                .checkpointType("NODE_SUCCESS")
                .nodeName("collect_sources")
                .summary("可从信息采集完成后继续")
                .payloadSnapshot("{\"resumeFrom\":\"collect_sources\"}")
                .createdAt(baseTime.minusMinutes(2))
                .sourceUrls(List.of("https://example.com/checkpoint"))
                .build();

        when(analysisTaskRepository.findById(42L)).thenReturn(Optional.of(task));
        when(taskPlanRepository.findByTaskIdOrderByPlanVersionAsc(42L)).thenReturn(List.of(activePlan));
        when(taskPlanRepository.findFirstByTaskIdAndActiveTrueOrderByPlanVersionDesc(42L)).thenReturn(Optional.of(activePlan));
        when(taskPlanRepository.findById(12L)).thenReturn(Optional.of(activePlan));
        when(taskWorkflowEventRepository.findAll()).thenReturn(List.of(workflowEvent));
        when(taskNodeRepository.findByTaskIdOrderByExecutionOrderAsc(42L)).thenReturn(List.of(collectNode, reviewNode));
        when(taskNodeExecutionAttemptRepository.findAll()).thenReturn(List.of(reviewAttempt));
        when(taskNodeExecutionAttemptRepository.findByTaskIdAndNodeIdOrderByAttemptNoAsc(42L, 202L)).thenReturn(List.of(reviewAttempt));
        when(memorySnapshotRepository.findByTaskIdOrderByIdDesc(42L)).thenReturn(List.of(memorySnapshot));
        when(agentExecutionLogRepository.findByTaskIdOrderByCreatedAtAsc(42L)).thenReturn(List.of(executionLog));
        when(recoveryCheckpointService.listTaskCheckpoints(42L)).thenReturn(List.of(checkpoint));
        when(dynamicTaskGraphService.calculateAffectedNodes(List.of(collectNode, reviewNode), reviewNode))
                .thenReturn(List.of(reviewNode));

        TaskRecoveryService taskRecoveryService = new TaskRecoveryService(
                analysisTaskRepository,
                taskNodeRepository,
                taskRunner,
                taskSnapshotCacheService,
                workflowEventOutboxService,
                new RecoveryEngine(),
                dynamicTaskGraphService,
                taskPlanRepository,
                taskNodeExecutionAttemptRepository,
                taskWorkflowEventRepository,
                recoveryCheckpointService,
                new TaskQuotaCoordinator(mock(OrganizationQuotaPolicy.class), new ObjectMapper()));

        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        TaskReplayProjectionService projectionService = new TaskReplayProjectionService(
                taskPlanRepository,
                taskWorkflowEventRepository,
                taskNodeRepository,
                taskNodeExecutionAttemptRepository,
                memorySnapshotRepository,
                agentExecutionLogRepository,
                recoveryCheckpointService,
                taskRecoveryService,
                objectMapper);

        TaskReplayResponse replayResponse = projectionService.getTaskReplay(42L);
        JsonNode replayPayload = objectMapper.valueToTree(replayResponse);

        // 主链路断言用于锁定当前任务 / 节点 / 审计回放不会在后续扩展接入点时退化。
        assertThat(replayResponse.getTimeline()).hasSize(4);
        assertThat(replayResponse.getNodeSummaries()).hasSize(2);
        assertThat(replayResponse.getRecoveryAdvice()).isNotNull();
        assertThat(replayResponse.getPlanVersions()).hasSize(1);
        assertThat(replayResponse.getSourceUrls())
                .containsExactly("https://example.com/event", "https://example.com/memory", "https://example.com/checkpoint");

        // 这里显式要求存在稳定接入点，后续 Task 5.7 / 5.9 只需补充内容，不再改动主响应边界。
        assertThat(replayPayload.has("integrationEntryPoints")).isTrue();
        assertThat(replayPayload.path("integrationEntryPoints")).hasSize(2);
        assertThat(replayPayload.at("/integrationEntryPoints/0/entryKey").asText()).isEqualTo("CONVERSATION_ACTION_REPLAY");
        assertThat(replayPayload.at("/integrationEntryPoints/0/readinessStatus").asText()).isEqualTo("RESERVED_FOR_TASK_5_9");
        assertThat(replayPayload.at("/integrationEntryPoints/1/entryKey").asText()).isEqualTo("REPORT_EXPORT_REPLAY");
        assertThat(replayPayload.at("/integrationEntryPoints/1/readinessStatus").asText()).isEqualTo("RESERVED_FOR_TASK_5_7");
    }
}
