package cn.bugstack.competitoragent.model.dto;

import cn.bugstack.competitoragent.search.SearchExecutionPlan;
import cn.bugstack.competitoragent.search.SearchExecutionTrace;
import cn.bugstack.competitoragent.search.SearchProgressSnapshot;
import cn.bugstack.competitoragent.source.SourceCandidate;
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
@Schema(description = "Collector node insight")
public class CollectorNodeInsightResponse {

    @Schema(description = "Competitor name")
    private String competitorName;

    @Schema(description = "Source type enum")
    private String sourceType;

    @Schema(description = "Localized source type label")
    private String sourceTypeLabel;

    @Schema(description = "Collector source scope")
    private List<String> sourceScope;

    @Schema(description = "Collector competitor URLs")
    private List<String> competitorUrls;

    @Schema(description = "Search mode enum")
    private String searchMode;

    @Schema(description = "Localized search mode label")
    private String searchModeLabel;

    @Schema(description = "Search queries")
    private List<String> searchQueries;

    @Schema(description = "Whether browser search is enabled")
    private Boolean browserSearchEnabled;

    @Schema(description = "Whether result page verification is enabled")
    private Boolean verifyResultPage;

    @Schema(description = "Minimum verified candidates")
    private Integer minVerifiedCandidates;

    @Schema(description = "Preferred domains")
    private List<String> preferredDomains;

    @Schema(description = "Candidate count")
    private Integer candidateCount;

    @Schema(description = "Selected count")
    private Integer selectedCount;

    @Schema(description = "Successful collected count")
    private Integer successCollected;

    @Schema(description = "Total collected count")
    private Integer totalCollected;

    @Schema(description = "Discovery notes")
    private String discoveryNotes;

    @Schema(description = "Current search progress")
    private SearchProgressSnapshot searchProgress;

    @Schema(description = "Search execution plan")
    private SearchExecutionPlan searchExecutionPlan;

    @Schema(description = "Search execution trace")
    private SearchExecutionTrace searchExecutionTrace;

    @Schema(description = "Search progress snapshots")
    private List<SearchProgressSnapshot> searchProgressSnapshots;

    @Schema(description = "Source candidates")
    private List<SourceCandidate> sourceCandidates;

    @Schema(description = "Selected targets")
    private List<CollectorSelectedTargetSummary> selectedTargets;
}
