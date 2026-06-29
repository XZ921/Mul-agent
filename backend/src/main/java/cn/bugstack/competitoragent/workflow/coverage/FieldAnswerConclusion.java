package cn.bugstack.competitoragent.workflow.coverage;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * 字段级答案合成结论。
 * 它把字段状态、答案文本、支撑来源和下一步动作显式结构化，避免只在最终报告自然语言里隐藏判断依据。
 */
@Value
@Builder(toBuilder = true)
public class FieldAnswerConclusion {

    String field;
    String coverageStatus;
    String answerValue;
    List<String> sourceUrls;
    List<String> reasoningSteps;
    List<String> contradictions;
    double confidence;
    String recommendedNextAction;
}
