package cn.bugstack.competitoragent.log;

import cn.bugstack.competitoragent.common.BusinessException;
import cn.bugstack.competitoragent.common.ResultCode;
import cn.bugstack.competitoragent.model.dto.AgentLogResponse;
import cn.bugstack.competitoragent.model.entity.AgentExecutionLog;
import cn.bugstack.competitoragent.repository.AgentExecutionLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Agent 日志服务 — 查询 Agent 执行记录
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentLogService {

    private final AgentExecutionLogRepository logRepository;

    /**
     * 获取某任务的所有 Agent 日志
     */
    public List<AgentLogResponse> getLogsByTask(Long taskId) {
        List<AgentExecutionLog> logs = logRepository.findByTaskIdOrderByCreatedAtAsc(taskId);
        return logs.stream().map(this::toLogResponse).toList();
    }

    /**
     * 获取某任务中某类 Agent 的日志
     */
    public List<AgentLogResponse> getLogsByTaskAndAgentType(Long taskId, String agentType) {
        List<AgentExecutionLog> logs = logRepository.findByTaskIdAndAgentTypeOrderByCreatedAtAsc(
                taskId, cn.bugstack.competitoragent.model.enums.AgentType.valueOf(agentType.toUpperCase()));
        return logs.stream().map(this::toLogResponse).toList();
    }

    /**
     * 获取单条日志详情（含完整 prompt 和响应）
     */
    public AgentLogResponse getLogDetail(Long logId) {
        AgentExecutionLog log = logRepository.findById(logId)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "日志不存在, id=" + logId));
        return toLogResponse(log);
    }

    private AgentLogResponse toLogResponse(AgentExecutionLog log) {
        return AgentLogResponse.builder()
                .id(log.getId())
                .taskId(log.getTaskId())
                .nodeId(log.getNodeId())
                .agentType(log.getAgentType())
                .agentName(log.getAgentName())
                .status(log.getStatus())
                .modelName(log.getModelName())
                .durationMs(log.getDurationMs())
                .reasoningSummary(log.getReasoningSummary())
                .promptUsed(log.getPromptUsed())
                .inputData(log.getInputData())
                .outputData(log.getOutputData())
                .tokenUsage(log.getTokenUsage())
                .errorMessage(log.getErrorMessage())
                .traceId(log.getTraceId())
                .needsHumanIntervention(log.isNeedsHumanIntervention())
                .createdAt(log.getCreatedAt())
                .build();
    }
}
