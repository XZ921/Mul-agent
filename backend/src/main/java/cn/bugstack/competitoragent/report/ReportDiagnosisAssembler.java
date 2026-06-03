package cn.bugstack.competitoragent.report;

import cn.bugstack.competitoragent.model.dto.ReportResponse;
import cn.bugstack.competitoragent.model.dto.ReportResponse.ContentEvidenceFragment;
import cn.bugstack.competitoragent.model.dto.ReportResponse.DiagnosisItem;
import cn.bugstack.competitoragent.model.dto.ReportResponse.DiagnosisSection;
import cn.bugstack.competitoragent.model.dto.ReportResponse.EvidenceCoverageOverview;
import cn.bugstack.competitoragent.model.dto.ReportResponse.EvidenceInfo;
import cn.bugstack.competitoragent.model.dto.ReportResponse.EvidenceReference;
import cn.bugstack.competitoragent.model.dto.ReportResponse.QualityIssue;
import cn.bugstack.competitoragent.model.dto.ReportResponse.ReportDiagnosisInfo;
import cn.bugstack.competitoragent.model.dto.ReportResponse.ReviewCheckpoint;
import cn.bugstack.competitoragent.model.dto.ReportResponse.ReviewNextAction;
import cn.bugstack.competitoragent.model.dto.ReportResponse.SectionEvidenceCoverage;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.workflow.contract.EvidenceFragment;
import cn.bugstack.competitoragent.workflow.contract.QualityDiagnosis;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 报告诊断组装器。
 * 统一把三个来源收口到一个前端可直接消费的模型里：
 * 1. Writer 输出的 evidenceFragments / sourceUrls，负责说明“正文引用了哪些来源”；
 * 2. Reviewer 输出的 diagnoses / nextActions，负责说明“哪里被诊断出问题、怎么修”；
 * 3. Report / Coverage 概览中的兜底问题，负责在链路局部缺失时仍能稳定返回诊断闭环。
 */
@Component
@RequiredArgsConstructor
public class ReportDiagnosisAssembler {

    private final ObjectMapper objectMapper;
    private final EvidenceQueryService evidenceQueryService;

    public ReportDiagnosisInfo assemble(List<EvidenceInfo> evidences,
                                        List<QualityIssue> reportIssues,
                                        ReviewCheckpoint initialReview,
                                        ReviewCheckpoint finalReview,
                                        EvidenceCoverageOverview coverageOverview,
                                        List<TaskNode> nodes) {
        List<ContentEvidenceFragment> contentEvidences = extractContentEvidences(nodes, evidences);
        List<StageDiagnosis> stagedDiagnoses = collectDiagnoses(reportIssues, initialReview, finalReview);
        Map<String, SectionAccumulator> sections = new LinkedHashMap<>();
        LinkedHashSet<String> aggregatedSourceUrls = new LinkedHashSet<>();
        int blockerCount = 0;

        for (ContentEvidenceFragment contentEvidence : contentEvidences) {
            if (contentEvidence.getSourceUrl() != null && !contentEvidence.getSourceUrl().isBlank()) {
                aggregatedSourceUrls.add(contentEvidence.getSourceUrl());
            }
        }

        for (StageDiagnosis stagedDiagnosis : stagedDiagnoses) {
            QualityDiagnosis diagnosis = stagedDiagnosis.diagnosis().normalized();
            List<EvidenceReference> references = evidenceQueryService.resolveEvidenceReferences(
                    evidences,
                    diagnosis.getEvidenceIds(),
                    diagnosis.getSourceUrls()
            );
            if ("BLOCKER".equalsIgnoreCase(diagnosis.getLevel())) {
                blockerCount++;
            }

            String sectionName = normalizeSection(diagnosis.getSection());
            SectionAccumulator accumulator = sections.computeIfAbsent(sectionName, key -> new SectionAccumulator(sectionName));
            accumulator.evidenceInsufficient = accumulator.evidenceInsufficient || isEvidenceInsufficient(diagnosis, references);
            if (diagnosis.getRepairSuggestion() != null && !diagnosis.getRepairSuggestion().isBlank()) {
                accumulator.repairSuggestions.add(diagnosis.getRepairSuggestion().trim());
            }
            for (String sourceUrl : diagnosis.getSourceUrls() == null ? List.<String>of() : diagnosis.getSourceUrls()) {
                if (sourceUrl != null && !sourceUrl.isBlank()) {
                    accumulator.sourceUrls.add(sourceUrl.trim());
                    aggregatedSourceUrls.add(sourceUrl.trim());
                }
            }
            for (EvidenceReference reference : references) {
                if (reference.getUrl() != null && !reference.getUrl().isBlank()) {
                    accumulator.sourceUrls.add(reference.getUrl());
                    aggregatedSourceUrls.add(reference.getUrl());
                }
            }
            accumulator.diagnoses.add(DiagnosisItem.builder()
                    .reviewStage(stagedDiagnosis.stage())
                    .diagnosis(diagnosis)
                    .evidenceReferences(references)
                    .build());
        }

        mergeCoverageSections(sections, coverageOverview);

        List<DiagnosisSection> diagnosisSections = sections.values().stream()
                .map(accumulator -> DiagnosisSection.builder()
                        .section(accumulator.section)
                        .evidenceInsufficient(accumulator.evidenceInsufficient)
                        .sourceUrls(new ArrayList<>(accumulator.sourceUrls))
                        .repairSuggestions(new ArrayList<>(accumulator.repairSuggestions))
                        .diagnoses(new ArrayList<>(accumulator.diagnoses))
                        .build())
                .toList();

        int diagnosisCount = stagedDiagnoses.size();
        int evidenceGapCount = (int) diagnosisSections.stream()
                .filter(section -> Boolean.TRUE.equals(section.getEvidenceInsufficient()))
                .count();

        return ReportDiagnosisInfo.builder()
                .diagnosisCount(diagnosisCount)
                .blockerCount(blockerCount)
                .evidenceGapCount(evidenceGapCount)
                .sourceUrls(new ArrayList<>(aggregatedSourceUrls))
                .contentEvidences(contentEvidences)
                .sections(diagnosisSections)
                .nextActions(mergeNextActions(initialReview, finalReview))
                .build();
    }

    /**
     * 优先回流 Writer 节点中的 evidenceFragments。
     * 如果当前任务还没有写入结构化片段，就退回到证据列表构造最小可用的正文来源视图，
     * 保证报告页始终知道“这份正文至少引用了哪些 URL / 证据编号”。
     */
    private List<ContentEvidenceFragment> extractContentEvidences(List<TaskNode> nodes, List<EvidenceInfo> evidences) {
        TaskNode writerNode = selectPrimaryWriterNode(nodes);
        if (writerNode == null) {
            return buildFallbackContentEvidences(evidences);
        }

        JsonNode output = readJson(writerNode.getOutputData());
        List<EvidenceFragment> fragments = readEvidenceFragments(output == null ? null : output.path("evidenceFragments"));
        if (fragments.isEmpty()) {
            List<ContentEvidenceFragment> fallback = buildFallbackContentEvidences(evidences);
            if (!fallback.isEmpty()) {
                return fallback;
            }
            return buildSyntheticContentEvidences(readStringList(output == null ? null : output.path("sourceUrls")));
        }

        List<ContentEvidenceFragment> result = new ArrayList<>();
        for (EvidenceFragment fragment : fragments) {
            EvidenceFragment normalized = fragment.normalized();
            List<EvidenceReference> references = evidenceQueryService.resolveEvidenceReferences(
                    evidences,
                    normalized.getEvidenceId() == null ? List.of() : List.of(normalized.getEvidenceId()),
                    normalized.getSourceUrl() == null ? List.of() : List.of(normalized.getSourceUrl())
            );
            result.add(ContentEvidenceFragment.builder()
                    .stage(normalized.getStage())
                    .competitorName(normalized.getCompetitorName())
                    .fieldName(normalized.getFieldName())
                    .evidenceId(normalized.getEvidenceId())
                    .sourceUrl(normalized.getSourceUrl())
                    .title(normalized.getTitle())
                    .snippet(normalized.getSnippet())
                    .issueFlags(normalized.getIssueFlags())
                    .evidence(references.isEmpty() ? null : references.get(0))
                    .build());
        }
        return result;
    }

    private List<ContentEvidenceFragment> buildFallbackContentEvidences(List<EvidenceInfo> evidences) {
        List<ContentEvidenceFragment> result = new ArrayList<>();
        for (EvidenceInfo evidence : evidences == null ? List.<EvidenceInfo>of() : evidences) {
            result.add(ContentEvidenceFragment.builder()
                    .stage("WRITE")
                    .competitorName(evidence.getCompetitorName())
                    .fieldName("report")
                    .evidenceId(evidence.getEvidenceId())
                    .sourceUrl(evidence.getUrl())
                    .title(evidence.getTitle())
                    .snippet(evidence.getContentSnippet())
                    .issueFlags(List.of())
                    .evidence(evidenceQueryService.toEvidenceReference(evidence))
                    .build());
        }
        return result;
    }

    private List<ContentEvidenceFragment> buildSyntheticContentEvidences(List<String> sourceUrls) {
        List<ContentEvidenceFragment> result = new ArrayList<>();
        for (String sourceUrl : sourceUrls) {
            if (sourceUrl != null && !sourceUrl.isBlank()) {
                result.add(ContentEvidenceFragment.builder()
                        .stage("WRITE")
                        .fieldName("report")
                        .sourceUrl(sourceUrl.trim())
                        .issueFlags(List.of())
                        .evidence(EvidenceReference.builder().url(sourceUrl.trim()).build())
                        .build());
            }
        }
        return result;
    }

    private List<StageDiagnosis> collectDiagnoses(List<QualityIssue> reportIssues,
                                                  ReviewCheckpoint initialReview,
                                                  ReviewCheckpoint finalReview) {
        List<StageDiagnosis> stagedDiagnoses = new ArrayList<>();
        appendReviewDiagnoses(stagedDiagnoses, "INITIAL_REVIEW", initialReview);
        appendReviewDiagnoses(stagedDiagnoses, "FINAL_REVIEW", finalReview);
        if (!stagedDiagnoses.isEmpty()) {
            return stagedDiagnoses;
        }

        for (QualityIssue issue : reportIssues == null ? List.<QualityIssue>of() : reportIssues) {
            stagedDiagnoses.add(new StageDiagnosis("REPORT", QualityDiagnosis.builder()
                    .dimensionCode(issue.getDimensionCode())
                    .dimensionName(issue.getDimensionName())
                    .type(issue.getType())
                    .section(issue.getSection())
                    .severity(issue.getSeverity())
                    .level(issue.getLevel())
                    .title(resolveIssueTitle(issue))
                    .detail(issue.getSuggestion())
                    .evidenceBasis(issue.getEvidenceBasis())
                    .evidenceIds(issue.getEvidenceIds())
                    .sourceUrls(issue.getSourceUrls())
                    .repairSuggestion(issue.getSuggestion())
                    .build().normalized()));
        }
        return stagedDiagnoses;
    }

    private void appendReviewDiagnoses(List<StageDiagnosis> stagedDiagnoses, String stage, ReviewCheckpoint checkpoint) {
        if (checkpoint == null || checkpoint.getDiagnoses() == null) {
            return;
        }
        for (QualityDiagnosis diagnosis : checkpoint.getDiagnoses()) {
            if (diagnosis != null) {
                stagedDiagnoses.add(new StageDiagnosis(stage, diagnosis.normalized()));
            }
        }
    }

    /**
     * Coverage 只告诉我们“哪个章节缺证据”，但不直接给修复动作。
     * 这里把它补成章节级别的 evidence gap，避免前端再自己把 coverage 和 diagnoses 做二次合并。
     */
    private void mergeCoverageSections(Map<String, SectionAccumulator> sections, EvidenceCoverageOverview coverageOverview) {
        if (coverageOverview == null || coverageOverview.getSections() == null) {
            return;
        }
        for (SectionEvidenceCoverage coverage : coverageOverview.getSections()) {
            boolean hasGap = defaultNumber(coverage.getMissingEvidenceFields()) > 0 || defaultNumber(coverage.getEmptyFields()) > 0;
            if (!hasGap) {
                continue;
            }
            String sectionName = normalizeSection(coverage.getSectionTitle());
            SectionAccumulator accumulator = sections.computeIfAbsent(sectionName, key -> new SectionAccumulator(sectionName));
            accumulator.evidenceInsufficient = true;
            accumulator.repairSuggestions.add(buildCoverageSuggestion(coverage));
        }
    }

    private String buildCoverageSuggestion(SectionEvidenceCoverage coverage) {
        return "请优先补齐“" + normalizeSection(coverage.getSectionTitle())
                + "”章节的证据链路，并复核缺失字段："
                + String.join("、", coverage.getMissingFields() == null ? List.of() : coverage.getMissingFields());
    }

    private List<ReviewNextAction> mergeNextActions(ReviewCheckpoint initialReview, ReviewCheckpoint finalReview) {
        LinkedHashMap<String, ReviewNextAction> merged = new LinkedHashMap<>();
        appendNextActions(merged, finalReview);
        appendNextActions(merged, initialReview);
        return new ArrayList<>(merged.values());
    }

    private void appendNextActions(Map<String, ReviewNextAction> merged, ReviewCheckpoint checkpoint) {
        if (checkpoint == null || checkpoint.getNextActions() == null) {
            return;
        }
        for (ReviewNextAction action : checkpoint.getNextActions()) {
            if (action == null) {
                continue;
            }
            String key = safe(action.getActionType()) + "|" + safe(action.getTargetNode()) + "|" + safe(action.getTitle());
            merged.putIfAbsent(key, action);
        }
    }

    private boolean isEvidenceInsufficient(QualityDiagnosis diagnosis, List<EvidenceReference> references) {
        if (diagnosis.getType() != null && diagnosis.getType().toLowerCase().contains("evidence")) {
            return true;
        }
        if (diagnosis.getLevel() != null && "BLOCKER".equalsIgnoreCase(diagnosis.getLevel()) && references.isEmpty()) {
            return true;
        }
        return (diagnosis.getSourceUrls() == null || diagnosis.getSourceUrls().isEmpty())
                && (diagnosis.getEvidenceIds() == null || diagnosis.getEvidenceIds().isEmpty());
    }

    private TaskNode selectPrimaryWriterNode(List<TaskNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return null;
        }
        TaskNode rewriteSuccess = null;
        TaskNode writeSuccess = null;
        TaskNode fallback = null;
        for (TaskNode node : nodes) {
            if (!isWriterNode(node)) {
                continue;
            }
            fallback = node;
            if ("rewrite_report".equals(node.getNodeName()) && node.getOutputData() != null && !node.getOutputData().isBlank()) {
                if (node.getStatus() == cn.bugstack.competitoragent.model.enums.TaskNodeStatus.SUCCESS) {
                    rewriteSuccess = node;
                }
            }
            if ("write_report".equals(node.getNodeName()) && node.getOutputData() != null && !node.getOutputData().isBlank()) {
                if (node.getStatus() == cn.bugstack.competitoragent.model.enums.TaskNodeStatus.SUCCESS) {
                    writeSuccess = node;
                }
            }
        }
        if (rewriteSuccess != null) {
            return rewriteSuccess;
        }
        if (writeSuccess != null) {
            return writeSuccess;
        }
        return fallback;
    }

    private boolean isWriterNode(TaskNode node) {
        if (node == null) {
            return false;
        }
        return node.getAgentType() == AgentType.WRITER
                || "write_report".equals(node.getNodeName())
                || "rewrite_report".equals(node.getNodeName());
    }

    private List<EvidenceFragment> readEvidenceFragments(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        try {
            List<EvidenceFragment> fragments = objectMapper.convertValue(node, new TypeReference<List<EvidenceFragment>>() {});
            List<EvidenceFragment> normalized = new ArrayList<>();
            for (EvidenceFragment fragment : fragments) {
                if (fragment != null) {
                    normalized.add(fragment.normalized());
                }
            }
            return normalized;
        } catch (IllegalArgumentException ignored) {
            return List.of();
        }
    }

    private List<String> readStringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (JsonNode item : node) {
            String value = item.asText(null);
            if (value != null && !value.isBlank()) {
                values.add(value.trim());
            }
        }
        return new ArrayList<>(values);
    }

    private JsonNode readJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException ignored) {
            return null;
        }
    }

    private String resolveIssueTitle(QualityIssue issue) {
        if (issue.getDimensionName() != null && !issue.getDimensionName().isBlank()) {
            return issue.getDimensionName() + "存在问题";
        }
        if (issue.getType() != null && !issue.getType().isBlank()) {
            return issue.getType();
        }
        return "报告诊断问题";
    }

    private String normalizeSection(String section) {
        return section == null || section.isBlank() ? "未分类" : section.trim();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private int defaultNumber(Integer value) {
        return value == null ? 0 : value;
    }

    private record StageDiagnosis(String stage, QualityDiagnosis diagnosis) {
    }

    private static final class SectionAccumulator {
        private final String section;
        private boolean evidenceInsufficient;
        private final LinkedHashSet<String> sourceUrls = new LinkedHashSet<>();
        private final LinkedHashSet<String> repairSuggestions = new LinkedHashSet<>();
        private final List<DiagnosisItem> diagnoses = new ArrayList<>();

        private SectionAccumulator(String section) {
            this.section = section;
        }
    }
}
