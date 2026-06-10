package cn.bugstack.competitoragent.task.application.cleanup;

import cn.bugstack.competitoragent.task.TaskArtifactCleanupService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * legacy task 清理适配端口。
 * <p>
 * phase3a 先保留既有集中清理实现，
 * 通过端口适配的方式纳入 coordinator，后续再逐步拆给各业务模块。
 */
@Service
@RequiredArgsConstructor
public class LegacyTaskArtifactCleanupPort implements TaskArtifactCleanupPort {

    private final TaskArtifactCleanupService taskArtifactCleanupService;

    @Override
    public String moduleName() {
        return "legacy-task-artifact-cleanup";
    }

    @Override
    public void cleanupTaskArtifacts(Long taskId) {
        taskArtifactCleanupService.cleanupTaskArtifacts(taskId);
    }

    @Override
    public void cleanupNodeArtifacts(Long taskId, String nodeName) {
        taskArtifactCleanupService.cleanupNodeArtifacts(taskId, nodeName);
    }
}
