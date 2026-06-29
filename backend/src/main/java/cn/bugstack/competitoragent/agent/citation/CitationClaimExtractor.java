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
 * 从报告 Markdown 正文里抽取需要做引用核查的 claim。
 * 这里只做文本结构化，不做外部抓取、模型推断或编排决策。
 */
@Component
public class CitationClaimExtractor {

    private static final Pattern HEADING_PATTERN = Pattern.compile("^#{1,6}\\s+(.+)$");
    private static final Pattern EVIDENCE_PATTERN = Pattern.compile(
            "\\[(?:证据|[Ee]vidence)\\s*[:：]\\s*([A-Za-z0-9_-]+)]");
    private static final Pattern SENTENCE_SPLIT_PATTERN = Pattern.compile("(?<=[。！？!?])\\s*");

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
     * 1. 遇到标题时更新当前章节上下文；
     * 2. 普通文本再按句号/问号/感叹号切分；
     * 3. 每条候选 claim 再判断证据编号、敏感度与降级语义。
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
                // 证据标记必须先从原始句子中提取，随后才能做正文归一化；
                // 否则 normalizeClaimText 会先把 `[证据：E001]` 剥掉，导致 UNKNOWN_EVIDENCE_ID 全部漏报。
                List<String> evidenceIds = extractEvidenceIds(sentence);
                String normalizedSentence = normalizeClaimText(sentence);
                if (normalizedSentence == null) {
                    continue;
                }
                if (isFormattingOnlyFragment(normalizedSentence) || isSectionLeadOnlyFragment(normalizedSentence)) {
                    continue;
                }

                boolean traceabilitySensitive = !evidenceIds.isEmpty()
                        || containsAny(currentSectionTitle, TRACEABILITY_KEYWORDS)
                        || containsAny(normalizedSentence, TRACEABILITY_KEYWORDS);

                // 只要句子显式带有“推测 / 待验证 / 需补证”等语义，就保留下游可识别的降级标记。
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
     * 这里只负责从稳定格式的引用标记中抽 evidenceId，
     * 如 `[证据：E001]`、`[证据:E001]`、`[Evidence: E001]`。
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

    /**
     * Writer 输出常带 Markdown 列表和加粗片段。
     * 这里先拆句，再把“只有证据标记的尾片段”或“被句号错误拆开的降级前缀”重新并回上一句。
     */
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
            if (shouldMergeWithPreviousFragment(sentences, normalizedFragment)) {
                int lastIndex = sentences.size() - 1;
                sentences.set(lastIndex, mergeMarkdownFragments(sentences.get(lastIndex), normalizedFragment));
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

    /**
     * Citation 检查直接消费 Markdown 正文，
     * 所以这里先折叠列表前缀、加粗标记和多余空白，避免把纯格式符号当成 claim。
     */
    private String normalizeClaimText(String sentence) {
        if (sentence == null) {
            return null;
        }
        String normalized = stripEvidenceMarkers(sentence);
        if (normalized == null) {
            return null;
        }
        normalized = normalized
                .replaceFirst("^\\s*([*+-]|\\d+\\.)\\s*", "")
                .replaceAll("\\*\\*", "")
                .replaceAll("\\s+", " ")
                .trim();
        return normalized.isBlank() ? null : normalized;
    }

    /**
     * 真实报告里会出现 `**`、`---`、`|` 之类纯 Markdown 装饰片段，
     * 这类内容没有业务语义，必须在抽取阶段直接忽略。
     */
    private boolean isFormattingOnlyFragment(String fragment) {
        if (fragment == null || fragment.isBlank()) {
            return true;
        }
        String stripped = fragment
                .replace("*", "")
                .replace("|", "")
                .replace("-", "")
                .replace("_", "")
                .replace("`", "")
                .replace(" ", "")
                .trim();
        return stripped.isBlank();
    }

    /**
     * 只包含“小标题/列表标签”的片段本身不是需要核查的判断句，
     * 例如“生态互补性探索：”“潜在短板与风险：”应交由后续正文承载事实内容。
     */
    private boolean isSectionLeadOnlyFragment(String fragment) {
        if (fragment == null || fragment.isBlank()) {
            return false;
        }
        String stripped = fragment.trim();
        if (!stripped.endsWith("：") && !stripped.endsWith(":")) {
            return false;
        }
        return !stripped.contains("。")
                && !stripped.contains("？")
                && !stripped.contains("?")
                && !stripped.contains("[证据")
                && !stripped.contains("[Evidence");
    }

    /**
     * Writer 常把“推测，当前公开资料未能验证……”这种降级前缀单独拆成一句，
     * 这里需要显式与下一句合并，避免后续 Citation Agent 丢失谨慎语义。
     */
    private boolean shouldMergeWithPreviousFragment(List<String> sentences, String currentFragment) {
        if (sentences == null || sentences.isEmpty() || currentFragment == null || currentFragment.isBlank()) {
            return false;
        }
        String previous = normalizeClaimText(sentences.get(sentences.size() - 1));
        String current = normalizeClaimText(currentFragment);
        if (previous == null || current == null) {
            return false;
        }
        return isStandaloneDowngradeFragment(previous)
                && !isFormattingOnlyFragment(current)
                && !isSectionLeadOnlyFragment(current);
    }

    private boolean isStandaloneDowngradeFragment(String fragment) {
        if (fragment == null || fragment.isBlank()) {
            return false;
        }
        return containsAny(fragment, DOWNGRADE_KEYWORDS) && fragment.length() <= 32;
    }

    private String mergeMarkdownFragments(String previousFragment, String currentFragment) {
        String previous = normalizeClaimText(previousFragment);
        String current = normalizeClaimText(currentFragment);
        if (previous == null) {
            return currentFragment;
        }
        if (current == null) {
            return previousFragment;
        }
        return previous + " " + current;
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
