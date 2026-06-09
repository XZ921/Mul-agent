package cn.bugstack.competitoragent.controller;

import cn.bugstack.competitoragent.model.dto.RecoveryCheckpointResponse;
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
import cn.bugstack.competitoragent.task.AnalysisTaskRunner;
import cn.bugstack.competitoragent.task.RecoveryCheckpointService;
import cn.bugstack.competitoragent.task.TaskRecoveryService;
import cn.bugstack.competitoragent.task.TaskReplayProjectionService;
import cn.bugstack.competitoragent.task.TaskSnapshotCacheService;
import cn.bugstack.competitoragent.workflow.DynamicTaskGraphService;
import cn.bugstack.competitoragent.workflow.NodeFailureCategory;
import cn.bugstack.competitoragent.workflow.RecoveryEngine;
import cn.bugstack.competitoragent.workflow.event.WorkflowEventOutboxService;
import cn.bugstack.competitoragent.workflow.event.WorkflowEventType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 5.6.c 回放接口黑盒契约测试。
 * <p>
 * 该测试通过“真实统一投影服务 + 真实恢复服务 + 仓储 mock”的方式，
 * 验证恢复窗口边界、占位释放规则和失败恢复建议都能以结构化对象返回。
 */
class TaskReplayControllerTest {

    @Test
    void shouldExposeSingleReplayApiWithTimelineNodeSummaryAndRecoveryAdvice() throws Exception {
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

        LocalDateTime baseTime = LocalDateTime.of(2026, 6, 8, 14, 0, 0);
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
                .errorSummary("证据存在冲突，需人工确认")
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
                recoveryCheckpointService);

        TaskReplayProjectionService projectionService = new TaskReplayProjectionService(
                taskPlanRepository,
                taskWorkflowEventRepository,
                taskNodeRepository,
                taskNodeExecutionAttemptRepository,
                memorySnapshotRepository,
                agentExecutionLogRepository,
                recoveryCheckpointService,
                taskRecoveryService,
                new ObjectMapper().registerModule(new JavaTimeModule()));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new TaskReplayController(projectionService)).build();

        mockMvc.perform(get("/api/task/42/replay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.taskId").value(42))
                .andExpect(jsonPath("$.data.currentPlanVersionId").value(12))
                .andExpect(jsonPath("$.data.timeline[0].eventType").value("NODE_COMPLETED"))
                .andExpect(jsonPath("$.data.nodeSummaries[1].nodeName").value("quality_check"))
                .andExpect(jsonPath("$.data.nodeSummaries[1].latestAttemptNo").value(2))
                .andExpect(jsonPath("$.data.recoveryAdvice.recommendedAction").value("MANUAL_INTERVENTION"))
                .andExpect(jsonPath("$.data.recoveryAdvice.blockingNodeNames[0]").value("quality_check"))
                .andExpect(jsonPath("$.data.recoveryAdvice.recoveryWindow.windowScope").value("ACTIVE_PLAN_BRANCH"))
                .andExpect(jsonPath("$.data.recoveryAdvice.recoveryWindow.planVersionId").value(12))
                .andExpect(jsonPath("$.data.recoveryAdvice.recoveryWindow.boundaryNodeNames[0]").value("quality_check"))
                .andExpect(jsonPath("$.data.recoveryAdvice.recoveryWindow.replayableEventIds[0]").value("evt-101"))
                .andExpect(jsonPath("$.data.recoveryAdvice.releasePolicy.releaseTaskExecutionLock").value(true))
                .andExpect(jsonPath("$.data.recoveryAdvice.releasePolicy.releaseNodeExecutionLocks").value(true))
                .andExpect(jsonPath("$.data.recoveryAdvice.auditTrail.decisionSource").value("RECOVERY_ENGINE"))
                .andExpect(jsonPath("$.data.recoveryAdvice.auditTrail.triggerEventId").value("evt-101"))
                .andExpect(jsonPath("$.data.recoveryAdvice.auditTrail.latestAttemptId").value(301))
                .andExpect(jsonPath("$.data.recoveryCheckpoints[0].checkpointKey").value("checkpoint-collect-1"))
                .andExpect(jsonPath("$.data.planVersions[0].planVersion").value(3))
                .andExpect(jsonPath("$.data.sourceUrls[0]").value("https://example.com/event"));
    }
}
