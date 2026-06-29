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

    private String searchDepth;
    private boolean includeRawContent;
    private int maxResults;
    private String expansionReason;
}
