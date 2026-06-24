package cn.bugstack.competitoragent.workflow.runtime;

import cn.bugstack.competitoragent.event.TaskEventPublisher;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.repository.AnalysisTaskRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import cn.bugstack.competitoragent.task.TaskProgressSnapshot;
import cn.bugstack.competitoragent.task.TaskSnapshotCacheService;
import cn.bugstack.competitoragent.workflow.NodeExecutionRecoveryPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 运行时快照刷新协作者。
 * <p>
 * DagExecutor 在多个状态流转点都要重复执行“读取任务 -> 拉取最新节点 -> 生成快照 -> 写入缓存 -> 发布快照事件”，
 * 这里把这段固定流程抽出，保证运行态快照刷新语义在一个地方统一维护。
 */
@Component
@RequiredArgsConstructor
public class RuntimeStateRefresher {

    private final AnalysisTaskRepository taskRepository;
    private final TaskNodeRepository nodeRepository;
    private final TaskSnapshotCacheService taskSnapshotCacheService;
    private final TaskEventPublisher taskEventPublisher;
    private final NodeExecutionRecoveryPolicy recoveryPolicy = new NodeExecutionRecoveryPolicy();

    /**
     * 根据数据库中的最新任务与节点状态刷新运行时快照。
     */
    public void refreshRuntimeSnapshot(Long taskId) {
        taskRepository.findById(taskId).ifPresent(task -> {
            List<TaskNode> latestNodes = nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId);
            NodeExecutionRecoveryPolicy.TaskExecutionResolution resolution =
                    recoveryPolicy.resolveTaskExecution(task, latestNodes);
            TaskProgressSnapshot snapshot = TaskProgressSnapshot.fromTask(
                    task,
                    // 运行时快照必须以节点权威状态归约任务状态，避免异步链路中任务主表短暂滞后导致 PENDING 覆盖 RUNNING。
                    resolution.getStatus(),
                    resolution.getErrorMessage(),
                    latestNodes);
            taskSnapshotCacheService.saveTaskSnapshot(snapshot);
            taskEventPublisher.publishTaskSnapshot(snapshot);
        });
    }
}
