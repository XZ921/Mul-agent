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
