package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.collection.quality.EvidenceQualityVerdict;
import cn.bugstack.competitoragent.source.SourceCandidate;
import cn.bugstack.competitoragent.source.SourceCollector;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PublicEvidenceRecoveryServiceTest {

    private final PublicEvidenceRecoveryService service = new PublicEvidenceRecoveryService();

    @Test
    void shouldGenerateSameDomainPublicRecoveryCandidatesAndPreserveFieldContext() {
        SourceCandidate blockedCandidate = SourceCandidate.builder()
                .url("https://app.example.com/login")
                .domain("app.example.com")
                .title("Example Login")
                .sourceType("OFFICIAL")
                .discoveryMethod("DIRECT_LOCATOR")
                .sourceUrls(List.of("https://app.example.com/login"))
                .qualitySignals(List.of("LOGIN_GATE_PARTIAL"))
                .build();
        SearchCollectionTarget attemptedTarget = SearchCollectionTarget.builder()
                .candidate(blockedCandidate)
                .collectedPage(SourceCollector.CollectedPage.builder()
                        .url("https://app.example.com/login")
                        .title("Example Login")
                        .content("Please login to continue.")
                        .metadata("""
                                {
                                  "canonicalUrl":"https://www.example.com/about",
                                  "openGraphUrl":"https://www.example.com/features",
                                  "ignoredMediator":"https://aiqicha.baidu.com/feedback/official?from=baidu&type=gw"
                                }
                                """)
                        .success(true)
                        .build())
                .build();
        Map<String, SearchCollectionTarget> attemptedTargets = new LinkedHashMap<>();
        attemptedTargets.put("https://app.example.com/login", attemptedTarget);

        PublicEvidenceRecoveryService.RecoveryResult result = service.recover(
                PublicEvidenceRecoveryService.RecoveryContext.builder()
                        .competitorName("Example")
                        .sourceType("OFFICIAL")
                        .fieldName("summary")
                        .evidencePathKey("OFFICIAL_PUBLIC_PROFILE")
                        .queryIntents(List.of("OFFICIAL_DOCS"))
                        .seedCandidates(List.of(blockedCandidate))
                        .attemptedTargets(attemptedTargets)
                        .build()
        );

        assertThat(result.getStatus()).isEqualTo("RECOVERY_CANDIDATES_GENERATED");
        assertThat(result.getAttemptedEvidencePaths()).containsExactly("OFFICIAL_PUBLIC_PROFILE");
        assertThat(result.getAttemptedAlternativeUrls())
                .contains("https://app.example.com/about", "https://app.example.com/download", "https://app.example.com/app");
        assertThat(result.getCandidates())
                .extracting(SourceCandidate::getUrl)
                .contains(
                        "https://app.example.com/about",
                        "https://app.example.com/help",
                        "https://app.example.com/download",
                        "https://app.example.com/app",
                        "https://example.com/about",
                        "https://example.com/features"
                )
                .doesNotContain("https://aiqicha.baidu.com/feedback/official?from=baidu&type=gw");
        assertThat(result.getCandidates()).allSatisfy(candidate -> {
            assertThat(candidate.getSourceUrls()).contains("https://app.example.com/login");
            assertThat(candidate.getQualitySignals()).contains("PUBLIC_EVIDENCE_RECOVERY");
            assertThat(candidate.getReason()).contains("OFFICIAL_PUBLIC_PROFILE");
        });
        assertThat(result.getFieldName()).isEqualTo("summary");
        assertThat(result.getEvidencePathKey()).isEqualTo("OFFICIAL_PUBLIC_PROFILE");
        assertThat(result.getQueryIntents()).containsExactly("OFFICIAL_DOCS");
    }

    @Test
    void shouldPrioritizePricingRecoveryPathsWhenPricingEvidenceIsRequested() {
        SourceCandidate blockedCandidate = SourceCandidate.builder()
                .url("https://example.com/login")
                .domain("example.com")
                .title("Example Pricing Login")
                .sourceType("PRICING")
                .discoveryMethod("DIRECT_LOCATOR")
                .sourceUrls(List.of("https://example.com/login"))
                .qualitySignals(List.of("LOGIN_GATE_PARTIAL"))
                .build();

        PublicEvidenceRecoveryService.RecoveryResult result = service.recover(
                PublicEvidenceRecoveryService.RecoveryContext.builder()
                        .competitorName("Example")
                        .sourceType("PRICING")
                        .fieldName("pricing")
                        .evidencePathKey("OFFICIAL_PRICING_PAGE")
                        .queryIntents(List.of("OFFICIAL_PRICING", "DOCS_BILLING"))
                        .seedCandidates(List.of(blockedCandidate))
                        .attemptedTargets(Map.of())
                        .build()
        );

        assertThat(result.getAttemptedEvidencePaths()).containsExactly("OFFICIAL_PRICING_PAGE");
        assertThat(result.getCandidates())
                .extracting(SourceCandidate::getUrl)
                .containsSequence(
                        "https://example.com/pricing",
                        "https://example.com/plans",
                        "https://example.com/billing"
                );
        assertThat(result.getAttemptedAlternativeUrls())
                .contains("https://example.com/pricing", "https://example.com/billing");
    }

    @Test
    void shouldRejectRecoveryCandidatesWithoutCompetitorDomainOwnershipSignal() {
        SourceCandidate blockedCandidate = SourceCandidate.builder()
                .url("https://apps.microsoft.com/detail/xpffsrj7q4n302?launch=true&hl=zh-CN&gl=CN")
                .domain("apps.microsoft.com")
                .title("Download Douyin")
                .sourceType("OFFICIAL")
                .discoveryMethod("QIANFAN_SEARCH")
                .sourceUrls(List.of("https://apps.microsoft.com/detail/xpffsrj7q4n302?launch=true&hl=zh-CN&gl=CN"))
                .qualitySignals(List.of("NAVIGATION_SHELL"))
                .build();

        PublicEvidenceRecoveryService.RecoveryResult result = service.recover(
                PublicEvidenceRecoveryService.RecoveryContext.builder()
                        .competitorName("抖音")
                        .competitorUrls(List.of("https://open.douyin.com"))
                        .sourceType("OFFICIAL")
                        .fieldName("summary")
                        .evidencePathKey("OFFICIAL_PUBLIC_PROFILE")
                        .queryIntents(List.of("OFFICIAL_DOCS"))
                        .seedCandidates(List.of(blockedCandidate))
                        .attemptedTargets(Map.of())
                        .build()
        );

        assertThat(result.getAttemptedAlternativeUrls())
                .contains("https://apps.microsoft.com/about", "https://apps.microsoft.com/app");
        assertThat(result.getCandidates()).isEmpty();
    }

    @Test
    void shouldKeepRecoveryCandidatesWhenCompetitorDomainOwnershipSignalMatchesCompetitorUrls() {
        SourceCandidate blockedCandidate = SourceCandidate.builder()
                .url("https://open.feishu.cn/login")
                .domain("open.feishu.cn")
                .title("Open Feishu Login")
                .sourceType("OFFICIAL")
                .discoveryMethod("DIRECT_LOCATOR")
                .sourceUrls(List.of("https://open.feishu.cn/login"))
                .qualitySignals(List.of("LOGIN_GATE_PARTIAL"))
                .build();

        PublicEvidenceRecoveryService.RecoveryResult result = service.recover(
                PublicEvidenceRecoveryService.RecoveryContext.builder()
                        .competitorName("飞书")
                        .competitorUrls(List.of("https://www.feishu.cn"))
                        .sourceType("OFFICIAL")
                        .fieldName("summary")
                        .evidencePathKey("OFFICIAL_PUBLIC_PROFILE")
                        .queryIntents(List.of("OFFICIAL_DOCS"))
                        .seedCandidates(List.of(blockedCandidate))
                        .attemptedTargets(Map.of())
                        .build()
        );

        assertThat(result.getCandidates())
                .extracting(SourceCandidate::getUrl)
                .contains("https://open.feishu.cn/about", "https://open.feishu.cn/app");
    }

    @Test
    void shouldGenerateRepairCandidatesWithFieldEvidenceContext() {
        PublicEvidenceRecoveryService.RecoveryContext context = service.toRecoveryContext(
                "Bilibili",
                List.of("https://open.bilibili.com"),
                "DOCS",
                "coreFeatures",
                "DOCS_API_GUIDE",
                List.of("API_DOCS", "SDK_GUIDE"),
                EvidenceQualityVerdict.builder()
                        .repairRequired(true)
                        .qualitySignals(List.of("EVIDENCE_REPAIR_REQUIRED"))
                        .build()
        ).toBuilder()
                .maxAlternatives(12)
                .build();

        PublicEvidenceRecoveryService.RecoveryPlan plan = service.planRecovery(
                context,
                List.of(SourceCandidate.builder()
                        .url("https://open.bilibili.com")
                        .sourceType("DOCS")
                        .verified(Boolean.FALSE)
                        .verificationReason("????????????")
                        .qualitySignals(List.of("AUTH_OR_CAPTCHA_GATE", "NAVIGATION_SHELL"))
                        .sourceUrls(List.of("https://open.bilibili.com"))
                        .build()),
                List.of());

        assertThat(plan.triggered()).isTrue();
        assertThat(plan.fieldName()).isEqualTo("coreFeatures");
        assertThat(plan.evidencePathKey()).isEqualTo("DOCS_API_GUIDE");
        assertThat(plan.candidates()).allSatisfy(candidate ->
                assertThat(candidate.getQualitySignals()).contains("FIELD_EVIDENCE_PATH_RECOVERY"));
    }

    @Test
    void shouldPromoteVerifiedRepairUrls() {
        EvidenceRepairPlan proposed = EvidenceRepairPlan.builder()
                .state(EvidenceRepairState.REPAIR_QUERY_PROPOSED)
                .repairQueries(List.of("site:open.bilibili.com ???? API"))
                .sourceUrl("https://open.bilibili.com")
                .build();

        EvidenceRepairPlan promoted = service.promoteVerifiedUrls(
                proposed,
                List.of("https://open.bilibili.com/doc/4/feb66f99"));

        assertThat(promoted.getState()).isEqualTo(EvidenceRepairState.REPAIR_EVIDENCE_PROMOTED);
        assertThat(promoted.isComplete()).isTrue();
        assertThat(promoted.getPromotedUrls()).containsExactly("https://open.bilibili.com/doc/4/feb66f99");
    }
}
