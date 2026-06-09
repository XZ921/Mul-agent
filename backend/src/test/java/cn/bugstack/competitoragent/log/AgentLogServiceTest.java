package cn.bugstack.competitoragent.log;

import cn.bugstack.competitoragent.event.TaskEventPublisher;
import cn.bugstack.competitoragent.model.dto.AgentLogResponse;
import cn.bugstack.competitoragent.model.entity.AiCallAuditRecord;
import cn.bugstack.competitoragent.model.entity.AgentExecutionLog;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.repository.AiCallAuditRecordRepository;
import cn.bugstack.competitoragent.repository.AgentExecutionLogRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentLogServiceTest {

    private final AgentExecutionLogRepository logRepository = mock(AgentExecutionLogRepository.class);
    private final AiCallAuditRecordRepository aiCallAuditRecordRepository = mock(AiCallAuditRecordRepository.class);
    private final TaskNodeRepository taskNodeRepository = mock(TaskNodeRepository.class);
    private final TaskEventPublisher taskEventPublisher = mock(TaskEventPublisher.class);

    private final AgentLogService agentLogService = new AgentLogService(
            logRepository,
            aiCallAuditRecordRepository,
            taskNodeRepository,
            taskEventPublisher
    );

    @Test
    void shouldExposeReadableAiGovernanceSummaryInAgentLogResponse() {
        // 日志面板是用户排查 AI 调用影响的高频入口，
        // 这里必须直接看到治理摘要，而不是只剩 modelName 和原始 token JSON。
        AgentExecutionLog executionLog = AgentExecutionLog.builder()
                .id(1L)
                .taskId(501L)
                .nodeId(11L)
                .agentType(AgentType.WRITER)
                .agentName("报告撰写智能体")
                .status(TaskNodeStatus.SUCCESS)
                .modelName("siliconflow-chat")
                .tokenUsage("{\"input\":10,\"output\":20,\"total\":30}")
                .build();
        when(logRepository.findByTaskIdOrderByCreatedAtAsc(501L)).thenReturn(List.of(executionLog));
        when(taskNodeRepository.findById(11L)).thenReturn(Optional.of(TaskNode.builder()
                .id(11L)
                .taskId(501L)
                .nodeName("write_report")
                .build()));
        when(aiCallAuditRecordRepository.findTopByTaskIdAndNodeNameOrderByCreatedAtDesc(501L, "write_report"))
                .thenReturn(Optional.of(AiCallAuditRecord.builder()
                        .taskId(501L)
                        .nodeName("write_report")
                        .summary("主 Provider 超时，已切换到备用 Provider")
                        .totalTokens(30)
                        .build()));

        List<AgentLogResponse> responses = agentLogService.getLogsByTask(501L);

        assertEquals(1, responses.size());
        assertTrue(responses.get(0).getAiGovernanceSummary().contains("备用 Provider"));
        assertTrue(responses.get(0).getAiGovernanceSummary().contains("Token 总量=30"));
    }
}
