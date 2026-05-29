package cn.bugstack.competitoragent.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 质检失败后的修订计划
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "质检失败后的修订计划")
public class RevisionPlan {

    @Schema(description = "是否建议重写", example = "true")
    private boolean rewriteRequired;

    @Schema(description = "修订优先级摘要", example = "优先补齐证据，再修正文档结构")
    private String summary;

    @Schema(description = "需要修订的章节/问题清单")
    private List<RevisionItem> items;

    @Schema(description = "下一轮写作要点")
    private List<String> rewriteGuidelines;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "单条修订项")
    public static class RevisionItem {
        @Schema(description = "问题类型", example = "MISSING_EVIDENCE")
        private String type;

        @Schema(description = "对应章节", example = "功能对比")
        private String section;

        @Schema(description = "严重程度", example = "WARNING")
        private String severity;

        @Schema(description = "修订建议")
        private String suggestion;
    }
}
