package cn.bugstack.competitoragent.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Collector selected target summary")
public class CollectorSelectedTargetSummary {

    @Schema(description = "Target URL")
    private String url;

    @Schema(description = "Target title")
    private String title;

    @Schema(description = "Whether target is verified")
    private Boolean verified;

    @Schema(description = "Browser trace identifier")
    private String browserTraceId;

    @Schema(description = "Selection stage")
    private String selectionStage;

    @Schema(description = "Selection reason")
    private String selectionReason;

    /**
     * 兼容旧字段名的选中摘要。
     * 该字段历史上直接面向前端渲染，因此这里继续保留，
     * 避免旧页面在升级期间因为字段名变化而读不到选源解释。
     */
    @Schema(description = "Human readable target selection summary")
    private String targetSelectionSummary;

    /**
     * 标准化后的选中摘要。
     * 与 SourceCandidate.selectionSummary 保持同一语义，
     * 用于统一描述“为什么这个候选最终进入正式采集”。
     */
    @Schema(description = "Normalized selection summary")
    private String selectionSummary;

    @Schema(description = "Source trust tier")
    private String trustTier;

    @Schema(description = "Localized source trust tier label")
    private String trustTierLabel;

    @Schema(description = "Candidate total score")
    private Double totalScore;

    /**
     * 排序原因明细。
     * 让前端不仅看到最终得分，还能看到“高可信来源 / 命中文档 / 时效性更高”等排序依据。
     */
    @Schema(description = "Human readable ranking reasons")
    private java.util.List<String> rankingReasons;

    @Schema(description = "Human readable ranking summary")
    private String rankingSummary;

    @Schema(description = "Whether prefetched page content is available")
    private Boolean hasPrefetchedPage;
}
