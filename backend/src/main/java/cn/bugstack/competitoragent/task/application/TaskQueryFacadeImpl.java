package cn.bugstack.competitoragent.task.application;

import cn.bugstack.competitoragent.model.dto.TaskListPageResponse;
import cn.bugstack.competitoragent.model.dto.TaskNodeResponse;
import cn.bugstack.competitoragent.model.dto.TaskResponse;
import cn.bugstack.competitoragent.task.query.TaskQueryAppService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 任务查询门面默认实现。
 * <p>
 * phase3a 先通过这一层把查询入口固定下来，
 * 后续即使查询应用服务内部继续拆分，上层依赖关系也不用再次扩散。
 */
@Service
@RequiredArgsConstructor
public class TaskQueryFacadeImpl implements TaskQueryFacade {

    private final TaskQueryAppService taskQueryAppService;

    @Override
    public TaskListPageResponse listTasks(String status, int pageNum, int pageSize) {
        return taskQueryAppService.listTasks(status, pageNum, pageSize);
    }

    @Override
    public TaskResponse getTask(Long taskId) {
        return taskQueryAppService.getTask(taskId);
    }

    @Override
    public List<TaskNodeResponse> getTaskNodes(Long taskId) {
        return taskQueryAppService.getTaskNodes(taskId);
    }
}
