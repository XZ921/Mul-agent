package cn.bugstack.competitoragent.search;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * 正文可用性评分结果。
 * sourceTier 给报告标注和人工终审使用，usability 只描述正文是否可用，不等同于来源可信度。
 */
@Value
@Builder(toBuilder = true)
public class ContentUsabilityScore {

    double usability;
    String sourceTier;
    List<String> reasons;
}
