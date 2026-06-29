package cn.bugstack.competitoragent.collection.quality;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * 证据质量评估上下文。
 * 这里优先承接字段级证据路径信号；如果当前阶段只有节点级 coverage 轻量视图，也允许退化运行。
 */
@Value
@Builder(toBuilder = true)
public class EvidenceQualityContext {

    String url;
    String sourceType;
    String fieldName;
    String evidencePathKey;
    List<String> expectedSignals;
    List<String> requiredCoverageFields;
    List<String> blockingCoverageFields;
    List<String> coverageQueryIntents;
}
