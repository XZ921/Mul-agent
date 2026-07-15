package cn.bugstack.competitoragent.orchestration;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 严格解析 Orchestrator LLM JSON 响应。
 * Parser 只负责验证和构造候选 decision，不调用模型、Policy、Rule Brain、Executor 或 Trace。
 */
@Component
public class OrchestrationDecisionResponseParser {

    public static final String EMPTY_LLM_RESPONSE = "EMPTY_LLM_RESPONSE";
    public static final String MALFORMED_LLM_JSON = "MALFORMED_LLM_JSON";
    public static final String INVALID_RESPONSE_SHAPE = "INVALID_RESPONSE_SHAPE";
    public static final String UNKNOWN_RESPONSE_FIELD = "UNKNOWN_RESPONSE_FIELD";
    public static final String UNKNOWN_DECISION_FIELD = "UNKNOWN_DECISION_FIELD";
    public static final String MISSING_REQUIRED_FIELD = "MISSING_REQUIRED_FIELD";
    public static final String INVALID_FIELD_TYPE = "INVALID_FIELD_TYPE";
    public static final String EMPTY_DECISIONS = "EMPTY_DECISIONS";
    public static final String TOO_MANY_DECISIONS = "TOO_MANY_DECISIONS";
    public static final String UNKNOWN_DECISION_TYPE = "UNKNOWN_DECISION_TYPE";
    public static final String UNKNOWN_ACTION_TYPE = "UNKNOWN_ACTION_TYPE";
    public static final String INVALID_PRIORITY = "INVALID_PRIORITY";
    public static final String INVALID_CONFIDENCE = "INVALID_CONFIDENCE";
    public static final String INVALID_HUMAN_FLAGS = "INVALID_HUMAN_FLAGS";
    public static final String INVALID_SOURCE_URL = "INVALID_SOURCE_URL";
    public static final String SOURCE_URL_OUTSIDE_CONTEXT = "SOURCE_URL_OUTSIDE_CONTEXT";

    private static final Set<String> ROOT_FIELDS = Set.of("decisions");
    private static final List<String> REQUIRED_FIELDS = List.of(
            "decisionType",
            "actionType",
            "priority",
            "reason",
            "confidence",
            "requiresHumanIntervention",
            "requiresConfirmation",
            "sourceUrls",
            "suggestedQueries"
    );
    private static final Set<String> CANDIDATE_FIELDS = Set.of(
            "decisionType",
            "actionType",
            "targetNode",
            "targetSection",
            "affectedScope",
            "priority",
            "reason",
            "confidence",
            "requiresHumanIntervention",
            "requiresConfirmation",
            "sourceUrls",
            "suggestedQueries"
    );
    private static final List<String> OPTIONAL_TEXT_FIELDS = List.of(
            "targetNode", "targetSection", "affectedScope");
    private static final Set<String> PRIORITIES = Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");

    private final ObjectMapper strictObjectMapper;
    private final OrchestrationDecisionActionMatrix actionMatrix;
    private final DecisionPolicyRuleSet ruleSet;
    private final Set<String> allowedDecisionTypes;
    private final Set<String> allowedActionTypes;

    public OrchestrationDecisionResponseParser(ObjectMapper objectMapper,
                                               OrchestrationDecisionActionMatrix actionMatrix,
                                               DecisionPolicyRuleSet ruleSet) {
        this.strictObjectMapper = objectMapper.copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.actionMatrix = actionMatrix;
        if (ruleSet == null) {
            throw new IllegalArgumentException("ruleSet 不能为空");
        }
        this.ruleSet = ruleSet;
        this.allowedDecisionTypes = collectDecisionTypes(actionMatrix.rules());
        this.allowedActionTypes = collectActionTypes(actionMatrix.rules());
    }

    /**
     * 解析一批模型候选。任一候选非法都会让整批失败，防止调用方误执行部分结果。
     */
    public OrchestrationDecisionParseResult parse(String rawResponse,
                                                   OrchestrationContext normalizedContext,
                                                   OrchestrationDecisionOrigin llmOrigin) {
        requireCallerContract(normalizedContext, llmOrigin);
        if (rawResponse == null || rawResponse.isBlank()) {
            return failure(issue(null, EMPTY_LLM_RESPONSE, null));
        }

        JsonNode root;
        try {
            root = strictObjectMapper.readTree(rawResponse);
        } catch (JsonProcessingException exception) {
            return failure(issue(null, MALFORMED_LLM_JSON, null));
        }

        List<OrchestrationDecisionParseResult.ParseIssue> structureIssues = validateStructure(root);
        if (!structureIssues.isEmpty()) {
            return OrchestrationDecisionParseResult.failure(structureIssues, List.of());
        }

        OrchestrationDecisionResponse response;
        try {
            response = strictObjectMapper.treeToValue(root, OrchestrationDecisionResponse.class);
        } catch (JsonProcessingException exception) {
            return failure(issue(null, MALFORMED_LLM_JSON, null));
        }

        OrchestrationSourceEvidenceCatalog sourceCatalog =
                OrchestrationSourceEvidenceCatalog.from(normalizedContext);
        List<OrchestrationDecisionParseResult.ParseIssue> issues = new ArrayList<>();
        List<OrchestrationDecisionParseResult.DiscardedSourceUrl> discardedSourceUrls = new ArrayList<>();
        List<OrchestrationDecision> decisions = new ArrayList<>();
        for (int index = 0; index < response.decisions().size(); index++) {
            OrchestrationDecisionResponse.DecisionCandidate candidate = response.decisions().get(index);
            int issueCountBeforeCandidate = issues.size();
            ParsedCandidate parsedCandidate = validateCandidateFields(candidate, index, issues);
            if (issues.size() != issueCountBeforeCandidate) {
                continue;
            }

            OrchestrationDecisionActionMatrix.ActionRule rule = actionMatrix
                    .findRule(parsedCandidate.decisionType(), parsedCandidate.actionType())
                    .orElse(null);
            if (rule == null) {
                issues.add(issue(index,
                        OrchestrationDecisionActionMatrix.INVALID_DECISION_ACTION_PAIR,
                        "decisionType/actionType"));
                continue;
            }

            FilteredSources filteredSources = filterSources(
                    index,
                    candidate.sourceUrls(),
                    sourceCatalog,
                    discardedSourceUrls);
            String targetNode = parsedCandidate.targetNode() == null
                    ? rule.resolveTargetNode(normalizedContext.getTriggerNodeName())
                    : parsedCandidate.targetNode();
            String affectedScope = parsedCandidate.affectedScope() == null
                    ? rule.affectedScope()
                    : parsedCandidate.affectedScope();
            OrchestrationDecision decision = OrchestrationDecision.builder()
                    .decisionId("od-" + normalizedContext.getTaskId()
                            + "-" + normalizedContext.getTriggerNodeName()
                            + "-llm-" + (index + 1))
                    .taskId(normalizedContext.getTaskId())
                    .triggerNodeName(normalizedContext.getTriggerNodeName())
                    .decisionOrigin(llmOrigin)
                    .decisionMetadata(OrchestratorDecisionMetadata.empty())
                    .decisionType(parsedCandidate.decisionType())
                    .actionType(parsedCandidate.actionType())
                    .targetNode(targetNode)
                    .affectedScope(affectedScope)
                    .priority(parsedCandidate.priority())
                    .targetSection(parsedCandidate.targetSection())
                    .reason(parsedCandidate.reason())
                    .requiresHumanIntervention(candidate.requiresHumanIntervention())
                    .requiresConfirmation(candidate.requiresConfirmation())
                    .confidence(candidate.confidence())
                    .suggestedQueries(normalizeDistinctText(candidate.suggestedQueries()))
                    .inputRefs(buildInputRefs(normalizedContext, filteredSources.discardedValues()))
                    .sourceUrls(filteredSources.acceptedUrls())
                    .evidenceState(sourceCatalog.resolveDecisionEvidenceState(filteredSources.acceptedUrls()))
                    .build();

            OrchestrationDecisionActionMatrix.ActionMatrixValidation matrixValidation = actionMatrix.validate(decision);
            if (!matrixValidation.valid()) {
                for (String violation : matrixValidation.violationCodes()) {
                    issues.add(issue(index, violation, matrixViolationField(violation)));
                }
                continue;
            }
            decisions.add(decision.normalized());
        }

        if (!issues.isEmpty()) {
            return OrchestrationDecisionParseResult.failure(issues, discardedSourceUrls);
        }
        return OrchestrationDecisionParseResult.success(decisions, discardedSourceUrls);
    }

    private void requireCallerContract(OrchestrationContext context, OrchestrationDecisionOrigin origin) {
        if (context == null) {
            throw new IllegalArgumentException("normalizedContext 不能为空");
        }
        if (context.getTaskId() == null) {
            throw new IllegalArgumentException("taskId 不能为空");
        }
        if (context.getTriggerNodeName() == null || context.getTriggerNodeName().isBlank()) {
            throw new IllegalArgumentException("triggerNodeName 不能为空");
        }
        if (origin == null || !origin.usesLlmActionMatrix()) {
            throw new IllegalArgumentException("Parser 只接受 LLM_PRIMARY 或 LLM_SHADOW origin");
        }
    }

    /**
     * 先按固定顺序完成 envelope、unknown、required 和类型检查，
     * 只有结构完全可信后才绑定 DTO，避免 Jackson 默认值掩盖模型缺字段。
     */
    private List<OrchestrationDecisionParseResult.ParseIssue> validateStructure(JsonNode root) {
        List<OrchestrationDecisionParseResult.ParseIssue> issues = new ArrayList<>();
        if (root == null || !root.isObject()) {
            issues.add(issue(null, INVALID_RESPONSE_SHAPE, null));
            return issues;
        }
        root.fieldNames().forEachRemaining(fieldName -> {
            if (!ROOT_FIELDS.contains(fieldName)) {
                issues.add(issue(null, UNKNOWN_RESPONSE_FIELD, fieldName));
            }
        });
        if (!root.has("decisions")) {
            issues.add(issue(null, MISSING_REQUIRED_FIELD, "decisions"));
            return issues;
        }
        JsonNode decisions = root.get("decisions");
        if (!decisions.isArray()) {
            issues.add(issue(null, INVALID_RESPONSE_SHAPE, "decisions"));
            return issues;
        }
        if (decisions.isEmpty()) {
            issues.add(issue(null, EMPTY_DECISIONS, "decisions"));
            return issues;
        }
        // 在反序列化和候选构造前整批拒绝超限数组，既不产生部分 success，也不让审计 payload 无界增长。
        if (decisions.size() > ruleSet.getMaxDecisionsPerCycle()) {
            issues.add(issue(null, TOO_MANY_DECISIONS, "decisions"));
            return issues;
        }
        for (int index = 0; index < decisions.size(); index++) {
            JsonNode candidate = decisions.get(index);
            if (!candidate.isObject()) {
                issues.add(issue(index, INVALID_RESPONSE_SHAPE, "decisions[" + index + "]"));
                continue;
            }
            int candidateIndex = index;
            candidate.fieldNames().forEachRemaining(fieldName -> {
                if (!CANDIDATE_FIELDS.contains(fieldName)) {
                    issues.add(issue(candidateIndex, UNKNOWN_DECISION_FIELD, fieldName));
                }
            });
            for (String fieldName : REQUIRED_FIELDS) {
                if (!candidate.has(fieldName)) {
                    issues.add(issue(index, MISSING_REQUIRED_FIELD, fieldName));
                }
            }
            validateRequiredTypes(candidate, index, issues);
            for (String fieldName : OPTIONAL_TEXT_FIELDS) {
                if (candidate.has(fieldName) && !candidate.get(fieldName).isTextual()) {
                    issues.add(issue(index, INVALID_FIELD_TYPE, fieldName));
                }
            }
        }
        return issues;
    }

    private void validateRequiredTypes(JsonNode candidate,
                                       int index,
                                       List<OrchestrationDecisionParseResult.ParseIssue> issues) {
        validateTextType(candidate, index, "decisionType", issues);
        validateTextType(candidate, index, "actionType", issues);
        validateTextType(candidate, index, "priority", issues);
        validateTextType(candidate, index, "reason", issues);
        validateNumberType(candidate, index, "confidence", issues);
        validateBooleanType(candidate, index, "requiresHumanIntervention", issues);
        validateBooleanType(candidate, index, "requiresConfirmation", issues);
        validateStringArrayType(candidate, index, "sourceUrls", issues);
        validateStringArrayType(candidate, index, "suggestedQueries", issues);
    }

    private void validateTextType(JsonNode candidate,
                                  int index,
                                  String fieldName,
                                  List<OrchestrationDecisionParseResult.ParseIssue> issues) {
        if (candidate.has(fieldName) && !candidate.get(fieldName).isTextual()) {
            issues.add(issue(index, INVALID_FIELD_TYPE, fieldName));
        }
    }

    private void validateNumberType(JsonNode candidate,
                                    int index,
                                    String fieldName,
                                    List<OrchestrationDecisionParseResult.ParseIssue> issues) {
        if (candidate.has(fieldName) && !candidate.get(fieldName).isNumber()) {
            issues.add(issue(index, INVALID_FIELD_TYPE, fieldName));
        }
    }

    private void validateBooleanType(JsonNode candidate,
                                     int index,
                                     String fieldName,
                                     List<OrchestrationDecisionParseResult.ParseIssue> issues) {
        if (candidate.has(fieldName) && !candidate.get(fieldName).isBoolean()) {
            issues.add(issue(index, INVALID_FIELD_TYPE, fieldName));
        }
    }

    private void validateStringArrayType(JsonNode candidate,
                                         int index,
                                         String fieldName,
                                         List<OrchestrationDecisionParseResult.ParseIssue> issues) {
        if (!candidate.has(fieldName)) {
            return;
        }
        JsonNode value = candidate.get(fieldName);
        if (!value.isArray()) {
            issues.add(issue(index, INVALID_FIELD_TYPE, fieldName));
            return;
        }
        for (JsonNode item : value) {
            if (!item.isTextual()) {
                issues.add(issue(index, INVALID_FIELD_TYPE, fieldName));
                return;
            }
        }
    }

    private ParsedCandidate validateCandidateFields(
            OrchestrationDecisionResponse.DecisionCandidate candidate,
            int index,
            List<OrchestrationDecisionParseResult.ParseIssue> issues) {
        String decisionType = upperText(candidate.decisionType());
        String actionType = upperText(candidate.actionType());
        String priority = upperText(candidate.priority());
        String reason = normalizeText(candidate.reason());
        String targetNode = normalizeText(candidate.targetNode());
        String targetSection = normalizeText(candidate.targetSection());
        String affectedScope = upperText(candidate.affectedScope());

        if (!allowedDecisionTypes.contains(decisionType)) {
            issues.add(issue(index, UNKNOWN_DECISION_TYPE, "decisionType"));
        }
        if (!allowedActionTypes.contains(actionType)) {
            issues.add(issue(index, UNKNOWN_ACTION_TYPE, "actionType"));
        }
        if (!PRIORITIES.contains(priority)) {
            issues.add(issue(index, INVALID_PRIORITY, "priority"));
        }
        if (candidate.confidence() == null
                || !Double.isFinite(candidate.confidence())
                || candidate.confidence() < 0.0d
                || candidate.confidence() > 1.0d) {
            issues.add(issue(index, INVALID_CONFIDENCE, "confidence"));
        }
        if (reason == null) {
            issues.add(issue(index, MISSING_REQUIRED_FIELD, "reason"));
        }
        if (!validHumanFlags(decisionType,
                candidate.requiresHumanIntervention(),
                candidate.requiresConfirmation())) {
            issues.add(issue(index,
                    INVALID_HUMAN_FLAGS,
                    "requiresHumanIntervention/requiresConfirmation"));
        }
        return new ParsedCandidate(
                decisionType,
                actionType,
                targetNode,
                targetSection,
                affectedScope,
                priority,
                reason);
    }

    /**
     * WAIT 必须显式表示人工暂停；其他动作不能伪装成“边执行边人工”。
     * rewrite/supplement 可以要求 confirmation，真实暂停门由后续 runtime 实现。
     */
    private boolean validHumanFlags(String decisionType,
                                    Boolean requiresHuman,
                                    Boolean requiresConfirmation) {
        if (requiresHuman == null || requiresConfirmation == null) {
            return false;
        }
        if ("WAIT_FOR_HUMAN".equals(decisionType)) {
            return requiresHuman && requiresConfirmation;
        }
        if (requiresHuman) {
            return false;
        }
        return !"NO_ACTION".equals(decisionType) || !requiresConfirmation;
    }

    /**
     * URL 过滤只做 exact allowlist 判断，不按相似 host、路径前缀或重定向猜测授权。
     */
    private FilteredSources filterSources(
            int decisionIndex,
            List<String> rawUrls,
            OrchestrationSourceEvidenceCatalog catalog,
            List<OrchestrationDecisionParseResult.DiscardedSourceUrl> allDiscarded) {
        LinkedHashSet<String> accepted = new LinkedHashSet<>();
        LinkedHashSet<String> discardedKeys = new LinkedHashSet<>();
        List<String> discardedValues = new ArrayList<>();
        for (String rawUrl : rawUrls) {
            String normalizedUrl = normalizeText(rawUrl);
            String displayUrl = normalizedUrl == null ? "" : normalizedUrl;
            String code = null;
            if (!OrchestrationSourceEvidenceCatalog.isValidHttpUrl(normalizedUrl)) {
                code = INVALID_SOURCE_URL;
            } else if (!catalog.contains(normalizedUrl)) {
                code = SOURCE_URL_OUTSIDE_CONTEXT;
            } else {
                accepted.add(normalizedUrl);
            }
            if (code != null && discardedKeys.add(code + "\u0000" + displayUrl)) {
                discardedValues.add(displayUrl);
                allDiscarded.add(new OrchestrationDecisionParseResult.DiscardedSourceUrl(
                        decisionIndex, displayUrl, code));
            }
        }
        return new FilteredSources(List.copyOf(accepted), List.copyOf(discardedValues));
    }

    private Map<String, Object> buildInputRefs(OrchestrationContext context, List<String> discardedUrls) {
        Map<String, Object> refs = new LinkedHashMap<>();
        refs.put("qualityDiagnosisIds", List.of());
        List<String> suggestionIds = new ArrayList<>();
        for (AgentSuggestion suggestion : safeList(context.getAgentSuggestions())) {
            if (suggestion != null && suggestion.getSuggestionId() != null && !suggestion.getSuggestionId().isBlank()) {
                suggestionIds.add(suggestion.getSuggestionId().trim());
            }
        }
        refs.put("agentSuggestionIds", List.copyOf(new LinkedHashSet<>(suggestionIds)));
        refs.put("triggerNodeName", context.getTriggerNodeName());
        refs.put("discardedSourceUrls", discardedUrls);
        return refs;
    }

    private String matrixViolationField(String violation) {
        if (OrchestrationDecisionActionMatrix.INVALID_LLM_TARGET_NODE.equals(violation)) {
            return "targetNode";
        }
        if (OrchestrationDecisionActionMatrix.INVALID_LLM_AFFECTED_SCOPE.equals(violation)) {
            return "affectedScope";
        }
        return "decisionType/actionType";
    }

    private Set<String> collectDecisionTypes(List<OrchestrationDecisionActionMatrix.ActionRule> rules) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        rules.forEach(rule -> values.add(rule.decisionType()));
        return Set.copyOf(values);
    }

    private Set<String> collectActionTypes(List<OrchestrationDecisionActionMatrix.ActionRule> rules) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        rules.forEach(rule -> values.add(rule.actionType()));
        return Set.copyOf(values);
    }

    private List<String> normalizeDistinctText(List<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : safeList(values)) {
            String item = normalizeText(value);
            if (item != null) {
                normalized.add(item);
            }
        }
        return List.copyOf(normalized);
    }

    private String upperText(String value) {
        String normalized = normalizeText(value);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }

    private String normalizeText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private OrchestrationDecisionParseResult failure(
            OrchestrationDecisionParseResult.ParseIssue issue) {
        return OrchestrationDecisionParseResult.failure(List.of(issue), List.of());
    }

    private OrchestrationDecisionParseResult.ParseIssue issue(
            Integer decisionIndex, String code, String fieldName) {
        return new OrchestrationDecisionParseResult.ParseIssue(decisionIndex, code, fieldName);
    }

    private record ParsedCandidate(
            String decisionType,
            String actionType,
            String targetNode,
            String targetSection,
            String affectedScope,
            String priority,
            String reason
    ) {
    }

    private record FilteredSources(List<String> acceptedUrls, List<String> discardedValues) {
    }

}
