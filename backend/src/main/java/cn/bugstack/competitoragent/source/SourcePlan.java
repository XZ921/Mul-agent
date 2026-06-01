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
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SourcePlan {

    private String sourceType;
    private List<String> urls;
    private String notes;
    private List<SourceCandidate> candidates;
}
