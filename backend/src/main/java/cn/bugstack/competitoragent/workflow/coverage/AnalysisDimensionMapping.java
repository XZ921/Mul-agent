package cn.bugstack.competitoragent.workflow.coverage;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * 分析维度到字段契约的结构化映射。
 * 解析器不会直接散落写关键词判断，而是先从目录拿到命中的映射，再统一转换成字段契约。
 */
@Value
@Builder(toBuilder = true)
public class AnalysisDimensionMapping {

    String dimensionKey;
    List<String> matchedTerms;
    List<String> targetFields;
    List<String> evidencePathKeys;
    List<String> sourceTypes;
    List<String> queryIntents;
    boolean requiredByDefault;
    int priority;
    String reason;
}
