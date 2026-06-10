package cn.bugstack.competitoragent.task.application;

import cn.bugstack.competitoragent.model.dto.TaskListPageResponse;
import cn.bugstack.competitoragent.model.dto.TaskNodeResponse;
import cn.bugstack.competitoragent.model.dto.TaskResponse;

import java.util.List;

/**
 * 任务查询门面。
 * <p>
 * 这一层统一暴露任务列表、详情与节点视图查询，
 * 让上层只依赖稳定只读接口，而不是直接耦合具体查询应用服务。
 */
public interface TaskQueryFacade {

    TaskListPageResponse listTasks(String status, int pageNum, int pageSize);

    TaskResponse getTask(Long taskId);

    List<TaskNodeResponse> getTaskNodes(Long taskId);
}
