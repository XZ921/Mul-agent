package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.source.SourceCandidate;
import cn.bugstack.competitoragent.source.SourceCollector;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CandidateOwnershipPolicyTest {

    private final CandidateOwnershipPolicy policy = new CandidateOwnershipPolicy();

    @Test
    void shouldRejectSearchCertificationMediatorPages() {
        SourceCandidate candidate = SourceCandidate.builder()
                .url("https://aiqicha.baidu.com/feedback/official?from=baidu&type=gw")
                .domain("aiqicha.baidu.com")
                .title("官网认证")
                .reason("官网认证是百度对网站在强关联关系触发词下展示官方标识的增值服务认证")
                .sourceType("OFFICIAL")
                .discoveryMethod("BROWSER")
                .build();

        assertTrue(policy.isRejectedMediator(candidate, null));
    }

    @Test
    void shouldRejectEnterpriseInformationPages() {
        SourceCandidate candidate = SourceCandidate.builder()
                .url("https://www.qcc.com/firm/example.html")
                .domain("www.qcc.com")
                .title("某公司企业信息")
                .reason("企业工商信息、股东信息、风险信息")
                .sourceType("OFFICIAL")
                .discoveryMethod("BROWSER")
                .build();

        assertTrue(policy.isRejectedMediator(candidate, null));
    }

    @Test
    void shouldRejectLoginAndCaptchaUtilityPagesAsFormalEvidence() {
        SourceCandidate loginCandidate = SourceCandidate.builder()
                .url("https://example.com/login")
                .domain("example.com")
                .title("Login")
                .sourceType("DOCS")
                .discoveryMethod("SEARCH")
                .build();
        SourceCollector.CollectedPage captchaPage = SourceCollector.CollectedPage.builder()
                .url("https://example.com/challenge")
                .title("Verify you are human")
                .content("captcha security check")
                .success(true)
                .build();

        assertTrue(policy.isUtilityGatePage(loginCandidate, null));
        assertTrue(policy.isUtilityGatePage(null, captchaPage));
    }

    @Test
    void shouldExemptThirdPartyFallbackCandidateFromOfficialOwnershipValidation() {
        SourceCandidate fallbackCandidate = SourceCandidate.builder()
                .url("https://www.getapp.com/collaboration-software/a/notion/reviews/")
                .domain("www.getapp.com")
                .title("Notion third-party review")
                .sourceType("DOCS")
                .discoveryMethod("THIRD_PARTY_FALLBACK")
                .providerKey("tavily")
                .build();
        SourceCandidate normalSearchCandidate = SourceCandidate.builder()
                .url("https://partner.example.com/notion-docs")
                .domain("partner.example.com")
                .title("Notion docs mirror")
                .sourceType("OFFICIAL")
                .discoveryMethod("TAVILY_PHASE1_BOOTSTRAP")
                .providerKey("tavily")
                .build();

        assertFalse(policy.shouldRequireOwnershipValidation(fallbackCandidate, "OFFICIAL"));
        assertTrue(policy.shouldRequireOwnershipValidation(normalSearchCandidate, "OFFICIAL"));
    }

    @Test
    void shouldKeepDirectOfficialDomainWhenOwnershipSignalMatches() {
        SourceCandidate candidate = SourceCandidate.builder()
                .url("https://app.bilibili.com")
                .domain("app.bilibili.com")
                .title("哔哩哔哩下载中心")
                .sourceType("OFFICIAL")
                .discoveryMethod("DIRECT_LOCATOR")
                .build();

        assertFalse(policy.isRejectedMediator(candidate, null));
        assertTrue(policy.hasCompetitorOwnershipSignal("哔哩哔哩", candidate, null));
    }

    @Test
    void shouldRejectSearchDiscoveredDomainsThatOnlyContainCompetitorAlias() {
        SourceCandidate apifoxCandidate = SourceCandidate.builder()
                .url("https://bilibili.apifox.cn")
                .domain("bilibili.apifox.cn")
                .title("哔哩哔哩开放平台 API")
                .sourceType("OFFICIAL")
                .providerKey("tavily")
                .discoveryMethod("TAVILY_PHASE1_BOOTSTRAP")
                .selectionStage("HTTP")
                .build();
        SourceCandidate githubCandidate = SourceCandidate.builder()
                .url("https://github.com/bilibili-openplatform/example")
                .domain("github.com")
                .title("bilibili-openplatform")
                .sourceType("OFFICIAL")
                .providerKey("tavily")
                .discoveryMethod("TAVILY_PHASE1_BOOTSTRAP")
                .selectionStage("HTTP")
                .build();
        SourceCandidate officialCandidate = SourceCandidate.builder()
                .url("https://open.bilibili.com")
                .domain("open.bilibili.com")
                .title("哔哩哔哩开放平台")
                .sourceType("OFFICIAL")
                .providerKey("tavily")
                .discoveryMethod("TAVILY_PHASE1_BOOTSTRAP")
                .selectionStage("HTTP")
                .build();

        assertFalse(policy.hasCompetitorDomainOwnershipSignalForCandidate(
                "哔哩哔哩",
                List.of("https://open.bilibili.com"),
                apifoxCandidate
        ));
        assertFalse(policy.hasCompetitorOwnershipSignal(
                "哔哩哔哩",
                List.of("https://open.bilibili.com"),
                apifoxCandidate,
                SourceCollector.CollectedPage.builder()
                        .title("哔哩哔哩开放平台 API")
                        .content("页面正文提到了 bilibili，但域名仍然属于第三方 API 文档站。")
                        .success(true)
                        .build()
        ));
        assertFalse(policy.hasCompetitorDomainOwnershipSignalForCandidate(
                "哔哩哔哩",
                List.of("https://open.bilibili.com"),
                githubCandidate
        ));
        assertTrue(policy.hasCompetitorDomainOwnershipSignalForCandidate(
                "哔哩哔哩",
                List.of("https://open.bilibili.com"),
                officialCandidate
        ));
    }

    @Test
    void shouldRejectSitemapDiscoveryRootWithoutCompetitorOwnershipSignal() {
        SourceCandidate candidate = SourceCandidate.builder()
                .url("https://apps.microsoft.com/store/detail/9nblggh4nns1")
                .domain("apps.microsoft.com")
                .title("Download Bilibili")
                .sourceType("OFFICIAL")
                .discoveryMethod("SITEMAP_DISCOVERY")
                .selectionStage("SUPPLEMENTED")
                .build();

        assertFalse(policy.isTrustedSearchRoot("鍝斿摡鍝斿摡", List.of("https://www.bilibili.com"), candidate));
    }

    @Test
    void shouldAllowSearchDiscoveredSinglePageEvidenceByCollectedBrandTextButKeepRootExpansionStrict() {
        SourceCandidate candidate = SourceCandidate.builder()
                .url("https://partner.example.com/research/douyin-open-platform")
                .domain("partner.example.com")
                .title("Douyin open platform overview")
                .sourceType("OFFICIAL")
                .providerKey("tavily")
                .discoveryMethod("TAVILY_FAST_LANE")
                .selectionStage("HTTP")
                .build();
        SourceCollector.CollectedPage page = SourceCollector.CollectedPage.builder()
                .url("https://partner.example.com/research/douyin-open-platform")
                .title("Douyin official developer overview")
                .content("Douyin official overview and features for creators and developers.")
                .success(true)
                .build();

        assertFalse(policy.hasCompetitorDomainOwnershipSignalForCandidate(
                "Douyin",
                List.of("https://www.douyin.com"),
                candidate
        ));
        assertFalse(policy.isTrustedSearchRoot("Douyin", List.of("https://www.douyin.com"), candidate));
        assertTrue(policy.hasCompetitorEvidenceOwnershipSignal(
                "Douyin",
                List.of("https://www.douyin.com"),
                candidate,
                page
        ));
    }

    @Test
    void shouldNotPromoteVerifiedSearchDiscoveredThirdPartyEvidenceToExpandableRoot() {
        SourceCandidate verifiedThirdPartyEvidence = SourceCandidate.builder()
                .url("https://partner.example.com/research/douyin-open-platform")
                .domain("partner.example.com")
                .title("Douyin official developer overview")
                .sourceType("OFFICIAL")
                .providerKey("tavily")
                .discoveryMethod("TAVILY_FAST_LANE")
                .selectionStage("VERIFIED")
                .verified(true)
                .build();

        assertFalse(policy.isTrustedSearchRoot(
                "Douyin",
                List.of("https://www.douyin.com"),
                verifiedThirdPartyEvidence
        ));
    }

    @Test
    void shouldTreatCompetitorUrlPrimaryDomainAsOwnershipAlias() {
        SourceCandidate candidate = SourceCandidate.builder()
                .url("https://open.feishu.cn/document/server-docs/docs")
                .domain("open.feishu.cn")
                .title("Open Feishu API Documentation")
                .sourceType("DOCS")
                .discoveryMethod("SITEMAP_DISCOVERY")
                .selectionStage("SUPPLEMENTED")
                .build();

        assertTrue(policy.hasCompetitorOwnershipSignal("椋炰功", List.of("https://www.feishu.cn"), candidate, null));
        assertTrue(policy.isTrustedSearchRoot("椋炰功", List.of("https://www.feishu.cn"), candidate));
    }
    @Test
    void shouldRejectRuntimeSearchRootWithoutCompetitorOwnershipSignal() {
        SourceCandidate candidate = SourceCandidate.builder()
                .url("https://apps.microsoft.com/detail/xpffsrj7q4n302?launch=true&hl=zh-CN&gl=CN")
                .domain("apps.microsoft.com")
                .title("Download Douyin")
                .sourceType("OFFICIAL")
                .providerKey("qianfan")
                .discoveryMethod("QIANFAN_SEARCH")
                .selectionStage("HTTP")
                .build();

        assertFalse(policy.isTrustedSearchRoot("抖音", List.of("https://open.douyin.com"), candidate));
    }

    @Test
    void shouldSeparateAnyContentSignalFromSatisfyingContentSignal() {
        SourceCandidate emptyShell = SourceCandidate.builder()
                .url("https://open.example.com/")
                .title("Example Open Platform")
                .sourceType("OFFICIAL")
                .build();
        SourceCandidate mediumPrefetch = SourceCandidate.builder()
                .url("https://open.example.com/docs")
                .prefetchedRawContentLength(150)
                .build();
        SourceCandidate satisfyingPrefetch = SourceCandidate.builder()
                .url("https://open.example.com/docs/api")
                .prefetchedRawContentLength(500)
                .build();
        SourceCandidate fullEnough = SourceCandidate.builder()
                .url("https://open.example.com/docs/full")
                .contentCompleteness("FULL_ENOUGH")
                .build();
        SourceCandidate fastLaneUsable = SourceCandidate.builder()
                .url("https://open.example.com/docs/fast-lane")
                .fastLaneUsable(true)
                .build();

        assertFalse(policy.hasAnyContentSignal(emptyShell));
        assertFalse(policy.hasSatisfyingContentSignal(emptyShell));
        assertTrue(policy.hasAnyContentSignal(mediumPrefetch));
        assertFalse(policy.hasSatisfyingContentSignal(mediumPrefetch));
        assertTrue(policy.hasAnyContentSignal(satisfyingPrefetch));
        assertTrue(policy.hasSatisfyingContentSignal(satisfyingPrefetch));
        assertTrue(policy.hasAnyContentSignal(fullEnough));
        assertTrue(policy.hasSatisfyingContentSignal(fullEnough));
        assertTrue(policy.hasAnyContentSignal(fastLaneUsable));
        assertTrue(policy.hasSatisfyingContentSignal(fastLaneUsable));
    }

    @Test
    void shouldRejectThinAndUtilityGateCandidatesForContentSignals() {
        SourceCandidate thinContent = SourceCandidate.builder()
                .url("https://open.example.com/docs/thin")
                .prefetchedRawContentLength(2_000)
                .contentCompleteness("THIN")
                .build();
        SourceCandidate loginShell = SourceCandidate.builder()
                .url("https://open.example.com/login")
                .title("Login")
                .prefetchedRawContentLength(2_000)
                .fastLaneUsable(true)
                .build();

        assertFalse(policy.hasAnyContentSignal(thinContent));
        assertFalse(policy.hasSatisfyingContentSignal(thinContent));
        assertFalse(policy.hasAnyContentSignal(loginShell));
        assertFalse(policy.hasSatisfyingContentSignal(loginShell));
    }
}
