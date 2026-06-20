package cn.bugstack.competitoragent.collection;

import cn.bugstack.competitoragent.search.CanonicalUrlResolver;
import cn.bugstack.competitoragent.source.DirectHtmlReaderClient;
import cn.bugstack.competitoragent.source.JinaReaderClient;
import cn.bugstack.competitoragent.source.PageContentExtractionResult;
import cn.bugstack.competitoragent.source.SourceCollectRequest;
import cn.bugstack.competitoragent.source.SourceCollector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 网页采集执行器。
 * 这里继续复用现有 SourceCollector，但把它降级为 collection 子域中的浏览器渲染执行器，
 * 不再让整个网页采集链路退回到“单一路径 + 直接 URL 抓取”的旧模式。
 */
@Component
public class WebPageCollectionExecutor implements CollectionExecutor {

    private final DirectHtmlReaderClient directHtmlReaderClient;
    private final JinaReaderClient jinaReaderClient;
    private final SourceCollector sourceCollector;
    private final InternalLinkDiscoveryService internalLinkDiscoveryService;
    private final WebPageCollectionProperties webPageCollectionProperties;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public WebPageCollectionExecutor(SourceCollector sourceCollector) {
        this(null, null, sourceCollector, defaultInternalLinkDiscoveryService(), new WebPageCollectionProperties());
    }

    public WebPageCollectionExecutor(JinaReaderClient jinaReaderClient, SourceCollector sourceCollector) {
        this(null, jinaReaderClient, sourceCollector, defaultInternalLinkDiscoveryService(), new WebPageCollectionProperties());
    }

    public WebPageCollectionExecutor(DirectHtmlReaderClient directHtmlReaderClient,
                                     JinaReaderClient jinaReaderClient,
                                     SourceCollector sourceCollector) {
        this(directHtmlReaderClient, jinaReaderClient, sourceCollector, defaultInternalLinkDiscoveryService(), new WebPageCollectionProperties());
    }

    /**
     * Spring 容器中的正式执行器需要同时接入轻量正文抓取、完整渲染抓取和站内链接发现。
     * 这里对内部链接发现保留 ObjectProvider 兜底，避免测试场景没有显式注册该 Bean 时影响执行器装配。
     */
    @Autowired
    public WebPageCollectionExecutor(ObjectProvider<DirectHtmlReaderClient> directHtmlReaderClientProvider,
                                     ObjectProvider<JinaReaderClient> jinaReaderClientProvider,
                                     SourceCollector sourceCollector,
                                     ObjectProvider<InternalLinkDiscoveryService> internalLinkDiscoveryServiceProvider,
                                     ObjectProvider<WebPageCollectionProperties> webPageCollectionPropertiesProvider) {
        this(directHtmlReaderClientProvider == null ? null : directHtmlReaderClientProvider.getIfAvailable(),
                jinaReaderClientProvider == null ? null : jinaReaderClientProvider.getIfAvailable(),
                sourceCollector,
                internalLinkDiscoveryServiceProvider == null
                        ? null
                        : internalLinkDiscoveryServiceProvider.getIfAvailable(),
                webPageCollectionPropertiesProvider == null
                        ? null
                        : webPageCollectionPropertiesProvider.getIfAvailable());
    }

    WebPageCollectionExecutor(DirectHtmlReaderClient directHtmlReaderClient,
                              JinaReaderClient jinaReaderClient,
                              SourceCollector sourceCollector,
                              InternalLinkDiscoveryService internalLinkDiscoveryService,
                              WebPageCollectionProperties webPageCollectionProperties) {
        this.directHtmlReaderClient = directHtmlReaderClient;
        this.jinaReaderClient = jinaReaderClient;
        this.sourceCollector = sourceCollector;
        this.internalLinkDiscoveryService = internalLinkDiscoveryService == null
                ? defaultInternalLinkDiscoveryService()
                : internalLinkDiscoveryService;
        this.webPageCollectionProperties = webPageCollectionProperties == null
                ? new WebPageCollectionProperties()
                : webPageCollectionProperties;
    }

    @Override
    public String executorType() {
        return "WEB_PAGE";
    }

    @Override
    public boolean supports(CollectionTaskPackage taskPackage) {
        return taskPackage != null
                && ("JINA_READER".equalsIgnoreCase(taskPackage.getPrimaryTool())
                || "WEB_SCRAPER".equalsIgnoreCase(taskPackage.getPrimaryTool()));
    }

    @Override
    public CollectionExecutionResult execute(CollectionTaskPackage taskPackage) {
        if (taskPackage == null) {
            return buildFailureResult(null,
                    CollectionFailureKind.RUNTIME_FAILURE.name(),
                    "collection task package is null",
                    List.of(),
                    0L);
        }
        long startedAt = System.currentTimeMillis();

        if (requiresFullRender(taskPackage.getRenderHint())) {
            return collectByPlaywright(taskPackage, List.of("FULL_RENDER_REQUIRED"), null, startedAt);
        }

        PageContentExtractionResult directResult = collectByDirectHtmlReader(taskPackage);
        if (directResult != null && directResult.isUsable()) {
            CollectionExecutionResult mappedDirect = mapLightweightResult(taskPackage, directResult, startedAt);
            return maybeSupplementLinksWithPlaywright(taskPackage, mappedDirect, startedAt);
        }

        PageContentExtractionResult jinaResult = collectByJinaReader(taskPackage);
        if (jinaResult != null && jinaResult.isUsable()) {
            CollectionExecutionResult mappedJina = mapLightweightResult(taskPackage, jinaResult, startedAt);
            return maybeSupplementLinksWithPlaywright(taskPackage, mappedJina, startedAt);
        }

        return collectByPlaywright(taskPackage,
                mergeSignals(mergeLightweightFailureSignals(directResult, jinaResult), List.of("UPGRADED_TO_FULL_RENDER")),
                jinaResult == null ? directResult : jinaResult,
                startedAt);
    }

    /**
     * 第一轻量路径：Direct HTTP+Jsoup。
     * 该路径直接访问目标站点，成功时能避开 r.jina.ai 的跨境转发与页面空壳问题；
     * 失败时只返回质量信号，不在这里做 Playwright 兜底，避免轻量客户端承担路由职责。
     */
    private PageContentExtractionResult collectByDirectHtmlReader(CollectionTaskPackage taskPackage) {
        if (directHtmlReaderClient == null) {
            return null;
        }
        try {
            return directHtmlReaderClient.collect(buildCollectRequest(taskPackage, WebPageRenderHint.LIGHTWEIGHT));
        } catch (RuntimeException exception) {
            return PageContentExtractionResult.builder()
                    .success(false)
                    .failureKind(CollectionFailureKind.RUNTIME_FAILURE.name())
                    .errorMessage(exception.getMessage())
                    .qualitySignals(List.of("DIRECT_HTML_RUNTIME_FAILURE"))
                    .qualityScore(0.0D)
                    .structuredBlocks(List.of())
                    .collectedAt(Instant.now())
                    .durationMillis(0L)
                    .build();
        }
    }

    /**
     * 第二轻量路径：JinaReader。
     * Direct 不可用或正文质量不足时再进入该路径，保留原有 Jina -> Playwright 兜底语义。
     */
    private PageContentExtractionResult collectByJinaReader(CollectionTaskPackage taskPackage) {
        if (jinaReaderClient == null) {
            return null;
        }
        try {
            return jinaReaderClient.collect(buildCollectRequest(taskPackage, WebPageRenderHint.LIGHTWEIGHT));
        } catch (RuntimeException exception) {
            return PageContentExtractionResult.builder()
                    .success(false)
                    .failureKind(CollectionFailureKind.RUNTIME_FAILURE.name())
                    .errorMessage(exception.getMessage())
                    .qualitySignals(List.of("LIGHTWEIGHT_RUNTIME_FAILURE"))
                    .qualityScore(0.0D)
                    .structuredBlocks(List.of())
                    .collectedAt(Instant.now())
                    .durationMillis(0L)
                    .build();
        }
    }

    private List<String> resolveLightweightFailureSignals(PageContentExtractionResult lightweightResult, String fallbackSignal) {
        if (lightweightResult == null || lightweightResult.getQualitySignals() == null || lightweightResult.getQualitySignals().isEmpty()) {
            return List.of(fallbackSignal);
        }
        return lightweightResult.getQualitySignals();
    }

    private List<String> mergeLightweightFailureSignals(PageContentExtractionResult directResult,
                                                        PageContentExtractionResult jinaResult) {
        return mergeSignals(
                resolveLightweightFailureSignals(directResult, "DIRECT_HTML_UNAVAILABLE"),
                resolveLightweightFailureSignals(jinaResult, "LIGHTWEIGHT_RUNTIME_FAILURE")
        );
    }

    /**
     * FULL_RENDER 路径现在明确由 Playwright 承接。
     * 这里统一消费 request 结构，保证 renderHint、expectedBlockTypes、sourceUrls 都能完整透传。
     */
    private CollectionExecutionResult collectByPlaywright(CollectionTaskPackage taskPackage,
                                                          List<String> qualitySignals,
                                                          PageContentExtractionResult lightweightResult,
                                                          long startedAt) {
        if (sourceCollector == null) {
            return buildFailureResult(taskPackage,
                    resolveFailureKind(lightweightResult, "source collector unavailable"),
                    "source collector unavailable",
                    qualitySignals,
                    startedAt);
        }
        try {
            SourceCollector.CollectedPage page = sourceCollector.collect(buildCollectRequest(taskPackage, WebPageRenderHint.FULL_RENDER));
            if (page == null) {
                return buildFailureResult(taskPackage,
                        resolveFailureKind(lightweightResult, "collector returned null page"),
                        "collector returned null page",
                        qualitySignals,
                        startedAt);
            }

            JsonNode pageMetadata = readMetadata(page.getMetadata());
            boolean success = page.isSuccess();
            CollectionExecutionResult collectedResult = CollectionExecutionResult.builder()
                    .taskPackageKey(taskPackage.getPackageKey())
                    .targetIndex(taskPackage.getTargetIndex())
                    .executorType(executorType())
                    .success(success)
                    .status(success ? "SUCCESS" : "FAILED")
                    .resourceLocator(taskPackage.getResourceLocator())
                    .title(page.getTitle())
                    .content(page.getContent())
                    .sourceUrls(resolveCollectedSourceUrls(taskPackage, pageMetadata))
                    .errorMessage(page.getErrorMessage())
                    .failureKind(success ? null : resolveFailureKind(lightweightResult, resolveErrorMessage(page, pageMetadata)))
                    .qualitySignals(resolveQualitySignals(qualitySignals, success, pageMetadata))
                    .qualityScore(resolveQualityScore(success, pageMetadata))
                    .structuredBlocks(resolveStructuredBlocks(pageMetadata))
                    .collectedAt(resolveCollectedAt(pageMetadata, startedAt))
                    .durationMillis(resolveDurationMillis(pageMetadata, startedAt))
                    .build()
                    .normalize();
            return attachInternalDiscovery(taskPackage, collectedResult);
        } catch (RuntimeException exception) {
            return buildFailureResult(taskPackage,
                    resolveFailureKind(lightweightResult, exception.getMessage()),
                    exception.getMessage(),
                    qualitySignals,
                    startedAt);
        }
    }

    /**
     * 入口页轻量正文已经可用时，Playwright 只作为“补链接”工具使用。
     * 这里故意不把渲染后的标题、正文、评分覆盖回轻量结果，避免“补链接”路径被误解为完整渲染升级。
     */
    private CollectionExecutionResult maybeSupplementLinksWithPlaywright(CollectionTaskPackage taskPackage,
                                                                         CollectionExecutionResult lightweightResult,
                                                                         long startedAt) {
        CollectionExecutionResult normalizedLightweight = lightweightResult == null ? null : lightweightResult.normalize();
        if (!shouldSupplementLinks(taskPackage, normalizedLightweight)) {
            return normalizedLightweight;
        }
        try {
            SourceCollector.CollectedPage page = sourceCollector.collect(buildCollectRequest(taskPackage, WebPageRenderHint.FULL_RENDER));
            if (page == null || !page.isSuccess()) {
                return normalizedLightweight.toBuilder()
                        .qualitySignals(mergeSignals(normalizedLightweight.getQualitySignals(), List.of("PLAYWRIGHT_LINK_SUPPLEMENT_FAILED")))
                        .build()
                        .normalize();
            }

            JsonNode metadata = readMetadata(page.getMetadata());
            CollectionExecutionResult renderedLinkResult = CollectionExecutionResult.builder()
                    .taskPackageKey(taskPackage.getPackageKey())
                    .targetIndex(taskPackage.getTargetIndex())
                    .executorType(executorType())
                    .success(true)
                    .status("SUCCESS")
                    .resourceLocator(taskPackage.getResourceLocator())
                    .title(page.getTitle())
                    .content(page.getContent())
                    .sourceUrls(resolveCollectedSourceUrls(taskPackage, metadata))
                    .qualitySignals(resolveQualitySignals(List.of("PLAYWRIGHT_LINK_SUPPLEMENT_RENDERED"), true, metadata))
                    .qualityScore(resolveQualityScore(true, metadata))
                    .structuredBlocks(resolveStructuredBlocks(metadata))
                    .collectedAt(resolveCollectedAt(metadata, startedAt))
                    .durationMillis(resolveDurationMillis(metadata, startedAt))
                    .build()
                    .normalize();
            CollectionExecutionResult renderedWithLinks = attachInternalDiscovery(taskPackage, renderedLinkResult);
            return normalizedLightweight.toBuilder()
                    .discoveredCandidates(renderedWithLinks.getDiscoveredCandidates())
                    .qualitySignals(mergeSignals(normalizedLightweight.getQualitySignals(), List.of("PLAYWRIGHT_LINK_SUPPLEMENT_READY")))
                    .build()
                    .normalize();
        } catch (RuntimeException exception) {
            return normalizedLightweight.toBuilder()
                    .qualitySignals(mergeSignals(normalizedLightweight.getQualitySignals(), List.of("PLAYWRIGHT_LINK_SUPPLEMENT_FAILED")))
                    .build()
                    .normalize();
        }
    }

    /**
     * 补链接只允许发生在配置允许的入口页上。
     * 递归详情页即使链接少也不升级 Playwright，避免深层采集重新变成重渲染链路。
     */
    private boolean shouldSupplementLinks(CollectionTaskPackage taskPackage, CollectionExecutionResult lightweightResult) {
        if (taskPackage == null || lightweightResult == null || !lightweightResult.isSuccess()) {
            return false;
        }
        if (sourceCollector == null || !webPageCollectionProperties.isPlaywrightLinkSupplementEnabled()) {
            return false;
        }
        // 兼容旧任务包或直接 builder 构造路径：缺省 discoveryDepth 按入口页 0 处理，避免补链接静默失效。
        int depth = taskPackage.getDiscoveryDepth() == null
                ? 0
                : Math.max(0, taskPackage.getDiscoveryDepth());
        if (depth > Math.max(0, webPageCollectionProperties.getPlaywrightLinkSupplementMaxDepth())) {
            return false;
        }
        if (!isSupplementSourceTypeAllowed(taskPackage.getSourceType())) {
            return false;
        }
        int discoveredCount = lightweightResult.getDiscoveredCandidates() == null
                ? 0
                : lightweightResult.getDiscoveredCandidates().size();
        return discoveredCount < Math.max(0, webPageCollectionProperties.getPlaywrightLinkSupplementMinLinks());
    }

    private boolean isSupplementSourceTypeAllowed(String sourceType) {
        if (!StringUtils.hasText(sourceType)) {
            return false;
        }
        List<String> allowedSourceTypes = webPageCollectionProperties.getPlaywrightLinkSupplementSourceTypes();
        if (allowedSourceTypes == null || allowedSourceTypes.isEmpty()) {
            allowedSourceTypes = List.of("DOCS");
        }
        String normalizedSourceType = sourceType.trim();
        return allowedSourceTypes.stream()
                .filter(StringUtils::hasText)
                .anyMatch(allowed -> allowed.trim().equalsIgnoreCase(normalizedSourceType));
    }

    /**
     * 轻量正文成功时，直接映射统一结果契约，不再经过 Playwright。
     */
    private CollectionExecutionResult mapLightweightResult(CollectionTaskPackage taskPackage,
                                                           PageContentExtractionResult lightweightResult,
                                                           long startedAt) {
        CollectionExecutionResult collectedResult = CollectionExecutionResult.builder()
                .taskPackageKey(taskPackage.getPackageKey())
                .targetIndex(taskPackage.getTargetIndex())
                .executorType(executorType())
                .success(true)
                .status("SUCCESS")
                .resourceLocator(taskPackage.getResourceLocator())
                .title(lightweightResult.getTitle())
                .content(lightweightResult.getMainContent())
                .sourceUrls(resolveSourceUrls(taskPackage))
                .errorMessage(null)
                .failureKind(null)
                .qualitySignals(lightweightResult.getQualitySignals() == null ? List.of() : lightweightResult.getQualitySignals())
                .qualityScore(lightweightResult.getQualityScore())
                .structuredBlocks(lightweightResult.getStructuredBlocks() == null ? List.of() : lightweightResult.getStructuredBlocks())
                .collectedAt(lightweightResult.getCollectedAt() == null ? Instant.now() : lightweightResult.getCollectedAt())
                .durationMillis(lightweightResult.getDurationMillis() == null
                        ? Math.max(0L, System.currentTimeMillis() - startedAt)
                        : lightweightResult.getDurationMillis())
                .build()
                .normalize();
        return attachInternalDiscovery(taskPackage, collectedResult);
    }

    /**
     * Task 3 先把 request 化入口接通，避免 Task 4 再次搬运参数。
     */
    private SourceCollectRequest buildCollectRequest(CollectionTaskPackage taskPackage, WebPageRenderHint renderHint) {
        return SourceCollectRequest.builder()
                .url(taskPackage.getUrl())
                .competitorName(taskPackage.getCompetitorName())
                .sourceType(taskPackage.getSourceType())
                .renderHint(renderHint)
                .expectedBlockTypes(taskPackage.getExpectedBlockTypes())
                .sourceUrls(resolveSourceUrls(taskPackage))
                .build();
    }

    private boolean requiresFullRender(WebPageRenderHint renderHint) {
        return renderHint == WebPageRenderHint.FULL_RENDER
                || renderHint == WebPageRenderHint.LOGIN_REQUIRED
                || renderHint == WebPageRenderHint.INTERACTION_REQUIRED
                || renderHint == WebPageRenderHint.ANTI_BOT_RISK_HIGH;
    }

    private List<String> resolveSourceUrls(CollectionTaskPackage taskPackage) {
        if (taskPackage == null || taskPackage.getSourceUrls() == null || taskPackage.getSourceUrls().isEmpty()) {
            return List.of();
        }
        return taskPackage.getSourceUrls();
    }

    /**
     * 失败分类优先消费上游 failureKind；如果上游没有，再根据错误消息推断最小失败语义。
     */
    private String resolveFailureKind(PageContentExtractionResult lightweightResult, String errorMessage) {
        if (lightweightResult != null && StringUtils.hasText(lightweightResult.getFailureKind())) {
            return lightweightResult.getFailureKind();
        }
        if (!StringUtils.hasText(errorMessage)) {
            return CollectionFailureKind.RUNTIME_FAILURE.name();
        }
        String normalized = errorMessage.toLowerCase(Locale.ROOT);
        if (normalized.contains("timeout")) {
            return CollectionFailureKind.PAGE_TIMEOUT.name();
        }
        if (normalized.contains("blocked") || normalized.contains("captcha") || normalized.contains("bot")) {
            return CollectionFailureKind.ANTI_BOT_BLOCKED.name();
        }
        if (normalized.contains("status")) {
            return CollectionFailureKind.HTTP_STATUS_ERROR.name();
        }
        if (normalized.contains("thin") || normalized.contains("empty") || normalized.contains("unusable")) {
            return CollectionFailureKind.CONTENT_UNUSABLE.name();
        }
        return CollectionFailureKind.RUNTIME_FAILURE.name();
    }

    private String resolveErrorMessage(SourceCollector.CollectedPage page, JsonNode metadata) {
        if (metadata != null && metadata.path("failureKind").isTextual() && StringUtils.hasText(metadata.path("failureKind").asText())) {
            return metadata.path("failureKind").asText();
        }
        return page == null ? null : page.getErrorMessage();
    }

    private List<String> mergeSignals(List<String> baseSignals, List<String> additionalSignals) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (baseSignals != null) {
            merged.addAll(baseSignals);
        }
        if (additionalSignals != null) {
            merged.addAll(additionalSignals);
        }
        return new ArrayList<>(merged);
    }

    /**
     * Playwright 会把结构化结果写进旧的 metadata 字段，这里负责把它们回填成统一执行结果契约。
     */
    private JsonNode readMetadata(String metadata) {
        if (!StringUtils.hasText(metadata)) {
            return null;
        }
        try {
            return objectMapper.readTree(metadata);
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<String> resolveCollectedSourceUrls(CollectionTaskPackage taskPackage, JsonNode metadata) {
        LinkedHashSet<String> sourceUrls = new LinkedHashSet<>();
        if (metadata != null && metadata.path("sourceUrls").isArray()) {
            metadata.path("sourceUrls").forEach(node -> {
                if (node != null && node.isTextual() && StringUtils.hasText(node.asText())) {
                    sourceUrls.add(node.asText());
                }
            });
        }
        sourceUrls.addAll(resolveSourceUrls(taskPackage));
        return new ArrayList<>(sourceUrls);
    }

    private List<String> resolveQualitySignals(List<String> baseSignals, boolean success, JsonNode metadata) {
        List<String> metadataSignals = new ArrayList<>();
        if (metadata != null && metadata.path("qualitySignals").isArray()) {
            metadata.path("qualitySignals").forEach(node -> {
                if (node != null && node.isTextual() && StringUtils.hasText(node.asText())) {
                    metadataSignals.add(node.asText());
                }
            });
        }
        return mergeSignals(mergeSignals(baseSignals, metadataSignals), success ? List.of("FULL_RENDER_READY") : List.of());
    }

    private Double resolveQualityScore(boolean success, JsonNode metadata) {
        if (!success) {
            return 0.0D;
        }
        if (metadata != null && metadata.path("qualityScore").isNumber()) {
            return metadata.path("qualityScore").asDouble();
        }
        return 0.60D;
    }

    private List<StructuredContentBlock> resolveStructuredBlocks(JsonNode metadata) {
        if (metadata == null || !metadata.path("structuredBlocks").isArray()) {
            return List.of();
        }
        List<StructuredContentBlock> blocks = new ArrayList<>();
        metadata.path("structuredBlocks").forEach(node -> blocks.add(StructuredContentBlock.builder()
                .blockType(node.path("blockType").asText(null))
                .title(node.path("title").asText(null))
                .content(node.path("content").asText(null))
                .qualitySignal(node.path("qualitySignal").asText(null))
                .build()));
        return blocks;
    }

    private Instant resolveCollectedAt(JsonNode metadata, long startedAt) {
        if (metadata != null && metadata.path("collectedAt").isTextual()) {
            try {
                return Instant.parse(metadata.path("collectedAt").asText());
            } catch (Exception ignored) {
                // metadata 来自兼容层时可能是非 ISO 时间串，这里继续回退到当前时间
            }
        }
        return Instant.ofEpochMilli(Math.max(0L, startedAt));
    }

    private Long resolveDurationMillis(JsonNode metadata, long startedAt) {
        if (metadata != null && metadata.path("durationMillis").canConvertToLong()) {
            return metadata.path("durationMillis").asLong();
        }
        return Math.max(0L, System.currentTimeMillis() - startedAt);
    }

    private CollectionExecutionResult buildFailureResult(CollectionTaskPackage taskPackage,
                                                         String failureKind,
                                                         String errorMessage,
                                                         List<String> qualitySignals,
                                                         long startedAt) {
        return CollectionExecutionResult.builder()
                .taskPackageKey(taskPackage == null ? null : taskPackage.getPackageKey())
                .targetIndex(taskPackage == null ? null : taskPackage.getTargetIndex())
                .executorType(executorType())
                .success(false)
                .status("FAILED")
                .resourceLocator(taskPackage == null ? null : taskPackage.getResourceLocator())
                .discoveryDepth(taskPackage == null ? 0 : taskPackage.getDiscoveryDepth())
                .sourceUrls(taskPackage == null ? List.of() : resolveSourceUrls(taskPackage))
                .errorMessage(errorMessage)
                .failureKind(failureKind)
                .qualitySignals(qualitySignals == null ? List.of() : qualitySignals)
                .qualityScore(0.0D)
                .structuredBlocks(List.of())
                .collectedAt(Instant.now())
                .durationMillis(Math.max(0L, System.currentTimeMillis() - startedAt))
                .build()
                .normalize();
    }

    /**
     * 站内链接发现只在单页采集成功后执行，并把发现深度显式写回结果对象。
     * 这样协调器后续可以直接消费 discoveredCandidates 做递归调度，而不需要重新读取页面正文再次解析。
     */
    private CollectionExecutionResult attachInternalDiscovery(CollectionTaskPackage taskPackage,
                                                              CollectionExecutionResult result) {
        CollectionExecutionResult normalizedResult = result == null ? null : result.normalize();
        if (normalizedResult == null) {
            return null;
        }
        List<cn.bugstack.competitoragent.source.SourceCandidate> discoveredCandidates =
                internalLinkDiscoveryService.discover(
                        taskPackage,
                        normalizedResult,
                        taskPackage == null || taskPackage.getDiscoveryDepth() == null
                                ? 0
                                : taskPackage.getDiscoveryDepth()
                );
        return normalizedResult.toBuilder()
                .discoveredCandidates(discoveredCandidates)
                .discoveryDepth(taskPackage == null || taskPackage.getDiscoveryDepth() == null
                        ? 0
                        : taskPackage.getDiscoveryDepth())
                .build()
                .normalize();
    }

    private static InternalLinkDiscoveryService defaultInternalLinkDiscoveryService() {
        return new InternalLinkDiscoveryService(
                new InternalLinkDiscoveryProperties(),
                new CanonicalUrlResolver()
        );
    }
}
