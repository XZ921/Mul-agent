package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.agent.collector.CollectorNodeConfig;
import cn.bugstack.competitoragent.source.SourceCandidate;
import cn.bugstack.competitoragent.source.SourceCollector;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CollectionTargetSelectorTest {

    private final CollectionTargetSelector selector = new CollectionTargetSelector();

    @Test
    void shouldPreferOfficialDocumentPrimaryEvidenceOverRelatedDomainArticleFastLaneForOfficialNode() {
        CollectorNodeConfig config = CollectorNodeConfig.builder()
                .competitorName("抖音开放平台")
                .competitorUrls(List.of("https://open.douyin.com"))
                .includeDomains(List.of("op.jinritemai.com"))
                .sourceType("OFFICIAL")
                .build();
        SourceCandidate relatedDomainArticle = SourceCandidate.builder()
                .url("https://op.jinritemai.com")
                .title("巨量百应开放能力")
                .sourceType("OFFICIAL")
                .providerKey("tavily")
                .discoveryMethod("TAVILY_PHASE1_BOOTSTRAP")
                .domain("op.jinritemai.com")
                .pageType("ARTICLE")
                .qualityTier("STRONG")
                .fastLaneUsable(Boolean.TRUE)
                .hasPrefetchedContent(Boolean.TRUE)
                .prefetchedContentRef("prefetch-related-domain")
                .prefetchedRawContentLength(8_000)
                .totalScore(0.99)
                .build();
        SourceCandidate officialDocument = SourceCandidate.builder()
                .url("https://open.douyin.com/platform/resource/docs/develop/permission/overall-permission")
                .title("权限申请与能力说明")
                .sourceType("OFFICIAL")
                .providerKey("tavily")
                .discoveryMethod("TAVILY_PHASE1_BOOTSTRAP")
                .domain("open.douyin.com")
                .pageType("OFFICIAL_DOC")
                .qualityTier("STRONG")
                .fastLaneUsable(Boolean.TRUE)
                .hasPrefetchedContent(Boolean.TRUE)
                .prefetchedContentRef("prefetch-official-doc")
                .prefetchedRawContentLength(4_000)
                .queryIntent("API_DOCS")
                .evidencePathKey("OFFICIAL_PUBLIC_PROFILE")
                .totalScore(0.56)
                .build();

        SearchSelectionDecision decision = selector.selectTargets(
                config,
                List.of(relatedDomainArticle, officialDocument),
                Map.of(),
                2
        );

        assertEquals(2, decision.getSelectedTargets().size());
        assertEquals(officialDocument.getUrl(), decision.getSelectedTargets().get(0).getCandidate().getUrl());
        assertEquals(relatedDomainArticle.getUrl(), decision.getSelectedTargets().get(1).getCandidate().getUrl());
        assertEquals(0, decision.getSelectedTargets().get(0).getCandidate().getSelectionTier());
        assertEquals("OFFICIAL_PRIMARY_EVIDENCE",
                decision.getSelectedTargets().get(0).getCandidate().getSelectionRole());
        assertEquals(1, decision.getSelectedTargets().get(1).getCandidate().getSelectionTier());
        assertEquals("OFFICIAL_SUPPLEMENT_EVIDENCE",
                decision.getSelectedTargets().get(1).getCandidate().getSelectionRole());
    }

    @Test
    void shouldNotPromoteOfficialDocPageTypeWithoutDocumentPathSignalAsOfficialPrimaryEvidence() {
        CollectorNodeConfig config = CollectorNodeConfig.builder()
                .competitorName("抖音开放平台")
                .competitorUrls(List.of("https://open.douyin.com"))
                .includeDomains(List.of("op.jinritemai.com"))
                .sourceType("OFFICIAL")
                .build();
        SourceCandidate misclassifiedRelatedDomain = SourceCandidate.builder()
                .url("https://op.jinritemai.com")
                .title("巨量百应首页")
                .sourceType("OFFICIAL")
                .providerKey("tavily")
                .discoveryMethod("TAVILY_PHASE1_BOOTSTRAP")
                .domain("op.jinritemai.com")
                .pageType("OFFICIAL_DOC")
                .qualityTier("STRONG")
                .fastLaneUsable(Boolean.TRUE)
                .hasPrefetchedContent(Boolean.TRUE)
                .prefetchedContentRef("prefetch-misclassified-related-domain")
                .prefetchedRawContentLength(8_000)
                .totalScore(0.99)
                .build();
        SourceCandidate officialDocument = SourceCandidate.builder()
                .url("https://open.douyin.com/platform/resource/docs/develop/permission/overall-permission")
                .title("权限申请与能力说明")
                .sourceType("DOCS")
                .providerKey("tavily")
                .discoveryMethod("TAVILY_PHASE1_BOOTSTRAP")
                .domain("open.douyin.com")
                .pageType("OFFICIAL_DOC")
                .qualityTier("STRONG")
                .fastLaneUsable(Boolean.TRUE)
                .hasPrefetchedContent(Boolean.TRUE)
                .prefetchedContentRef("prefetch-official-doc")
                .prefetchedRawContentLength(4_000)
                .queryIntent("OFFICIAL_DOCS")
                .evidencePathKey("OFFICIAL_PUBLIC_PROFILE")
                .totalScore(0.56)
                .build();

        SearchSelectionDecision decision = selector.selectTargets(
                config,
                List.of(misclassifiedRelatedDomain, officialDocument),
                Map.of(),
                1
        );

        assertEquals(1, decision.getSelectedTargets().size());
        assertEquals(officialDocument.getUrl(), decision.getSelectedTargets().get(0).getCandidate().getUrl());
    }

    @Test
    void shouldStillSelectThirdPartyFastLaneAsSupplementWhenNoOfficialPrimaryEvidenceExists() {
        CollectorNodeConfig config = CollectorNodeConfig.builder()
                .competitorName("哔哩哔哩开放平台")
                .competitorUrls(List.of("https://open.bilibili.com"))
                .sourceType("OFFICIAL")
                .build();
        SourceCandidate thirdPartyFastLane = SourceCandidate.builder()
                .url("https://explinks.com/api/scd20240709052919a4a3d7")
                .title("哔哩哔哩开放平台 API")
                .sourceType("OFFICIAL")
                .providerKey("tavily")
                .discoveryMethod("TAVILY_PHASE1_BOOTSTRAP")
                .domain("explinks.com")
                .pageType("ARTICLE")
                .qualityTier("STRONG")
                .fastLaneUsable(Boolean.TRUE)
                .hasPrefetchedContent(Boolean.TRUE)
                .prefetchedContentRef("prefetch-explinks")
                .prefetchedRawContentLength(5_000)
                .totalScore(0.72)
                .build();

        SearchSelectionDecision decision = selector.selectTargets(
                config,
                List.of(thirdPartyFastLane),
                Map.of(),
                1
        );

        assertEquals(1, decision.getSelectedTargets().size());
        assertEquals(thirdPartyFastLane.getUrl(), decision.getSelectedTargets().get(0).getCandidate().getUrl());
    }

    @Test
    void shouldTreatVerifiedOfficialDocumentPathWithoutPageTypeAsOfficialPrimaryEvidence() {
        CollectorNodeConfig config = CollectorNodeConfig.builder()
                .competitorName("哔哩哔哩开放平台")
                .competitorUrls(List.of("https://open.bilibili.com"))
                .sourceType("OFFICIAL")
                .build();
        SourceCandidate thirdPartyFastLane = SourceCandidate.builder()
                .url("https://explinks.com/api/scd20240709052919a4a3d7")
                .title("哔哩哔哩开放平台 API")
                .sourceType("OFFICIAL")
                .providerKey("tavily")
                .discoveryMethod("TAVILY_PHASE1_BOOTSTRAP")
                .domain("explinks.com")
                .pageType("ARTICLE")
                .qualityTier("STRONG")
                .fastLaneUsable(Boolean.TRUE)
                .hasPrefetchedContent(Boolean.TRUE)
                .prefetchedContentRef("prefetch-explinks")
                .prefetchedRawContentLength(5_000)
                .totalScore(0.99)
                .build();
        SourceCandidate verifiedOfficialDocs = SourceCandidate.builder()
                .url("https://open.bilibili.com/docs/api/oauth")
                .title("开放平台接口文档")
                .sourceType("DOCS")
                .discoveryMethod("SEARCH_ROOT_TEMPLATE")
                .domain("open.bilibili.com")
                .selectionStage("VERIFIED")
                .verified(Boolean.TRUE)
                .queryIntent("API_DOCS")
                .totalScore(0.56)
                .build();
        Map<String, SearchCollectionTarget> attemptedTargets = new LinkedHashMap<>();
        attemptedTargets.put(verifiedOfficialDocs.getUrl(), SearchCollectionTarget.builder()
                .candidate(verifiedOfficialDocs)
                .collectedPage(SourceCollector.CollectedPage.builder()
                        .url(verifiedOfficialDocs.getUrl())
                        .title("开放平台接口文档")
                        .content("开放平台接口文档".repeat(120))
                        .success(true)
                        .build())
                .build());

        SearchSelectionDecision decision = selector.selectTargets(
                config,
                List.of(thirdPartyFastLane, verifiedOfficialDocs),
                attemptedTargets,
                1
        );

        assertEquals(1, decision.getSelectedTargets().size());
        assertEquals(verifiedOfficialDocs.getUrl(), decision.getSelectedTargets().get(0).getCandidate().getUrl());
    }

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

    @Test
    void shouldAllowRetainedHttpFallbackCandidateEvenWhenVerificationReturnedNoUsablePage() {
        SourceCandidate fallbackCandidate = SourceCandidate.builder()
                .url("https://http.example.com/docs")
                .title("HTTP Docs")
                .sourceType("DOCS")
                .providerKey("http")
                .discoveryMethod("SEARCH")
                .selectionStage("SUPPLEMENTED")
                .selectionReason("browser disabled keep http fallback")
                .selectionSummary("HTTP fallback candidate should remain selectable")
                .verified(Boolean.FALSE)
                .totalScore(0.81)
                .build();

        Map<String, SearchCollectionTarget> attemptedTargets = new LinkedHashMap<>();
        attemptedTargets.put(fallbackCandidate.getUrl(), SearchCollectionTarget.builder()
                .candidate(fallbackCandidate)
                .collectedPage(SourceCollector.CollectedPage.builder()
                        .url(fallbackCandidate.getUrl())
                        .success(false)
                        .errorMessage("verification page unavailable")
                        .build())
                .build());

        SearchSelectionDecision decision = selector.selectTargets(
                List.of(fallbackCandidate),
                attemptedTargets,
                1
        );

        assertEquals(1, decision.getSelectedTargets().size());
        assertEquals("https://http.example.com/docs",
                decision.getSelectedTargets().get(0).getCandidate().getUrl());
        assertEquals("SELECTED",
                decision.getSelectedTargets().get(0).getCandidate().getSelectionStage());
    }

    @Test
    void shouldSelectUsableTavilyPrefetchCandidateAheadOfHigherScoredVerifiedRootShell() {
        SourceCandidate verifiedRootShell = SourceCandidate.builder()
                .url("https://open.douyin.com/")
                .title("抖音开放平台")
                .sourceType("OFFICIAL")
                .discoveryMethod("SEARCH_ROOT_TEMPLATE")
                .qualitySignals(List.of("PUBLIC_SHELL_ONLY"))
                .selectionStage("VERIFIED")
                .verified(Boolean.TRUE)
                .totalScore(0.98)
                .build();
        SourceCandidate prefetchedLongForm = SourceCandidate.builder()
                .url("https://open.douyin.com/platform/resource/docs/develop/guide")
                .title("开发指南")
                .sourceType("DOCS")
                .discoveryMethod("TAVILY_PHASE1_BOOTSTRAP")
                .selectionStage("BOOTSTRAPPED")
                .verified(null)
                .fastLaneUsable(Boolean.TRUE)
                .hasPrefetchedContent(Boolean.TRUE)
                .prefetchedContentRef("tavily:req-75:1")
                .prefetchedRawContentLength(2049)
                .totalScore(0.41)
                .build();

        Map<String, SearchCollectionTarget> attemptedTargets = new LinkedHashMap<>();
        attemptedTargets.put(verifiedRootShell.getUrl(), SearchCollectionTarget.builder()
                .candidate(verifiedRootShell)
                .collectedPage(SourceCollector.CollectedPage.builder()
                        .url(verifiedRootShell.getUrl())
                        .title("抖音开放平台")
                        .content("开放平台首页公开壳")
                        .snippet("开放平台首页公开壳")
                        .metadata("{\"qualitySignals\":[\"PUBLIC_SHELL_ONLY\"]}")
                        .success(true)
                        .build())
                .build());

        SearchSelectionDecision decision = selector.selectTargets(
                List.of(verifiedRootShell, prefetchedLongForm),
                attemptedTargets,
                1
        );

        assertEquals(1, decision.getSelectedTargets().size());
        assertEquals(prefetchedLongForm.getUrl(), decision.getSelectedTargets().get(0).getCandidate().getUrl());
        assertEquals("SELECTED", decision.getSelectedTargets().get(0).getCandidate().getSelectionStage());
        assertEquals("Tavily prefetch 正文可用",
                decision.getSelectedTargets().get(0).getCandidate().getSelectionReason());
        assertEquals("Tavily prefetch 正文可用",
                decision.getSelectedTargets().get(0).getCandidate().getSelectionSummary());
    }

    @Test
    void shouldKeepVerifiedRichContentCompetingNormallyAgainstPrefetchCandidate() {
        SourceCandidate verifiedRichDoc = SourceCandidate.builder()
                .url("https://open.douyin.com/doc/api/reference")
                .title("API 参考文档")
                .sourceType("DOCS")
                .discoveryMethod("SEARCH")
                .selectionStage("VERIFIED")
                .verified(Boolean.TRUE)
                .totalScore(0.93)
                .build();
        SourceCandidate prefetchedLongForm = SourceCandidate.builder()
                .url("https://open.douyin.com/platform/resource/docs/develop/guide")
                .title("开发指南")
                .sourceType("DOCS")
                .discoveryMethod("TAVILY_PHASE1_BOOTSTRAP")
                .selectionStage("BOOTSTRAPPED")
                .verified(null)
                .fastLaneUsable(Boolean.TRUE)
                .hasPrefetchedContent(Boolean.TRUE)
                .prefetchedContentRef("tavily:req-75:2")
                .prefetchedRawContentLength(2049)
                .totalScore(0.41)
                .build();

        Map<String, SearchCollectionTarget> attemptedTargets = new LinkedHashMap<>();
        attemptedTargets.put(verifiedRichDoc.getUrl(), SearchCollectionTarget.builder()
                .candidate(verifiedRichDoc)
                .collectedPage(SourceCollector.CollectedPage.builder()
                        .url(verifiedRichDoc.getUrl())
                        .title("API 参考文档")
                        .content("这里是完整 API 参考文档内容，不是官网壳页。")
                        .snippet("完整 API 参考文档")
                        .metadata("{\"collector\":\"http\"}")
                        .success(true)
                        .build())
                .build());

        SearchSelectionDecision decision = selector.selectTargets(
                List.of(verifiedRichDoc, prefetchedLongForm),
                attemptedTargets,
                1
        );

        assertEquals(1, decision.getSelectedTargets().size());
        assertEquals(verifiedRichDoc.getUrl(), decision.getSelectedTargets().get(0).getCandidate().getUrl());
        assertEquals("运行期验证通过后被选为正式采集目标",
                decision.getSelectedTargets().get(0).getCandidate().getSelectionReason());
    }

    @Test
    void shouldRejectUnverifiedCandidateWithoutPrefetchWhenAttemptedTargetHasNoUsableContent() {
        SourceCandidate ordinaryUnverified = SourceCandidate.builder()
                .url("https://open.douyin.com/platform/overview")
                .title("平台概览")
                .sourceType("OFFICIAL")
                .discoveryMethod("SEARCH")
                .selectionStage("SUPPLEMENTED")
                .verified(Boolean.FALSE)
                .totalScore(0.77)
                .build();

        Map<String, SearchCollectionTarget> attemptedTargets = new LinkedHashMap<>();
        attemptedTargets.put(ordinaryUnverified.getUrl(), SearchCollectionTarget.builder()
                .candidate(ordinaryUnverified)
                .collectedPage(SourceCollector.CollectedPage.builder()
                        .url(ordinaryUnverified.getUrl())
                        .title("平台概览")
                        .success(false)
                        .errorMessage("collector returned no usable content")
                        .build())
                .build());

        SearchSelectionDecision decision = selector.selectTargets(
                List.of(ordinaryUnverified),
                attemptedTargets,
                1
        );

        assertTrue(decision.getSelectedTargets().isEmpty());
        assertFalse(decision.getDiscardedCandidates().isEmpty());
        assertEquals("未验证候选不能进入正式采集目标",
                decision.getDiscardedCandidates().get(0).getSelectionReason());
    }
    @Test
    void shouldRetainExplicitAttemptedFailureAsSelectedAuditTarget() {
        SourceCandidate explicitFailedCandidate = SourceCandidate.builder()
                .url("https://example.com/help")
                .title("Help")
                .sourceType("DOCS")
                .discoveryMethod("DIRECT_LOCATOR")
                .providerKey("planned")
                .selectionStage("DISCARDED")
                .selectionReason("verification timeout")
                .verified(Boolean.FALSE)
                .sourceUrls(List.of("https://example.com/help"))
                .totalScore(0.30)
                .build();

        Map<String, SearchCollectionTarget> attemptedTargets = new LinkedHashMap<>();
        attemptedTargets.put(explicitFailedCandidate.getUrl(), SearchCollectionTarget.builder()
                .candidate(explicitFailedCandidate)
                .collectedPage(SourceCollector.CollectedPage.builder()
                        .url("https://example.com/help")
                        .title("Help")
                        .success(false)
                        .errorMessage("timeout")
                        .build())
                .build());

        SearchSelectionDecision decision = selector.selectTargets(
                List.of(explicitFailedCandidate),
                attemptedTargets,
                1
        );

        assertEquals(1, decision.getSelectedTargets().size());
        assertEquals("https://example.com/help", decision.getSelectedTargets().get(0).getCandidate().getUrl());
        assertNotNull(decision.getSelectedTargets().get(0).getCollectedPage());
        assertFalse(decision.getSelectedTargets().get(0).getCollectedPage().isSuccess());
        assertTrue(decision.getUpdatedCandidates().stream()
                .anyMatch(candidate -> "https://example.com/help".equals(candidate.getUrl())
                        && "SELECTED".equals(candidate.getSelectionStage())));
    }

    @Test
    void shouldPreferStrongPrefetchedContentOverThinVerifiedShell() {
        SourceCandidate verifiedShell = SourceCandidate.builder()
                .url("https://bilibili.apifox.cn/about")
                .title("Apifox About")
                .verified(Boolean.TRUE)
                .selectionStage("VERIFIED")
                .totalScore(0.99)
                .build();
        SourceCandidate tavilyStrong = SourceCandidate.builder()
                .url("https://open-live.bilibili.com/document/doc/guide")
                .title("哔哩哔哩直播开放平台文档")
                .providerKey("tavily")
                .tavilyQueryMode("TRUSTED_WEB_EXPANSION")
                .qualityTier("STRONG")
                .fastLaneUsable(Boolean.TRUE)
                .hasPrefetchedContent(Boolean.TRUE)
                .prefetchedContentRef("prefetch-bilibili-live")
                .prefetchedRawContentLength(7_444)
                .skipNetworkVerification(Boolean.TRUE)
                .totalScore(0.86)
                .build();

        Map<String, SearchCollectionTarget> attemptedTargets = new LinkedHashMap<>();
        attemptedTargets.put(verifiedShell.getUrl(), SearchCollectionTarget.builder()
                .candidate(verifiedShell)
                .collectedPage(SourceCollector.CollectedPage.builder()
                        .url(verifiedShell.getUrl())
                        .title("Apifox About")
                        .content("Bilibili API platform about page with a very thin shell.")
                        .snippet("thin shell")
                        .success(true)
                        .build())
                .build());

        SearchSelectionDecision decision = selector.selectTargets(
                List.of(verifiedShell, tavilyStrong),
                attemptedTargets,
                1
        );

        assertEquals(1, decision.getSelectedTargets().size());
        assertEquals("https://open-live.bilibili.com/document/doc/guide",
                decision.getSelectedTargets().get(0).getCandidate().getUrl());
    }

    @Test
    void shouldKeepDiscoveryOnlyCandidateSelectableWithoutPretendingPrefetchedEvidenceIsReady() {
        SourceCandidate discoveryCandidate = SourceCandidate.builder()
                .url("https://open.example.com/docs")
                .title("开放平台文档")
                .sourceType("DOCS")
                .providerKey("tavily")
                .discoveryMethod("TAVILY_FIELD_EVIDENCE_QUERY")
                .candidateDiscoveryUsable(Boolean.TRUE)
                .fastLaneUsable(Boolean.FALSE)
                .hasPrefetchedContent(Boolean.FALSE)
                .prefetchedRawContentLength(0)
                .sourceUrls(List.of("https://open.example.com/docs"))
                .totalScore(0.72)
                .build();

        SearchSelectionDecision decision = selector.selectTargets(
                List.of(discoveryCandidate),
                Map.of(),
                1
        );

        assertEquals(1, decision.getSelectedTargets().size());
        SourceCandidate selectedCandidate = decision.getSelectedTargets().get(0).getCandidate();
        assertEquals("https://open.example.com/docs", selectedCandidate.getUrl());
        assertEquals("SELECTED", selectedCandidate.getSelectionStage());
        assertEquals(Boolean.FALSE, selectedCandidate.getFastLaneUsable());
        assertEquals(Boolean.FALSE, selectedCandidate.getHasPrefetchedContent());
        assertEquals("字段发现候选已入选，仍需后续正文采集", selectedCandidate.getSelectionReason());
        assertEquals("字段发现候选可继续进入正文采集链路", selectedCandidate.getSelectionSummary());
    }

    @Test
    void shouldKeepOfficialSearchRootTemplateAsSupplementContinuationNotPrimaryEvidence() {
        CollectorNodeConfig config = CollectorNodeConfig.builder()
                .competitorName("Douyin")
                .competitorUrls(List.of("https://open.douyin.com/"))
                .sourceType("OFFICIAL")
                .build();
        SourceCandidate discoveryRoot = SourceCandidate.builder()
                .url("https://open.douyin.com")
                .title("Douyin Open Platform")
                .sourceType("OFFICIAL")
                .domain("open.douyin.com")
                .discoveryMethod("SEARCH_ROOT_TEMPLATE")
                .candidateDiscoveryUsable(Boolean.TRUE)
                .fastLaneUsable(Boolean.FALSE)
                .hasPrefetchedContent(Boolean.FALSE)
                .prefetchedRawContentLength(0)
                .sourceUrls(List.of("https://www.douyin.com"))
                .totalScore(0.74)
                .build();

        SearchSelectionDecision decision = selector.selectTargets(
                config,
                List.of(discoveryRoot),
                Map.of(),
                1
        );

        assertEquals(1, decision.getSelectedTargets().size());
        SourceCandidate selectedCandidate = decision.getSelectedTargets().get(0).getCandidate();
        assertEquals("https://open.douyin.com", selectedCandidate.getUrl());
        assertEquals("SELECTED", selectedCandidate.getSelectionStage());
        assertEquals(1, selectedCandidate.getSelectionTier());
        assertEquals("OFFICIAL_SUPPLEMENT_EVIDENCE", selectedCandidate.getSelectionRole());
    }

    @Test
    void shouldKeepMultipleHighValueTargetsForSearchFirstFamilyEvenWhenInputUrlCountIsOne() {
        SourceCandidate officialCandidate = SourceCandidate.builder()
                .url("https://open.example.com/protocol")
                .title("开放平台协议")
                .sourceType("OFFICIAL")
                .providerKey("tavily")
                .discoveryMethod("TAVILY_PHASE1_BOOTSTRAP")
                .domain("open.example.com")
                .fastLaneUsable(Boolean.TRUE)
                .hasPrefetchedContent(Boolean.TRUE)
                .prefetchedContentRef("prefetch-official")
                .prefetchedRawContentLength(9_000)
                .sourceUrls(List.of("https://open.example.com/protocol"))
                .totalScore(0.95)
                .build();
        SourceCandidate docsCandidate = SourceCandidate.builder()
                .url("https://developer.example.com/docs")
                .title("开发者文档")
                .sourceType("DOCS")
                .providerKey("tavily")
                .discoveryMethod("TAVILY_PHASE1_BOOTSTRAP")
                .domain("developer.example.com")
                .fastLaneUsable(Boolean.TRUE)
                .hasPrefetchedContent(Boolean.TRUE)
                .prefetchedContentRef("prefetch-docs")
                .prefetchedRawContentLength(12_000)
                .sourceUrls(List.of("https://developer.example.com/docs"))
                .totalScore(0.92)
                .build();
        SourceCandidate thirdPartyCandidate = SourceCandidate.builder()
                .url("https://news.example.com/platform-review")
                .title("平台行业观察")
                .sourceType("NEWS")
                .providerKey("tavily")
                .discoveryMethod("TAVILY_PHASE1_BOOTSTRAP")
                .domain("news.example.com")
                .fastLaneUsable(Boolean.TRUE)
                .hasPrefetchedContent(Boolean.TRUE)
                .prefetchedContentRef("prefetch-news")
                .prefetchedRawContentLength(2_000)
                .sourceUrls(List.of("https://news.example.com/platform-review"))
                .totalScore(0.87)
                .build();

        SearchSelectionDecision decision = selector.selectTargets(
                List.of(officialCandidate, docsCandidate, thirdPartyCandidate),
                Map.of(),
                3
        );

        assertEquals(3, decision.getSelectedTargets().size());
        assertEquals(List.of(
                        "https://open.example.com/protocol",
                        "https://developer.example.com/docs",
                        "https://news.example.com/platform-review"
                ),
                decision.getSelectedTargets().stream()
                        .map(target -> target.getCandidate().getUrl())
                        .toList());
    }
}
