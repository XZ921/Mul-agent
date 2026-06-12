package cn.bugstack.competitoragent.workflow.runtime;

import cn.bugstack.competitoragent.event.TaskEventPublisher;
import cn.bugstack.competitoragent.log.AgentLogService;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeEventEmitterTest {

    @Test
    void shouldPublishFormalSearchAuditAndSelectedTargetsFromCollectorOutput() {
        TaskEventPublisher taskEventPublisher = mock(TaskEventPublisher.class);
        AgentLogService agentLogService = mock(AgentLogService.class);
        when(agentLogService.publishLatestLogEvent(24L, "collect_sources_docs", AgentType.COLLECTOR)).thenReturn(true);

        RuntimeEventEmitter emitter = new RuntimeEventEmitter(taskEventPublisher, agentLogService, new ObjectMapper());
        TaskNode node = TaskNode.builder()
                .taskId(24L)
                .nodeName("collect_sources_docs")
                .displayName("collect_sources_docs")
                .agentType(AgentType.COLLECTOR)
                .status(TaskNodeStatus.SUCCESS)
                .outputData("""
                        {
                          "searchProgress":{"status":"SUCCESS","currentStep":"SELECT_TARGETS"},
                          "searchExecutionTrace":{"recoveryCheckpoint":"SELECT_TARGETS","degraded":false},
                          "searchAudit":{"executionTrace":{"recoveryCheckpoint":"SELECT_TARGETS"}},
                          "selectedTargets":[{"url":"https://docs.notion.so/reference","title":"Reference"}],
                          "sourceUrls":["https://docs.notion.so/reference"]
                        }
                        """)
                .build();

        emitter.publishNodeExecutionEvents(24L, node);

        verify(taskEventPublisher).publishSearchProgressEvent(eq(24L), eq("collect_sources_docs"), argThat(payload ->
                payload.containsKey("searchAudit")
                        && payload.containsKey("selectedTargets")
                        && payload.containsKey("sourceUrls")));
    }
}
