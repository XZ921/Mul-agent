package cn.bugstack.competitoragent.collection;

import cn.bugstack.competitoragent.source.JinaReaderClient;
import cn.bugstack.competitoragent.source.PageContentExtractionResult;
import cn.bugstack.competitoragent.source.SourceCollectRequest;
import cn.bugstack.competitoragent.source.SourceCollector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
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

    private final JinaReaderClient jinaReaderClient;
    private final SourceCollector sourceCollector;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public WebPageCollectionExecutor(SourceCollector sourceCollector) {
        this(null, sourceCollector);
    }

    /**
     * Spring 容器中必须显式使用双依赖构造器。
     * 第五轮引入 JinaReader 主路径后，这个执行器已经不再是“只有一个浏览器依赖”的单构造器 bean，
     * 如果不明确声明注入入口，集成上下文会退回到寻找默认无参构造器并在启动阶段失败。
     */
    @Autowired
    public WebPageCollectionExecutor(JinaReaderClient jinaReaderClient, SourceCollector sourceCollector) {
        this.jinaReaderClient = jinaReaderClient;
        this.sourceCollector = sourceCollector;
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

        PageContentExtractionResult lightweightResult = collectByJinaReader(taskPackage);
        if (lightweightResult != null && lightweightResult.isUsable()) {
            return mapLightweightResult(taskPackage, lightweightResult, startedAt);
        }

        return collectByPlaywright(taskPackage,
                mergeSignals(lightweightResult == null ? List.of("LIGHTWEIGHT_RUNTIME_FAILURE") : lightweightResult.getQualitySignals(),
                        List.of("UPGRADED_TO_FULL_RENDER")),
                lightweightResult,
                startedAt);
    }

    /**
     * 轻量页面优先走 JinaReader。
     * 如果这里拿不到可用正文，再统一升级到 Playwright 兜底，避免旧的单路径采集重新回流。
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
            return CollectionExecutionResult.builder()
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
        } catch (RuntimeException exception) {
            return buildFailureResult(taskPackage,
                    resolveFailureKind(lightweightResult, exception.getMessage()),
                    exception.getMessage(),
                    qualitySignals,
                    startedAt);
        }
    }

    /**
     * 轻量正文成功时，直接映射统一结果契约，不再经过 Playwright。
     */
    private CollectionExecutionResult mapLightweightResult(CollectionTaskPackage taskPackage,
                                                           PageContentExtractionResult lightweightResult,
                                                           long startedAt) {
        return CollectionExecutionResult.builder()
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
}
