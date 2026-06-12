package cn.bugstack.competitoragent.task.definition;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * 任务草稿只承载“用户刚提交的原始意图”。
 * 这里允许字段尚未补齐，但必须保持字段语义稳定，避免后续规划阶段直接读取 CreateTaskRequest 这类边界输入对象。
 */
@Value
@Builder
public class TaskDraft {
    String taskName;
    String subjectProduct;
    List<String> competitorNames;
    List<String> competitorUrls;
    List<String> analysisDimensions;
    List<String> sourceScope;
    String reportLanguage;
    String reportTemplate;
    Long schemaId;
    List<String> sourceUrls;
}
