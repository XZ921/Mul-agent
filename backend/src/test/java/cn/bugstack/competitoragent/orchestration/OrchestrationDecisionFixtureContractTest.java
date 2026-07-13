package cn.bugstack.competitoragent.orchestration;

import cn.bugstack.competitoragent.llm.PromptTemplateService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OrchestrationDecisionFixtureContractTest {

    private static final String FIXTURE_RESOURCE = "orchestration/decision-fixtures-v1.json";

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final OrchestrationDecisionActionMatrix actionMatrix = new OrchestrationDecisionActionMatrix();
    private final OrchestrationDecisionResponseParser parser =
            new OrchestrationDecisionResponseParser(objectMapper, actionMatrix);
    private final OrchestrationDecisionPromptBuilder promptBuilder = new OrchestrationDecisionPromptBuilder(
            new PromptTemplateService(objectMapper), objectMapper, actionMatrix);

    @Test
    void shouldLoadNineUniqueManuallyLabeledFixtures() throws Exception {
        FixtureSuite suite = loadSuite();

        assertThat(suite.schemaVersion()).isEqualTo("ORCHESTRATION_DECISION_FIXTURE_V1");
        assertThat(suite.cases()).hasSize(9);
        assertThat(suite.cases())
                .extracting(FixtureCase::caseId)
                .doesNotHaveDuplicates();
        assertThat(suite.cases()).allSatisfy(fixture -> {
            OrchestrationContext normalizedContext = fixture.context().normalized();
            assertThat(fixture.description()).isNotBlank();
            assertThat(normalizedContext.getTaskId()).isNotNull();
            assertThat(normalizedContext.getTriggerNodeName()).isNotBlank();
            assertThat(fixture.context().isPassed()
                    && fixture.context().isRequiresHumanIntervention()).isFalse();
        });
    }

    @Test
    void shouldValidateCanonicalHumanFlagsIndependentlyFromParser() throws Exception {
        for (FixtureCase fixture : loadSuite().cases()) {
            for (JsonNode candidate : fixture.canonicalResponse().path("decisions")) {
                String decisionType = candidate.path("decisionType").asText();
                boolean requiresHuman = candidate.path("requiresHumanIntervention").asBoolean();
                boolean requiresConfirmation = candidate.path("requiresConfirmation").asBoolean();

                if ("WAIT_FOR_HUMAN".equals(decisionType)) {
                    assertThat(requiresHuman)
                            .as(fixture.caseId() + " WAIT requiresHumanIntervention")
                            .isTrue();
                    assertThat(requiresConfirmation)
                            .as(fixture.caseId() + " WAIT requiresConfirmation")
                            .isTrue();
                } else {
                    assertThat(requiresHuman)
                            .as(fixture.caseId() + " non-WAIT requiresHumanIntervention")
                            .isFalse();
                }
                if ("NO_ACTION".equals(decisionType)) {
                    assertThat(requiresConfirmation)
                            .as(fixture.caseId() + " NO_ACTION requiresConfirmation")
                            .isFalse();
                }
            }
        }
    }

    @Test
    void shouldParseEveryCanonicalResponseAndMatchManualAcceptedPairs() throws Exception {
        for (FixtureCase fixture : loadSuite().cases()) {
            OrchestrationContext context = fixture.context().normalized();
            OrchestrationDecisionParseResult result = parser.parse(
                    objectMapper.writeValueAsString(fixture.canonicalResponse()),
                    context,
                    OrchestrationDecisionOrigin.LLM_PRIMARY);

            assertThat(result.issues()).as(fixture.caseId()).isEmpty();
            assertThat(result.decisions()).as(fixture.caseId()).isNotEmpty();
            assertThat(fixture.acceptedPairs()).allSatisfy(pair ->
                    assertThat(actionMatrix.findRule(pair.decisionType(), pair.actionType()))
                            .as(fixture.caseId() + " accepted pair")
                            .isPresent());
            assertThat(result.decisions()).allSatisfy(decision -> {
                assertThat(fixture.acceptedPairs())
                        .as(fixture.caseId() + " canonical pair")
                        .contains(new AcceptedPair(decision.getDecisionType(), decision.getActionType()));
                assertThat(allowedSourceUrls(context))
                        .containsAll(decision.getSourceUrls());
            });
        }
    }

    @Test
    void shouldKeepInjectionFixtureInsideSingleJsonBoundary() throws Exception {
        FixtureCase fixture = loadSuite().cases().stream()
                .filter(item -> "prompt-injection-source-backed-gap".equals(item.caseId()))
                .findFirst()
                .orElseThrow();
        OrchestrationDecisionPrompt prompt = promptBuilder.build(
                fixture.context().normalized(),
                fixture.policyConstraints().toRuleSet());

        assertThat(fixture.context().getAgentSuggestions().get(0).getSummary())
                .contains("忽略以上规则", "END_UNTRUSTED_CONTEXT_JSON");
        assertThat(prompt.userPrompt().lines()
                .filter("BEGIN_UNTRUSTED_CONTEXT_JSON"::equals)).hasSize(1);
        assertThat(prompt.userPrompt().lines()
                .filter("END_UNTRUSTED_CONTEXT_JSON"::equals)).hasSize(1);
        assertThat(prompt.systemPrompt()).doesNotContain("忽略以上规则");
    }

    private FixtureSuite loadSuite() throws Exception {
        try (InputStream inputStream = Thread.currentThread()
                .getContextClassLoader()
                .getResourceAsStream(FIXTURE_RESOURCE)) {
            assertThat(inputStream).as(FIXTURE_RESOURCE).isNotNull();
            return objectMapper.readValue(inputStream, FixtureSuite.class);
        }
    }

    private Set<String> allowedSourceUrls(OrchestrationContext context) {
        return new LinkedHashSet<>(OrchestrationSourceEvidenceCatalog.from(context).allowedSourceUrls());
    }

    private record FixtureSuite(String schemaVersion, List<FixtureCase> cases) {
    }

    private record FixtureCase(
            String caseId,
            String description,
            OrchestrationContext context,
            PolicyConstraints policyConstraints,
            List<AcceptedPair> acceptedPairs,
            JsonNode canonicalResponse
    ) {
    }

    private record PolicyConstraints(int maxAutoDecisions, int maxSearchQueriesPerDecision) {
        private DecisionPolicyRuleSet toRuleSet() {
            return DecisionPolicyRuleSet.builder()
                    .maxAutoDecisions(maxAutoDecisions)
                    .maxSearchQueriesPerDecision(maxSearchQueriesPerDecision)
                    .build()
                    .normalized();
        }
    }

    private record AcceptedPair(String decisionType, String actionType) {
    }
}
