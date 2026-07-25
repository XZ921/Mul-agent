package cn.bugstack.competitoragent.orchestration;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * LLM 编排决策的唯一动作语义矩阵。
 * 矩阵只负责固定 decision/action、目标节点、作用域和可执行动作之间的协议关系，
 * 不读取运行策略、证据状态或工作流上下文。
 */
@Component
public class OrchestrationDecisionActionMatrix {

    public static final String INVALID_DECISION_ACTION_PAIR = "INVALID_DECISION_ACTION_PAIR";
    public static final String INVALID_LLM_TARGET_NODE = "INVALID_LLM_TARGET_NODE";
    public static final String INVALID_LLM_AFFECTED_SCOPE = "INVALID_LLM_AFFECTED_SCOPE";

    private final List<ActionRule> rules;
    private final Map<ActionKey, ActionRule> rulesByKey;

    /**
     * 在单一位置建立五条 LLM 动作规则，并同时构建不可变索引。
     * 后续 Parser 和 Policy 必须查询这份规则，禁止各自维护 target/scope 映射。
     */
    public OrchestrationDecisionActionMatrix() {
        this.rules = List.of(
                new ActionRule(
                        "LLM_NO_ACTION",
                        "NO_ACTION",
                        "NO_ACTION",
                        "NO_ACTION",
                        TargetNodePolicy.TRIGGER_NODE,
                        null,
                        "CURRENT_NODE_ONLY"),
                new ActionRule(
                        "LLM_SUPPLEMENT_EVIDENCE",
                        "APPEND_DYNAMIC_BRANCH",
                        "SUPPLEMENT_EVIDENCE",
                        "CREATE_SUPPLEMENT_BRANCH",
                        TargetNodePolicy.FIXED_NODE,
                        "collect_sources",
                        "CURRENT_NODE_AND_DOWNSTREAM"),
                new ActionRule(
                        "LLM_RERUN_EXTRACT_SCHEMA",
                        "RERUN_NODE",
                        "RERUN_NODE",
                        "CREATE_RERUN_BRANCH",
                        TargetNodePolicy.FIXED_NODE,
                        "extract_schema",
                        "CURRENT_NODE_ONLY"),
                new ActionRule(
                        "LLM_REWRITE_SECTION",
                        "REWRITE_ONLY",
                        "REWRITE_SECTION",
                        "CREATE_REWRITE_BRANCH",
                        TargetNodePolicy.FIXED_NODE,
                        "rewrite_report",
                        "CURRENT_NODE_ONLY"),
                new ActionRule(
                        "LLM_REWRITE_CLAIM",
                        "REWRITE_ONLY",
                        "REWRITE_CLAIM",
                        "CREATE_REWRITE_BRANCH",
                        TargetNodePolicy.FIXED_NODE,
                        "rewrite_report",
                        "CURRENT_NODE_ONLY"),
                new ActionRule(
                        "LLM_MANUAL_REVIEW",
                        "WAIT_FOR_HUMAN",
                        "MANUAL_REVIEW",
                        "MANUAL_ONLY",
                        TargetNodePolicy.TRIGGER_NODE,
                        null,
                        "CURRENT_NODE_ONLY")
        );
        Map<ActionKey, ActionRule> index = new LinkedHashMap<>();
        for (ActionRule rule : rules) {
            ActionRule duplicatedRule = index.put(
                    new ActionKey(rule.decisionType(), rule.actionType()),
                    rule);
            if (duplicatedRule != null) {
                throw new IllegalStateException("LLM 动作矩阵存在重复 decision/action 组合："
                        + rule.decisionType() + "/" + rule.actionType());
            }
        }
        this.rulesByKey = Collections.unmodifiableMap(index);
    }

    /**
     * 返回不可变规则快照，供契约测试和后续协议消费者核对完整矩阵。
     */
    public List<ActionRule> rules() {
        return rules;
    }

    /**
     * 按归一化后的 decision/action 组合查找唯一规则。
     * 未知或空值直接返回 empty，不能降级成 NO_ACTION，否则非法 LLM 输出会被静默执行。
     */
    public Optional<ActionRule> findRule(String decisionType, String actionType) {
        String normalizedDecisionType = normalizeEnumText(decisionType);
        String normalizedActionType = normalizeEnumText(actionType);
        if (normalizedDecisionType == null || normalizedActionType == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(rulesByKey.get(new ActionKey(normalizedDecisionType, normalizedActionType)));
    }

    /**
     * 严格校验已经构造完成的 LLM decision，不补 targetNode 或 affectedScope。
     * Parser 可以先查询规则填默认值，但任何绕过 Parser 的不完整输入都会在这里被阻断。
     */
    public ActionMatrixValidation validate(OrchestrationDecision decision) {
        if (decision == null) {
            return invalidPair();
        }
        Optional<ActionRule> matchedRule = findRule(decision.getDecisionType(), decision.getActionType());
        if (matchedRule.isEmpty()) {
            return invalidPair();
        }

        ActionRule rule = matchedRule.get();
        List<String> violations = new ArrayList<>();
        String expectedTargetNode = rule.resolveTargetNode(decision.getTriggerNodeName());
        String normalizedTargetNode = normalizeNodeName(decision.getTargetNode());
        String normalizedExpectedTargetNode = normalizeNodeName(expectedTargetNode);
        if (normalizedTargetNode == null
                || normalizedExpectedTargetNode == null
                || !Objects.equals(normalizedTargetNode, normalizedExpectedTargetNode)) {
            violations.add(INVALID_LLM_TARGET_NODE);
        }
        if (!Objects.equals(normalizeEnumText(decision.getAffectedScope()), rule.affectedScope())) {
            violations.add(INVALID_LLM_AFFECTED_SCOPE);
        }
        return new ActionMatrixValidation(
                violations.isEmpty(),
                rule.ruleId(),
                rule.normalizedAction(),
                violations);
    }

    private ActionMatrixValidation invalidPair() {
        return new ActionMatrixValidation(false, null, null, List.of(INVALID_DECISION_ACTION_PAIR));
    }

    private String normalizeEnumText(String value) {
        String normalized = normalizeNodeName(value);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }

    private String normalizeNodeName(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * 单条动作规则。TRIGGER_NODE 规则在消费时根据当前 decision 解析目标节点，
     * FIXED_NODE 规则则始终返回矩阵中固定的工作流节点。
     */
    public record ActionRule(
            String ruleId,
            String decisionType,
            String actionType,
            String normalizedAction,
            TargetNodePolicy targetNodePolicy,
            String fixedTargetNode,
            String affectedScope
    ) {
        public String resolveTargetNode(String triggerNodeName) {
            return targetNodePolicy == TargetNodePolicy.TRIGGER_NODE ? triggerNodeName : fixedTargetNode;
        }
    }

    /**
     * 不可变校验结果。violationCodes 会被复制，防止调用方修改审计结果。
     */
    public record ActionMatrixValidation(
            boolean valid,
            String ruleId,
            String normalizedAction,
            List<String> violationCodes
    ) {
        public ActionMatrixValidation {
            violationCodes = violationCodes == null ? List.of() : List.copyOf(violationCodes);
        }
    }

    public enum TargetNodePolicy {
        TRIGGER_NODE,
        FIXED_NODE
    }

    private record ActionKey(String decisionType, String actionType) {
    }
}
