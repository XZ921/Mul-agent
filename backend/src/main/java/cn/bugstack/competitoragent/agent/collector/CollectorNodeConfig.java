package cn.bugstack.competitoragent.agent.collector;

import cn.bugstack.competitoragent.search.SearchExecutionPlan;
import cn.bugstack.competitoragent.search.SearchAuditSnapshot;
import cn.bugstack.competitoragent.search.SearchRuntimePolicy;
import cn.bugstack.competitoragent.source.SourceCandidate;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 采集节点配置。
 * 从 WorkflowFactory 规划阶段写入，并由 CollectorAgent 在运行时消费。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({
        "competitorName",
        "competitorUrls",
        "sourceType",
        "sourceScope",
        "schemaName",
        "discoveryNotes",
        "sourceCandidates",
        "searchMode",
        "searchQueries",
        "searchFallbackOrder",
        "verifyCandidates",
        "verifyResultPage",
        "minVerifiedCandidates",
        "preferredDomains",
        "blockedDomains",
        "browserSearchEnabled",
        "maxSearchResults",
        "searchTimeoutMillis",
        "searchRuntimePolicy",
        "searchExecutionPlan",
        "searchAuditCheckpoint"
})
public class CollectorNodeConfig {

    private String competitorName;
    private List<String> competitorUrls;
    private String sourceType;
    private List<String> sourceScope;
    private String schemaName;
    private String discoveryNotes;
    private List<SourceCandidate> sourceCandidates;

    // 下面这些字段为运行期搜索与验证流程预留，当前阶段先由规划层写入默认值。
    private String searchMode;
    private List<String> searchQueries;
    private List<String> searchFallbackOrder;
    private Boolean verifyCandidates;
    private Boolean verifyResultPage;
    private Integer minVerifiedCandidates;
    private List<String> preferredDomains;
    private List<String> blockedDomains;
    private Boolean browserSearchEnabled;
    private Integer maxSearchResults;
    private Long searchTimeoutMillis;
    private SearchRuntimePolicy searchRuntimePolicy;
    private SearchExecutionPlan searchExecutionPlan;
    private SearchAuditSnapshot searchAuditCheckpoint;
}
