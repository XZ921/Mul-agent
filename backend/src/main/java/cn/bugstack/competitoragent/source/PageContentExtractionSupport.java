package cn.bugstack.competitoragent.source;

import cn.bugstack.competitoragent.collection.CollectionFailureKind;
import cn.bugstack.competitoragent.collection.StructuredContentBlock;
import com.microsoft.playwright.Page;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 页面正文提取公共能力。
 * 统一给浏览器搜索预览和正式页面采集复用，避免两套正文识别规则越走越偏。
 */
@Slf4j
public final class PageContentExtractionSupport {

    private static final int MAX_EXTERNAL_SCRIPT_COUNT = 4;
    private static final int MAX_EXTERNAL_SCRIPT_CHARS = 512_000;
    private static final Duration EXTERNAL_SCRIPT_TIMEOUT = Duration.ofSeconds(2);
    private static final HttpClient EXTERNAL_SCRIPT_HTTP_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(EXTERNAL_SCRIPT_TIMEOUT)
            .build();
    private static final Pattern SCRIPT_DOCUMENT_CARD_PATTERN = Pattern.compile(
            "\\{[^{}]*title\\s*:\\s*['\"]([^'\"]{1,80})['\"][^{}]*url\\s*:\\s*['\"]([^'\"]+)['\"][^{}]*}",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern SCRIPT_DOCUMENT_CARD_URL_FIRST_PATTERN = Pattern.compile(
            "\\{[^{}]*url\\s*:\\s*['\"]([^'\"]+)['\"][^{}]*title\\s*:\\s*['\"]([^'\"]{1,80})['\"][^{}]*}",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern EXTERNAL_SCRIPT_PATTERN = Pattern.compile(
            "(?:src|href)\\s*=\\s*['\"]([^'\"]+\\.js(?:\\?[^'\"]*)?)['\"]",
            Pattern.CASE_INSENSITIVE);
    private static final List<String> HIGH_VALUE_DOCUMENT_URL_MARKERS = List.of(
            "/doc",
            "/docs",
            "/api",
            "/sdk",
            "/reference",
            "/guide"
    );
    private static final List<String> LOW_VALUE_DOCUMENT_LABEL_MARKERS = List.of(
            "联系我们",
            "联系",
            "contact",
            "feedback",
            "邮箱",
            "mail",
            "商务合作",
            "关于",
            "about",
            "隐私",
            "privacy",
            "协议",
            "terms"
    );

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
                      const toMarkdownText = (root) => {
                        const clone = root.cloneNode(true);
                        clone.querySelectorAll('script, style, noscript').forEach(item => item.remove());
                        clone.querySelectorAll('a[href]').forEach(anchor => {
                          const label = (anchor.innerText || anchor.textContent || '').replace(/\\s+/g, ' ').trim();
                          const href = anchor.href || anchor.getAttribute('href') || '';
                          const replacement = label && href ? `[${label}](${href})` : label;
                          anchor.replaceWith(document.createTextNode(replacement ? ` ${replacement} ` : ' '));
                        });
                        return (clone.textContent || '').replace(/\\s+/g, ' ').trim();
                      };
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
                            markdownText: toMarkdownText(node),
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
                          markdownText: toMarkdownText(document.body),
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
        String mainContent = page == null ? "" : enrichMainContentWithScriptDocumentLinks(
                extractMainContent(page),
                html,
                page.url()
        );
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

    /**
     * 某些 SPA 文档首页会把卡片渲染成 div + click handler，而不是标准 <a href>。
     * 此时正文能看到“账号授权 / Android SDK”等标题，但站内递归拿不到 URL；
     * 这里从页面脚本中的卡片数据恢复 Markdown 链接，继续交给统一的内部链接发现链路处理。
     */
    private static String enrichMainContentWithScriptDocumentLinks(String mainContent, String html, String pageUrl) {
        String normalizedContent = cleanContent(mainContent);
        List<ScriptDocumentLink> scriptLinks = extractScriptDocumentLinks(html, normalizedContent, pageUrl);
        if (scriptLinks.isEmpty()) {
            return normalizedContent;
        }
        LinkedHashSet<String> appendedLinks = new LinkedHashSet<>();
        for (ScriptDocumentLink scriptLink : scriptLinks) {
            String markdownLink = "[" + scriptLink.title() + "](" + scriptLink.url() + ")";
            if (!normalizedContent.contains(markdownLink)) {
                appendedLinks.add(markdownLink);
            }
        }
        if (appendedLinks.isEmpty()) {
            return normalizedContent;
        }
        return cleanContent(normalizedContent + "\n\n" + String.join("\n", appendedLinks));
    }

    private static List<ScriptDocumentLink> extractScriptDocumentLinks(String html, String mainContent, String pageUrl) {
        if (html == null || html.isBlank() || mainContent == null || mainContent.isBlank()) {
            return List.of();
        }
        LinkedHashSet<ScriptDocumentLink> links = new LinkedHashSet<>();
        appendScriptDocumentLinks(SCRIPT_DOCUMENT_CARD_PATTERN, html, mainContent, links, false);
        appendScriptDocumentLinks(SCRIPT_DOCUMENT_CARD_URL_FIRST_PATTERN, html, mainContent, links, true);
        for (String scriptContent : fetchCandidateExternalScripts(html, pageUrl)) {
            appendScriptDocumentLinks(SCRIPT_DOCUMENT_CARD_PATTERN, scriptContent, mainContent, links, false);
            appendScriptDocumentLinks(SCRIPT_DOCUMENT_CARD_URL_FIRST_PATTERN, scriptContent, mainContent, links, true);
        }
        return new ArrayList<>(links);
    }

    /**
     * 外部 JS 读取是兜底能力，只读取当前页面直接引用的少量 chunk。
     * 失败时静默降级，避免文档卡片补链影响主正文采集稳定性。
     */
    private static List<String> fetchCandidateExternalScripts(String html, String pageUrl) {
        LinkedHashSet<String> scriptUrls = extractCandidateExternalScriptUrls(html, pageUrl);
        if (scriptUrls.isEmpty()) {
            return List.of();
        }
        List<String> contents = new ArrayList<>();
        int fetched = 0;
        for (String scriptUrl : scriptUrls) {
            if (fetched >= MAX_EXTERNAL_SCRIPT_COUNT) {
                break;
            }
            String content = fetchExternalScript(scriptUrl);
            if (content == null || content.isBlank()) {
                continue;
            }
            contents.add(content);
            fetched++;
        }
        return contents;
    }

    private static LinkedHashSet<String> extractCandidateExternalScriptUrls(String html, String pageUrl) {
        List<String> scriptUrls = new ArrayList<>();
        Matcher matcher = EXTERNAL_SCRIPT_PATTERN.matcher(html);
        while (matcher.find()) {
            String resolvedUrl = resolveExternalScriptUrl(pageUrl, matcher.group(1));
            if (!isAllowedExternalScriptUrl(resolvedUrl)) {
                continue;
            }
            if (!scriptUrls.contains(resolvedUrl)) {
                scriptUrls.add(resolvedUrl);
            }
        }
        scriptUrls.sort(Comparator.comparingInt(PageContentExtractionSupport::scriptPriority));
        return new LinkedHashSet<>(scriptUrls);
    }

    private static int scriptPriority(String scriptUrl) {
        if (scriptUrl == null || scriptUrl.isBlank()) {
            return 99;
        }
        String normalized = scriptUrl.toLowerCase(Locale.ROOT);
        String fileName = normalized.substring(normalized.lastIndexOf('/') + 1);
        if (fileName.startsWith("doc.") || fileName.startsWith("docs.") || fileName.contains("documentation")) {
            return 0;
        }
        if (fileName.contains("doc") || normalized.contains("/doc")) {
            return 1;
        }
        if (fileName.contains("home")) {
            return 2;
        }
        return 5;
    }

    private static String fetchExternalScript(String scriptUrl) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(scriptUrl))
                    .timeout(EXTERNAL_SCRIPT_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = EXTERNAL_SCRIPT_HTTP_CLIENT.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8)
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300 || response.body() == null) {
                return null;
            }
            String body = response.body();
            return body.length() <= MAX_EXTERNAL_SCRIPT_CHARS
                    ? body
                    : body.substring(0, MAX_EXTERNAL_SCRIPT_CHARS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return null;
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    private static String resolveExternalScriptUrl(String pageUrl, String rawScriptUrl) {
        if (rawScriptUrl == null || rawScriptUrl.isBlank()) {
            return null;
        }
        try {
            String trimmed = rawScriptUrl.trim();
            if (trimmed.startsWith("//")) {
                return "https:" + trimmed;
            }
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                return trimmed;
            }
            if (pageUrl == null || pageUrl.isBlank()) {
                return null;
            }
            return URI.create(pageUrl).resolve(trimmed).toString();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static boolean isAllowedExternalScriptUrl(String scriptUrl) {
        if (scriptUrl == null || scriptUrl.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(scriptUrl);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && host != null
                    && !host.isBlank();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static void appendScriptDocumentLinks(Pattern pattern,
                                                  String html,
                                                  String mainContent,
                                                  LinkedHashSet<ScriptDocumentLink> links,
                                                  boolean urlFirst) {
        Matcher matcher = pattern.matcher(html);
        while (matcher.find()) {
            String title = cleanScriptValue(urlFirst ? matcher.group(2) : matcher.group(1));
            String url = cleanScriptValue(urlFirst ? matcher.group(1) : matcher.group(2));
            if (!isRecoverableScriptDocumentLink(title, url, mainContent)) {
                continue;
            }
            links.add(new ScriptDocumentLink(title, url));
        }
    }

    private static boolean isRecoverableScriptDocumentLink(String title, String url, String mainContent) {
        if (title.isBlank() || url.isBlank() || !mainContent.contains(title)) {
            return false;
        }
        String signal = (title + " " + url).toLowerCase(Locale.ROOT);
        if (containsAny(signal, LOW_VALUE_DOCUMENT_LABEL_MARKERS)) {
            return false;
        }
        return containsAny(url.toLowerCase(Locale.ROOT), HIGH_VALUE_DOCUMENT_URL_MARKERS);
    }

    private static String cleanScriptValue(String value) {
        return value == null
                ? ""
                : value.replace("\\/", "/")
                .replace("\\u002F", "/")
                .replace("\\\"", "\"")
                .trim();
    }

    public static String selectBestContentBlock(List<Map<String, Object>> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return "";
        }
        return blocks.stream()
                .map(PageContentExtractionSupport::toScoredContentBlock)
                .filter(block -> !block.text().isBlank())
                .max(Comparator.comparingDouble(ScoredContentBlock::score))
                .map(ScoredContentBlock::outputText)
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
        // 保留正文块中的链接锚点，递归采集才能继续进入文档子页面。
        String markdownText = removeNoiseLines(cleanContent(stringValue(block.get("markdownText"))));
        String outputText = markdownText.isBlank() ? text : markdownText;
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
        return new ScoredContentBlock(text, outputText, score);
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

    private static boolean containsAny(String value, List<String> keywords) {
        if (value == null || value.isBlank() || keywords == null || keywords.isEmpty()) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        return keywords.stream()
                .filter(keyword -> keyword != null && !keyword.isBlank())
                .map(keyword -> keyword.toLowerCase(Locale.ROOT))
                .anyMatch(normalized::contains);
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

    private record ScoredContentBlock(String text, String outputText, double score) {
    }

    private record ScriptDocumentLink(String title, String url) {
    }
}
