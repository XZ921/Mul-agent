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

    @Test
    void shouldRoundRobinExecutableOrderAcrossFieldsBeforeProviderConsumesShortBudget() {
        FieldEvidenceQueryExecutionGate gate = new FieldEvidenceQueryExecutionGate();
        List<FieldEvidenceQuery> planned = List.of(
                query("coreFeatures", "DOCS", 10, "core-docs-1"),
                query("coreFeatures", "DOCS", 20, "core-docs-2"),
                query("pricing", "DOCS", 30, "pricing-docs-1"),
                query("pricing", "DOCS", 40, "pricing-docs-2")
        );

        FieldEvidenceQueryExecutionPlan plan = gate.resolve(planned, 2, 0, 8);

        assertThat(plan.executable())
                .extracting(FieldEvidenceQuery::getQueryFingerprint)
                .containsExactly("core-docs-1", "pricing-docs-1", "core-docs-2", "pricing-docs-2");
    }

    @Test
    void shouldSpreadFirstProviderSlotsAcrossFieldsWhenOnlyFewQueriesCanRun() {
        FieldEvidenceQueryExecutionGate gate = new FieldEvidenceQueryExecutionGate();
        List<FieldEvidenceQuery> planned = new ArrayList<>();
        for (int fieldIndex = 0; fieldIndex < 7; fieldIndex++) {
            String fieldName = "field-" + fieldIndex;
            planned.add(query(fieldName, "DOCS", fieldIndex * 10 + 1, fieldName + "-docs-1"));
            planned.add(query(fieldName, "OFFICIAL", fieldIndex * 10 + 2, fieldName + "-official-1"));
            planned.add(query(fieldName, "REVIEW", fieldIndex * 10 + 3, fieldName + "-review-1"));
            planned.add(query(fieldName, "NEWS", fieldIndex * 10 + 4, fieldName + "-news-1"));
        }

        FieldEvidenceQueryExecutionPlan plan = gate.resolve(planned, 3, 1, 24);

        assertThat(plan.executable()).hasSize(21);
        assertThat(plan.executable().stream()
                .limit(4)
                .map(FieldEvidenceQuery::getFieldName)
                .distinct()
                .count()).isEqualTo(4L);
        assertThat(plan.skipReasons()).containsEntry("SKIPPED_FIELD_SOURCE_QUOTA_EXHAUSTED", 7);
    }

    @Test
    void shouldSkipQueryWhenFingerprintAlreadyClaimedBySiblingCollector() {
        FieldEvidenceQueryExecutionGate gate = new FieldEvidenceQueryExecutionGate();
        List<FieldEvidenceQuery> planned = List.of(
                query("summary", "OFFICIAL", 0, "summary-official-1"),
                query("summary", "DOCS", 1, "summary-docs-1"),
                query("pricing", "OFFICIAL", 2, "pricing-official-1")
        );

        java.util.Set<String> claimSet = java.util.concurrent.ConcurrentHashMap.newKeySet();
        claimSet.add("summary-official-1");

        FieldEvidenceQueryExecutionPlan plan = gate.resolve(
                planned,
                3,
                0,
                24,
                claimSet
        );

        assertThat(plan.executable())
                .extracting(FieldEvidenceQuery::getQueryFingerprint)
                .containsExactly("summary-docs-1", "pricing-official-1");
        assertThat(plan.skipped())
                .extracting(FieldEvidenceQuery::getQueryFingerprint)
                .contains("summary-official-1");
        assertThat(plan.skipReasons())
                .containsEntry("SKIPPED_CROSS_NODE_DEDUP", 1);
        assertThat(plan.claimedFingerprints())
                .containsExactly("summary-docs-1", "pricing-official-1");
        assertThat(claimSet)
                .contains("summary-official-1", "summary-docs-1", "pricing-official-1");
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
