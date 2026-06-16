package cn.bugstack.competitoragent.search;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 最终采集目标轻量摘要。
 * <p>
 * 用于共享上下文、报告投影和节点洞察，避免把页面正文继续传给下游。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SearchSelectedTargetSummary {

    private String url;
    private String title;
    private String sourceType;
    private String sourceFamilyKey;
    private String providerKey;
    private String selectionStage;
    private String selectionReason;
    private Boolean reusedCollectedPage;
    private List<String> sourceUrls;
}
