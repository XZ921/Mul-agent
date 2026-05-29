package cn.bugstack.competitoragent.agent;

import cn.bugstack.competitoragent.common.TraceIdHolder;
import cn.bugstack.competitoragent.model.entity.AgentExecutionLog;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.repository.AgentExecutionLogRepository;
import lombok.extern.slf4j.Slf4j;

/**
 * Agent 抽象基类。
 * 提供统一的异常兜底、耗时统计与执行日志持久化，子类只需专注业务逻辑本身。
 */
@Slf4j
public abstract class BaseAgent implements Agent {

    protected final AgentExecutionLogRepository logRepository;

    protected BaseAgent(AgentExecutionLogRepository logRepository) {
        this.logRepository = logRepository;
    }

    @Override
    public AgentResult execute(AgentContext context) {
        long startTime = System.currentTimeMillis();
        String traceId = context.getTraceId() != null ? context.getTraceId() : TraceIdHolder.get();

        log.info("[{}] 开始执行, taskId={}, traceId={}", getName(), context.getTaskId(), traceId);

        AgentResult result;
        try {
            result = doExecute(context);
            result.setDurationMs(System.currentTimeMillis() - startTime);
            log.info("[{}] 执行完成, status={}, durationMs={}",
                    getName(), result.getStatus(), result.getDurationMs());
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("[{}] 执行异常, taskId={}, traceId={}, durationMs={}",
                    getName(), context.getTaskId(), traceId, duration, e);
            result = AgentResult.failed(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            result.setDurationMs(duration);
        }

        // 执行日志单独兜底，避免日志写库失败反过来影响主业务流程。
        saveExecutionLog(context, result, traceId);
        return result;
    }

    /**
     * 子类实现自己的核心业务逻辑。
     */
    protected abstract AgentResult doExecute(AgentContext context);

    /**
     * 保存 Agent 执行日志，支撑节点级可观测性和人工排查。
     */
    private void saveExecutionLog(AgentContext context, AgentResult result, String traceId) {
        try {
            AgentExecutionLog logEntry = AgentExecutionLog.builder()
                    .taskId(context.getTaskId())
                    .agentType(getType())
                    .agentName(getName())
                    .inputData(buildInputSummary(context))
                    .outputData(result.getOutputData())
                    .status(result.getStatus())
                    .modelName(result.getModelName())
                    .promptUsed(result.getPromptUsed())
                    .durationMs(result.getDurationMs())
                    .tokenUsage(result.getTokenUsage())
                    .errorMessage(result.getErrorMessage())
                    .traceId(traceId)
                    .reasoningSummary(result.getReasoningSummary())
                    .needsHumanIntervention(result.getStatus() == TaskNodeStatus.FAILED)
                    .build();
            logRepository.save(logEntry);
        } catch (Exception e) {
            log.error("[{}] 保存执行日志失败", getName(), e);
        }
    }

    /**
     * 构建输入摘要，默认只记录任务和节点级关键信息，避免日志膨胀。
     */
    protected String buildInputSummary(AgentContext context) {
        return String.format("{\"taskId\":%d,\"taskName\":\"%s\",\"nodeName\":\"%s\"}",
                context.getTaskId(), context.getTaskName(), context.getCurrentNodeName());
    }
}
