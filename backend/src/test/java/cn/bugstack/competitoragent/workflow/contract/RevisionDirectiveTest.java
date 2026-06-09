package cn.bugstack.competitoragent.workflow.contract;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RevisionDirectiveTest {

    @Test
    void shouldNormalizeSearchQualityDirectiveAndDefaultCollectionTarget() {
        RevisionDirective directive = RevisionDirective.builder()
                .category("search_quality")
                .targetSection("定价策略")
                .searchQueries(List.of("Notion AI pricing", " Notion AI pricing "))
                .sourceUrls(List.of("https://www.notion.so/pricing", "", "https://www.notion.so/pricing"))
                .build();

        RevisionDirective normalized = directive.normalized();

        assertEquals("SEARCH_QUALITY", normalized.getCategory());
        assertEquals("SUPPLEMENT_EVIDENCE", normalized.getActionType());
        assertEquals("HIGH", normalized.getPriority());
        assertEquals("collect_sources", normalized.getTargetNode());
        assertEquals("CREATE_SUPPLEMENT_BRANCH", normalized.getOrchestrationAction());
        assertEquals(List.of("Notion AI pricing"), normalized.getSearchQueries());
        assertEquals(List.of("https://www.notion.so/pricing"), normalized.getSourceUrls());
        assertTrue(normalized.getSearchFeedback().contains("定价策略"));
    }

    @Test
    void shouldFallbackToRewriteActionForExpressionIssue() {
        RevisionDirective directive = RevisionDirective.builder()
                .category("expression_issue")
                .targetSection("结论")
                .summary("收紧绝对化表述")
                .build();

        RevisionDirective normalized = directive.normalized();

        assertEquals("EXPRESSION_ISSUE", normalized.getCategory());
        assertEquals("REWRITE_SECTION", normalized.getActionType());
        assertEquals("MEDIUM", normalized.getPriority());
        assertEquals("rewrite_report", normalized.getTargetNode());
        assertEquals("CREATE_REWRITE_BRANCH", normalized.getOrchestrationAction());
        assertTrue(normalized.getExpectedOutcome().contains("结论"));
    }
}
