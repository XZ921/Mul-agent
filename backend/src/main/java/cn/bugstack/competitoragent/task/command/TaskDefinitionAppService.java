package cn.bugstack.competitoragent.task.command;

import cn.bugstack.competitoragent.common.BusinessException;
import cn.bugstack.competitoragent.common.ResultCode;
import cn.bugstack.competitoragent.event.TaskEventPublisher;
import cn.bugstack.competitoragent.governance.GovernanceBlockException;
import cn.bugstack.competitoragent.governance.GovernanceDefaults;
import cn.bugstack.competitoragent.governance.OrganizationQuotaPolicy;
import cn.bugstack.competitoragent.governance.QuotaDecision;
import cn.bugstack.competitoragent.model.dto.CreateTaskRequest;
import cn.bugstack.competitoragent.model.dto.TaskPlanPreviewResponse;
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
import cn.bugstack.competitoragent.task.assembler.TaskPlanPreviewAssembler;
import cn.bugstack.competitoragent.task.definition.TaskDefinition;
import cn.bugstack.competitoragent.task.definition.TaskDefinitionMapper;
import cn.bugstack.competitoragent.task.definition.TaskDefinitionValidator;
import cn.bugstack.competitoragent.task.definition.TaskDraft;
import cn.bugstack.competitoragent.workflow.NodeExecutionRecoveryPolicy;
import cn.bugstack.competitoragent.workflow.WorkflowFactory;
import cn.bugstack.competitoragent.workflow.WorkflowPlan;
import cn.bugstack.competitoragent.workflow.event.WorkflowEventPublisher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 任务定义命令应用服务。
 * 这一层负责“创建任务 / 预览工作流 / 删除任务”这类偏任务定义期的命令，
 * 避免 AnalysisTaskService 同时承载查询、定义和运行时控制三类职责。
 */
@Slf4j
@Service
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
    private final TaskDefinitionMapper taskDefinitionMapper;
    private final TaskDefinitionValidator taskDefinitionValidator;
    private final TaskPlanPreviewAssembler taskPlanPreviewAssembler;

    /**
     * Spring 正式装配路径会显式注入任务定义相关协作者，
     * 让 create / preview 两条链路统一经过 mapper + validator + preview assembler。
     */
    @Autowired
    public TaskDefinitionAppService(AnalysisTaskRepository taskRepository,
                                    TaskNodeRepository nodeRepository,
                                    WorkflowFactory workflowFactory,
                                    TaskSnapshotCacheService taskSnapshotCacheService,
                                    TaskEventPublisher taskEventPublisher,
                                    WorkflowEventPublisher workflowEventPublisher,
                                    TaskNodeViewAssembler assembler,
                                    ObjectMapper objectMapper,
                                    OrganizationQuotaPolicy organizationQuotaPolicy,
                                    TaskArtifactCleanupCoordinator taskArtifactCleanupCoordinator,
                                    TaskQuotaCoordinator taskQuotaCoordinator,
                                    TaskDefinitionMapper taskDefinitionMapper,
                                    TaskDefinitionValidator taskDefinitionValidator,
                                    TaskPlanPreviewAssembler taskPlanPreviewAssembler) {
        this.taskRepository = taskRepository;
        this.nodeRepository = nodeRepository;
        this.workflowFactory = workflowFactory;
        this.taskSnapshotCacheService = taskSnapshotCacheService;
        this.taskEventPublisher = taskEventPublisher;
        this.workflowEventPublisher = workflowEventPublisher;
        this.assembler = assembler;
        this.objectMapper = objectMapper;
        this.organizationQuotaPolicy = organizationQuotaPolicy;
        this.taskArtifactCleanupCoordinator = taskArtifactCleanupCoordinator;
        this.taskQuotaCoordinator = taskQuotaCoordinator;
        this.taskDefinitionMapper = taskDefinitionMapper;
        this.taskDefinitionValidator = taskDefinitionValidator;
        this.taskPlanPreviewAssembler = taskPlanPreviewAssembler;
    }

    /**
     * 兼容当前测试与旧构造路径。
     * 这里用 ObjectMapper 派生默认 mapper / assembler，避免因为新增协作者导致大量测试先被构造器噪声打断。
     */
    public TaskDefinitionAppService(AnalysisTaskRepository taskRepository,
                                    TaskNodeRepository nodeRepository,
                                    WorkflowFactory workflowFactory,
                                    TaskSnapshotCacheService taskSnapshotCacheService,
                                    TaskEventPublisher taskEventPublisher,
                                    WorkflowEventPublisher workflowEventPublisher,
                                    TaskNodeViewAssembler assembler,
                                    ObjectMapper objectMapper,
                                    OrganizationQuotaPolicy organizationQuotaPolicy,
                                    TaskArtifactCleanupCoordinator taskArtifactCleanupCoordinator,
                                    TaskQuotaCoordinator taskQuotaCoordinator) {
        this(
                taskRepository,
                nodeRepository,
                workflowFactory,
                taskSnapshotCacheService,
                taskEventPublisher,
                workflowEventPublisher,
                assembler,
                objectMapper,
                organizationQuotaPolicy,
                taskArtifactCleanupCoordinator,
                taskQuotaCoordinator,
                new TaskDefinitionMapper(objectMapper),
                new TaskDefinitionValidator(),
                new TaskPlanPreviewAssembler(objectMapper)
        );
    }

    @Transactional
    public TaskResponse createTask(CreateTaskRequest request) {
        ensureTaskCreationAllowed(request);

        TaskDraft draft = taskDefinitionMapper.toDraft(request);
        TaskDefinition definition = taskDefinitionMapper.toDefinition(draft);
        taskDefinitionValidator.validate(definition);

        /*
         * 创建阶段只固化任务输入与初始 DAG，
         * 不在这里直接触发执行，保证“创建”和“运行”两个命令边界清晰。
         */
        AnalysisTask task = toAnalysisTask(definition);
        taskQuotaCoordinator.markTaskQuotaReserved(task);

        task = taskRepository.save(task);
        workflowFactory.createWorkflow(task);
        task = taskRepository.save(task);
        refreshTaskSnapshot(task.getId());
        workflowEventPublisher.publishTaskCreated(task);

        log.info("create analysis task success, taskId={}, taskName={}", task.getId(), task.getTaskName());
        return toTaskResponse(task);
    }

    /**
     * 预览链路不再复用运行态节点 DTO，
     * 而是统一输出正式的 TASK_PLAN_PREVIEW_V1 合同，明确区分“计划值”和“运行值”。
     */
    public TaskPlanPreviewResponse previewWorkflow(CreateTaskRequest request) {
        TaskDraft draft = taskDefinitionMapper.toDraft(request);
        TaskDefinition definition = taskDefinitionMapper.toDefinition(draft);
        taskDefinitionValidator.validate(definition);

        WorkflowPlan previewPlan = workflowFactory.buildPreviewPlan(toAnalysisTask(definition));
        return taskPlanPreviewAssembler.toPreviewResponse(definition, previewPlan);
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
     * 任务定义层到持久化层的投影目前仍复用 AnalysisTask 作为落库载体，
     * 但字段来源统一收口到 TaskDefinition，避免后续继续从 request 上各自取值。
     */
    private AnalysisTask toAnalysisTask(TaskDefinition definition) {
        return AnalysisTask.builder()
                .taskName(definition.getTaskName())
                .subjectProduct(definition.getSubjectProduct())
                .competitorNames(toJson(definition.getCompetitors().stream()
                        .map(TaskDefinition.CompetitorDefinition::getCompetitorName)
                        .toList()))
                .competitorUrls(toJson(definition.getCompetitors().stream()
                        .map(TaskDefinition.CompetitorDefinition::getOfficialUrl)
                        .filter(url -> url != null && !url.isBlank())
                        .toList()))
                .analysisDimensions(toJson(definition.getAnalysisDimensions()))
                .sourceScope(toJson(definition.getSourceScope()))
                .reportLanguage(defaultIfBlank(definition.getReportLanguage(), "中文"))
                .reportTemplate(defaultIfBlank(definition.getReportTemplate(), "标准版"))
                .schemaId(definition.getSchemaId())
                .status(AnalysisTaskStatus.PENDING)
                .build();
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
