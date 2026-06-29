package cn.bugstack.competitoragent.search.tavily;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 单个域名提示对象。
 * sourceUrls 是“无幻觉原则”的硬约束，任何域名提示都必须能够回指它是从哪里推导出来的。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class DomainHint {

    private String domain;
    private String sourceFamily;
    private double confidence;
    private String source;
    private String reason;
    private List<String> sourceUrls;
}
