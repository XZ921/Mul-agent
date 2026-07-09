package cn.bugstack.competitoragent.conversation;

import cn.bugstack.competitoragent.model.dto.ConversationResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FormDraftBuilderTest {

    private final FormDraftBuilder builder = new FormDraftBuilder();

    @Test
    void shouldFallbackToStageOneFriendlyDimensionsWhenMessageHasNoExplicitDimensionKeywords() {
        ConversationResponse.FormDraftSummary draft = builder.buildDraft(
                "帮我做一个飞书和 Notion 的竞品分析",
                null
        );

        assertThat(draft.getAnalysisDimensions())
                .containsExactly("产品功能", "目标用户", "市场定位");
    }
}
