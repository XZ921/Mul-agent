package cn.bugstack.competitoragent.source;

import com.microsoft.playwright.Page;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
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
