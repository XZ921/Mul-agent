package cn.bugstack.competitoragent.collection.quality;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.util.List;

/**
 * 证据质量门禁结论。
 * 统一承接来源真实性、正文可用性、任务相关性和最终可用性得分，便于采集链路与审计链路共享同一份判断。
 */
@Value
@Builder(toBuilder = true)
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class EvidenceQualityVerdict {

    double sourceAuthenticityScore;
    double contentUsabilityScore;
    String sourceTier;
    double taskRelevanceScore;
    double evidenceUsabilityScore;
    List<EvidenceQualityIssue> issues;
    List<String> qualitySignals;
    boolean repairRequired;
}
