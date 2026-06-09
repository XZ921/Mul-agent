package cn.bugstack.competitoragent.log;

import cn.bugstack.competitoragent.common.BusinessException;
import cn.bugstack.competitoragent.common.ResultCode;
import cn.bugstack.competitoragent.event.TaskEventPublisher;
import cn.bugstack.competitoragent.model.dto.AgentLogResponse;
import cn.bugstack.competitoragent.model.entity.AiCallAuditRecord;
import cn.bugstack.competitoragent.model.entity.AgentExecutionLog;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.repository.AiCallAuditRecordRepository;
import cn.bugstack.competitoragent.repository.AgentExecutionLogRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

/**
 * Agent 日志服务 — 查询 Agent 执行记录
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentLogService {

    private final AgentExecutionLogRepository logRepository;
    private final AiCallAuditRecordRepository aiCallAuditRecordRepository;
    private final TaskNodeRepository taskNodeRepository;
    private final TaskEventPublisher taskEventPublisher;

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

    /**
     * 把某个节点最近一次 Agent 执行日志转成实时事件。
     * DagExecutor 在节点执行完成后调用该方法，让日志面板能和节点状态同步刷新。
     */
    public boolean publishLatestLogEvent(Long taskId, String nodeName, AgentType agentType) {
        if (taskId == null || agentType == null) {
            return false;
        }
        List<AgentExecutionLog> logs = logRepository.findByTaskIdAndAgentTypeOrderByCreatedAtAsc(taskId, agentType);
        if (logs == null || logs.isEmpty()) {
            return false;
        }
        AgentExecutionLog latest = logs.get(logs.size() - 1);
        taskEventPublisher.publishAgentLogEvent(taskId, nodeName, toLogResponse(latest));
        return true;
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
                .aiGovernanceSummary(resolveAiGovernanceSummary(log))
                .errorMessage(log.getErrorMessage())
                .traceId(log.getTraceId())
                .needsHumanIntervention(log.isNeedsHumanIntervention())
                .createdAt(log.getCreatedAt())
                .build();
    }

    /**
     * 日志主路径只拼用户可读治理摘要，
     * 让使用者无需回看原始审计表也能理解预算、降级和 Provider 影响。
     */
    private String resolveAiGovernanceSummary(AgentExecutionLog log) {
        if (log == null || log.getTaskId() == null || log.getNodeId() == null) {
            return null;
        }
        Optional<TaskNode> taskNode = taskNodeRepository.findById(log.getNodeId());
        if (taskNode.isEmpty() || !StringUtils.hasText(taskNode.get().getNodeName())) {
            return null;
        }
        Optional<AiCallAuditRecord> auditRecord = aiCallAuditRecordRepository.findTopByTaskIdAndNodeNameOrderByCreatedAtDesc(
                log.getTaskId(),
                taskNode.get().getNodeName()
        );
        if (auditRecord.isEmpty()) {
            return null;
        }
        List<String> parts = new java.util.ArrayList<>();
        if (StringUtils.hasText(auditRecord.get().getSummary())) {
            parts.add(auditRecord.get().getSummary());
        }
        if (auditRecord.get().getTotalTokens() != null && auditRecord.get().getTotalTokens() > 0) {
            parts.add("Token 总量=" + auditRecord.get().getTotalTokens());
        }
        return parts.isEmpty() ? null : String.join("；", parts);
    }
}
