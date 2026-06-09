package cn.bugstack.competitoragent.conversation;

import cn.bugstack.competitoragent.model.dto.ConversationMessageRequest;
import cn.bugstack.competitoragent.model.dto.ConversationResponse;
import cn.bugstack.competitoragent.model.dto.TaskNodeResponse;
import cn.bugstack.competitoragent.model.entity.ConversationSession;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 结构化澄清编排器。
 * 它负责在意图初判之后、正式执行模式之前，再做一轮“槽位是否完整”的收口，
 * 避免高风险动作因为上下文不全而直接猜测目标对象。
 */
@Component
public class ClarificationOrchestrator {

    public ClarificationDecision resolve(ConversationMessageRequest request,
                                         ConversationSession session,
                                         IntentRecognitionService.RecognitionResult recognitionResult,
                                         Long taskId,
                                         List<TaskNodeResponse> nodeResponses) {
        if (recognitionResult == null) {
            return ClarificationDecision.none();
        }
        if (recognitionResult.isNeedsClarification()) {
            return ClarificationDecision.builder()
                    .required(true)
                    .clarificationSummary(buildSummaryFromRecognition(recognitionResult))
                    .build();
        }
        if (recognitionResult.getMode() != ConversationMode.TASK_ACTION
                || !"RERUN_FROM_NODE".equalsIgnoreCase(safe(recognitionResult.getIntentType()))) {
            return ClarificationDecision.none();
        }

        List<TaskNodeResponse> rerunCandidates = collectRerunCandidates(nodeResponses);
        if (rerunCandidates.size() <= 1) {
            return ClarificationDecision.none();
        }
        if (findExplicitTargetNode(request == null ? null : request.getMessage(), rerunCandidates) != null) {
            return ClarificationDecision.none();
        }

        List<ConversationResponse.ClarificationOption> options = new ArrayList<>();
        for (TaskNodeResponse candidate : rerunCandidates) {
            options.add(ConversationResponse.ClarificationOption.builder()
                    .slotName("targetNodeName")
                    .optionValue(candidate.getNodeName())
                    .label(firstNonBlank(candidate.getDisplayName(), candidate.getNodeName()))
                    .description(firstNonBlank(candidate.getImpactSummary(), candidate.getStatusSummary()))
                    .build());
        }

        return ClarificationDecision.builder()
                .required(true)
                .clarificationSummary(ConversationResponse.ClarificationSummary.builder()
                        .clarificationType("MISSING_ACTION_TARGET")
                        .reason("当前请求命中了重跑语义，但还没有明确要从哪个节点开始重跑。")
                        .question("当前任务有多个可重跑节点，请先确认要从哪个节点开始重跑。")
                        .missingSlots(List.of("targetNodeName"))
                        .options(options)
                        .build())
                .build();
    }

    private ConversationResponse.ClarificationSummary buildSummaryFromRecognition(
            IntentRecognitionService.RecognitionResult recognitionResult) {
        List<ConversationResponse.ClarificationOption> options = new ArrayList<>();
        if (recognitionResult.getCandidateIntentTypes() != null) {
            for (String candidateIntentType : recognitionResult.getCandidateIntentTypes()) {
                options.add(ConversationResponse.ClarificationOption.builder()
                        .slotName("intentType")
                        .optionValue(candidateIntentType)
                        .label(candidateIntentType)
                        .description("请明确本轮对话希望进入的动作语义。")
                        .build());
            }
        }
        return ConversationResponse.ClarificationSummary.builder()
                .clarificationType(recognitionResult.getClarificationType())
                .question(recognitionResult.getClarificationQuestion())
                .reason(firstNonBlank(recognitionResult.getClarificationReason(), recognitionResult.getDecisionReason()))
                .missingSlots(recognitionResult.getMissingSlots() == null ? List.of() : recognitionResult.getMissingSlots())
                .options(options)
                .build();
    }

    private List<TaskNodeResponse> collectRerunCandidates(List<TaskNodeResponse> nodeResponses) {
        if (nodeResponses == null || nodeResponses.isEmpty()) {
            return List.of();
        }
        return nodeResponses.stream()
                .filter(node -> node != null && node.getStatus() != null)
                .filter(node -> node.getStatus() == TaskNodeStatus.FAILED
                        || node.getStatus() == TaskNodeStatus.WAITING_INTERVENTION
                        || node.getStatus() == TaskNodeStatus.WAITING_RETRY)
                .toList();
    }

    /**
     * 澄清阶段只判断用户是否已经明确点名目标节点，
     * 不负责在没点名时替用户猜测一个默认节点。
     */
    private TaskNodeResponse findExplicitTargetNode(String message, List<TaskNodeResponse> candidates) {
        String normalizedMessage = safe(message).toLowerCase(Locale.ROOT);
        for (TaskNodeResponse candidate : candidates) {
            String nodeName = safe(candidate.getNodeName()).toLowerCase(Locale.ROOT);
            String displayName = safe(candidate.getDisplayName()).toLowerCase(Locale.ROOT);
            if ((!nodeName.isBlank() && normalizedMessage.contains(nodeName))
                    || (!displayName.isBlank() && normalizedMessage.contains(displayName))) {
                return candidate;
            }
        }
        return null;
    }

    private String firstNonBlank(String primary, String fallback) {
        return primary == null || primary.isBlank() ? fallback : primary;
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ClarificationDecision {
        private boolean required;
        private ConversationResponse.ClarificationSummary clarificationSummary;

        public static ClarificationDecision none() {
            return ClarificationDecision.builder()
                    .required(false)
                    .build();
        }
    }
}
