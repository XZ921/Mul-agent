package cn.bugstack.competitoragent.workflow.runtime;

import cn.bugstack.competitoragent.event.TaskEventPublisher;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.repository.AnalysisTaskRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import cn.bugstack.competitoragent.task.TaskProgressSnapshot;
import cn.bugstack.competitoragent.task.TaskSnapshotCacheService;
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

    /**
     * 根据数据库中的最新任务与节点状态刷新运行时快照。
     */
    public void refreshRuntimeSnapshot(Long taskId) {
        taskRepository.findById(taskId).ifPresent(task -> {
            List<TaskNode> latestNodes = nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId);
            TaskProgressSnapshot snapshot = TaskProgressSnapshot.fromTask(
                    task,
                    task.getStatus(),
                    task.getErrorMessage(),
                    latestNodes);
            taskSnapshotCacheService.saveTaskSnapshot(snapshot);
            taskEventPublisher.publishTaskSnapshot(snapshot);
        });
    }
}
