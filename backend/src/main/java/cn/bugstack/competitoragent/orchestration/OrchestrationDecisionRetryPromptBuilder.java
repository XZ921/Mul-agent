package cn.bugstack.competitoragent.orchestration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 根据严格 Parser 的结构化问题构建一次纠正 Prompt。
 * 原始模型响应不是输入参数，从类型边界上阻止 raw response 被回显给下一次模型调用。
 */
@Component
public class OrchestrationDecisionRetryPromptBuilder {

    static final String BEGIN_PARSER_FEEDBACK = "PARSER_FEEDBACK_JSON";
    static final String END_PARSER_FEEDBACK = "END_PARSER_FEEDBACK_JSON";
    private static final String CORRECTION_INSTRUCTION =
            "上一次响应未通过严格结构校验。请根据同一 schema 重新生成完整 JSON；不要解释或复述错误。";

    private final ObjectMapper objectMapper;

    public OrchestrationDecisionRetryPromptBuilder(ObjectMapper objectMapper) {
        if (objectMapper == null) {
            throw new IllegalArgumentException("objectMapper 不能为空");
        }
        this.objectMapper = objectMapper;
    }

    /**
     * issues 按 Parser 原顺序写入 JSON；fieldName 等外部派生文本全部交给 Jackson 转义，禁止手工拼接。
     */
    public OrchestrationDecisionPrompt build(
            OrchestrationDecisionPrompt originalPrompt,
            int retryNumber,
            List<OrchestrationDecisionParseResult.ParseIssue> issues) {
        requireRetryContract(originalPrompt, retryNumber, issues);
        String feedbackJson = serializeFeedback(retryNumber, issues);
        String retrySystemPrompt = originalPrompt.systemPrompt()
                + "\n\n" + CORRECTION_INSTRUCTION
                + "\n\n" + BEGIN_PARSER_FEEDBACK
                + "\n" + feedbackJson
                + "\n" + END_PARSER_FEEDBACK;
        return new OrchestrationDecisionPrompt(
                retrySystemPrompt,
                originalPrompt.userPrompt(),
                originalPrompt.responseSchema());
    }

    private String serializeFeedback(
            int retryNumber,
            List<OrchestrationDecisionParseResult.ParseIssue> issues) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("retryNumber", retryNumber);
        ArrayNode issueArray = root.putArray("issues");
        for (OrchestrationDecisionParseResult.ParseIssue issue : issues) {
            if (issue == null) {
                throw new IllegalArgumentException("issues 不能包含 null");
            }
            ObjectNode item = issueArray.addObject();
            if (issue.decisionIndex() == null) {
                item.putNull("decisionIndex");
            } else {
                item.put("decisionIndex", issue.decisionIndex());
            }
            item.put("code", issue.code());
            if (issue.fieldName() == null) {
                item.putNull("fieldName");
            } else {
                item.put("fieldName", issue.fieldName());
            }
        }
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Parser feedback JSON 序列化失败", exception);
        }
    }

    private void requireRetryContract(
            OrchestrationDecisionPrompt originalPrompt,
            int retryNumber,
            List<OrchestrationDecisionParseResult.ParseIssue> issues) {
        if (originalPrompt == null) {
            throw new IllegalArgumentException("originalPrompt 不能为空");
        }
        if (retryNumber < 1) {
            throw new IllegalArgumentException("retryNumber 必须大于等于 1");
        }
        if (issues == null || issues.isEmpty()) {
            throw new IllegalArgumentException("issues 不能为空");
        }
    }
}
