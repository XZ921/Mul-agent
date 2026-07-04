package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.workflow.coverage.FieldEvidenceQuery;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class FieldEvidenceQueryExecutionGateTest {

    @Test
    void shouldReserveThirdPartyQueryWhenOfficialPriorityComesFirst() {
        FieldEvidenceQueryExecutionGate gate = new FieldEvidenceQueryExecutionGate();
        List<FieldEvidenceQuery> planned = List.of(
                query("pricing", "OFFICIAL", 0, "official-1"),
                query("pricing", "DOCS", 1, "docs-1"),
                query("pricing", "PRICING", 2, "pricing-1"),
                query("pricing", "REVIEW", 9, "review-1")
        );

        FieldEvidenceQueryExecutionPlan plan = gate.resolve(planned, 3, 1, 24);

        assertThat(plan.executable()).hasSize(3);
        assertThat(plan.executable()).extracting(FieldEvidenceQuery::getSourceType)
                .contains("REVIEW");
        assertThat(plan.skipped()).hasSize(1);
        assertThat(plan.skipReasons()).containsEntry("SKIPPED_FIELD_SOURCE_QUOTA_EXHAUSTED", 1);
    }

    @Test
    void shouldApplyPerFieldQuotaBeforeGlobalFailSafe() {
        FieldEvidenceQueryExecutionGate gate = new FieldEvidenceQueryExecutionGate();
        List<FieldEvidenceQuery> planned = new ArrayList<>();
        for (int fieldIndex = 0; fieldIndex < 10; fieldIndex++) {
            String fieldName = "field-" + fieldIndex;
            planned.add(query(fieldName, "OFFICIAL", 0, fieldName + "-official"));
            planned.add(query(fieldName, "DOCS", 1, fieldName + "-docs"));
            planned.add(query(fieldName, "NEWS", 2, fieldName + "-news"));
            planned.add(query(fieldName, "REVIEW", 3, fieldName + "-review"));
        }

        FieldEvidenceQueryExecutionPlan plan = gate.resolve(planned, 3, 1, 24);

        assertThat(plan.executable()).hasSizeLessThanOrEqualTo(24);
        Map<String, Long> byField = plan.executable().stream()
                .collect(Collectors.groupingBy(FieldEvidenceQuery::getFieldName, Collectors.counting()));
        assertThat(byField.values()).allMatch(count -> count <= 3L);
        assertThat(plan.skipReasons()).containsKey("SKIPPED_NODE_QUERY_CAP_EXHAUSTED");
    }

    private FieldEvidenceQuery query(String fieldName,
                                     String sourceType,
                                     Integer priority,
                                     String fingerprint) {
        return FieldEvidenceQuery.builder()
                .fieldName(fieldName)
                .sourceType(sourceType)
                .priority(priority)
                .queryFingerprint(fingerprint)
                .query(fieldName + " " + sourceType + " query")
                .reason("字段证据配额测试")
                .evidencePathKey(fieldName + "_PATH")
                .queryIntent("TEST_INTENT")
                .build();
    }
}
