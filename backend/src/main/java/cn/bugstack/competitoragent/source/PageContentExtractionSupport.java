package cn.bugstack.competitoragent.source;

import cn.bugstack.competitoragent.collection.CollectionFailureKind;
import cn.bugstack.competitoragent.collection.StructuredContentBlock;
import com.microsoft.playwright.Page;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 页面正文提取公共能力。
 * 统一给浏览器搜索预览和正式页面采集复用，避免两套正文识别规则越走越偏。
 */
@Slf4j
public final class PageContentExtractionSupport {

    private PageContentExtractionSupport() {
    }

    /**
     * 先提取候选正文块，再做评分挑选最佳正文。
     * 这样浏览器搜索阶段和正式采集阶段拿到的“正文定义”是一致的。
     */
    public static String extractMainContent(Page page) {
        try {
            Object rawBlocks = page.evaluate("""
                    () => {
                      const selectors = [
                        'article',
                        'main',
                        '[role="main"]',
                        '.article',
                        '.article-body',
                        '.article-content',
                        '.post-content',
                        '.entry-content',
                        '.markdown-body',
                        '.docs-content',
                        '.doc-content',
                        '.documentation',
                        '.content',
                        '.content-body',
                        '.prose',
                        'section'
                      ];
                      const blocks = [];
                      const seen = new Set();
                      for (const selector of selectors) {
                        const nodes = Array.from(document.querySelectorAll(selector)).slice(0, 8);
                        for (const node of nodes) {
                          const text = node && node.innerText ? node.innerText.trim() : '';
                          if (!text || text.length < 40) continue;
                          const key = `${selector}::${text.slice(0, 120)}`;
                          if (seen.has(key)) continue;
                          seen.add(key);
                          const links = Array.from(node.querySelectorAll('a'));
                          const linkTextLength = links
                            .map(item => item && item.innerText ? item.innerText.trim().length : 0)
                            .reduce((sum, len) => sum + len, 0);
                          blocks.push({
                            selector,
                            tagName: node.tagName || '',
                            className: node.className || '',
                            idName: node.id || '',
                            text,
                            linkTextLength
                          });
                        }
                      }
                      if (document.body && document.body.innerText) {
                        blocks.push({
                          selector: 'body',
                          tagName: 'BODY',
                          className: document.body.className || '',
                          idName: document.body.id || '',
                          text: document.body.innerText.trim(),
                          linkTextLength: Array.from(document.body.querySelectorAll('a'))
                            .map(item => item && item.innerText ? item.innerText.trim().length : 0)
                            .reduce((sum, len) => sum + len, 0)
                        });
                      }
                      return blocks;
                    }
                    """);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> blocks = rawBlocks instanceof List<?> ? (List<Map<String, Object>>) rawBlocks : List.of();
            return cleanContent(selectBestContentBlock(blocks));
        } catch (Exception e) {
            log.warn("提取正文失败，回退到 HTML 文本: {}", e.getMessage());
            return cleanContent(htmlToText(page.content()));
        }
    }

    /**
     * Task 5 的正式抽取结果入口。
     * 这里把正文、结构块、质量信号和评分统一收敛成一个结果对象，供 Playwright 与执行器链路复用。
     */
    public static PageContentExtractionResult extract(Page page, String sourceType) {
        Instant startedAt = Instant.now();
        String html = page == null ? null : page.content();
        String mainContent = page == null ? "" : extractMainContent(page);
        List<StructuredContentBlock> structuredBlocks = extractStructuredBlocks(html, sourceType);
        List<String> qualitySignals = buildQualitySignals(mainContent, structuredBlocks);
        double qualityScore = calculateQualityScore(mainContent, structuredBlocks);
        boolean success = (mainContent != null && !mainContent.isBlank()) || !structuredBlocks.isEmpty();

        PageContentExtractionResult.PageContentExtractionResultBuilder builder = PageContentExtractionResult.builder()
                .success(success)
                .title(page == null ? null : page.title())
                .mainContent(mainContent)
                .qualitySignals(qualitySignals)
                .qualityScore(qualityScore)
                .structuredBlocks(structuredBlocks)
                .collectedAt(Instant.now())
                .durationMillis(Duration.between(startedAt, Instant.now()).toMillis());
        if (!success) {
            builder.failureKind(CollectionFailureKind.EXTRACTION_EMPTY.name())
                    .errorMessage("page extraction produced neither main content nor structured blocks");
        }
        return builder.build();
    }

    public static String selectBestContentBlock(List<Map<String, Object>> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return "";
        }
        return blocks.stream()
                .map(PageContentExtractionSupport::toScoredContentBlock)
                .filter(block -> !block.text().isBlank())
                .max(Comparator.comparingDouble(ScoredContentBlock::score))
                .map(ScoredContentBlock::text)
                .orElse("");
    }

    /**
     * 搜索预览阶段只需要把正文前若干字符带回候选原因，避免 reason 字段无限膨胀。
     */
    public static String truncateForSummary(String content, int maxLength) {
        String normalized = cleanContent(content);
        if (normalized.isBlank()) {
            return "";
        }
        int safeMaxLength = Math.max(80, maxLength);
        return normalized.length() <= safeMaxLength
                ? normalized
                : normalized.substring(0, safeMaxLength);
    }

    /**
     * 结构块抽取先用最小启发式命中价格卡、文档目录和 JSON-LD。
     * 后续可以继续扩展更多 blockType，而不需要改动调用方契约。
     */
    private static List<StructuredContentBlock> extractStructuredBlocks(String html, String sourceType) {
        if (html == null || html.isBlank()) {
            return List.of();
        }
        String normalizedHtml = html.toLowerCase(Locale.ROOT);
        List<StructuredContentBlock> blocks = new ArrayList<>();
        if (normalizedHtml.contains("pricing-card")) {
            blocks.add(StructuredContentBlock.builder()
                    .blockType("PRICING_BLOCK")
                    .title("pricing")
                    .content("pricing-card hit")
                    .qualitySignal("PRICING_BLOCK_HIT")
                    .build());
        }
        if (normalizedHtml.contains("docs-outline")) {
            blocks.add(StructuredContentBlock.builder()
                    .blockType("DOCUMENTATION_OUTLINE")
                    .title("docs-outline")
                    .content("docs-outline hit")
                    .qualitySignal("DOCUMENTATION_OUTLINE_HIT")
                    .build());
        }
        if (normalizedHtml.contains("application/ld+json")) {
            blocks.add(StructuredContentBlock.builder()
                    .blockType("JSON_LD_METADATA")
                    .title(resolveStructuredBlockTitle(sourceType, "json-ld"))
                    .content("json-ld hit")
                    .qualitySignal("JSON_LD_METADATA_HIT")
                    .build());
        }
        return blocks;
    }

    /**
     * 质量信号既要表达命中，也要表达缺失，方便后续路由和兼容映射做出明确判断。
     */
    private static List<String> buildQualitySignals(String mainContent, List<StructuredContentBlock> structuredBlocks) {
        LinkedHashSet<String> signals = new LinkedHashSet<>();
        if (mainContent == null || mainContent.isBlank()) {
            signals.add("NO_MAIN_CONTENT");
        } else {
            signals.add("MAIN_CONTENT_READY");
        }
        if (structuredBlocks == null || structuredBlocks.isEmpty()) {
            signals.add("NO_STRUCTURED_BLOCKS");
            return new ArrayList<>(signals);
        }
        signals.add("STRUCTURED_BLOCK_HIT");
        for (StructuredContentBlock structuredBlock : structuredBlocks) {
            if (structuredBlock != null && structuredBlock.getQualitySignal() != null && !structuredBlock.getQualitySignal().isBlank()) {
                signals.add(structuredBlock.getQualitySignal());
            }
        }
        return new ArrayList<>(signals);
    }

    /**
     * 当前评分是 Task 5 的最小稳定分。
     * 正文长度提供基础分，结构块命中提供结构化加分，保证 fallback 后能稳定回填给执行器与 CollectorAgent。
     */
    private static double calculateQualityScore(String mainContent, List<StructuredContentBlock> structuredBlocks) {
        boolean hasMainContent = mainContent != null && !mainContent.isBlank();
        boolean hasStructuredBlocks = structuredBlocks != null && !structuredBlocks.isEmpty();
        if (!hasMainContent && !hasStructuredBlocks) {
            return 0.0D;
        }

        double score = 0.0D;
        if (hasMainContent) {
            int contentLength = mainContent.trim().length();
            if (contentLength >= 800) {
                score += 0.52D;
            } else if (contentLength >= 400) {
                score += 0.46D;
            } else if (contentLength >= 180) {
                score += 0.40D;
            } else {
                score += 0.32D;
            }
        }
        if (hasStructuredBlocks) {
            score += 0.15D;
            score += Math.min(0.33D, structuredBlocks.size() * 0.11D);
        }
        return Math.min(0.98D, Math.round(score * 100.0D) / 100.0D);
    }

    private static String resolveStructuredBlockTitle(String sourceType, String fallbackTitle) {
        if (sourceType == null || sourceType.isBlank()) {
            return fallbackTitle;
        }
        return sourceType.trim().toLowerCase(Locale.ROOT) + "-" + fallbackTitle;
    }

    private static ScoredContentBlock toScoredContentBlock(Map<String, Object> block) {
        String selector = stringValue(block.get("selector"));
        String tagName = stringValue(block.get("tagName"));
        String className = stringValue(block.get("className"));
        String idName = stringValue(block.get("idName"));
        String text = removeNoiseLines(cleanContent(stringValue(block.get("text"))));
        int linkTextLength = parseInt(block.get("linkTextLength"));

        double score = text.length();
        String classifier = (selector + " " + tagName + " " + className + " " + idName).toLowerCase(Locale.ROOT);
        if (classifier.contains("article")) {
            score += 220;
        }
        if (classifier.contains("main")) {
            score += 180;
        }
        if (classifier.contains("content") || classifier.contains("prose") || classifier.contains("markdown")) {
            score += 140;
        }
        if (classifier.contains("docs") || classifier.contains("documentation")) {
            score += 120;
        }
        if (classifier.contains("body")) {
            score -= 260;
        }
        if (containsNoiseMarker(classifier)) {
            score -= 360;
        }
        double linkDensity = text.isBlank() ? 0D : Math.min(1D, (double) linkTextLength / Math.max(1, text.length()));
        score -= linkDensity * 280;
        score += countLongLines(text) * 22D;
        return new ScoredContentBlock(text, score);
    }

    private static String htmlToText(String html) {
        if (html == null) {
            return "";
        }
        return html
                .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?is)<noscript[^>]*>.*?</noscript>", " ")
                .replaceAll("(?is)<br\\s*/?>", "\n")
                .replaceAll("(?is)</p>|</div>|</section>|</article>|</li>|</h[1-6]>", "\n")
                .replaceAll("(?is)<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
    }

    private static String cleanContent(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            return "";
        }
        return rawContent
                .replaceAll("\\r\\n", "\n")
                .replaceAll("[ \\t\\x0B\\f]+", " ")
                .replaceAll("\\n\\s*\\n\\s*\\n+", "\n\n")
                .replaceAll("[\\x00-\\x08\\x0E-\\x1F]", "")
                .trim();
    }

    private static String removeNoiseLines(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        List<String> cleanedLines = new ArrayList<>();
        for (String rawLine : content.split("\\n+")) {
            String line = rawLine.trim();
            if (line.isBlank()) {
                continue;
            }
            String normalized = line.toLowerCase(Locale.ROOT);
            if (normalized.startsWith("skip to content")
                    || normalized.startsWith("cookie")
                    || normalized.contains("accept cookies")
                    || normalized.contains("privacy preference")
                    || normalized.contains("subscribe")
                    || normalized.contains("breadcrumb")) {
                continue;
            }
            cleanedLines.add(line);
        }
        return String.join("\n", cleanedLines);
    }

    private static int countLongLines(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int count = 0;
        for (String line : text.split("\\n+")) {
            if (line.trim().length() >= 60) {
                count++;
            }
        }
        return count;
    }

    private static boolean containsNoiseMarker(String classifier) {
        return classifier.contains("nav")
                || classifier.contains("menu")
                || classifier.contains("footer")
                || classifier.contains("header")
                || classifier.contains("sidebar")
                || classifier.contains("breadcrumb")
                || classifier.contains("cookie")
                || classifier.contains("modal")
                || classifier.contains("banner");
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int parseInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private record ScoredContentBlock(String text, double score) {
    }
}
