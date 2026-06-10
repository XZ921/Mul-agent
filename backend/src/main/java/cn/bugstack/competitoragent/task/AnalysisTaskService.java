package cn.bugstack.competitoragent.task;

import cn.bugstack.competitoragent.model.dto.CreateTaskRequest;
import cn.bugstack.competitoragent.model.dto.TaskListPageResponse;
import cn.bugstack.competitoragent.model.dto.TaskNodeResponse;
import cn.bugstack.competitoragent.model.dto.TaskResponse;
import cn.bugstack.competitoragent.model.dto.UpdateNodeConfigRequest;
import cn.bugstack.competitoragent.task.application.TaskQueryFacade;
import cn.bugstack.competitoragent.task.application.TaskRuntimeFacade;
import cn.bugstack.competitoragent.task.command.TaskDefinitionAppService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 任务应用门面。
 * <p>
 * 这里专门给 controller 和现有调用方提供稳定入口，
 * 真实职责按查询、定义期命令、运行时命令拆分到专门的应用服务中。
 * 这样门面层不会再次承载业务细节，也能避免像“重复配额预留”这类副作用在多层重复执行。
 */
@Service
@RequiredArgsConstructor
public class AnalysisTaskService {

    private final TaskQueryFacade taskQueryFacade;
    private final TaskRuntimeFacade taskRuntimeFacade;
    private final TaskDefinitionAppService taskDefinitionAppService;

    @Transactional
    public TaskResponse createTask(CreateTaskRequest request) {
        return taskDefinitionAppService.createTask(request);
    }

    public TaskListPageResponse listTasks(String status, int pageNum, int pageSize) {
        return taskQueryFacade.listTasks(status, pageNum, pageSize);
    }

    public TaskResponse getTask(Long taskId) {
        return taskQueryFacade.getTask(taskId);
    }

    public List<TaskNodeResponse> getTaskNodes(Long taskId) {
        return taskQueryFacade.getTaskNodes(taskId);
    }

    public List<TaskNodeResponse> previewWorkflow(CreateTaskRequest request) {
        return taskDefinitionAppService.previewWorkflow(request);
    }

    @Transactional
    public void executeTask(Long taskId) {
        taskRuntimeFacade.executeTask(taskId);
    }

    @Transactional
    public void retryTask(Long taskId) {
        taskRuntimeFacade.retryTask(taskId);
    }

    @Transactional
    public void rerunFromNode(Long taskId, String nodeName) {
        taskRuntimeFacade.rerunFromNode(taskId, nodeName);
    }

    @Transactional
    public void resumeTask(Long taskId) {
        taskRuntimeFacade.resumeTask(taskId);
    }

    @Transactional
    public void updateNodeConfigAndRerun(Long taskId, String nodeName, UpdateNodeConfigRequest request) {
        taskRuntimeFacade.updateNodeConfigAndRerun(taskId, nodeName, request);
    }

    @Transactional
    public void pauseNode(Long taskId, String nodeName) {
        taskRuntimeFacade.pauseNode(taskId, nodeName);
    }

    @Transactional
    public void resumeNode(Long taskId, String nodeName) {
        taskRuntimeFacade.resumeNode(taskId, nodeName);
    }

    @Transactional
    public void skipNode(Long taskId, String nodeName) {
        taskRuntimeFacade.skipNode(taskId, nodeName);
    }

    @Transactional
    public void terminateNode(Long taskId, String nodeName) {
        taskRuntimeFacade.terminateNode(taskId, nodeName);
    }

    @Transactional
    public void stopTask(Long taskId) {
        taskRuntimeFacade.stopTask(taskId);
    }

    @Transactional
    public void deleteTask(Long taskId) {
        taskDefinitionAppService.deleteTask(taskId);
    }
}
