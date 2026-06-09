package cn.bugstack.competitoragent.agent.conversation;

import cn.bugstack.competitoragent.llm.PromptTemplateService;
import cn.bugstack.competitoragent.model.dto.ConversationResponse;
import cn.bugstack.competitoragent.model.dto.TaskNodeResponse;
import cn.bugstack.competitoragent.model.dto.TaskResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 对话入口专用 Agent。
 * Task 4.6 先让它作为“解释文本生成器”存在，
 * 不进入 DAG 运行时注册表，也不负责直接执行高风险动作。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationAgent {

    private final PromptTemplateService promptTemplateService;

    public String composeChatAnswer(String userMessage) {
        preparePrompt(userMessage, "普通解释对话");
        return "我可以帮你解释任务进展、整理任务草稿、预览高风险动作，以及在安全边界内给出补证建议。";
    }

    public String composeExplainAnswer(String userMessage,
                                       TaskResponse task,
                                       TaskNodeResponse focusNode) {
        String contextSummary = task == null
                ? "当前缺少任务上下文。"
                : firstNonBlank(task.getCurrentStage(), "当前阶段未知") + " | " + firstNonBlank(task.getStatusSummary(), "暂无状态摘要");
        preparePrompt(userMessage, contextSummary);
        if (task == null) {
            return "当前没有足够的任务上下文，我还不能解释任务为什么停在这里。";
        }
        StringBuilder answer = new StringBuilder();
        answer.append("当前任务停在 ")
                .append(firstNonBlank(task.getCurrentStage(), "当前阶段"))
                .append("。");
        answer.append(firstNonBlank(task.getStatusSummary(), "系统还没有生成更细的状态摘要。"));
        if (focusNode != null) {
            answer.append(" 目前最需要关注的节点是 ")
                    .append(firstNonBlank(focusNode.getDisplayName(), focusNode.getNodeName()))
                    .append("，")
                    .append(firstNonBlank(focusNode.getInterventionReason(), firstNonBlank(focusNode.getStatusSummary(), "正在等待进一步处理。")));
        }
        answer.append(" 建议优先查看动作预览，再决定是恢复执行还是从特定节点重跑。");
        return answer.toString();
    }

    public String composeFormDraftAnswer(String userMessage,
                                         ConversationResponse.FormDraftSummary draftSummary) {
        preparePrompt(userMessage, draftSummary == null ? "暂无草稿" : draftSummary.getPreviewSummary());
        if (draftSummary == null) {
            return "我还没有生成出可用草稿，建议你补充想分析的竞品和重点维度。";
        }
        return "我已经先整理出一版任务草稿。"
                + firstNonBlank(draftSummary.getPreviewSummary(), "你可以继续用自然语言修改重点维度或竞品范围。");
    }

    public String composeActionPreviewAnswer(String userMessage,
                                             ConversationResponse.TaskActionPreview preview) {
        preparePrompt(userMessage, preview == null ? "暂无动作预览" : preview.getTitle());
        if (preview == null) {
            return "我还不能可靠地翻译这条动作请求，所以先不给出执行建议。";
        }
        return "我先给你做动作预览："
                + firstNonBlank(preview.getTitle(), "任务动作")
                + "。"
                + firstNonBlank(preview.getImpactSummary(), "该动作会影响当前任务后续链路。")
                + " 这是需要确认的动作，我不会直接替你执行。";
    }

    public String composeResearchAnswer(String userMessage,
                                        ConversationResponse.TaskActionPreview preview,
                                        List<ConversationResponse.RetrievalEvidence> evidences,
                                        String gapSummary) {
        preparePrompt(userMessage, gapSummary);
        if (evidences == null || evidences.isEmpty()) {
            return "我先做了安全补证检查，但当前没有找到可直接回指的片段。"
                    + firstNonBlank(gapSummary, "建议先补充新的公开证据来源。");
        }
        return "我先给你补证预览：已找到 "
                + evidences.size()
                + " 条可回指片段。"
                + firstNonBlank(gapSummary, "当前只返回补证建议，不直接执行任务控制。")
                + " 如果你确认需要继续补源，可以再走动作确认链路。";
    }

    public String composeClarificationAnswer(String userMessage,
                                             ConversationResponse.ClarificationSummary clarificationSummary) {
        preparePrompt(userMessage, clarificationSummary == null ? "结构化澄清" : clarificationSummary.getQuestion());
        if (clarificationSummary == null) {
            return "当前上下文还不够完整，我需要先补齐关键信息，才能继续安全推进。";
        }
        StringBuilder answer = new StringBuilder(firstNonBlank(
                clarificationSummary.getQuestion(),
                "我需要先确认关键信息，才能继续安全推进。"));
        if (clarificationSummary.getOptions() != null && !clarificationSummary.getOptions().isEmpty()) {
            answer.append(" 可选项包括：");
            for (int i = 0; i < clarificationSummary.getOptions().size(); i++) {
                ConversationResponse.ClarificationOption option = clarificationSummary.getOptions().get(i);
                if (option == null) {
                    continue;
                }
                if (i > 0) {
                    answer.append("；");
                }
                answer.append(firstNonBlank(option.getLabel(), option.getOptionValue()));
            }
            answer.append("。");
        }
        return answer.toString();
    }

    /**
     * 这里显式通过 PromptTemplateService 准备对话系统 Prompt，
     * 保证统一阶段状态输出格式真正沉淀到运行时，而不只是测试里存在。
     */
    private void preparePrompt(String userMessage, String contextSummary) {
        // 对话 Prompt 预览只承担审计与调试作用，模板返回 null 时必须降级为空串，不能打断解释/预览主流程。
        String prompt = safe(promptTemplateService.render("conversation-agent", Map.of(
                "userMessage", safe(userMessage),
                "contextSummary", safe(contextSummary)
        )));
        log.debug("conversation agent prompt prepared, length={}", prompt.length());
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String firstNonBlank(String primary, String fallback) {
        return primary == null || primary.isBlank() ? fallback : primary;
    }
}
