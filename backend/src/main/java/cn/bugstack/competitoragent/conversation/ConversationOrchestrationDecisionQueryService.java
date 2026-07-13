package cn.bugstack.competitoragent.conversation;

import cn.bugstack.competitoragent.model.dto.OrchestrationDecisionSummary;
import cn.bugstack.competitoragent.model.entity.TaskWorkflowEvent;
import cn.bugstack.competitoragent.orchestration.OrchestrationDecisionSummaryProjector;
import cn.bugstack.competitoragent.repository.TaskWorkflowEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 对话入口编排决策只读查询服务。
 * 它只从最近一次 ORCHESTRATION_DECISION_RECORDED 事件提取稳定视图，不重算任何编排规则。
 */
@Service
@RequiredArgsConstructor
public class ConversationOrchestrationDecisionQueryService {

    private final TaskWorkflowEventRepository taskWorkflowEventRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public Optional<ConversationOrchestrationDecisionView> findLatestDecision(Long taskId) {
        if (taskId == null) {
            return Optional.empty();
        }
        Optional<TaskWorkflowEvent> latestEvent = taskWorkflowEventRepository.findLatestOrchestrationDecisionEvent(taskId);
        if (latestEvent.isEmpty()) {
            return Optional.empty();
        }
        return extractView(latestEvent.get());
    }

    /**
     * Conversation 只把统一投影摘要收窄成动作预览视图，
     * 不再自行解释嵌套、平铺或历史 inputRefs 等 workflow event 格式。
     */
    private Optional<ConversationOrchestrationDecisionView> extractView(TaskWorkflowEvent event) {
        return OrchestrationDecisionSummaryProjector.fromWorkflowEvent(event, objectMapper)
                .map(this::toConversationView);
    }

    private ConversationOrchestrationDecisionView toConversationView(OrchestrationDecisionSummary summary) {
        return ConversationOrchestrationDecisionView.builder()
                .decisionId(summary.getDecisionId())
                .taskId(summary.getTaskId())
                .triggerNodeName(summary.getTriggerNodeName())
                .decisionType(summary.getDecisionType())
                .actionType(summary.getActionType())
                .targetNode(summary.getTargetNode())
                .affectedScope(summary.getAffectedScope())
                .reason(summary.getReason())
                .requiresHumanIntervention(summary.isRequiresHumanIntervention())
                .requiresConfirmation(summary.getRequiresConfirmation())
                .evidenceState(summary.getEvidenceState())
                .sourceUrls(summary.getSourceUrls())
                .build()
                .normalized();
    }
}
