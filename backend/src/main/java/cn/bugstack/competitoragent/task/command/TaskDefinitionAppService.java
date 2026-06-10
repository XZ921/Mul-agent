package cn.bugstack.competitoragent.task.command;

import cn.bugstack.competitoragent.common.BusinessException;
import cn.bugstack.competitoragent.common.ResultCode;
import cn.bugstack.competitoragent.event.TaskEventPublisher;
import cn.bugstack.competitoragent.governance.GovernanceBlockException;
import cn.bugstack.competitoragent.governance.GovernanceDefaults;
import cn.bugstack.competitoragent.governance.OrganizationQuotaPolicy;
import cn.bugstack.competitoragent.governance.QuotaDecision;
import cn.bugstack.competitoragent.model.dto.CreateTaskRequest;
import cn.bugstack.competitoragent.model.dto.TaskNodeResponse;
import cn.bugstack.competitoragent.model.dto.TaskResponse;
import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.enums.AnalysisTaskStatus;
import cn.bugstack.competitoragent.repository.AnalysisTaskRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import cn.bugstack.competitoragent.task.TaskProgressSnapshot;
import cn.bugstack.competitoragent.task.TaskQuotaCoordinator;
import cn.bugstack.competitoragent.task.TaskSnapshotCacheService;
import cn.bugstack.competitoragent.task.application.cleanup.TaskArtifactCleanupCoordinator;
import cn.bugstack.competitoragent.task.assembler.TaskNodeViewAssembler;
import cn.bugstack.competitoragent.workflow.NodeExecutionRecoveryPolicy;
import cn.bugstack.competitoragent.workflow.WorkflowFactory;
import cn.bugstack.competitoragent.workflow.WorkflowPlan;
import cn.bugstack.competitoragent.workflow.event.WorkflowEventPublisher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 任务定义命令应用服务。
 * <p>
 * 这一层负责“创建任务 / 预览工作流 / 删除任务”这类偏任务定义期的命令，
 * 避免 AnalysisTaskService 同时承载查询、定义和运行时控制三类职责。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskDefinitionAppService {

    private final AnalysisTaskRepository taskRepository;
    private final TaskNodeRepository nodeRepository;
    private final WorkflowFactory workflowFactory;
    private final TaskSnapshotCacheService taskSnapshotCacheService;
    private final TaskEventPublisher taskEventPublisher;
    private final WorkflowEventPublisher workflowEventPublisher;
    private final TaskNodeViewAssembler assembler;
    private final ObjectMapper objectMapper;
    private final OrganizationQuotaPolicy organizationQuotaPolicy;
    private final TaskArtifactCleanupCoordinator taskArtifactCleanupCoordinator;
    private final TaskQuotaCoordinator taskQuotaCoordinator;

    @Transactional
    public TaskResponse createTask(CreateTaskRequest request) {
        ensureTaskCreationAllowed(request);

        /*
         * 创建阶段只固化任务输入与初始 DAG，
         * 不在这里直接触发执行，保证“创建”和“运行”两个命令边界清晰。
         */
        AnalysisTask task = AnalysisTask.builder()
                .taskName(request.getTaskName())
                .subjectProduct(request.getSubjectProduct())
                .competitorNames(toJson(request.getCompetitorNames()))
                .competitorUrls(toJson(request.getCompetitorUrls()))
                .analysisDimensions(toJson(request.getAnalysisDimensions()))
                .sourceScope(toJson(request.getSourceScope()))
                .reportLanguage(defaultIfBlank(request.getReportLanguage(), "中文"))
                .reportTemplate(defaultIfBlank(request.getReportTemplate(), "标准模板"))
                .schemaId(request.getSchemaId())
                .status(AnalysisTaskStatus.PENDING)
                .build();
        taskQuotaCoordinator.markTaskQuotaReserved(task);

        task = taskRepository.save(task);
        workflowFactory.createWorkflow(task);
        task = taskRepository.save(task);
        refreshTaskSnapshot(task.getId());
        workflowEventPublisher.publishTaskCreated(task);

        log.info("create analysis task success, taskId={}, taskName={}", task.getId(), task.getTaskName());
        return toTaskResponse(task);
    }

    public List<TaskNodeResponse> previewWorkflow(CreateTaskRequest request) {
        AnalysisTask draftTask = AnalysisTask.builder()
                .taskName(request.getTaskName())
                .subjectProduct(request.getSubjectProduct())
                .competitorNames(toJson(request.getCompetitorNames()))
                .competitorUrls(toJson(request.getCompetitorUrls()))
                .analysisDimensions(toJson(request.getAnalysisDimensions()))
                .sourceScope(toJson(request.getSourceScope()))
                .reportLanguage(defaultIfBlank(request.getReportLanguage(), "中文"))
                .reportTemplate(defaultIfBlank(request.getReportTemplate(), "标准模板"))
                .schemaId(request.getSchemaId())
                .build();

        WorkflowPlan previewPlan = workflowFactory.buildPreviewPlan(draftTask);
        return previewPlan.getNodes().stream()
                .map(assembler::toPreviewNodeResponse)
                .toList();
    }

    @Transactional
    public void deleteTask(Long taskId) {
        AnalysisTask task = getTaskOrThrow(taskId);
        if (task.getStatus() == AnalysisTaskStatus.RUNNING) {
            throw new BusinessException(ResultCode.TASK_DELETE_FAILED, "Running task cannot be deleted");
        }

        taskQuotaCoordinator.releaseTaskQuotaIfHeld(task);
        taskArtifactCleanupCoordinator.cleanupTaskArtifacts(taskId);
        nodeRepository.deleteAll(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId));
        taskRepository.delete(task);
        taskSnapshotCacheService.evictTaskRuntime(taskId);
        log.info("delete task success, taskId={}", taskId);
    }

    private void ensureTaskCreationAllowed(CreateTaskRequest request) {
        QuotaDecision decision = organizationQuotaPolicy.checkAndReserve(
                GovernanceDefaults.DEFAULT_ORGANIZATION_KEY,
                GovernanceDefaults.TASK_SCOPE,
                GovernanceDefaults.TASK_CONCURRENCY_KEY,
                1,
                request == null ? List.of() : request.getCompetitorUrls()
        );
        if (decision != null && !decision.isAllowed()) {
            throw new GovernanceBlockException(decision);
        }
    }

    /**
     * 创建命令完成后立即刷新任务快照，
     * 这样列表、详情和事件流在任务刚落库时就能看到统一的初始状态。
     */
    private void refreshTaskSnapshot(Long taskId) {
        taskRepository.findById(taskId).ifPresent(task -> {
            List<TaskNode> nodes = nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId);
            NodeExecutionRecoveryPolicy.TaskExecutionResolution resolution =
                    recoveryPolicy().resolveTaskExecution(task, nodes);
            TaskProgressSnapshot snapshot = TaskProgressSnapshot.fromTask(
                    task,
                    resolution.getStatus(),
                    resolution.getErrorMessage(),
                    nodes);
            taskSnapshotCacheService.saveTaskSnapshot(snapshot);
            taskEventPublisher.publishTaskSnapshot(snapshot);
        });
    }

    private TaskResponse toTaskResponse(AnalysisTask task) {
        List<TaskNode> nodes = nodeRepository.findByTaskIdOrderByExecutionOrderAsc(task.getId());
        return assembler.toTaskResponse(task, nodes);
    }

    private AnalysisTask getTaskOrThrow(Long taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(ResultCode.TASK_NOT_FOUND, "taskId=" + taskId));
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("serialize json failed", e);
            return null;
        }
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private NodeExecutionRecoveryPolicy recoveryPolicy() {
        return new NodeExecutionRecoveryPolicy(objectMapper);
    }
}
