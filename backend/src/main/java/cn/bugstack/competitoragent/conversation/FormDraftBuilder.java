package cn.bugstack.competitoragent.conversation;

import cn.bugstack.competitoragent.model.dto.ConversationResponse;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 表单草稿构建器。
 * 当前阶段先把自然语言映射成“够用、可修改、可预览”的草稿，
 * 而不是追求一次性抽取所有字段。
 */
@Component
public class FormDraftBuilder {

    public ConversationResponse.FormDraftSummary buildDraft(String message,
                                                            ConversationResponse.FormDraftSummary currentDraft) {
        List<String> competitorNames = currentDraft == null
                ? new ArrayList<>()
                : new ArrayList<>(safeList(currentDraft.getCompetitorNames()));
        List<String> analysisDimensions = currentDraft == null
                ? new ArrayList<>()
                : new ArrayList<>(safeList(currentDraft.getAnalysisDimensions()));
        List<String> sourceScope = currentDraft == null
                ? new ArrayList<>()
                : new ArrayList<>(safeList(currentDraft.getSourceScope()));
        String subjectProduct = currentDraft == null || currentDraft.getSubjectProduct() == null
                ? "待补充本方产品"
                : currentDraft.getSubjectProduct();

        if (competitorNames.isEmpty()) {
            competitorNames.addAll(extractCompetitors(message));
        }
        if (analysisDimensions.isEmpty() || containsFocusChange(message)) {
            List<String> extractedDimensions = extractDimensions(message);
            if (!extractedDimensions.isEmpty()) {
                analysisDimensions = extractedDimensions;
            }
        }
        if (sourceScope.isEmpty()) {
            sourceScope.addAll(extractSourceScope(message));
        }
        if (competitorNames.isEmpty()) {
            competitorNames.add("待补充竞品");
        }

        String taskName = buildTaskName(competitorNames);
        String changeSummary = containsFocusChange(message)
                ? "已根据自然语言调整重点维度。"
                : "已根据自然语言生成任务草稿。";
        String previewSummary = "当前草稿包含 "
                + competitorNames.size()
                + " 个竞品，重点维度为 "
                + String.join("、", analysisDimensions)
                + "。";

        return ConversationResponse.FormDraftSummary.builder()
                .taskName(taskName)
                .subjectProduct(subjectProduct)
                .competitorNames(competitorNames)
                .analysisDimensions(analysisDimensions)
                .sourceScope(sourceScope)
                .changeSummary(changeSummary)
                .previewSummary(previewSummary)
                .build();
    }

    private List<String> extractCompetitors(String message) {
        String normalized = message == null ? "" : message.trim();
        String marker = "竞品分析";
        int markerIndex = normalized.indexOf(marker);
        if (markerIndex < 0) {
            return List.of();
        }
        String candidatePart = normalized.substring(0, markerIndex)
                .replace("帮我做一个", "")
                .replace("帮我做", "")
                .replace("做一个", "")
                .replace("做个", "")
                .replace("关于", "")
                .replace("的", "")
                .trim();
        if (candidatePart.isBlank()) {
            return List.of();
        }
        String[] rawItems = candidatePart.split("和|、|,|，|/|VS|vs");
        LinkedHashSet<String> competitors = new LinkedHashSet<>();
        for (String rawItem : rawItems) {
            String competitor = rawItem == null ? "" : rawItem.trim();
            if (!competitor.isBlank()) {
                competitors.add(competitor);
            }
        }
        return new ArrayList<>(competitors);
    }

    /**
     * 维度抽取只认少量稳定业务词，避免在 MVP 阶段把不确定推断直接写进草稿。
     */
    private List<String> extractDimensions(String message) {
        String lowerMessage = message == null ? "" : message.toLowerCase(Locale.ROOT);
        LinkedHashSet<String> dimensions = new LinkedHashSet<>();
        if (lowerMessage.contains("ai")) {
            dimensions.add("AI 能力");
        }
        if (message != null && message.contains("定价")) {
            dimensions.add("价格策略");
        }
        if (message != null && message.contains("功能")) {
            dimensions.add("产品功能");
        }
        if (message != null && message.contains("定位")) {
            dimensions.add("市场定位");
        }
        if (dimensions.isEmpty()) {
            dimensions.add("产品功能");
            dimensions.add("价格策略");
        }
        return new ArrayList<>(dimensions);
    }

    private List<String> extractSourceScope(String message) {
        Set<String> scope = new LinkedHashSet<>();
        scope.add("官网");
        scope.add("产品文档");
        if (message != null && (message.contains("定价") || message.toLowerCase(Locale.ROOT).contains("pricing"))) {
            scope.add("定价页");
        }
        return new ArrayList<>(scope);
    }

    private String buildTaskName(List<String> competitorNames) {
        if (competitorNames == null || competitorNames.isEmpty()) {
            return "新任务草稿";
        }
        if (competitorNames.size() == 1) {
            return competitorNames.get(0) + " 竞品分析";
        }
        return competitorNames.get(0) + " vs " + competitorNames.get(1) + " 竞品分析";
    }

    private boolean containsFocusChange(String message) {
        return message != null && (message.contains("重点改成") || message.contains("重点放在"));
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }
}
