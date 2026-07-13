package cn.bugstack.competitoragent.orchestration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrchestrationDecisionResponseParserTest {

    private static final String FULL_URL = "https://example.com/full";
    private static final String PARTIAL_URL = "https://example.com/partial";

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final OrchestrationDecisionActionMatrix actionMatrix = new OrchestrationDecisionActionMatrix();
    private final OrchestrationDecisionResponseParser parser =
            new OrchestrationDecisionResponseParser(objectMapper, actionMatrix);

    @Test
    void shouldRejectNullOrBlankResponseWithStableIssue() {
        for (String response : new String[]{null, "", "   "}) {
            OrchestrationDecisionParseResult result = parse(response, baseContext());

            assertThat(result.successful()).isFalse();
            assertThat(result.decisions()).isEmpty();
            assertThat(result.issues()).containsExactly(issue(
                    null, OrchestrationDecisionResponseParser.EMPTY_LLM_RESPONSE, null));
        }
    }

    @Test
    void shouldRejectMalformedFenceTrailingTextAndDuplicateKeys() throws Exception {
        String valid = response(validCandidate("NO_ACTION", "NO_ACTION", List.of()));
        List<String> malformedResponses = List.of(
                "{not-json}",
                "```json\n" + valid + "\n```",
                valid + "\n额外解释",
                "{\"decisions\":[],\"decisions\":[]}"
        );

        for (String malformed : malformedResponses) {
            OrchestrationDecisionParseResult result = parse(malformed, baseContext());

            assertThat(result.successful()).isFalse();
            assertThat(result.decisions()).isEmpty();
            assertThat(result.issues()).containsExactly(issue(
                    null, OrchestrationDecisionResponseParser.MALFORMED_LLM_JSON, null));
        }
    }

    @Test
    void shouldRejectUnknownTopLevelAndCandidateFields() throws Exception {
        ObjectNode candidate = validCandidate("NO_ACTION", "NO_ACTION", List.of());
        candidate.put("decisionOrigin", "LLM_PRIMARY");
        ObjectNode root = root(candidate);
        root.put("modelName", "unauthorized");

        OrchestrationDecisionParseResult result = parse(objectMapper.writeValueAsString(root), baseContext());

        assertThat(result.successful()).isFalse();
        assertThat(result.decisions()).isEmpty();
        assertThat(result.issues()).containsExactly(
                issue(null, OrchestrationDecisionResponseParser.UNKNOWN_RESPONSE_FIELD, "modelName"),
                issue(0, OrchestrationDecisionResponseParser.UNKNOWN_DECISION_FIELD, "decisionOrigin"));
    }

    @Test
    void shouldRejectInvalidEnvelopeShapesAndOptionalFieldTypes() throws Exception {
        List<String> invalidEnvelopes = List.of(
                "[]",
                "{\"decisions\":{}}",
                "{\"decisions\":[\"NO_ACTION\"]}");

        for (String response : invalidEnvelopes) {
            OrchestrationDecisionParseResult result = parse(response, baseContext());

            assertThat(result.successful()).isFalse();
            assertThat(result.decisions()).isEmpty();
            assertThat(result.issues())
                    .extracting(OrchestrationDecisionParseResult.ParseIssue::code)
                    .containsExactly(OrchestrationDecisionResponseParser.INVALID_RESPONSE_SHAPE);
        }

        ObjectNode candidate = validCandidate("NO_ACTION", "NO_ACTION", List.of());
        candidate.put("targetNode", 1);
        candidate.put("targetSection", false);
        candidate.putArray("affectedScope");

        OrchestrationDecisionParseResult optionalTypeResult = parse(response(candidate), baseContext());

        assertThat(optionalTypeResult.issues()).containsExactly(
                issue(0, OrchestrationDecisionResponseParser.INVALID_FIELD_TYPE, "targetNode"),
                issue(0, OrchestrationDecisionResponseParser.INVALID_FIELD_TYPE, "targetSection"),
                issue(0, OrchestrationDecisionResponseParser.INVALID_FIELD_TYPE, "affectedScope"));
    }

    @Test
    void shouldRejectServerOwnedCandidateFields() throws Exception {
        ObjectNode candidate = validCandidate("NO_ACTION", "NO_ACTION", List.of());
        candidate.put("taskId", 999L);
        candidate.put("triggerNodeName", "attacker_node");
        candidate.put("evidenceState", "FULL_SOURCE");
        candidate.putObject("inputRefs").put("forged", true);

        OrchestrationDecisionParseResult result = parse(response(candidate), baseContext());

        assertThat(result.successful()).isFalse();
        assertThat(result.decisions()).isEmpty();
        assertThat(result.issues()).containsExactly(
                issue(0, OrchestrationDecisionResponseParser.UNKNOWN_DECISION_FIELD, "taskId"),
                issue(0, OrchestrationDecisionResponseParser.UNKNOWN_DECISION_FIELD, "triggerNodeName"),
                issue(0, OrchestrationDecisionResponseParser.UNKNOWN_DECISION_FIELD, "evidenceState"),
                issue(0, OrchestrationDecisionResponseParser.UNKNOWN_DECISION_FIELD, "inputRefs"));
    }

    @Test
    void shouldRejectMissingRequiredFieldsInsteadOfUsingDecisionDefaults() throws Exception {
        ObjectNode candidate = validCandidate("REWRITE_ONLY", "REWRITE_SECTION", List.of(FULL_URL));
        candidate.remove("sourceUrls");
        candidate.remove("reason");

        OrchestrationDecisionParseResult result = parse(response(candidate), baseContext());

        assertThat(result.successful()).isFalse();
        assertThat(result.issues()).containsExactly(
                issue(0, OrchestrationDecisionResponseParser.MISSING_REQUIRED_FIELD, "reason"),
                issue(0, OrchestrationDecisionResponseParser.MISSING_REQUIRED_FIELD, "sourceUrls"));
    }

    @Test
    void shouldRejectWrongFieldTypesAndOutOfRangeConfidence() throws Exception {
        ObjectNode wrongTypes = validCandidate("NO_ACTION", "NO_ACTION", List.of());
        wrongTypes.put("confidence", "high");
        wrongTypes.put("requiresConfirmation", "false");
        wrongTypes.put("sourceUrls", FULL_URL);

        OrchestrationDecisionParseResult typeResult = parse(response(wrongTypes), baseContext());

        assertThat(typeResult.successful()).isFalse();
        assertThat(typeResult.issues()).containsExactly(
                issue(0, OrchestrationDecisionResponseParser.INVALID_FIELD_TYPE, "confidence"),
                issue(0, OrchestrationDecisionResponseParser.INVALID_FIELD_TYPE, "requiresConfirmation"),
                issue(0, OrchestrationDecisionResponseParser.INVALID_FIELD_TYPE, "sourceUrls"));

        ObjectNode outOfRange = validCandidate("NO_ACTION", "NO_ACTION", List.of());
        outOfRange.put("confidence", 1.01d);
        OrchestrationDecisionParseResult confidenceResult = parse(response(outOfRange), baseContext());

        assertThat(confidenceResult.issues()).containsExactly(issue(
                0, OrchestrationDecisionResponseParser.INVALID_CONFIDENCE, "confidence"));
    }

    @Test
    void shouldRejectBlankReasonInvalidPriorityAndNegativeConfidenceInStableOrder() throws Exception {
        ObjectNode candidate = validCandidate("NO_ACTION", "NO_ACTION", List.of());
        candidate.put("priority", "urgent");
        candidate.put("reason", " ");
        candidate.put("confidence", -0.01d);

        OrchestrationDecisionParseResult result = parse(response(candidate), baseContext());

        assertThat(result.issues()).containsExactly(
                issue(0, OrchestrationDecisionResponseParser.INVALID_PRIORITY, "priority"),
                issue(0, OrchestrationDecisionResponseParser.INVALID_CONFIDENCE, "confidence"),
                issue(0, OrchestrationDecisionResponseParser.MISSING_REQUIRED_FIELD, "reason"));
    }

    @Test
    void shouldKeepStrictJsonFeaturesLocalToParserMapperCopy() throws Exception {
        JsonNode parsedBySharedMapper = objectMapper.readTree("{\"value\":1,\"value\":2}");

        assertThat(parsedBySharedMapper.path("value").asInt()).isEqualTo(2);
        assertThat(objectMapper.isEnabled(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS))
                .isFalse();
    }

    @Test
    void shouldRejectHumanFlagsThatConflictWithDecisionSemantics() throws Exception {
        ObjectNode wait = validCandidate("WAIT_FOR_HUMAN", "MANUAL_REVIEW", List.of());
        wait.put("requiresHumanIntervention", false);
        wait.put("requiresConfirmation", false);
        ObjectNode noAction = validCandidate("NO_ACTION", "NO_ACTION", List.of());
        noAction.put("requiresConfirmation", true);

        OrchestrationDecisionParseResult result = parse(response(wait, noAction), baseContext());

        assertThat(result.successful()).isFalse();
        assertThat(result.decisions()).isEmpty();
        assertThat(result.issues()).containsExactly(
                issue(0, OrchestrationDecisionResponseParser.INVALID_HUMAN_FLAGS,
                        "requiresHumanIntervention/requiresConfirmation"),
                issue(1, OrchestrationDecisionResponseParser.INVALID_HUMAN_FLAGS,
                        "requiresHumanIntervention/requiresConfirmation"));
    }

    @Test
    void shouldRejectEmptyDecisionsInsteadOfTreatingThemAsNoAction() throws Exception {
        OrchestrationDecisionParseResult result = parse("{\"decisions\":[]}", baseContext());

        assertThat(result.successful()).isFalse();
        assertThat(result.issues()).containsExactly(issue(
                null, OrchestrationDecisionResponseParser.EMPTY_DECISIONS, "decisions"));
    }

    @Test
    void shouldRejectNonLlmOriginAsCallerContractViolation() throws Exception {
        String response = response(validCandidate("NO_ACTION", "NO_ACTION", List.of()));

        assertThatThrownBy(() -> parser.parse(response, baseContext(), OrchestrationDecisionOrigin.RULE_ONLY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse(response, baseContext(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse(response,
                baseContext().toBuilder().taskId(null).build().normalized(),
                OrchestrationDecisionOrigin.LLM_PRIMARY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldResolveAllFiveMatrixRulesAndFillOnlyMissingDefaults() throws Exception {
        ObjectNode noAction = validCandidate(" no_action ", " no_action ", List.of(FULL_URL));
        ObjectNode supplement = validCandidate(
                "append_dynamic_branch", "supplement_evidence", List.of(FULL_URL));
        ObjectNode rewriteSection = validCandidate(
                "rewrite_only", "rewrite_section", List.of(FULL_URL));
        ObjectNode rewriteClaim = validCandidate(
                "rewrite_only", "rewrite_claim", List.of(FULL_URL));
        ObjectNode manual = validCandidate("wait_for_human", "manual_review", List.of());

        OrchestrationDecisionParseResult result = parse(
                response(noAction, supplement, rewriteSection, rewriteClaim, manual), baseContext());

        assertThat(result.successful()).isTrue();
        assertThat(result.decisions()).hasSize(5);
        assertThat(result.decisions())
                .extracting(OrchestrationDecision::getDecisionType)
                .containsExactly("NO_ACTION", "APPEND_DYNAMIC_BRANCH", "REWRITE_ONLY", "REWRITE_ONLY", "WAIT_FOR_HUMAN");
        assertThat(result.decisions())
                .extracting(OrchestrationDecision::getActionType)
                .containsExactly("NO_ACTION", "SUPPLEMENT_EVIDENCE", "REWRITE_SECTION", "REWRITE_CLAIM", "MANUAL_REVIEW");
        assertThat(result.decisions())
                .extracting(OrchestrationDecision::getTargetNode)
                .containsExactly(
                        "quality_check_final", "collect_sources", "rewrite_report", "rewrite_report", "quality_check_final");
        assertThat(result.decisions())
                .extracting(OrchestrationDecision::getAffectedScope)
                .containsExactly(
                        "CURRENT_NODE_ONLY",
                        "CURRENT_NODE_AND_DOWNSTREAM",
                        "CURRENT_NODE_ONLY",
                        "CURRENT_NODE_ONLY",
                        "CURRENT_NODE_ONLY");
        assertThat(result.decisions()).allSatisfy(decision -> {
            assertThat(decision.getDecisionOrigin()).isEqualTo(OrchestrationDecisionOrigin.LLM_PRIMARY);
            assertThat(actionMatrix.validate(decision).valid()).isTrue();
        });
    }

    @Test
    void shouldRejectWrongExplicitTargetAndScopeWithoutReplacingThem() throws Exception {
        ObjectNode candidate = validCandidate(
                "REWRITE_ONLY", "REWRITE_SECTION", List.of(FULL_URL));
        candidate.put("targetNode", "collect_sources");
        candidate.put("affectedScope", "CURRENT_NODE_AND_DOWNSTREAM");

        OrchestrationDecisionParseResult result = parse(response(candidate), baseContext());

        assertThat(result.successful()).isFalse();
        assertThat(result.issues()).containsExactly(
                issue(0, OrchestrationDecisionActionMatrix.INVALID_LLM_TARGET_NODE, "targetNode"),
                issue(0, OrchestrationDecisionActionMatrix.INVALID_LLM_AFFECTED_SCOPE, "affectedScope"));
    }

    @Test
    void shouldRejectUnknownAndCrossedPairsIncludingLegacyOnlyActions() throws Exception {
        ObjectNode unknown = validCandidate("FREE_FORM_DAG", "FREE_FORM_ACTION", List.of());
        ObjectNode crossed = validCandidate("REWRITE_ONLY", "SUPPLEMENT_EVIDENCE", List.of(FULL_URL));
        ObjectNode legacy = validCandidate("RERUN_NODE", "RERUN_NODE", List.of(FULL_URL));

        OrchestrationDecisionParseResult result = parse(response(unknown, crossed, legacy), baseContext());

        assertThat(result.successful()).isFalse();
        assertThat(result.decisions()).isEmpty();
        assertThat(result.issues()).containsExactly(
                issue(0, OrchestrationDecisionResponseParser.UNKNOWN_DECISION_TYPE, "decisionType"),
                issue(0, OrchestrationDecisionResponseParser.UNKNOWN_ACTION_TYPE, "actionType"),
                issue(1, OrchestrationDecisionActionMatrix.INVALID_DECISION_ACTION_PAIR, "decisionType/actionType"),
                issue(2, OrchestrationDecisionResponseParser.UNKNOWN_DECISION_TYPE, "decisionType"),
                issue(2, OrchestrationDecisionResponseParser.UNKNOWN_ACTION_TYPE, "actionType"));
    }

    @Test
    void shouldFailAtomicallyWhenAnyCandidateIsInvalid() throws Exception {
        ObjectNode valid = validCandidate("NO_ACTION", "NO_ACTION", List.of());
        ObjectNode invalid = validCandidate("REWRITE_ONLY", "SUPPLEMENT_EVIDENCE", List.of(FULL_URL));

        OrchestrationDecisionParseResult result = parse(response(valid, invalid), baseContext());

        assertThat(result.successful()).isFalse();
        assertThat(result.decisions()).isEmpty();
        assertThat(result.issues()).containsExactly(issue(
                1,
                OrchestrationDecisionActionMatrix.INVALID_DECISION_ACTION_PAIR,
                "decisionType/actionType"));
    }

    @Test
    void shouldPopulateServerOwnedFieldsWithoutAcceptingModelOverrides() throws Exception {
        OrchestrationContext context = baseContext();
        ObjectNode candidate = validCandidate(
                "APPEND_DYNAMIC_BRANCH", "SUPPLEMENT_EVIDENCE", List.of(FULL_URL));

        OrchestrationDecision decision = parser.parse(
                        response(candidate), context, OrchestrationDecisionOrigin.LLM_SHADOW)
                .decisions()
                .get(0);

        assertThat(decision.getDecisionId()).isEqualTo("od-501-quality_check_final-llm-1");
        assertThat(decision.getTaskId()).isEqualTo(501L);
        assertThat(decision.getTriggerNodeName()).isEqualTo("quality_check_final");
        assertThat(decision.getDecisionOrigin()).isEqualTo(OrchestrationDecisionOrigin.LLM_SHADOW);
        assertThat(decision.getDecisionMetadata().isFallbackUsed()).isFalse();
        assertThat(decision.getDecisionMetadata().getFallbackReason()).isNull();
        assertThat(decision.getInputRefs())
                .containsEntry("triggerNodeName", "quality_check_final")
                .containsEntry("agentSuggestionIds", List.of("as-501-quality-1"))
                .containsEntry("discardedSourceUrls", List.of());
    }

    @Test
    void shouldFilterInventedAndInvalidUrlsWhileKeepingParseSuccessful() throws Exception {
        ObjectNode candidate = validCandidate(
                "APPEND_DYNAMIC_BRANCH",
                "SUPPLEMENT_EVIDENCE",
                List.of(FULL_URL, "https://outside.example/fake", "javascript:alert(1)", "https://outside.example/fake"));

        OrchestrationDecisionParseResult result = parse(response(candidate), baseContext());

        assertThat(result.successful()).isTrue();
        assertThat(result.decisions().get(0).getSourceUrls()).containsExactly(FULL_URL);
        assertThat(result.discardedSourceUrls()).containsExactly(
                new OrchestrationDecisionParseResult.DiscardedSourceUrl(
                        0, "https://outside.example/fake", OrchestrationDecisionResponseParser.SOURCE_URL_OUTSIDE_CONTEXT),
                new OrchestrationDecisionParseResult.DiscardedSourceUrl(
                        0, "javascript:alert(1)", OrchestrationDecisionResponseParser.INVALID_SOURCE_URL));
        assertThat(result.decisions().get(0).getInputRefs())
                .containsEntry("discardedSourceUrls", List.of("https://outside.example/fake", "javascript:alert(1)"));
    }

    @Test
    void shouldAggregateSameUrlAcrossOwnersConservativelyAndIndependentlyOfOwnerOrder() throws Exception {
        String sharedUrl = "https://example.com/shared";
        AgentSuggestion partialOwner = suggestion("as-partial", sharedUrl, EvidenceState.PARTIAL_SOURCE);
        AgentSuggestion fullOwner = suggestion("as-full", sharedUrl, EvidenceState.FULL_SOURCE);
        OrchestrationContext firstOrder = contextWithSources(
                List.of(sharedUrl), EvidenceState.FULL_SOURCE, List.of(partialOwner, fullOwner));
        OrchestrationContext reverseOrder = contextWithSources(
                List.of(sharedUrl), EvidenceState.FULL_SOURCE, List.of(fullOwner, partialOwner));
        String response = response(validCandidate(
                "REWRITE_ONLY", "REWRITE_SECTION", List.of(sharedUrl)));

        EvidenceState firstState = parse(response, firstOrder).decisions().get(0).getEvidenceState();
        EvidenceState reverseState = parse(response, reverseOrder).decisions().get(0).getEvidenceState();

        assertThat(firstState).isEqualTo(EvidenceState.PARTIAL_SOURCE);
        assertThat(reverseState).isEqualTo(EvidenceState.PARTIAL_SOURCE);
    }

    @Test
    void shouldUseMostConservativeStateAcrossSelectedUrls() throws Exception {
        OrchestrationContext context = contextWithSources(
                List.of(FULL_URL),
                EvidenceState.FULL_SOURCE,
                List.of(suggestion("as-partial", PARTIAL_URL, EvidenceState.PARTIAL_SOURCE)));
        ObjectNode mixed = validCandidate(
                "REWRITE_ONLY", "REWRITE_CLAIM", List.of(FULL_URL, PARTIAL_URL));
        ObjectNode fullOnly = validCandidate(
                "REWRITE_ONLY", "REWRITE_CLAIM", List.of(FULL_URL));

        OrchestrationDecisionParseResult mixedResult = parse(response(mixed), context);
        OrchestrationDecisionParseResult fullResult = parse(response(fullOnly), context);

        assertThat(mixedResult.decisions().get(0).getEvidenceState()).isEqualTo(EvidenceState.PARTIAL_SOURCE);
        assertThat(fullResult.decisions().get(0).getEvidenceState()).isEqualTo(EvidenceState.FULL_SOURCE);
    }

    @Test
    void shouldMarkFilteredEmptySourcesAsMissingAndExposeImmutableCollections() throws Exception {
        ObjectNode candidate = validCandidate(
                "WAIT_FOR_HUMAN", "MANUAL_REVIEW", List.of("https://outside.example/fake"));
        OrchestrationDecisionParseResult success = parse(response(candidate), baseContext());

        assertThat(success.successful()).isTrue();
        assertThat(success.decisions().get(0).getSourceUrls()).isEmpty();
        assertThat(success.decisions().get(0).getEvidenceState()).isEqualTo(EvidenceState.MISSING_SOURCE);
        assertThatThrownBy(() -> success.decisions().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> success.discardedSourceUrls().clear()).isInstanceOf(UnsupportedOperationException.class);

        OrchestrationDecisionParseResult failure = parse("", baseContext());
        assertThatThrownBy(() -> failure.issues().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    private OrchestrationDecisionParseResult parse(String response, OrchestrationContext context) {
        return parser.parse(response, context, OrchestrationDecisionOrigin.LLM_PRIMARY);
    }

    private OrchestrationContext baseContext() {
        return contextWithSources(
                List.of(FULL_URL),
                EvidenceState.FULL_SOURCE,
                List.of(suggestion("as-501-quality-1", FULL_URL, EvidenceState.FULL_SOURCE)));
    }

    private OrchestrationContext contextWithSources(List<String> sourceUrls,
                                                     EvidenceState evidenceState,
                                                     List<AgentSuggestion> suggestions) {
        return OrchestrationContext.builder()
                .taskId(501L)
                .triggerNodeName("quality_check_final")
                .agentSuggestions(suggestions)
                .sourceUrls(sourceUrls)
                .evidenceState(evidenceState)
                .build()
                .normalized();
    }

    private AgentSuggestion suggestion(String id, String sourceUrl, EvidenceState evidenceState) {
        return AgentSuggestion.builder()
                .suggestionId(id)
                .producerNodeName("quality_check_final")
                .producerAgentType("REVIEWER")
                .suggestionType("CITATION_GAP")
                .summary("需要处理来源问题")
                .severity("HIGH")
                .sourceUrls(List.of(sourceUrl))
                .evidenceState(evidenceState)
                .build();
    }

    private ObjectNode validCandidate(String decisionType, String actionType, List<String> sourceUrls) {
        ObjectNode candidate = objectMapper.createObjectNode();
        candidate.put("decisionType", decisionType);
        candidate.put("actionType", actionType);
        candidate.put("priority", "HIGH");
        candidate.put("reason", "根据当前证据状态选择下一步动作。");
        candidate.put("confidence", 0.8d);
        boolean manual = "WAIT_FOR_HUMAN".equals(decisionType.trim().toUpperCase())
                && "MANUAL_REVIEW".equals(actionType.trim().toUpperCase());
        candidate.put("requiresHumanIntervention", manual);
        candidate.put("requiresConfirmation", manual);
        ArrayNode sources = candidate.putArray("sourceUrls");
        sourceUrls.forEach(sources::add);
        candidate.putArray("suggestedQueries");
        return candidate;
    }

    private String response(ObjectNode... candidates) throws JsonProcessingException {
        return objectMapper.writeValueAsString(root(candidates));
    }

    private ObjectNode root(ObjectNode... candidates) {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode decisions = root.putArray("decisions");
        for (ObjectNode candidate : candidates) {
            decisions.add(candidate);
        }
        return root;
    }

    private OrchestrationDecisionParseResult.ParseIssue issue(Integer index, String code, String field) {
        return new OrchestrationDecisionParseResult.ParseIssue(index, code, field);
    }
}
