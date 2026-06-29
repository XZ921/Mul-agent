package cn.bugstack.competitoragent.collection;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TavilyPrefetchedContentBlockClassifierTest {

    private final TavilyPrefetchedContentBlockClassifier classifier =
            new TavilyPrefetchedContentBlockClassifier();

    @Test
    void shouldCreateDeveloperDocsBlockFromParagraphBody() {
        List<StructuredContentBlock> blocks = classifier.classify("""
                用户管理 API
                开放平台提供用户授权、身份识别和用户资料读取能力。开发者可以通过 SDK 调用接口完成授权登录，
                并根据接口返回的 open_id 进行用户管理。
                """);

        assertThat(blocks).anySatisfy(block -> {
            assertThat(block.getBlockType()).isEqualTo("DEVELOPER_DOCS_BLOCK");
            assertThat(block.getQualitySignal()).contains("blockConfidence=");
            assertThat(block.getQualitySignal()).contains("paragraph-body");
        });
    }

    @Test
    void shouldNotCreateDeveloperBlockFromNavigationShell() {
        List<StructuredContentBlock> blocks = classifier.classify("""
                主站 开放平台 文档中心 管理中心 账号管理 应用管理 授权管理
                登录 注册 立即加入 帮助中心 联系我们 友情链接
                """);

        assertThat(blocks).isEmpty();
    }

    @Test
    void shouldPreferRepairOverPricingBlockForImplicitQuotaWording() {
        List<StructuredContentBlock> blocks = classifier.classify("""
                开发者权益说明
                开放平台为开发者提供接口调用能力。开发者每天享有 10000 次免费配额，
                超出后的使用安排以平台后续通知和控制台展示为准。
                """);

        assertThat(blocks).extracting(StructuredContentBlock::getBlockType)
                .doesNotContain("PRICING_BLOCK");
    }
}
