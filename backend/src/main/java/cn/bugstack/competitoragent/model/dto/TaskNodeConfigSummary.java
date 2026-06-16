package cn.bugstack.competitoragent.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Structured node configuration summary")
public class TaskNodeConfigSummary {

    @Schema(description = "Human readable summary text")
    private String summaryText;

    @Schema(description = "Competitor name for collector node")
    private String competitorName;

    @Schema(description = "Raw source type enum")
    private String sourceType;

    /**
     * 数据源家族键。
     * 预览和运行态摘要都要显式暴露，便于验收 preview/runtime 是否使用同一套来源家族契约。
     */
    @Schema(description = "Source family key")
    private String sourceFamilyKey;

    /**
     * 数据源家族角色。
     * 用于区分业务主力来源与公网搜索辅助来源，避免只从 sourceType 反推策略语义。
     */
    @Schema(description = "Source family role")
    private String sourceFamilyRole;

    @Schema(description = "Primary tools declared by the source family")
    private List<String> primaryTools;

    @Schema(description = "Auxiliary tools declared by the source family")
    private List<String> auxiliaryTools;

    @Schema(description = "Query templates declared by the source family")
    private List<String> queryTemplates;

    @Schema(description = "Localized source type label")
    private String sourceTypeLabel;

    @Schema(description = "Raw search mode enum")
    private String searchMode;

    @Schema(description = "Localized search mode label")
    private String searchModeLabel;

    @Schema(description = "Planned source candidate count")
    private Integer candidateCount;

    @Schema(description = "Planned search query count")
    private Integer queryCount;

    @Schema(description = "Planned execution step count")
    private Integer stepCount;

    @Schema(description = "Whether browser search is enabled")
    private Boolean browserSearchEnabled;

    @Schema(description = "Whether result page verification is enabled")
    private Boolean verificationEnabled;

    @Schema(description = "Minimum verified candidates")
    private Integer minVerifiedCandidates;

    @Schema(description = "Collector source scope")
    private List<String> sourceScope;

    @Schema(description = "Preferred domains")
    private List<String> preferredDomains;

    @Schema(description = "Planned competitor URLs")
    private List<String> competitorUrls;

    @Schema(description = "Discovery note")
    private String discoveryNotes;

    @Schema(description = "Writer mode")
    private String mode;

    @Schema(description = "Report language")
    private String reportLanguage;

    @Schema(description = "Report template")
    private String reportTemplate;

    @Schema(description = "Reviewer quality policy")
    private String qualityPolicy;

    @Schema(description = "Reviewer source node")
    private String sourceNode;

    @Schema(description = "Analyzer competitor count")
    private Integer competitorCount;

    @Schema(description = "Analyzer dimension count")
    private Integer dimensionCount;

    @Schema(description = "Extractor dimensions")
    private List<String> dimensions;
}
