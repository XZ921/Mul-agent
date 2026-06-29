package cn.bugstack.competitoragent.collection;

import cn.bugstack.competitoragent.search.tavily.TavilyPrefetchedContent;
import cn.bugstack.competitoragent.search.tavily.TavilyPrefetchedContentRegistry;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Tavily 预取正文采集执行器。
 * 它只消费搜索阶段已经缓存好的 Tavily 正文，不再发起任何网络请求，
 * 这样可以把 “搜索命中 -> 直接落地 EvidenceSource” 的 Fast Lane 语义稳定收口到 collection 子域。
 */
@Component
public class TavilyPrefetchedExecutor implements CollectionExecutor {

    private static final List<String> NOISE_MARKERS = List.of("相关推荐", "猜你喜欢", "热门推荐");

    private final TavilyPrefetchedContentRegistry registry;

    public TavilyPrefetchedExecutor(TavilyPrefetchedContentRegistry registry) {
        this.registry = registry == null ? new TavilyPrefetchedContentRegistry() : registry;
    }

    @Override
    public String executorType() {
        return "TAVILY_PREFETCHED";
    }

    @Override
    public boolean supports(CollectionTaskPackage taskPackage) {
        return taskPackage != null && "TAVILY_PREFETCHED".equalsIgnoreCase(taskPackage.getPrimaryTool());
    }

    @Override
    public CollectionExecutionResult execute(CollectionTaskPackage taskPackage) {
        if (taskPackage == null) {
            return buildFailureResult(null,
                    CollectionFailureKind.RUNTIME_FAILURE.name(),
                    "collection task package is null",
                    List.of("TAVILY_TASK_PACKAGE_MISSING"));
        }
        if (!StringUtils.hasText(taskPackage.getPrefetchedContentRef())) {
            return buildFailureResult(taskPackage,
                    CollectionFailureKind.CONTENT_UNUSABLE.name(),
                    "tavily prefetched content ref missing",
                    List.of("TAVILY_PREFETCHED_CONTENT_MISSING"));
        }

        /**
         * 这里必须使用单次 remove 原子消费，避免同一份 Tavily 正文被下游重复使用，
         * 否则会把搜索阶段的临时缓存重新变成一个“可重复读取的数据源”，破坏 Fast Lane 的运行时边界。
         */
        TavilyPrefetchedContent content = registry.remove(taskPackage.getPrefetchedContentRef()).orElse(null);
        if (content == null) {
            return buildFailureResult(taskPackage,
                    CollectionFailureKind.CONTENT_UNUSABLE.name(),
                    "tavily prefetched content missing or already consumed",
                    List.of("TAVILY_PREFETCHED_CONTENT_MISSING"));
        }

        String cleanedContent = cleanContent(content);
        if (!StringUtils.hasText(cleanedContent)) {
            return buildFailureResult(taskPackage,
                    CollectionFailureKind.CONTENT_UNUSABLE.name(),
                    "tavily prefetched content empty after cleaning",
                    List.of("TAVILY_PREFETCHED_CONTENT_EMPTY"));
        }

        List<String> sourceUrls = resolveSourceUrls(taskPackage, content);
        return CollectionExecutionResult.builder()
                .taskPackageKey(taskPackage.getPackageKey())
                .targetIndex(taskPackage.getTargetIndex())
                .executorType(executorType())
                .success(true)
                .status("SUCCESS")
                .resourceLocator(resolveResourceLocator(taskPackage, content))
                .title(StringUtils.hasText(content.getTitle()) ? content.getTitle() : content.getUrl())
                .content(cleanedContent)
                .sourceUrls(sourceUrls)
                .qualitySignals(List.of("TAVILY_RAW_CONTENT_READY", "TAVILY_PREFETCHED_CONTENT_CONSUMED"))
                .qualityScore(resolveQualityScore(content))
                .structuredBlocks(List.of())
                .collectedAt(Instant.now())
                .durationMillis(0L)
                .build()
                .normalize();
    }

    /**
     * Tavily 的 raw_content 有时会在正文尾部拼接推荐位、相关推荐等噪声。
     * 这里先做最小清洗，只裁掉这些明确的尾部噪声标记，避免把真正正文中的普通句子误删。
     */
    private String cleanContent(TavilyPrefetchedContent content) {
        if (content == null) {
            return null;
        }
        String baseContent = firstNonBlank(content.getCleanedContent(), content.getRawContent(), content.getContent());
        if (!StringUtils.hasText(baseContent)) {
            return null;
        }
        String cleaned = baseContent.trim();
        for (String marker : NOISE_MARKERS) {
            int markerIndex = cleaned.indexOf(marker);
            if (markerIndex > 0) {
                cleaned = cleaned.substring(0, markerIndex).trim();
            }
        }
        return cleaned;
    }

    private List<String> resolveSourceUrls(CollectionTaskPackage taskPackage, TavilyPrefetchedContent content) {
        LinkedHashSet<String> sourceUrls = new LinkedHashSet<>();
        if (content != null && content.getSourceUrls() != null) {
            sourceUrls.addAll(content.getSourceUrls());
        }
        if (taskPackage != null && taskPackage.getSourceUrls() != null) {
            sourceUrls.addAll(taskPackage.getSourceUrls());
        }
        if (content != null && StringUtils.hasText(content.getUrl())) {
            sourceUrls.add(content.getUrl().trim());
        }
        if (taskPackage != null && StringUtils.hasText(taskPackage.getResourceLocator())) {
            sourceUrls.add(taskPackage.getResourceLocator().trim());
        }
        return new ArrayList<>(sourceUrls);
    }

    private String resolveResourceLocator(CollectionTaskPackage taskPackage, TavilyPrefetchedContent content) {
        if (taskPackage != null && StringUtils.hasText(taskPackage.getResourceLocator())) {
            return taskPackage.getResourceLocator();
        }
        return content == null ? null : content.getUrl();
    }

    private Double resolveQualityScore(TavilyPrefetchedContent content) {
        if (content == null || content.getTavilyScore() == null) {
            return 0.70D;
        }
        return Math.max(0.0D, Math.min(1.0D, content.getTavilyScore()));
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private CollectionExecutionResult buildFailureResult(CollectionTaskPackage taskPackage,
                                                         String failureKind,
                                                         String errorMessage,
                                                         List<String> qualitySignals) {
        return CollectionExecutionResult.builder()
                .taskPackageKey(taskPackage == null ? null : taskPackage.getPackageKey())
                .targetIndex(taskPackage == null ? null : taskPackage.getTargetIndex())
                .executorType(executorType())
                .success(false)
                .status("FAILED")
                .resourceLocator(taskPackage == null ? null : taskPackage.getResourceLocator())
                .sourceUrls(resolveFailureSourceUrls(taskPackage))
                .errorMessage(errorMessage)
                .failureKind(failureKind == null ? CollectionFailureKind.RUNTIME_FAILURE.name() : failureKind)
                .qualitySignals(qualitySignals == null ? List.of() : qualitySignals)
                .qualityScore(0.0D)
                .structuredBlocks(List.of())
                .collectedAt(Instant.now())
                .durationMillis(0L)
                .build()
                .normalize();
    }

    private List<String> resolveFailureSourceUrls(CollectionTaskPackage taskPackage) {
        if (taskPackage == null) {
            return List.of();
        }
        LinkedHashSet<String> sourceUrls = new LinkedHashSet<>();
        if (taskPackage.getSourceUrls() != null) {
            sourceUrls.addAll(taskPackage.getSourceUrls());
        }
        if (StringUtils.hasText(taskPackage.getResourceLocator())) {
            sourceUrls.add(taskPackage.getResourceLocator().trim());
        }
        return new ArrayList<>(sourceUrls);
    }
}
