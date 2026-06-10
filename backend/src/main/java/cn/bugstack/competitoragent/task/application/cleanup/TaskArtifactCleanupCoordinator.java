package cn.bugstack.competitoragent.task.application.cleanup;

/**
 * 任务附属数据清理协调器。
 * <p>
 * task-orchestration 用例只依赖这一层，
 * 具体由哪些模块参与清理、按什么顺序执行，都由 coordinator 统一编排。
 */
public interface TaskArtifactCleanupCoordinator {

    void cleanupTaskArtifacts(Long taskId);

    void cleanupNodeArtifacts(Long taskId, String nodeName);
}
