package cn.bugstack.competitoragent.agent.citation;

import cn.bugstack.competitoragent.workflow.contract.CitationClaim;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从报告正文里抽取可追溯的引用声明。
 * 这个类只做文本解析，不做外部抓取、模型推断或编排决策。
 */
@Component
public class CitationClaimExtractor {

    private static final Pattern HEADING_PATTERN = Pattern.compile("^#{1,6}\\s+(.+)$");
    private static final Pattern EVIDENCE_PATTERN = Pattern.compile("\\[证据[:：]\\s*([A-Za-z0-9_-]+)]");
    private static final Pattern SENTENCE_SPLIT_PATTERN = Pattern.compile("(?<=[。！？!?；;])\\s*");
    private static final List<String> TRACEABILITY_KEYWORDS = List.of(
            "建议",
            "结论",
            "风险",
            "机会",
            "启示",
            "行动",
            "应该",
            "必须",
            "优先"
    );
    private static final List<String> DOWNGRADE_KEYWORDS = List.of(
            "当前公开资料未能验证",
            "公开资料未能验证",
            "需补充证据",
            "待验证",
            "低置信度",
            "推测"
    );

    /**
     * 逐行扫描报告内容：
     * 1. 遇到 Markdown heading 时更新当前章节上下文；
     * 2. 普通内容再按中文/英文句末标点切分成候选声明；
     * 3. 每条候选声明抽取证据编号、敏感度与降级语义。
     */
    public List<CitationClaim> extract(String reportContent) {
        if (reportContent == null || reportContent.isBlank()) {
            return List.of();
        }

        List<CitationClaim> claims = new ArrayList<>();
        String currentSectionTitle = "report";
        String currentSectionKey = "report";
        int claimIndex = 1;

        for (String rawLine : reportContent.split("\\R")) {
            String line = normalizeText(rawLine);
            if (line == null) {
                continue;
            }

            Matcher headingMatcher = HEADING_PATTERN.matcher(line);
            if (headingMatcher.matches()) {
                currentSectionTitle = normalizeText(headingMatcher.group(1));
                currentSectionKey = normalizeSectionKey(currentSectionTitle);
                continue;
            }

            for (String sentence : splitSentences(line)) {
                String normalizedSentence = normalizeText(sentence);
                if (normalizedSentence == null) {
                    continue;
                }

                List<String> evidenceIds = extractEvidenceIds(normalizedSentence);
                boolean traceabilitySensitive = !evidenceIds.isEmpty()
                        || containsAny(currentSectionTitle, TRACEABILITY_KEYWORDS)
                        || containsAny(normalizedSentence, TRACEABILITY_KEYWORDS);

                // 降级短语一旦出现，就明确告诉后续 Citation Agent：这里不是“硬性结论”，而是需要保留审慎态度的陈述。
                boolean explicitlyDowngraded = containsAny(normalizedSentence, DOWNGRADE_KEYWORDS);

                claims.add(CitationClaim.builder()
                        .claimId(String.format(Locale.ROOT, "claim-%03d", claimIndex++))
                        .sectionKey(currentSectionKey)
                        .sectionTitle(currentSectionTitle)
                        .claimText(stripEvidenceMarkers(normalizedSentence))
                        .evidenceIds(evidenceIds)
                        .sourceUrls(List.of())
                        .traceabilitySensitive(traceabilitySensitive)
                        .explicitlyDowngraded(explicitlyDowngraded)
                        .issueFlags(defaultIssueFlags(evidenceIds, traceabilitySensitive))
                        .build()
                        .normalized());
            }
        }

        return List.copyOf(claims);
    }

    /**
     * 中文引证标记解析块。
     * 这里仅负责把 `[证据：E001]` / `[证据:E001]` 这种稳定格式抽出来，不做语义判断。
     */
    private List<String> extractEvidenceIds(String sentence) {
        LinkedHashSet<String> evidenceIds = new LinkedHashSet<>();
        Matcher matcher = EVIDENCE_PATTERN.matcher(sentence);
        while (matcher.find()) {
            String evidenceId = normalizeText(matcher.group(1));
            if (evidenceId != null) {
                evidenceIds.add(evidenceId);
            }
        }
        return new ArrayList<>(evidenceIds);
    }

    private List<String> defaultIssueFlags(List<String> evidenceIds, boolean traceabilitySensitive) {
        LinkedHashSet<String> flags = new LinkedHashSet<>();
        flags.add("MISSING_SOURCE_URL");
        if (traceabilitySensitive && (evidenceIds == null || evidenceIds.isEmpty())) {
            flags.add("UNKNOWN_EVIDENCE_ID");
        }
        return new ArrayList<>(flags);
    }

    private List<String> splitSentences(String line) {
        List<String> sentences = new ArrayList<>();
        for (String fragment : SENTENCE_SPLIT_PATTERN.split(line)) {
            if (fragment == null || fragment.isBlank()) {
                continue;
            }

            String normalizedFragment = fragment.trim();
            if (isEvidenceOnlyFragment(normalizedFragment) && !sentences.isEmpty()) {
                int lastIndex = sentences.size() - 1;
                sentences.set(lastIndex, sentences.get(lastIndex) + normalizedFragment);
                continue;
            }

            sentences.add(normalizedFragment);
        }
        if (sentences.isEmpty()) {
            sentences.add(line);
        }
        return sentences;
    }

    private boolean containsAny(String value, List<String> keywords) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (String keyword : keywords) {
            if (value.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String stripEvidenceMarkers(String sentence) {
        String cleaned = EVIDENCE_PATTERN.matcher(sentence).replaceAll("");
        return normalizeText(cleaned);
    }

    private boolean isEvidenceOnlyFragment(String fragment) {
        if (fragment == null || fragment.isBlank()) {
            return false;
        }
        String stripped = EVIDENCE_PATTERN.matcher(fragment).replaceAll("");
        return stripped.isBlank();
    }

    private String normalizeSectionKey(String sectionTitle) {
        if (sectionTitle == null || sectionTitle.isBlank()) {
            return "report";
        }
        String normalized = sectionTitle.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
        return normalized.isBlank() ? "report" : normalized;
    }

    private String normalizeText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isBlank() ? null : normalized;
    }
}
