package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.source.SourceCandidate;
import cn.bugstack.competitoragent.source.SourceCollector;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CollectionTargetSelectorTest {

    private final CollectionTargetSelector selector = new CollectionTargetSelector();

    @Test
    void shouldPreferVerifiedAttemptedTargetOverHigherScoredDiscardedCandidateAndReuseCollectedPage() {
        SourceCandidate discardedOfficial = SourceCandidate.builder()
                .url("https://www.aliyun.com/product/ecs")
                .title("阿里云 ECS 营销页")
                .selectionStage("DISCARDED")
                .verified(Boolean.FALSE)
                .totalScore(0.99)
                .build();
        SourceCandidate verifiedDoc = SourceCandidate.builder()
                .url("https://help.aliyun.com/document_detail/12345.html")
                .title("实例规格说明")
                .selectionStage("VERIFIED")
                .verified(Boolean.TRUE)
                .totalScore(0.71)
                .build();

        Map<String, SearchCollectionTarget> attemptedTargets = new LinkedHashMap<>();
        attemptedTargets.put(verifiedDoc.getUrl(), SearchCollectionTarget.builder()
                .candidate(verifiedDoc)
                .collectedPage(SourceCollector.CollectedPage.builder()
                        .url(verifiedDoc.getUrl())
                        .title("实例规格说明")
                        .content("规格说明")
                        .success(true)
                        .build())
                .build());

        SearchSelectionDecision decision = selector.selectTargets(
                List.of(discardedOfficial, verifiedDoc),
                attemptedTargets,
                1
        );

        assertEquals(1, decision.getSelectedTargets().size());
        assertEquals("https://help.aliyun.com/document_detail/12345.html",
                decision.getSelectedTargets().get(0).getCandidate().getUrl());
        assertNotNull(decision.getSelectedTargets().get(0).getCollectedPage());
        assertEquals(List.of("https://help.aliyun.com/document_detail/12345.html"), decision.getSourceUrls());
        assertTrue(decision.getUpdatedCandidates().stream()
                .anyMatch(candidate -> "https://help.aliyun.com/document_detail/12345.html".equals(candidate.getUrl())
                        && "SELECTED".equals(candidate.getSelectionStage())));
    }

    @Test
    void shouldRefreshSelectedTargetCandidateSnapshotByNormalizedUrl() {
        SourceCandidate selectedCandidate = SourceCandidate.builder()
                .url("https://docs.example.com/reference")
                .title("Reference")
                .selectionStage("VERIFIED")
                .verified(Boolean.TRUE)
                .totalScore(0.88)
                .build();

        Map<String, SearchCollectionTarget> attemptedTargets = new LinkedHashMap<>();
        attemptedTargets.put("https://docs.example.com/reference?utm_source=test", SearchCollectionTarget.builder()
                .candidate(SourceCandidate.builder()
                        .url("https://docs.example.com/reference?utm_source=test")
                        .title("Old Reference")
                        .selectionStage("VERIFIED")
                        .verified(Boolean.TRUE)
                        .build())
                .collectedPage(SourceCollector.CollectedPage.builder()
                        .url("https://docs.example.com/reference")
                        .title("Reference")
                        .content("api reference")
                        .success(true)
                        .build())
                .build());

        SearchSelectionDecision decision = selector.selectTargets(
                List.of(selectedCandidate),
                attemptedTargets,
                1
        );

        assertEquals("https://docs.example.com/reference",
                decision.getSelectedTargets().get(0).getCandidate().getUrl());
        assertEquals("SELECTED", decision.getSelectedTargets().get(0).getCandidate().getSelectionStage());
        assertNotNull(decision.getSelectedTargets().get(0).getCollectedPage());
        assertEquals(List.of("https://docs.example.com/reference"), decision.getSourceUrls());
    }

    @Test
    void shouldExposeDiscardedCandidatesWhenSelectingTargets() {
        SourceCandidate discarded = SourceCandidate.builder()
                .url("https://www.example.com/login")
                .selectionStage("DISCARDED")
                .selectionReason("LOW_SIGNAL_UTILITY_PAGE")
                .totalScore(0.99)
                .build();
        SourceCandidate selected = SourceCandidate.builder()
                .url("https://docs.example.com/reference")
                .selectionStage("VERIFIED")
                .verified(Boolean.TRUE)
                .totalScore(0.80)
                .build();

        SearchSelectionDecision decision = selector.selectTargets(List.of(discarded, selected), Map.of(), 1);

        assertEquals(1, decision.getSelectedTargets().size());
        assertEquals("https://docs.example.com/reference", decision.getSelectedTargets().get(0).getCandidate().getUrl());
        assertEquals(List.of("https://www.example.com/login"),
                decision.getDiscardedCandidates().stream().map(SourceCandidate::getUrl).toList());
    }

    @Test
    void shouldCollapseCanonicalUrlVariantsIntoSingleSelectedTarget() {
        SourceCandidate insecureVariant = SourceCandidate.builder()
                .url("http://www.example.com/docs?utm_source=campaign&gclid=test")
                .title("Docs Landing")
                .selectionStage("VERIFIED")
                .verified(Boolean.TRUE)
                .totalScore(0.98)
                .build();
        SourceCandidate canonicalVariant = SourceCandidate.builder()
                .url("https://example.com/docs")
                .title("Canonical Docs")
                .selectionStage("VERIFIED")
                .verified(Boolean.TRUE)
                .totalScore(0.80)
                .build();

        Map<String, SearchCollectionTarget> attemptedTargets = new LinkedHashMap<>();
        attemptedTargets.put(canonicalVariant.getUrl(), SearchCollectionTarget.builder()
                .candidate(canonicalVariant)
                .collectedPage(SourceCollector.CollectedPage.builder()
                        .url("https://example.com/docs")
                        .title("Canonical Docs")
                        .content("official docs content")
                        .success(true)
                        .build())
                .build());

        SearchSelectionDecision decision = selector.selectTargets(
                List.of(insecureVariant, canonicalVariant),
                attemptedTargets,
                2
        );

        assertEquals(1, decision.getSelectedTargets().size());
        assertEquals("https://example.com/docs", decision.getSelectedTargets().get(0).getCandidate().getUrl());
        assertNotNull(decision.getSelectedTargets().get(0).getCollectedPage());
        assertEquals(List.of("https://example.com/docs"), decision.getSourceUrls());
        assertEquals(1, decision.getUpdatedCandidates().stream()
                .filter(candidate -> "SELECTED".equals(candidate.getSelectionStage()))
                .count());
    }

    @Test
    void shouldNotSelectUnverifiedSearchMediatorWhenSupplementVerificationSkipped() {
        SourceCandidate plannedCandidate = SourceCandidate.builder()
                .url("https://app.bilibili.com")
                .title("哔哩哔哩下载中心")
                .sourceType("OFFICIAL")
                .discoveryMethod("DIRECT_LOCATOR")
                .verified(Boolean.FALSE)
                .selectionStage("DISCARDED")
                .verificationReason("页面已打开，但未命中 OFFICIAL 所需特征")
                .totalScore(0.87)
                .build();
        SourceCandidate baiduMediator = SourceCandidate.builder()
                .url("https://aiqicha.baidu.com/feedback/official?from=baidu&type=gw")
                .title("官网认证")
                .sourceType("OFFICIAL")
                .discoveryMethod("BROWSER")
                .providerKey("browser")
                .domain("aiqicha.baidu.com")
                .reason("浏览器搜索命中百度官网认证页，正文摘要包含官网认证增值服务说明")
                .verified(null)
                .selectionStage("SUPPLEMENTED")
                .totalScore(0.84)
                .build();

        SearchSelectionDecision decision = selector.selectTargets(
                List.of(plannedCandidate, baiduMediator),
                Map.of(),
                1
        );

        assertTrue(decision.getSelectedTargets().isEmpty());
        assertEquals(List.of("https://aiqicha.baidu.com/feedback/official?from=baidu&type=gw"),
                decision.getDiscardedCandidates().stream().map(SourceCandidate::getUrl).toList());
        assertTrue(decision.getDiscardedCandidates().get(0).getSelectionReason().contains("未验证"));
    }

    @Test
    void shouldAllowExplicitCandidateWithRecoveredPublicShellWhenNoVerifiedTargetExists() {
        SourceCandidate loginGateCandidate = SourceCandidate.builder()
                .url("https://docs.example.com/login")
                .title("Example Docs Login")
                .sourceType("DOCS")
                .discoveryMethod("DIRECT_LOCATOR")
                .providerKey("planned")
                .sourceUrls(List.of("https://docs.example.com/login"))
                .qualitySignals(List.of("LOGIN_GATE_PARTIAL", "PUBLIC_SHELL_ONLY"))
                .selectionStage("PARTIAL_PUBLIC_SHELL")
                .verified(Boolean.FALSE)
                .totalScore(0.42)
                .build();

        Map<String, SearchCollectionTarget> attemptedTargets = new LinkedHashMap<>();
        attemptedTargets.put(loginGateCandidate.getUrl(), SearchCollectionTarget.builder()
                .candidate(loginGateCandidate)
                .collectedPage(SourceCollector.CollectedPage.builder()
                        .url(loginGateCandidate.getUrl())
                        .title("Example Docs Login")
                        .content("Example Docs public shell. Product documentation login page.")
                        .snippet("Example Docs public shell")
                        .metadata("{\"qualitySignals\":[\"LOGIN_GATE_PARTIAL\",\"PUBLIC_SHELL_ONLY\"]}")
                        .success(true)
                        .build())
                .build());

        SearchSelectionDecision decision = selector.selectTargets(
                List.of(loginGateCandidate),
                attemptedTargets,
                1
        );

        assertEquals(1, decision.getSelectedTargets().size());
        assertEquals("https://docs.example.com/login",
                decision.getSelectedTargets().get(0).getCandidate().getUrl());
        assertTrue(decision.getSelectedTargets().get(0).getCandidate().getSelectionSummary()
                .contains("公开壳信息"));
    }

    @Test
    void shouldAllowExplicitCandidateWithUsablePublicPageEvenWhenVerificationMarkedDiscarded() {
        SourceCandidate explicitCandidate = SourceCandidate.builder()
                .url("https://app.bilibili.com")
                .title("哔哩哔哩下载中心")
                .sourceType("OFFICIAL")
                .discoveryMethod("DIRECT_LOCATOR")
                .providerKey("planned")
                .sourceUrls(List.of("https://app.bilibili.com"))
                .verified(Boolean.FALSE)
                .selectionStage("DISCARDED")
                .verificationReason("页面已打开，但未命中 OFFICIAL 所需特征")
                .totalScore(0.87)
                .build();

        Map<String, SearchCollectionTarget> attemptedTargets = new LinkedHashMap<>();
        attemptedTargets.put(explicitCandidate.getUrl(), SearchCollectionTarget.builder()
                .candidate(explicitCandidate)
                .collectedPage(SourceCollector.CollectedPage.builder()
                        .url(explicitCandidate.getUrl())
                        .title("哔哩哔哩下载中心")
                        .content("哔哩哔哩下载中心，提供安卓版、iPhone 版、PC 客户端和 TV 版下载。")
                        .snippet("哔哩哔哩下载中心")
                        .metadata("{\"collector\":\"http\"}")
                        .success(true)
                        .build())
                .build());

        SearchSelectionDecision decision = selector.selectTargets(
                List.of(explicitCandidate),
                attemptedTargets,
                1
        );

        assertEquals(1, decision.getSelectedTargets().size());
        assertEquals("https://app.bilibili.com",
                decision.getSelectedTargets().get(0).getCandidate().getUrl());
        assertEquals("SELECTED",
                decision.getSelectedTargets().get(0).getCandidate().getSelectionStage());
        assertTrue(decision.getSelectedTargets().get(0).getCandidate().getSelectionSummary()
                .contains("公开正文"));
    }
}
