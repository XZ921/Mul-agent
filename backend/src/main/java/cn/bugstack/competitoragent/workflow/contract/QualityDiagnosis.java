package cn.bugstack.competitoragent.workflow.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 单条可解释诊断。
 * 它不再只告诉系统“扣了多少分”，而是明确指出：
 * 1. 哪个质量维度出了问题；
 * 2. 问题等级和证据依据是什么；
 * 3. 下一步应该怎么修。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class QualityDiagnosis {

    private String dimensionCode;

    private String dimensionName;

    private String type;

    private String section;

    private String severity;

    private String level;

    private String title;

    private String detail;

    /** 诊断依据文本，既可来自证据覆盖摘要，也可来自正文审计结果。 */
    private String evidenceBasis;

    @Builder.Default
    private List<String> evidenceIds = List.of();

    @Builder.Default
    private List<String> sourceUrls = List.of();

    /** 面向自动改写与人工复核的修复建议。 */
    private String repairSuggestion;

    /**
     * 标准化诊断对象，确保等级、证据列表和修复建议都能稳定落地。
     */
    public QualityDiagnosis normalized() {
        String normalizedSeverity = normalizeText(severity);
        String normalizedLevel = normalizeText(level);
        if (normalizedLevel == null) {
            normalizedLevel = mapLevelFromSeverity(normalizedSeverity);
        }
        if (normalizedSeverity == null) {
            normalizedSeverity = mapSeverityFromLevel(normalizedLevel);
        }
        return this.toBuilder()
                .severity(normalizedSeverity)
                .level(normalizedLevel)
                .evidenceIds(normalizeDistinctList(evidenceIds))
                .sourceUrls(normalizeDistinctList(sourceUrls))
                .evidenceBasis(resolveEvidenceBasis())
                .repairSuggestion(resolveRepairSuggestion())
                .build();
    }

    /**
     * 兼容旧质检问题结构，让工作流与报告页在升级期间仍能复用同一份输出。
     */
    public QualityIssue toQualityIssue() {
        QualityDiagnosis normalized = normalized();
        return QualityIssue.builder()
                .type(normalized.getType())
                .section(normalized.getSection())
                .severity(normalized.getSeverity())
                .suggestion(normalized.getRepairSuggestion())
                .dimensionCode(normalized.getDimensionCode())
                .dimensionName(normalized.getDimensionName())
                .level(normalized.getLevel())
                .evidenceBasis(normalized.getEvidenceBasis())
                .evidenceIds(normalized.getEvidenceIds())
                .sourceUrls(normalized.getSourceUrls())
                .build();
    }

    private String resolveEvidenceBasis() {
        if (evidenceBasis != null && !evidenceBasis.isBlank()) {
            return evidenceBasis.trim();
        }
        String normalizedType = normalizeText(type);
        if (normalizedType != null && normalizedType.toLowerCase(Locale.ROOT).contains("evidence")) {
            return "当前结论缺少可回指证据，请核对段落中的 [证据：EID] 引用与来源链接。";
        }
        if (normalizedType != null && normalizedType.toLowerCase(Locale.ROOT).contains("claim")) {
            return "当前结论的支撑链路不完整，请确认对应判断是否有足够来源依据。";
        }
        if (normalizeDistinctList(evidenceIds).isEmpty()) {
            return "当前结论缺少可回指证据，请核对段落中的 [证据：EID] 引用与来源链接。";
        }
        if (normalizeDistinctList(sourceUrls).isEmpty()) {
            return "当前诊断已定位到证据编号，但来源链接不完整，请补齐 sourceUrls 便于回溯。";
        }
        return "当前诊断依据来自已标注的证据编号与来源链接。";
    }

    private String resolveRepairSuggestion() {
        if (repairSuggestion != null && !repairSuggestion.isBlank()) {
            return repairSuggestion.trim();
        }
        String normalizedType = normalizeText(type);
        String normalizedSection = section == null || section.isBlank() ? "对应章节" : section.trim();
        if (normalizedType != null && normalizedType.toLowerCase(Locale.ROOT).contains("evidence")) {
            return "请为" + normalizedSection + "补充可回指的证据编号与来源链接，必要时将绝对化判断改为保守表述。";
        }
        if (normalizedType != null && normalizedType.toLowerCase(Locale.ROOT).contains("claim")) {
            return "请核对" + normalizedSection + "中的关键结论是否被证据支撑，无法验证时请降级或删除该判断。";
        }
        return "请根据诊断依据修正" + normalizedSection + "，并确保修订后仍保留可追溯来源。";
    }

    private String mapLevelFromSeverity(String normalizedSeverity) {
        if (normalizedSeverity == null) {
            return "MINOR";
        }
        return switch (normalizedSeverity.toUpperCase(Locale.ROOT)) {
            case "ERROR" -> "BLOCKER";
            case "WARNING" -> "MAJOR";
            default -> "MINOR";
        };
    }

    private String mapSeverityFromLevel(String normalizedLevel) {
        if (normalizedLevel == null) {
            return "INFO";
        }
        return switch (normalizedLevel.toUpperCase(Locale.ROOT)) {
            case "BLOCKER" -> "ERROR";
            case "MAJOR" -> "WARNING";
            default -> "INFO";
        };
    }

    private List<String> normalizeDistinctList(List<String> values) {
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

    private String normalizeText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
