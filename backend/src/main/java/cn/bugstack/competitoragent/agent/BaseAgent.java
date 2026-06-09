package cn.bugstack.competitoragent.agent;

import cn.bugstack.competitoragent.common.TraceIdHolder;
import cn.bugstack.competitoragent.context.AgentContextAssembler;
import cn.bugstack.competitoragent.llm.ModelInvocationContextHolder;
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
    protected final AgentContextAssembler agentContextAssembler;

    protected BaseAgent(AgentExecutionLogRepository logRepository) {
        this(logRepository, null);
    }

    protected BaseAgent(AgentExecutionLogRepository logRepository,
                        AgentContextAssembler agentContextAssembler) {
        this.logRepository = logRepository;
        this.agentContextAssembler = agentContextAssembler;
    }

    @Override
    public AgentResult execute(AgentContext context) {
        long startTime = System.currentTimeMillis();
        try {
            String initialTraceId = context != null && context.getTraceId() != null ? context.getTraceId() : TraceIdHolder.get();
            if (context != null) {
                ModelInvocationContextHolder.set(context.getTaskId(), context.getCurrentNodeName(), initialTraceId);
            }

            AgentContext effectiveContext = enrichContext(context);
            String traceId = effectiveContext.getTraceId() != null ? effectiveContext.getTraceId() : TraceIdHolder.get();
            ModelInvocationContextHolder.set(effectiveContext.getTaskId(), effectiveContext.getCurrentNodeName(), traceId);

            log.info("[{}] 开始执行, taskId={}, traceId={}", getName(), effectiveContext.getTaskId(), traceId);

            AgentResult result;
            try {
                result = doExecute(effectiveContext);
                result.setDurationMs(System.currentTimeMillis() - startTime);
                log.info("[{}] 执行完成, status={}, durationMs={}",
                        getName(), result.getStatus(), result.getDurationMs());
            } catch (Exception e) {
                long duration = System.currentTimeMillis() - startTime;
                log.error("[{}] 执行异常, taskId={}, traceId={}, durationMs={}",
                        getName(), effectiveContext.getTaskId(), traceId, duration, e);
                result = AgentResult.failed(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                result.setDurationMs(duration);
            }

            // 执行日志单独兜底，避免日志写库失败反过来影响主业务流程。
            saveExecutionLog(effectiveContext, result, traceId);
            return result;
        } finally {
            ModelInvocationContextHolder.clear();
        }
    }

    /**
     * 所有 Agent 都通过同一个入口装配任务级上下文。
     * 这样可以保证检索摘要、来源 URL 和缺口说明在各节点看到的是同一份数据。
     * 如果装配过程失败，则回退到原始上下文，避免辅助链路阻塞主流程。
     */
    protected AgentContext enrichContext(AgentContext context) {
        if (context == null || agentContextAssembler == null) {
            return context;
        }
        try {
            AgentContext assembledContext = agentContextAssembler.assemble(context);
            // 装配器属于增强链路，返回空值时也必须回落到原始上下文，避免打断主流程与既有测试。
            return assembledContext != null ? assembledContext : context;
        } catch (Exception e) {
            log.warn("[{}] 统一上下文装配失败，继续使用原始上下文", getName(), e);
            return context;
        }
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
