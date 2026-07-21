package cn.bugstack.competitoragent.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** Task 09 验收测试唯一 fixture 读取器，避免各层各自解析并漂移人工标签。 */
final class Stage2DecisionFixtureLoader {

    static final String RESOURCE = "orchestration/decision-fixtures-v1.json";

    private final ObjectMapper objectMapper;

    Stage2DecisionFixtureLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    FixtureSuite load() throws IOException {
        try (InputStream inputStream = Thread.currentThread()
                .getContextClassLoader()
                .getResourceAsStream(RESOURCE)) {
            if (inputStream == null) {
                throw new IllegalStateException("缺少 Task 09 fixtures: " + RESOURCE);
            }
            return objectMapper.readValue(inputStream, FixtureSuite.class);
        }
    }

    FixtureCase requireCase(String caseId) throws IOException {
        return load().cases().stream()
                .filter(item -> item.caseId().equals(caseId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知 fixture: " + caseId));
    }

    record FixtureSuite(String schemaVersion, List<FixtureCase> cases) {
        FixtureSuite {
            cases = cases == null ? List.of() : List.copyOf(cases);
        }
    }

    record FixtureCase(
            String caseId,
            String description,
            OrchestrationContext context,
            PolicyConstraints policyConstraints,
            List<AcceptedPair> acceptedPairs,
            JsonNode canonicalResponse
    ) {
        FixtureCase {
            acceptedPairs = acceptedPairs == null ? List.of() : List.copyOf(acceptedPairs);
        }

        String canonicalResponseJson(ObjectMapper objectMapper) throws IOException {
            return objectMapper.writeValueAsString(canonicalResponse);
        }
    }

    record PolicyConstraints(int maxAutoDecisions, int maxSearchQueriesPerDecision) {
        DecisionPolicyRuleSet toRuleSet() {
            return DecisionPolicyRuleSet.builder()
                    .maxAutoDecisions(maxAutoDecisions)
                    .maxSearchQueriesPerDecision(maxSearchQueriesPerDecision)
                    .build()
                    .normalized();
        }
    }

    record AcceptedPair(String decisionType, String actionType) {
    }
}
