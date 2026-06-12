package cn.bugstack.competitoragent.task.definition;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * 正式任务定义是“任务创建链路的第一真相”。
 * 任何预览、计划生成、落库、恢复都只能消费这一层对象，不能继续各自从 AnalysisTask / CreateTaskRequest 上重新猜字段。
 */
@Value
@Builder(toBuilder = true)
public class TaskDefinition {
    String taskName;
    String subjectProduct;
    List<CompetitorDefinition> competitors;
    List<String> analysisDimensions;
    List<String> sourceScope;
    String reportLanguage;
    String reportTemplate;
    Long schemaId;
    String qualityPolicy;
    String contractVersion;
    List<String> sourceUrls;

    @Value
    @Builder
    public static class CompetitorDefinition {
        String competitorName;
        String officialUrl;
    }
}
