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
}
