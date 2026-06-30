package cn.bugstack.competitoragent.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cn.bugstack.competitoragent.search.SearchAuditSnapshot;
import cn.bugstack.competitoragent.search.SearchExecutionTrace;
import cn.bugstack.competitoragent.search.tavily.TavilyFastLaneAudit;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 搜索审计轻量摘要。
 * <p>
 * 该对象面向报告、洞察和恢复入口，只保留计数、结论和 sourceUrls，
 * 避免下游再次绑定完整候选池、执行计划和页面正文。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SearchAuditSummary {

    private Integer candidateCount;
    private Integer selectedCount;
    private Integer discardedCount;
    private Integer attemptedCount;
    private Boolean degraded;
    private String degradationReason;
    private String fallbackDecision;
    private String recoveryCheckpoint;
    private List<String> sourceUrls;
    private Integer fieldEvidenceQueryCount;
    private List<String> fieldEvidenceFields;
    private List<String> fieldEvidencePaths;
    private TavilyFastLaneAudit tavilyFastLaneAudit;

    /**
     * 报告层只应消费稳定的搜索审计 DTO，而不应自己拼装 Tavily 审计细节。
     * 因此把 trace/audit JSON 到轻量摘要的投影逻辑收口在 DTO 自身，
     * 避免 report 包重新依赖 search 实现类。
     */
    public static SearchAuditSummary fromTrace(ObjectMapper objectMapper,
                                               JsonNode traceNode,
                                               JsonNode auditNode) {
        if (traceNode == null || traceNode.isMissingNode() || traceNode.isNull()) {
            return SearchAuditSummary.builder()
                    .candidateCount(0)
                    .selectedCount(0)
                    .discardedCount(0)
                    .attemptedCount(0)
                    .sourceUrls(List.of())
                    .build();
        }
        return SearchAuditSummary.builder()
                .candidateCount(readInteger(traceNode, "plannedCandidateCount"))
                .selectedCount(readInteger(traceNode, "selectedCandidateCount"))
                .discardedCount(readInteger(traceNode, "discardedCandidateCount"))
                .attemptedCount(readInteger(traceNode, "attemptedCandidateCount"))
                .degraded(readBoolean(traceNode, "degraded"))
                .degradationReason(text(traceNode, "degradationReason"))
                .fallbackDecision(text(traceNode, "fallbackDecision"))
                .recoveryCheckpoint(text(traceNode, "recoveryCheckpoint"))
                .sourceUrls(readStringList(traceNode.path("selectedUrls")))
                .fieldEvidenceQueryCount(readInteger(traceNode, "fieldEvidenceQueryCount"))
                .fieldEvidenceFields(readStringList(traceNode.path("fieldEvidenceFields")))
                .fieldEvidencePaths(readStringList(traceNode.path("fieldEvidencePaths")))
                .tavilyFastLaneAudit(readTavilyFastLaneAudit(objectMapper, traceNode, auditNode))
                .build();
    }

    /**
     * 多个 collector 节点的搜索审计摘要在进入报告层前先统一合并。
     * 这样下游只面对稳定的计数、sourceUrls 和 Tavily 审计聚合结果，
     * 不需要再感知 search runtime 的内部对象结构。
     */
    public static SearchAuditSummary merge(List<SearchAuditSummary> summaries) {
        if (summaries == null || summaries.isEmpty()) {
            return SearchAuditSummary.builder()
                    .candidateCount(0)
                    .selectedCount(0)
                    .discardedCount(0)
                    .attemptedCount(0)
                    .sourceUrls(List.of())
                    .build();
        }
        LinkedHashSet<String> sourceUrls = new LinkedHashSet<>();
        int candidateCount = 0;
        int selectedCount = 0;
        int discardedCount = 0;
        int attemptedCount = 0;
        int fieldEvidenceQueryCount = 0;
        LinkedHashSet<String> fieldEvidenceFields = new LinkedHashSet<>();
        LinkedHashSet<String> fieldEvidencePaths = new LinkedHashSet<>();
        for (SearchAuditSummary summary : summaries) {
            if (summary == null) {
                continue;
            }
            candidateCount += summary.getCandidateCount() == null ? 0 : summary.getCandidateCount();
            selectedCount += summary.getSelectedCount() == null ? 0 : summary.getSelectedCount();
            discardedCount += summary.getDiscardedCount() == null ? 0 : summary.getDiscardedCount();
            attemptedCount += summary.getAttemptedCount() == null ? 0 : summary.getAttemptedCount();
            fieldEvidenceQueryCount += summary.getFieldEvidenceQueryCount() == null ? 0 : summary.getFieldEvidenceQueryCount();
            if (summary.getSourceUrls() != null) {
                sourceUrls.addAll(summary.getSourceUrls());
            }
            if (summary.getFieldEvidenceFields() != null) {
                fieldEvidenceFields.addAll(summary.getFieldEvidenceFields());
            }
            if (summary.getFieldEvidencePaths() != null) {
                fieldEvidencePaths.addAll(summary.getFieldEvidencePaths());
            }
        }
        TavilyFastLaneAudit tavilyFastLaneAudit = TavilyFastLaneAudit.merge(
                summaries.stream()
                        .map(summary -> summary == null ? null : summary.getTavilyFastLaneAudit())
                        .toList()
        );
        return SearchAuditSummary.builder()
                .candidateCount(candidateCount)
                .selectedCount(selectedCount)
                .discardedCount(discardedCount)
                .attemptedCount(attemptedCount)
                .sourceUrls(new ArrayList<>(sourceUrls))
                .fieldEvidenceQueryCount(fieldEvidenceQueryCount)
                .fieldEvidenceFields(new ArrayList<>(fieldEvidenceFields))
                .fieldEvidencePaths(new ArrayList<>(fieldEvidencePaths))
                .tavilyFastLaneAudit(tavilyFastLaneAudit)
                .build();
    }

    /**
     * 从正式审计快照中提取轻量摘要。
     * 这里显式兜底空列表和空计数，保证下游 DTO 不再因为 null 判空而重新回读大对象。
     */
    public static SearchAuditSummary from(SearchAuditSnapshot snapshot) {
        if (snapshot == null) {
            return SearchAuditSummary.builder()
                    .candidateCount(0)
                    .selectedCount(0)
                    .discardedCount(0)
                    .attemptedCount(0)
                    .sourceUrls(List.of())
                    .build();
        }
        SearchAuditSummary existing = snapshot.getSummary();
        SearchExecutionTrace trace = snapshot.getExecutionTrace();
        TavilyFastLaneAudit tavilyFastLaneAudit = snapshot.getTavilyFastLaneAudit();
        if (tavilyFastLaneAudit == null && trace != null) {
            tavilyFastLaneAudit = trace.getTavilyFastLaneAudit();
        }
        if (tavilyFastLaneAudit == null && existing != null) {
            tavilyFastLaneAudit = existing.getTavilyFastLaneAudit();
        }
        return SearchAuditSummary.builder()
                .candidateCount(size(snapshot.getSourceCandidates(), existing == null ? null : existing.getCandidateCount()))
                .selectedCount(size(snapshot.getSelectedTargets(), existing == null ? null : existing.getSelectedCount()))
                .discardedCount(size(snapshot.getDiscardedCandidates(), existing == null ? null : existing.getDiscardedCount()))
                .attemptedCount(size(snapshot.getAttemptedTargets(), existing == null ? null : existing.getAttemptedCount()))
                .degraded(trace != null ? trace.getDegraded() : existing == null ? null : existing.getDegraded())
                .degradationReason(trace != null ? trace.getDegradationReason() : existing == null ? null : existing.getDegradationReason())
                .fallbackDecision(trace != null ? trace.getFallbackDecision() : existing == null ? null : existing.getFallbackDecision())
                .recoveryCheckpoint(trace != null ? trace.getRecoveryCheckpoint() : existing == null ? null : existing.getRecoveryCheckpoint())
                .sourceUrls(snapshot.getSourceUrls() != null
                        ? snapshot.getSourceUrls()
                        : existing == null || existing.getSourceUrls() == null ? List.of() : existing.getSourceUrls())
                .fieldEvidenceQueryCount(trace != null ? trace.getFieldEvidenceQueryCount()
                        : existing == null ? null : existing.getFieldEvidenceQueryCount())
                .fieldEvidenceFields(trace != null && trace.getFieldEvidenceFields() != null
                        ? trace.getFieldEvidenceFields()
                        : existing == null || existing.getFieldEvidenceFields() == null ? List.of() : existing.getFieldEvidenceFields())
                .fieldEvidencePaths(trace != null && trace.getFieldEvidencePaths() != null
                        ? trace.getFieldEvidencePaths()
                        : existing == null || existing.getFieldEvidencePaths() == null ? List.of() : existing.getFieldEvidencePaths())
                .tavilyFastLaneAudit(tavilyFastLaneAudit)
                .build();
    }

    private static int size(List<?> values, Integer fallbackValue) {
        if (values != null) {
            return values.size();
        }
        return fallbackValue == null ? 0 : fallbackValue;
    }

    private static TavilyFastLaneAudit readTavilyFastLaneAudit(ObjectMapper objectMapper,
                                                               JsonNode traceNode,
                                                               JsonNode auditNode) {
        if (objectMapper == null) {
            return null;
        }
        JsonNode candidateNode = traceNode == null ? null : traceNode.path("tavilyFastLaneAudit");
        if (candidateNode != null && !candidateNode.isMissingNode() && !candidateNode.isNull()) {
            return objectMapper.convertValue(candidateNode, TavilyFastLaneAudit.class);
        }
        candidateNode = auditNode == null ? null : auditNode.path("tavilyFastLaneAudit");
        if (candidateNode != null && !candidateNode.isMissingNode() && !candidateNode.isNull()) {
            return objectMapper.convertValue(candidateNode, TavilyFastLaneAudit.class);
        }
        candidateNode = auditNode == null ? null : auditNode.path("summary").path("tavilyFastLaneAudit");
        if (candidateNode != null && !candidateNode.isMissingNode() && !candidateNode.isNull()) {
            return objectMapper.convertValue(candidateNode, TavilyFastLaneAudit.class);
        }
        return null;
    }

    private static Integer readInteger(JsonNode node, String field) {
        JsonNode valueNode = node == null ? null : node.path(field);
        if (valueNode == null || valueNode.isMissingNode() || valueNode.isNull()) {
            return 0;
        }
        return valueNode.asInt(0);
    }

    private static Boolean readBoolean(JsonNode node, String field) {
        JsonNode valueNode = node == null ? null : node.path(field);
        if (valueNode == null || valueNode.isMissingNode() || valueNode.isNull()) {
            return null;
        }
        return valueNode.asBoolean();
    }

    private static String text(JsonNode node, String field) {
        JsonNode valueNode = node == null ? null : node.path(field);
        if (valueNode == null || valueNode.isMissingNode() || valueNode.isNull()) {
            return null;
        }
        return valueNode.asText(null);
    }

    private static List<String> readStringList(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item != null && !item.isNull()) {
                String value = item.asText(null);
                if (value != null && !value.isBlank()) {
                    values.add(value);
                }
            }
        }
        return values;
    }
}
