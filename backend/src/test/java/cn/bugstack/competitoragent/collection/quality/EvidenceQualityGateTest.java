package cn.bugstack.competitoragent.collection.quality;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceQualityGateTest {

    @Test
    void shouldExposeRepairRequiredForAuthGate() {
        EvidenceQualityVerdict verdict = EvidenceQualityVerdict.builder()
                .sourceAuthenticityScore(0.90D)
                .contentUsabilityScore(0.20D)
                .taskRelevanceScore(0.10D)
                .evidenceUsabilityScore(0.20D)
                .issues(List.of(EvidenceQualityIssue.AUTH_OR_CAPTCHA_GATE))
                .qualitySignals(List.of("AUTH_GATE_DETECTED", "EVIDENCE_REPAIR_REQUIRED"))
                .repairRequired(true)
                .build();

        assertThat(verdict.isRepairRequired()).isTrue();
        assertThat(verdict.getIssues()).contains(EvidenceQualityIssue.AUTH_OR_CAPTCHA_GATE);
        assertThat(verdict.getEvidenceUsabilityScore()).isEqualTo(0.20D);
    }

    @Test
    void shouldCapAuthGateEvidenceEvenWhenSourceIsOfficial() {
        EvidenceQualityGate gate = new EvidenceQualityGate(new EvidenceQualityGateProperties());

        EvidenceQualityVerdict verdict = gate.evaluate(
                EvidenceQualityContext.builder()
                        .url("https://open.bilibili.com")
                        .sourceType("OFFICIAL")
                        .fieldName("coreFeatures")
                        .evidencePathKey("DOCS_API_GUIDE")
                        .expectedSignals(List.of("DEVELOPER_DOCS_BLOCK", "API", "SDK"))
                        .build(),
                "[主站] 开放平台 登录|注册 智能验证检测中 由极验提供技术支持",
                List.of("OFFICIAL_DOMAIN_MATCHED"),
                0.92D);

        assertThat(verdict.getIssues()).contains(EvidenceQualityIssue.AUTH_OR_CAPTCHA_GATE);
        assertThat(verdict.getContentUsabilityScore()).isLessThanOrEqualTo(0.20D);
        assertThat(verdict.getQualitySignals()).contains("AUTH_GATE_DETECTED", "EVIDENCE_REPAIR_REQUIRED");
    }

    @Test
    void shouldDetectHighTrustLowUsabilityContradiction() {
        EvidenceQualityGate gate = new EvidenceQualityGate(new EvidenceQualityGateProperties());

        EvidenceQualityVerdict verdict = gate.evaluate(
                EvidenceQualityContext.builder()
                        .url("https://open.bilibili.com")
                        .sourceType("OFFICIAL")
                        .fieldName("coreFeatures")
                        .evidencePathKey("DOCS_API_GUIDE")
                        .expectedSignals(List.of("DEVELOPER_DOCS_BLOCK", "API", "SDK"))
                        .build(),
                "开放平台 文档中心 管理中心 登录 注册 帮助中心 联系我们 友情链接",
                List.of("OFFICIAL_DOMAIN_MATCHED", "HIGH_TRUST"),
                0.92D);

        assertThat(verdict.getIssues()).contains(EvidenceQualityIssue.HIGH_TRUST_LOW_USABILITY);
        assertThat(verdict.getQualitySignals()).contains("SCORE_CONTRADICTION_DETECTED");
    }

    @Test
    void shouldRequireFieldSignalsForBusinessRelevance() {
        EvidenceQualityGate gate = new EvidenceQualityGate(new EvidenceQualityGateProperties());

        EvidenceQualityVerdict verdict = gate.evaluate(
                EvidenceQualityContext.builder()
                        .url("https://open.example.com/docs/api")
                        .sourceType("DOCS")
                        .fieldName("pricing")
                        .evidencePathKey("DOCS_BILLING_OR_LIMITS")
                        .expectedSignals(List.of("计费", "定价", "免费配额", "billing", "pricing"))
                        .build(),
                "开放平台提供 API 和 SDK 能力，开发者可以完成授权登录和用户管理。",
                List.of("TAVILY_RAW_CONTENT_READY"),
                0.88D);

        assertThat(verdict.getQualitySignals()).contains("FIELD_RELEVANCE_WEAK", "EVIDENCE_REPAIR_REQUIRED");
        assertThat(verdict.getTaskRelevanceScore()).isLessThan(0.50D);
    }

    @Test
    void shouldFallbackToNodeCoverageViewWhenFieldPathContextIsNotAvailableYet() {
        EvidenceQualityGate gate = new EvidenceQualityGate(new EvidenceQualityGateProperties());

        EvidenceQualityVerdict verdict = gate.evaluate(
                EvidenceQualityContext.builder()
                        .url("https://open.example.com")
                        .sourceType("OFFICIAL")
                        .requiredCoverageFields(List.of("pricing"))
                        .blockingCoverageFields(List.of("pricing"))
                        .coverageQueryIntents(List.of("OFFICIAL_PRICING", "DOCS_BILLING"))
                        .build(),
                "开放平台提供 API 和 SDK 能力，开发者可以完成授权登录和用户管理。",
                List.of("OFFICIAL_DOMAIN_MATCHED"),
                0.92D);

        assertThat(verdict.getQualitySignals())
                .contains("FIELD_CONTEXT_FALLBACK_FROM_NODE_CONFIG", "FIELD_RELEVANCE_WEAK", "EVIDENCE_REPAIR_REQUIRED");
        assertThat(verdict.getTaskRelevanceScore()).isLessThan(0.50D);
    }
}
