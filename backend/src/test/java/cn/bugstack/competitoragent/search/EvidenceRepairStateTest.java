package cn.bugstack.competitoragent.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceRepairStateTest {

    @Test
    void shouldDistinguishRepairQueryFromPromotedEvidence() {
        EvidenceRepairPlan plan = EvidenceRepairPlan.builder()
                .state(EvidenceRepairState.REPAIR_QUERY_PROPOSED)
                .repairQueries(List.of("site:open.bilibili.com ???? API"))
                .reason("AUTH_OR_CAPTCHA_GATE")
                .build();

        assertThat(plan.isComplete()).isFalse();

        EvidenceRepairPlan promoted = plan.toBuilder()
                .state(EvidenceRepairState.REPAIR_EVIDENCE_PROMOTED)
                .promotedUrls(List.of("https://open.bilibili.com/doc/4/feb66f99"))
                .build();

        assertThat(promoted.isComplete()).isTrue();
    }
}
