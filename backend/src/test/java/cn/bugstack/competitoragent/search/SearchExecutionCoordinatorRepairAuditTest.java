package cn.bugstack.competitoragent.search;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SearchExecutionCoordinatorRepairAuditTest {

    @Test
    @SuppressWarnings("unchecked")
    void shouldExposePromotedRepairStateInAuditProjection() {
        EvidenceRepairPlan repairPlan = EvidenceRepairPlan.builder()
                .state(EvidenceRepairState.REPAIR_EVIDENCE_PROMOTED)
                .reason("AUTH_OR_CAPTCHA_GATE")
                .sourceUrl("https://open.bilibili.com")
                .repairQueries(List.of("site:open.bilibili.com 用户管理 API"))
                .candidateUrls(List.of("https://open.bilibili.com/doc/4/feb66f99"))
                .promotedUrls(List.of("https://open.bilibili.com/doc/4/feb66f99"))
                .build();

        Map<String, Object> projection = SearchExecutionCoordinator.buildRepairAuditProjection(repairPlan);

        assertThat(projection).containsEntry("repairState", "REPAIR_EVIDENCE_PROMOTED");
        assertThat((List<String>) projection.get("promotedUrls"))
                .containsExactly("https://open.bilibili.com/doc/4/feb66f99");
    }
}
