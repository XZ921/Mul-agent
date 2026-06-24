package cn.bugstack.competitoragent.orchestration;

import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 协作目标组装器。
 * 它只从任务定义中提取前置规划事实，不调用外部模型，也不生成执行节点。
 */
@Slf4j
@Component
public class CollaborationGoalAssembler {

    private static final List<String> DEFAULT_DIMENSIONS = List.of("产品功能", "目标用户", "价格策略", "技术能力", "市场定位");
    private static final Map<String, Object> DEFAULT_BUDGET = Map.of(
            "maxSearchQueries", 20,
            "maxModelCalls", 12,
            "maxAutoDecisions", 5
    );
    private static final Map<String, Object> DEFAULT_CONSTRAINTS = Map.of(
            "requireSourceUrls", true,
            "allowDynamicBranch", true,
            "requiresHumanConfirmationForRerun", true
    );

    private final ObjectMapper objectMapper;

    public CollaborationGoalAssembler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 从 AnalysisTask 组装 CollaborationGoal。
     * 任务表中的列表字段以 JSON 字符串保存，解析失败时降级为空列表，让后续策略显式看到缺口。
     */
    public CollaborationGoal assemble(AnalysisTask task) {
        if (task == null) {
            return CollaborationGoal.builder()
                    .goalId("cg-task-unassigned")
                    .budget(DEFAULT_BUDGET)
                    .constraints(DEFAULT_CONSTRAINTS)
                    .analysisDimensions(DEFAULT_DIMENSIONS)
                    .build()
                    .normalized();
        }
        List<String> competitors = parseStringList(task.getCompetitorNames(), "competitorNames");
        List<String> sourceUrls = parseStringList(task.getCompetitorUrls(), "competitorUrls");
        List<String> analysisDimensions = parseStringList(task.getAnalysisDimensions(), "analysisDimensions");
        List<String> sourceScope = parseStringList(task.getSourceScope(), "sourceScope");
        Map<String, Object> constraints = new java.util.LinkedHashMap<>(DEFAULT_CONSTRAINTS);
        constraints.put("sourceScope", sourceScope);

        return CollaborationGoal.builder()
                .goalId("cg-task-" + task.getId())
                .taskId(task.getId())
                .subject(resolveSubject(task))
                .competitors(competitors)
                .analysisDimensions(analysisDimensions.isEmpty() ? DEFAULT_DIMENSIONS : analysisDimensions)
                .deliverableType("COMPETITOR_REPORT")
                .depth(resolveDepth(task.getReportTemplate()))
                .budget(DEFAULT_BUDGET)
                .constraints(constraints)
                .sourceUrls(sourceUrls)
                .build()
                .normalized();
    }

    private List<String> parseStringList(String rawValue, String fieldName) {
        if (rawValue == null || rawValue.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(rawValue, new TypeReference<>() {
            });
        } catch (Exception ex) {
            log.warn("collaboration goal assembler failed to parse {} as json array, raw={}", fieldName, rawValue, ex);
            return List.of();
        }
    }

    private String resolveSubject(AnalysisTask task) {
        String subjectProduct = OrchestrationTextNormalizer.blankToNull(task.getSubjectProduct());
        String taskName = OrchestrationTextNormalizer.blankToNull(task.getTaskName());
        if (subjectProduct != null && taskName != null) {
            return subjectProduct + "：" + taskName;
        }
        return subjectProduct == null ? taskName : subjectProduct;
    }

    private String resolveDepth(String reportTemplate) {
        String template = OrchestrationTextNormalizer.blankToNull(reportTemplate);
        if (template == null) {
            return "STANDARD";
        }
        if (template.contains("深度")) {
            return "DEEP";
        }
        if (template.contains("轻量")) {
            return "LIGHT";
        }
        return "STANDARD";
    }
}
