package cn.bugstack.competitoragent.task.definition;

import cn.bugstack.competitoragent.model.dto.CreateTaskRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务定义映射器负责把边界层请求统一归一化为稳定的任务定义对象。
 * 这样控制器 DTO 的演进不会直接污染计划生成和任务执行语义。
 */
@Component
@RequiredArgsConstructor
public class TaskDefinitionMapper {

    @SuppressWarnings("unused")
    private final ObjectMapper objectMapper;

    /**
     * 任务创建和计划预览都先走 TaskDraft。
     * 这样“用户输入长什么样”和“系统最终采用什么定义”能被显式区分，后续再补预算、质量要求、补充资料时也不会污染 controller DTO。
     */
    public TaskDraft toDraft(CreateTaskRequest request) {
        if (request == null) {
            return TaskDraft.builder()
                    .competitorNames(List.of())
                    .competitorUrls(List.of())
                    .analysisDimensions(List.of())
                    .sourceScope(List.of())
                    .sourceUrls(List.of())
                    .build();
        }
        return TaskDraft.builder()
                .taskName(trim(request.getTaskName()))
                .subjectProduct(trim(request.getSubjectProduct()))
                .competitorNames(normalizeList(request.getCompetitorNames()))
                .competitorUrls(normalizeList(request.getCompetitorUrls()))
                .analysisDimensions(normalizeList(request.getAnalysisDimensions()))
                .sourceScope(normalizeList(request.getSourceScope()))
                .reportLanguage(trimOrDefault(request.getReportLanguage(), "中文"))
                .reportTemplate(trimOrDefault(request.getReportTemplate(), "标准版"))
                .schemaId(request.getSchemaId())
                .sourceUrls(List.of())
                .build();
    }

    /**
     * 正式任务定义会把竞品名称和竞品官网收口到同一个对象里，
     * 避免后续规划链路继续在多个平行数组之间做脆弱的索引对齐。
     */
    public TaskDefinition toDefinition(TaskDraft draft) {
        List<TaskDefinition.CompetitorDefinition> competitors = new ArrayList<>();
        List<String> competitorNames = draft == null ? List.of() : defaultList(draft.getCompetitorNames());
        List<String> competitorUrls = draft == null ? List.of() : defaultList(draft.getCompetitorUrls());
        for (int index = 0; index < competitorNames.size(); index++) {
            competitors.add(TaskDefinition.CompetitorDefinition.builder()
                    .competitorName(competitorNames.get(index))
                    .officialUrl(index < competitorUrls.size() ? competitorUrls.get(index) : null)
                    .build());
        }
        return TaskDefinition.builder()
                .taskName(draft == null ? null : draft.getTaskName())
                .subjectProduct(draft == null ? null : draft.getSubjectProduct())
                .competitors(competitors)
                .analysisDimensions(draft == null ? List.of() : defaultList(draft.getAnalysisDimensions()))
                .sourceScope(draft == null ? List.of() : defaultList(draft.getSourceScope()))
                .reportLanguage(draft == null ? "中文" : trimOrDefault(draft.getReportLanguage(), "中文"))
                .reportTemplate(draft == null ? "标准版" : trimOrDefault(draft.getReportTemplate(), "标准版"))
                .schemaId(draft == null ? null : draft.getSchemaId())
                .qualityPolicy("score>=80 and no ERROR issues")
                .contractVersion("TASK_DEFINITION_V1")
                .sourceUrls(draft == null ? List.of() : defaultList(draft.getSourceUrls()))
                .build();
    }

    private List<String> normalizeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (String value : values) {
            String trimmed = trim(value);
            if (trimmed != null) {
                normalized.add(trimmed);
            }
        }
        return normalized;
    }

    private List<String> defaultList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private String trimOrDefault(String value, String defaultValue) {
        String trimmed = trim(value);
        return trimmed == null ? defaultValue : trimmed;
    }

    private String trim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
