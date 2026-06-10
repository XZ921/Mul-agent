package cn.bugstack.competitoragent.task.application;

import cn.bugstack.competitoragent.model.dto.UpdateNodeConfigRequest;

/**
 * 任务运行时门面。
 * <p>
 * 这一层对外暴露稳定的运行时写入口，
 * 让门面层和跨模块调用方只依赖固定接口，而不是直接绑定具体命令应用服务实现。
 */
public interface TaskRuntimeFacade {

    void executeTask(Long taskId);

    void retryTask(Long taskId);

    void resumeTask(Long taskId);

    void rerunFromNode(Long taskId, String nodeName);

    void updateNodeConfigAndRerun(Long taskId, String nodeName, UpdateNodeConfigRequest request);

    void pauseNode(Long taskId, String nodeName);

    void resumeNode(Long taskId, String nodeName);

    void skipNode(Long taskId, String nodeName);

    void terminateNode(Long taskId, String nodeName);

    void stopTask(Long taskId);
}
