package cn.bugstack.competitoragent.workflow;

import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CollectorEvidenceReadinessPolicyTest {

    private final CollectorEvidenceReadinessPolicy policy = new CollectorEvidenceReadinessPolicy(new ObjectMapper());

    @Test
    void shouldWaitWhenAnyCollectorHasNotReachedTerminalStatus() {
        CollectorEvidenceReadiness readiness = policy.evaluate(List.of(
                collector("collect_sources_01_01", TaskNodeStatus.SUCCESS, "OFFICIAL",
                        List.of("https://www.linear.app", "https://www.linear.app/features"), true),
                collector("collect_sources_01_02", TaskNodeStatus.RUNNING, "DOCS",
                        List.of(), false),
                collector("collect_sources_01_03", TaskNodeStatus.SUCCESS, "PRICING",
                        List.of("https://www.linear.app/pricing"), true),
                collector("collect_sources_01_04", TaskNodeStatus.SUCCESS, "REVIEW",
                        List.of("https://www.g2.com/products/linear/reviews", "https://www.capterra.com/p/linear"), true)
        ));

        assertThat(readiness.ready()).isFalse();
        assertThat(readiness.reason()).isEqualTo("WAITING_COLLECTOR_TERMINAL_STATUS");
        assertThat(readiness.missingFamilies()).contains("DOCS");
    }

    @Test
    void shouldAllowStageOneQuorumWhenDocsFailedButOfficialPricingAndReviewEvidenceIsEnough() {
        CollectorEvidenceReadiness readiness = policy.evaluate(List.of(
                collector("collect_sources_01_01", TaskNodeStatus.SUCCESS, "OFFICIAL",
                        List.of("https://www.linear.app", "https://www.linear.app/features"), true),
                collector("collect_sources_01_02", TaskNodeStatus.FAILED, "DOCS",
                        List.of("https://linear.app/docs"), false),
                collector("collect_sources_01_03", TaskNodeStatus.SUCCESS, "PRICING",
                        List.of("https://www.linear.app/pricing"), true),
                collector("collect_sources_01_04", TaskNodeStatus.SUCCESS_DEGRADED, "REVIEW",
                        List.of("https://www.g2.com/products/linear/reviews", "https://www.capterra.com/p/linear"), true)
        ));

        assertThat(readiness.ready()).isTrue();
        assertThat(readiness.degraded()).isTrue();
        assertThat(readiness.reason()).isEqualTo("STAGE1_COLLECTOR_QUORUM_READY");
        assertThat(readiness.satisfiedFamilies()).contains("OFFICIAL", "PRICING", "REVIEW");
        assertThat(readiness.missingFamilies()).contains("DOCS");
        assertThat(readiness.sourceUrls()).hasSizeGreaterThanOrEqualTo(5);
    }

    @Test
    void shouldAllowStageOneQuorumWithoutPricingWhenCoreSourceEvidenceIsEnough() {
        CollectorEvidenceReadiness readiness = policy.evaluate(List.of(
                collector("collect_sources_01_01", TaskNodeStatus.SUCCESS, "OFFICIAL",
                        List.of(
                                "https://www.linear.app",
                                "https://linear.app/features",
                                "https://linear.app/customers"
                        ), true),
                collector("collect_sources_01_04", TaskNodeStatus.SUCCESS_DEGRADED, "REVIEW",
                        List.of(
                                "https://www.g2.com/products/linear/reviews",
                                "https://www.capterra.com/p/linear"
                        ), true)
        ));

        assertThat(readiness.ready()).isTrue();
        assertThat(readiness.degraded()).isTrue();
        assertThat(readiness.reason()).isEqualTo("STAGE1_COLLECTOR_QUORUM_READY");
        assertThat(readiness.satisfiedFamilies()).containsExactly("OFFICIAL", "REVIEW");
        assertThat(readiness.missingFamilies()).contains("PRICING", "DOCS");
        assertThat(readiness.auditFlags()).contains("OPTIONAL_PRICING_NOT_READY");
    }

    @Test
    void shouldRejectQuorumWhenTraceableUrlsCollapseToSingleDomain() {
        CollectorEvidenceReadiness readiness = policy.evaluate(List.of(
                collector("collect_sources_01_01", TaskNodeStatus.SUCCESS, "OFFICIAL",
                        List.of(
                                "https://www.linear.app",
                                "https://linear.app/features"
                        ), true),
                collector("collect_sources_01_03", TaskNodeStatus.SUCCESS, "PRICING",
                        List.of("https://www.linear.app/pricing"), true),
                collector("collect_sources_01_04", TaskNodeStatus.SUCCESS, "REVIEW",
                        List.of(
                                "https://linear.app/reviews",
                                "https://www.linear.app/customers"
                        ), true)
        ));

        assertThat(readiness.ready()).isFalse();
        assertThat(readiness.reason()).isEqualTo("STAGE1_COLLECTOR_QUORUM_NOT_READY");
        assertThat(readiness.auditFlags()).contains("SOURCE_URLS_REDLINE_NOT_READY");
    }

    private TaskNode collector(String nodeName,
                               TaskNodeStatus status,
                               String sourceType,
                               List<String> sourceUrls,
                               boolean readyForQuorum) {
        return TaskNode.builder()
                .taskId(17L)
                .nodeName(nodeName)
                .displayName(nodeName)
                .agentType(AgentType.COLLECTOR)
                .status(status)
                .outputData("""
                        {
                          "sourceType": "%s",
                          "readyForQuorum": %s,
                          "sourceUrls": %s,
                          "degradationReasons": %s
                        }
                        """.formatted(
                        sourceType,
                        readyForQuorum,
                        toJsonArray(sourceUrls),
                        status == TaskNodeStatus.SUCCESS_DEGRADED || status == TaskNodeStatus.FAILED
                                ? "[\"HARD_DEADLINE_REACHED\"]"
                                : "[]"))
                .build();
    }

    private String toJsonArray(List<String> values) {
        return values.stream()
                .map(value -> "\"" + value + "\"")
                .toList()
                .toString();
    }
}
