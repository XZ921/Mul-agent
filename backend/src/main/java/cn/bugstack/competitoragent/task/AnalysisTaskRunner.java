package cn.bugstack.competitoragent.task;

import cn.bugstack.competitoragent.agent.AgentContext;
import cn.bugstack.competitoragent.common.BusinessException;
import cn.bugstack.competitoragent.common.ResultCode;
import cn.bugstack.competitoragent.event.TaskEventPublisher;
import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import cn.bugstack.competitoragent.model.enums.AnalysisTaskStatus;
import cn.bugstack.competitoragent.repository.AnalysisTaskRepository;
import cn.bugstack.competitoragent.workflow.DagExecutor;
import cn.bugstack.competitoragent.workflow.event.WorkflowEvent;
import cn.bugstack.competitoragent.workflow.event.WorkflowEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class AnalysisTaskRunner {

    private final AnalysisTaskRepository taskRepository;
    private final DagExecutor dagExecutor;
    private final TaskExecutionLockService taskExecutionLockService;
    private final TaskEventPublisher taskEventPublisher;
    private final WorkflowEventPublisher workflowEventPublisher;

    /**
     * Phase 4 开始，Runner 不再直接承担“拿到任务后马上同步推进完整 DAG”这条主链路。
     * 它的新职责是：
     * 1. 做一次任务级去重保护
     * 2. 把执行请求转换成正式的内部工作流事件
     * 3. 交给 MQ 消费侧接管后续编排
     */
    @Async
    public void runTask(Long taskId) {
        log.info("start async workflow dispatch, taskId={}", taskId);
        publishTaskStatusSafely(taskId, AnalysisTaskStatus.PENDING, "任务已进入异步编排队列", null);
        log.info("task status event published before workflow dispatch, taskId={}", taskId);

        String executionOwner = "task-runner-" + taskId + "-" + UUID.randomUUID();
        if (!taskExecutionLockService.tryAcquireTaskExecutionLock(taskId, executionOwner, Duration.ofMinutes(30))) {
            publishTaskStatusSafely(taskId, AnalysisTaskStatus.RUNNING, "检测到重复执行请求，已沿用现有编排链路", null);
            log.info("skip duplicated workflow dispatch because task lock is already held, taskId={}", taskId);
            return;
        }
        log.info("task execution lock acquired, taskId={}, owner={}", taskId, executionOwner);

        AnalysisTask task = loadTaskWithRetry(taskId);
        log.info("task loaded for workflow dispatch, taskId={}, currentPlanVersionId={}",
                taskId, task.getCurrentPlanVersionId());

        try {
            workflowEventPublisher.publishTaskExecutionRequested(task, buildContext(task));
            log.info("task execution requested event staged, taskId={}", taskId);
            publishTaskStatusSafely(taskId, AnalysisTaskStatus.PENDING, "任务已发起到异步编排通道", null);
        } catch (Exception e) {
            markTaskDispatchFailed(task, e);
            throw e;
        } finally {
            taskExecutionLockService.releaseTaskExecutionLock(taskId, executionOwner);
        }
    }

    /**
     * 这是 RocketMQ 消费侧接手后的正式编排入口。
     * 责任边界被明确拆开后，后续节点异步化、恢复、补偿和 DLQ 语义都可以继续沿用同一条事件主链路扩展。
     */
    public void consumeTaskExecutionRequested(WorkflowEvent workflowEvent) {
        if (workflowEvent == null || workflowEvent.getTaskId() == null) {
            throw new IllegalArgumentException("workflowEvent.taskId is required");
        }

        Long taskId = workflowEvent.getTaskId();
        String executionOwner = "workflow-consumer-" + taskId + "-" + UUID.randomUUID();
        if (!taskExecutionLockService.tryAcquireTaskExecutionLock(taskId, executionOwner, Duration.ofMinutes(30))) {
            log.info("skip workflow event execution because task lock is already held, taskId={}, eventId={}",
                    taskId, workflowEvent.getEventId());
            return;
        }

        try {
            AnalysisTask task = loadTaskWithRetry(taskId);
            publishTaskStatusSafely(taskId, AnalysisTaskStatus.PENDING, "编排消费侧已接管任务执行", null);
            dagExecutor.execute(taskId, buildContext(task));
        } finally {
            taskExecutionLockService.releaseTaskExecutionLock(taskId, executionOwner);
        }
    }

    /**
     * 这里只携带 DAG 运行必需字段，避免执行器直接依赖持久化实体。
     */
    private AgentContext buildContext(AnalysisTask task) {
        return AgentContext.builder()
                .taskId(task.getId())
                .taskName(task.getTaskName())
                .subjectProduct(task.getSubjectProduct())
                .competitorNames(task.getCompetitorNames())
                .competitorUrls(task.getCompetitorUrls())
                .analysisDimensions(task.getAnalysisDimensions())
                .sourceScope(task.getSourceScope())
                .reportLanguage(task.getReportLanguage())
                .reportTemplate(task.getReportTemplate())
                .build();
    }

    /**
     * 异步编排入口和事务提交之间可能存在极短的可见性窗口。
     * 这里做有限次重试，避免刚发起的编排线程因为“任务尚未对当前读取事务可见”而误判成任务不存在。
     */
    private AnalysisTask loadTaskWithRetry(Long taskId) {
        int maxAttempts = 10;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Optional<AnalysisTask> taskOptional = taskRepository.findById(taskId);
            if (taskOptional.isPresent()) {
                return taskOptional.get();
            }
            if (attempt == maxAttempts) {
                break;
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("task load interrupted, taskId=" + taskId, e);
            }
        }
        throw new BusinessException(ResultCode.TASK_NOT_FOUND, "taskId=" + taskId);
    }

    private void markTaskDispatchFailed(AnalysisTask task, Exception e) {
        if (task == null || task.getId() == null) {
            return;
        }
        String errorMessage = e.getMessage() == null || e.getMessage().isBlank()
                ? "workflow dispatch failed"
                : e.getMessage();
        task.setStatus(AnalysisTaskStatus.FAILED);
        task.setCompletedAt(LocalDateTime.now());
        task.setErrorMessage(errorMessage);
        taskRepository.save(task);
        publishTaskStatusSafely(task.getId(), AnalysisTaskStatus.FAILED, "异步编排发起失败", errorMessage);
    }

    /**
     * 任务状态事件属于前端观察侧链路，
     * 即使推送失败也不能反向阻断正式的异步编排主流程。
     */
    private void publishTaskStatusSafely(Long taskId,
                                         AnalysisTaskStatus status,
                                         String currentStage,
                                         String errorMessage) {
        try {
            taskEventPublisher.publishTaskStatusEvent(taskId, status, currentStage, errorMessage);
        } catch (Exception e) {
            log.warn("publish task status event failed, taskId={}, status={}", taskId, status, e);
        }
    }
}
