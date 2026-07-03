package cn.bugstack.competitoragent.search.tavily;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 单次 Tavily 请求的运行时 profile。
 * Provider 后续只消费 profile，而不是在内部硬编码各类 query 拼装逻辑。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class TavilySearchProfile {

    private String family;
    private TavilyQueryMode queryMode;
    private String query;

    @Builder.Default
    private List<String> includeDomains = new ArrayList<>();

    /**
     * Gate 使用的官方域名提示。
     * includeDomains 只表示 Tavily API 的检索范围约束；搜索优先模式会清空 includeDomains，
     * 但仍需要保留这些域名给页面类型识别与官方命中质量判断使用。
     */
    @Builder.Default
    private List<String> officialDomains = new ArrayList<>();

    private String searchDepth;
    private boolean includeRawContent;
    private int maxResults;
    private String expansionReason;

    /**
     * 字段级 query 元数据。
     * profile 需要携带这些字段，保证搜索结果、prefetched content 和审计日志都能回溯到原始字段路径。
     */
    private String fieldName;
    private String evidencePathKey;
    private String queryIntent;
    private String fieldEvidenceQueryFingerprint;
    private String fieldEvidenceQueryReason;
}
