package cn.bugstack.competitoragent.source;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 某一类来源范围的采集计划。
 * 规划阶段会为每个 sourceType 生成一个 SourcePlan，并挂到采集节点配置中。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class SourcePlan {

    private String sourceType;
    private String sourceFamilyKey;
    private String sourceFamilyRole;
    private List<String> primaryTools;
    private List<String> auxiliaryTools;
    private List<String> queryTemplates;
    private List<String> urls;
    private List<String> sourceUrls;
    private String notes;
    private List<SourceCandidate> candidates;
}
