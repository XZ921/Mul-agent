package cn.bugstack.competitoragent.agent.writer;

import cn.bugstack.competitoragent.workflow.contract.SectionEvidenceBundle;
import cn.bugstack.competitoragent.workflow.contract.WriterCitationGap;
import cn.bugstack.competitoragent.workflow.coverage.StageOneFirstReportPolicy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Writer 章节引用缺口检测器。
 * 它只基于 Writer 可见的章节证据束和来源列表生成缺口事实，不做 Citation Agent 级别的逐句真伪校验。
 */
@Component
public class WriterCitationGapInspector {

    /**
     * 从 Writer 输出上下文中识别章节引用缺口。
     * reportContent 当前仅保留为接口上下文的一部分，方便后续扩展逐章节文本定位；本轮先按证据束事实做判定。
     */
    public InspectionResult inspect(String reportContent,
                                    List<SectionEvidenceBundle> sectionEvidenceBundles,
                                    List<String> fallbackSourceUrls) {
        List<WriterCitationGap> gaps = new ArrayList<>();
        for (SectionEvidenceBundle bundle : sectionEvidenceBundles == null
                ? List.<SectionEvidenceBundle>of()
                : sectionEvidenceBundles) {
            SectionEvidenceBundle normalized = bundle == null ? null : bundle.normalized();
            if (normalized == null || !hasCitationGap(normalized)) {
                continue;
            }
            gaps.add(buildGap(normalized, fallbackSourceUrls));
        }
        List<String> issueFlags = buildIssueFlags(gaps);
        return new InspectionResult(
                gaps,
                resolveSeverity(gaps),
                resolveEvidenceState(gaps, fallbackSourceUrls),
                gaps.stream().map(WriterCitationGap::getTargetSection).toList(),
                issueFlags);
    }

    /**
     * Writer 需要区分两类问题：
     * 1. 首报核心字段的真缺口，仍然应该进入高优先级 citation gap 轨道；
     * 2. 定价/优势/短板这类增强字段缺口，只保留 optional 审计，不再冒充 blocker。
     */
    private boolean hasCitationGap(SectionEvidenceBundle bundle) {
        return hasBlockingCitationGap(bundle) || hasOptionalCitationGap(bundle);
    }

    private boolean hasBlockingCitationGap(SectionEvidenceBundle bundle) {
        if (isOptionalOnlyGap(bundle)) {
            return false;
        }
        return contains(bundle.getIssueFlags(), "NO_USABLE_EVIDENCE")
                || bundle.getSourceUrls() == null
                || bundle.getSourceUrls().isEmpty()
                || hasBlockingMissingFields(bundle)
                || (contains(bundle.getIssueFlags(), "SECTION_EVIDENCE_GAP") && !hasOnlyOptionalMissingFields(bundle));
    }

    private boolean hasOptionalCitationGap(SectionEvidenceBundle bundle) {
        return isOptionalOnlyGap(bundle)
                || contains(bundle.getIssueFlags(), "OPTIONAL_SECTION_EVIDENCE_GAP");
    }

    private boolean hasBlockingMissingFields(SectionEvidenceBundle bundle) {
        List<String> missingFields = normalize(bundle == null ? null : bundle.getMissingFields());
        return missingFields.stream().anyMatch(StageOneFirstReportPolicy::isFirstReportCriticalField);
    }

    private boolean hasOnlyOptionalMissingFields(SectionEvidenceBundle bundle) {
        List<String> missingFields = normalize(bundle == null ? null : bundle.getMissingFields());
        return !missingFields.isEmpty()
                && missingFields.stream().allMatch(StageOneFirstReportPolicy::isFirstReportEnhancementField);
    }

    /**
     * Writer 需要兼容 Analyzer/旧 bundle 的字段命名漂移。
     * 只要章节自身能稳定映射回 pricing / strengths / weaknesses，就应继续按 optional gap 处理，
     * 不能因为中间层仍带着 SECTION_EVIDENCE_GAP 旧标记就把增强字段重新抬成 blocker。
     */
    private boolean isOptionalOnlyGap(SectionEvidenceBundle bundle) {
        boolean hasGapSignal = contains(bundle == null ? null : bundle.getIssueFlags(), "SECTION_EVIDENCE_GAP")
                || contains(bundle == null ? null : bundle.getIssueFlags(), "OPTIONAL_SECTION_EVIDENCE_GAP")
                || !(normalize(bundle == null ? null : bundle.getMissingFields())).isEmpty();
        return hasGapSignal
                && (hasOnlyOptionalMissingFields(bundle)
                || (isOptionalSection(bundle) && !hasBlockingMissingFields(bundle)));
    }

    private boolean isOptionalSection(SectionEvidenceBundle bundle) {
        String stageOneField = resolveStageOneField(bundle);
        return StageOneFirstReportPolicy.isFirstReportEnhancementField(stageOneField);
    }

    private String resolveStageOneField(SectionEvidenceBundle bundle) {
        if (bundle == null) {
            return null;
        }
        List<String> candidates = new ArrayList<>();
        candidates.add(bundle.getSectionKey());
        candidates.add(bundle.getSectionTitle());
        candidates.addAll(bundle.getFieldNames() == null ? List.of() : bundle.getFieldNames());
        candidates.addAll(bundle.getMissingFields() == null ? List.of() : bundle.getMissingFields());
        for (String candidate : candidates) {
            String normalizedField = StageOneFirstReportPolicy.normalizeFieldName(candidate);
            if (StageOneFirstReportPolicy.isFirstReportCriticalField(normalizedField)
                    || StageOneFirstReportPolicy.isFirstReportEnhancementField(normalizedField)) {
                return normalizedField;
            }
            String sectionField = StageOneFirstReportPolicy.fieldForSection(candidate);
            if (StageOneFirstReportPolicy.isFirstReportCriticalField(sectionField)
                    || StageOneFirstReportPolicy.isFirstReportEnhancementField(sectionField)) {
                return StageOneFirstReportPolicy.normalizeFieldName(sectionField);
            }
        }
        return null;
    }

    /**
     * 当章节自己没有来源但 Writer 全局来源不为空时，按 PARTIAL_SOURCE 处理。
     * 这样既不会伪装成“来源完整”，也不会把可修复的问题误判成完全无来源。
     */
    private WriterCitationGap buildGap(SectionEvidenceBundle bundle, List<String> fallbackSourceUrls) {
        List<String> sectionSources = normalize(bundle.getSourceUrls());
        if (sectionSources.isEmpty()) {
            sectionSources = normalize(fallbackSourceUrls);
        }
        String sectionKey = firstNonBlank(bundle.getSectionKey(), "report");
        boolean optionalGap = !sectionSources.isEmpty() && isOptionalOnlyGap(bundle);
        return WriterCitationGap.builder()
                .targetSection(sectionKey)
                .sectionTitle(firstNonBlank(bundle.getSectionTitle(), sectionKey))
                .summary(firstNonBlank(bundle.getGapSummary(),
                        "Writer 章节缺少可回指引用：" + firstNonBlank(bundle.getSectionTitle(), sectionKey)))
                .severity(sectionSources.isEmpty() ? "ERROR" : optionalGap ? "WARNING" : "HIGH")
                .sourceUrls(sectionSources)
                .evidenceState(sectionSources.isEmpty() ? "MISSING_SOURCE" : "PARTIAL_SOURCE")
                .missingFields(normalize(bundle.getMissingFields()))
                .suggestedQueries(buildSuggestedQueries(sectionKey, bundle.getMissingFields()))
                .build()
                .normalized();
    }

    /**
     * suggestedQueries 只提供补证方向，不代表系统已经决定去自动采集。
     */
    private List<String> buildSuggestedQueries(String sectionKey, List<String> missingFields) {
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        queries.add(sectionKey + " official citation evidence");
        for (String field : missingFields == null ? List.<String>of() : missingFields) {
            if (field != null && !field.isBlank()) {
                queries.add(sectionKey + " " + field.trim() + " official source");
            }
        }
        return new ArrayList<>(queries);
    }

    private String resolveSeverity(List<WriterCitationGap> gaps) {
        if (gaps == null || gaps.isEmpty()) {
            return "NONE";
        }
        if (gaps.stream().anyMatch(gap -> "MISSING_SOURCE".equals(gap.getEvidenceState()))) {
            return "ERROR";
        }
        if (gaps.stream().anyMatch(gap -> "HIGH".equals(gap.getSeverity()))) {
            return "HIGH";
        }
        return "WARNING";
    }

    private String resolveEvidenceState(List<WriterCitationGap> gaps, List<String> fallbackSourceUrls) {
        if (gaps == null || gaps.isEmpty()) {
            return normalize(fallbackSourceUrls).isEmpty() ? "MISSING_SOURCE" : "FULL_SOURCE";
        }
        return gaps.stream().anyMatch(gap -> "MISSING_SOURCE".equals(gap.getEvidenceState()))
                ? "MISSING_SOURCE"
                : "PARTIAL_SOURCE";
    }

    /**
     * Writer 缺口统一保留 WRITER_CITATION_GAP；
     * 若当前仅存在增强字段缺口，则额外打上 OPTIONAL_CITATION_GAP，供报告层做降级说明。
     */
    private List<String> buildIssueFlags(List<WriterCitationGap> gaps) {
        if (gaps == null || gaps.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> flags = new LinkedHashSet<>();
        flags.add("WRITER_CITATION_GAP");
        if (gaps.stream().anyMatch(gap -> "MISSING_SOURCE".equals(gap.getEvidenceState()))) {
            flags.add("WRITER_MISSING_SOURCE");
        }
        if (gaps.stream().anyMatch(gap -> "WARNING".equals(gap.getSeverity()))) {
            flags.add("OPTIONAL_CITATION_GAP");
        }
        return new ArrayList<>(flags);
    }

    private boolean contains(List<String> values, String expected) {
        if (values == null || expected == null || expected.isBlank()) {
            return false;
        }
        for (String value : values) {
            if (expected.equalsIgnoreCase(value == null ? null : value.trim())) {
                return true;
            }
        }
        return false;
    }

    private List<String> normalize(List<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    normalized.add(value.trim());
                }
            }
        }
        return new ArrayList<>(normalized);
    }

    private String firstNonBlank(String value, String fallback) {
        if (value != null && !value.isBlank()) {
            return value.trim();
        }
        return fallback;
    }

    /**
     * 这里返回纯事实视图，便于 Writer、Assembler 和测试复用，避免把决策语义提前揉进检测器。
     */
    public record InspectionResult(List<WriterCitationGap> gaps,
                                   String severity,
                                   String evidenceState,
                                   List<String> missingCitationSections,
                                   List<String> issueFlags) {
    }
}
