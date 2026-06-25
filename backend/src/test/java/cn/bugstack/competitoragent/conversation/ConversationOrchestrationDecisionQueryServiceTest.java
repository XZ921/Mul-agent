package cn.bugstack.competitoragent.conversation;

import cn.bugstack.competitoragent.model.entity.TaskWorkflowEvent;
import cn.bugstack.competitoragent.repository.TaskWorkflowEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationOrchestrationDecisionQueryServiceTest {

    @Test
    void shouldReturnEmptyWhenTaskHasNoRecordedDecision() {
        TaskWorkflowEventRepository repository = mock(TaskWorkflowEventRepository.class);
        when(repository.findLatestOrchestrationDecisionEvent(88L)).thenReturn(Optional.empty());
        ConversationOrchestrationDecisionQueryService service =
                new ConversationOrchestrationDecisionQueryService(repository, new ObjectMapper());

        Optional<ConversationOrchestrationDecisionView> result = service.findLatestDecision(88L);

        assertThat(result).isEmpty();
        verify(repository).findLatestOrchestrationDecisionEvent(88L);
    }

    @Test
    void shouldReadLatestDecisionFromWorkflowEventPayload() {
        TaskWorkflowEventRepository repository = mock(TaskWorkflowEventRepository.class);
        TaskWorkflowEvent event = TaskWorkflowEvent.builder()
                .id(501L)
                .eventId("event-p3-3-001")
                .taskId(88L)
                .nodeName("analyze_competitors")
                .payload("""
                        {
                          "summary": "Orchestrator 已生成运行时编排决策",
                          "decision": {
                            "decisionId": "od-88-analyze_competitors-human",
                            "taskId": 88,
                            "triggerNodeName": "analyze_competitors",
                            "decisionType": "WAIT_FOR_HUMAN",
                            "actionType": "MANUAL_REVIEW",
                            "targetNode": "analyze_competitors",
                            "affectedScope": "CURRENT_NODE_ONLY",
                            "reason": "Analyzer 发现分析缺口但缺少 sourceUrls，禁止自动补证。",
                            "requiresHumanIntervention": true,
                            "requiresConfirmation": false,
                            "evidenceState": "MISSING_SOURCE",
                            "sourceUrls": [
                              "https://docs.example.com/analyze"
                            ]
                          }
                        }
                        """)
                .sourceUrls("[\"https://docs.example.com/analyze\",\"https://docs.example.com/replay\"]")
                .createdAt(LocalDateTime.now())
                .build();
        when(repository.findLatestOrchestrationDecisionEvent(88L)).thenReturn(Optional.of(event));
        ConversationOrchestrationDecisionQueryService service =
                new ConversationOrchestrationDecisionQueryService(repository, new ObjectMapper());

        Optional<ConversationOrchestrationDecisionView> result = service.findLatestDecision(88L);

        assertThat(result).isPresent();
        ConversationOrchestrationDecisionView view = result.orElseThrow();
        assertThat(view.getDecisionId()).isEqualTo("od-88-analyze_competitors-human");
        assertThat(view.getTaskId()).isEqualTo(88L);
        assertThat(view.getTriggerNodeName()).isEqualTo("analyze_competitors");
        assertThat(view.getDecisionType()).isEqualTo("WAIT_FOR_HUMAN");
        assertThat(view.getActionType()).isEqualTo("MANUAL_REVIEW");
        assertThat(view.getTargetNode()).isEqualTo("analyze_competitors");
        assertThat(view.getAffectedScope()).isEqualTo("CURRENT_NODE_ONLY");
        assertThat(view.getReason()).contains("禁止自动补证");
        assertThat(view.isRequiresHumanIntervention()).isTrue();
        assertThat(view.getRequiresConfirmation()).isFalse();
        assertThat(view.getEvidenceState()).isEqualTo("MISSING_SOURCE");
        assertThat(view.getSourceUrls()).containsExactly(
                "https://docs.example.com/analyze",
                "https://docs.example.com/replay");
    }
}
