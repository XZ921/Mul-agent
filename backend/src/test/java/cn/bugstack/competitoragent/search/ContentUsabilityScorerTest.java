package cn.bugstack.competitoragent.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContentUsabilityScorerTest {

    private final ContentUsabilityScorer scorer = new ContentUsabilityScorer();

    @Test
    void shouldScoreNavShellLowEvenWhenFromTrustedOfficialDomain() {
        ContentUsabilityScore score = scorer.score(CollectedPageView.builder()
                .url("https://app.bilibili.com")
                .sourceTrust(0.95D)
                .sourceType("OFFICIAL")
                .bodyText("下载 安卓 iOS TV PC 车机 扫码下载 首页 登录 注册 帮助中心 联系我们")
                .structuredBlocks(List.of())
                .build());

        assertThat(score.getUsability()).isLessThan(0.4D);
        assertThat(score.getReasons()).contains("NAV_SHELL_DETECTED");
        assertThat(score.getSourceTier()).isEqualTo("OFFICIAL");
    }

    @Test
    void shouldScoreThirdPartyHighWhenContentIsRichAndReal() {
        ContentUsabilityScore score = scorer.score(CollectedPageView.builder()
                .url("https://some-tech-blog.com/bilibili-open-pricing")
                .sourceTrust(0.5D)
                .sourceType("REVIEW")
                .bodyText("哔哩哔哩开放平台计费说明：基础接口免费，超出额度按调用量计费，"
                        + "直播开放能力需企业认证，具体档位为企业认证、接口额度、商业授权、"
                        + "开发者生态服务与开放能力组合。文章逐项对比官方文档、调用限制、"
                        + "常见接入教程和用户反馈，说明哪些功能免费、哪些能力需要申请。")
                .structuredBlocks(List.of("PRICING_BLOCK"))
                .build());

        assertThat(score.getUsability()).isGreaterThan(0.6D);
        assertThat(score.getSourceTier()).isEqualTo("THIRD_PARTY");
        assertThat(score.getReasons()).contains("RICH_BODY_TEXT");
    }
}
