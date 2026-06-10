package cn.bugstack.competitoragent.task.application;

import cn.bugstack.competitoragent.model.dto.UpdateNodeConfigRequest;
import cn.bugstack.competitoragent.task.command.TaskRuntimeCommandAppService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 任务运行时门面默认实现。
 * <p>
 * phase3a 第一阶段先保持“门面包裹既有应用服务”的最小落地，
 * 先稳定调用入口，再继续推进后续 cleanup 协调与模块边界收口。
 */
@Service
@RequiredArgsConstructor
public class TaskRuntimeFacadeImpl implements TaskRuntimeFacade {

    private final TaskRuntimeCommandAppService taskRuntimeCommandAppService;

    @Override
    public void executeTask(Long taskId) {
        taskRuntimeCommandAppService.executeTask(taskId);
    }

    @Override
    public void retryTask(Long taskId) {
        taskRuntimeCommandAppService.retryTask(taskId);
    }

    @Override
    public void resumeTask(Long taskId) {
        taskRuntimeCommandAppService.resumeTask(taskId);
    }

    @Override
    public void rerunFromNode(Long taskId, String nodeName) {
        taskRuntimeCommandAppService.rerunFromNode(taskId, nodeName);
    }

    @Override
    public void updateNodeConfigAndRerun(Long taskId, String nodeName, UpdateNodeConfigRequest request) {
        taskRuntimeCommandAppService.updateNodeConfigAndRerun(taskId, nodeName, request);
    }

    @Override
    public void pauseNode(Long taskId, String nodeName) {
        taskRuntimeCommandAppService.pauseNode(taskId, nodeName);
    }

    @Override
    public void resumeNode(Long taskId, String nodeName) {
        taskRuntimeCommandAppService.resumeNode(taskId, nodeName);
    }

    @Override
    public void skipNode(Long taskId, String nodeName) {
        taskRuntimeCommandAppService.skipNode(taskId, nodeName);
    }

    @Override
    public void terminateNode(Long taskId, String nodeName) {
        taskRuntimeCommandAppService.terminateNode(taskId, nodeName);
    }

    @Override
    public void stopTask(Long taskId) {
        taskRuntimeCommandAppService.stopTask(taskId);
    }
}
