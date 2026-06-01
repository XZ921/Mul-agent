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

    @Schema(description = "Whether prefetched page content is available")
    private Boolean hasPrefetchedPage;
}
