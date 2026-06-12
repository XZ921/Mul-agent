package cn.bugstack.competitoragent.search;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchKeywordPolicyTest {

    private final SearchKeywordPolicy policy = new SearchKeywordPolicy();

    @Test
    void shouldExposeChineseExpectedKeywordsForDocsNewsAndReview() {
        assertTrue(policy.expectedKeywords("DOCS").contains("文档"));
        assertTrue(policy.expectedKeywords("NEWS").contains("发布日志"));
        assertTrue(policy.expectedKeywords("REVIEW").contains("评测"));
    }

    @Test
    void shouldExposeMarketingAndHighValueSignalsSeparately() {
        assertTrue(policy.marketingKeywords("OFFICIAL").contains("立即购买"));
        assertTrue(policy.highValueInformationKeywords("PRICING").contains("计费"));
    }
}
