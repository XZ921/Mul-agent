package cn.bugstack.competitoragent.task.application.cleanup;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 任务附属数据清理协调器默认实现。
 * <p>
 * 这里严格按注册顺序同步执行全部 cleanup port，
 * 任一端口抛错都直接上抛，让外层 task 用例在同一事务里整体回滚。
 */
@Service
@RequiredArgsConstructor
public class TaskArtifactCleanupCoordinatorImpl implements TaskArtifactCleanupCoordinator {

    private final List<TaskArtifactCleanupPort> cleanupPorts;

    @Override
    public void cleanupTaskArtifacts(Long taskId) {
        for (TaskArtifactCleanupPort cleanupPort : cleanupPorts) {
            cleanupPort.cleanupTaskArtifacts(taskId);
        }
    }

    @Override
    public void cleanupNodeArtifacts(Long taskId, String nodeName) {
        for (TaskArtifactCleanupPort cleanupPort : cleanupPorts) {
            cleanupPort.cleanupNodeArtifacts(taskId, nodeName);
        }
    }
}
