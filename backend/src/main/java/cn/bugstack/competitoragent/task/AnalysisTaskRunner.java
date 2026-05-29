package cn.bugstack.competitoragent.task;

import cn.bugstack.competitoragent.agent.AgentContext;
import cn.bugstack.competitoragent.common.BusinessException;
import cn.bugstack.competitoragent.common.ResultCode;
import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import cn.bugstack.competitoragent.repository.AnalysisTaskRepository;
import cn.bugstack.competitoragent.workflow.DagExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AnalysisTaskRunner {

    private final AnalysisTaskRepository taskRepository;
    private final DagExecutor dagExecutor;

    @Async
    public void runTask(Long taskId) {
        // 任务执行走异步线程，避免前端创建任务时被整条 DAG 阻塞。
        log.info("开始异步执行任务, taskId={}", taskId);
        AnalysisTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(ResultCode.TASK_NOT_FOUND, "taskId=" + taskId));

        // Runner 负责把数据库任务实体转换成运行态上下文，执行细节交给 DagExecutor。
        dagExecutor.execute(taskId, buildContext(task));
    }

    // 这里只携带 DAG 运行必需字段，避免执行器直接依赖持久化实体。
    private AgentContext buildContext(AnalysisTask task) {
        return AgentContext.builder()
                .taskId(task.getId())
                .taskName(task.getTaskName())
                .subjectProduct(task.getSubjectProduct())
                .competitorNames(task.getCompetitorNames())
                .competitorUrls(task.getCompetitorUrls())
                .analysisDimensions(task.getAnalysisDimensions())
                .sourceScope(task.getSourceScope())
                .reportLanguage(task.getReportLanguage())
                .reportTemplate(task.getReportTemplate())
                .build();
    }
}
