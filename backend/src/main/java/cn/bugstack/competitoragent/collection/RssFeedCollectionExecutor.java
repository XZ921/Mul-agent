package cn.bugstack.competitoragent.collection;

import cn.bugstack.competitoragent.source.RssFeedClient;
import cn.bugstack.competitoragent.source.RssFeedItem;
import cn.bugstack.competitoragent.source.RssFeedProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * RSS feed 结构化采集执行器。
 * 它只承接显式 feed URL，并把结果继续收口到统一的 CollectionExecutionResult，
 * 这样 collectionAudit / replay / checkpoint 都不需要为 RSS 再开旁路协议。
 */
@Component
public class RssFeedCollectionExecutor extends ApiDataCollectionExecutor {

    private final RssFeedClient rssFeedClient;
    private final RssFeedProperties rssFeedProperties;

    public RssFeedCollectionExecutor(RssFeedClient rssFeedClient, RssFeedProperties rssFeedProperties) {
        this.rssFeedClient = rssFeedClient;
        this.rssFeedProperties = rssFeedProperties == null ? new RssFeedProperties() : rssFeedProperties;
    }

    @Override
    public boolean supports(CollectionTaskPackage taskPackage) {
        return taskPackage != null
                && rssFeedProperties.isEnabled()
                && "RSS".equalsIgnoreCase(taskPackage.getPrimaryTool());
    }

    @Override
    public CollectionExecutionResult execute(CollectionTaskPackage taskPackage) {
        Instant startedAt = Instant.now();
        try {
            List<RssFeedItem> items = rssFeedClient.fetch(taskPackage.getUrl());
            LinkedHashSet<String> sourceUrls = new LinkedHashSet<>();
            List<Map<String, Object>> payloadItems = new ArrayList<>();
            StringBuilder content = new StringBuilder();

            for (RssFeedItem item : items) {
                if (item == null) {
                    continue;
                }
                if (item.getSourceUrls() != null) {
                    sourceUrls.addAll(item.getSourceUrls());
                }
                Map<String, Object> payloadItem = new LinkedHashMap<>();
                payloadItem.put("title", item.getTitle());
                payloadItem.put("link", item.getLink());
                payloadItem.put("publishedAt", item.getPublishedAt());
                payloadItem.put("summary", item.getSummary());
                payloadItems.add(payloadItem);
                if (item.getTitle() != null) {
                    content.append(item.getTitle()).append('\n');
                }
                if (item.getSummary() != null) {
                    content.append(item.getSummary()).append("\n\n");
                }
            }
            if (sourceUrls.isEmpty() && taskPackage.getSourceUrls() != null) {
                sourceUrls.addAll(taskPackage.getSourceUrls());
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("feedUrl", taskPackage.getUrl());
            payload.put("items", payloadItems);

            return CollectionExecutionResult.builder()
                    .taskPackageKey(taskPackage.getPackageKey())
                    .targetIndex(taskPackage.getTargetIndex())
                    .executorType(executorType())
                    .success(true)
                    .status("SUCCESS")
                    .resourceLocator(taskPackage.getResourceLocator())
                    .title(taskPackage.getUrl())
                    .content(content.toString().trim())
                    .sourceUrls(new ArrayList<>(sourceUrls))
                    .structuredPayload(payload)
                    .qualitySignals(payloadItems.isEmpty()
                            ? List.of("FEED_EMPTY")
                            : List.of("FEED_ITEMS_READY"))
                    .qualityScore(payloadItems.isEmpty() ? 0.30D : 0.68D)
                    .structuredBlocks(List.of())
                    .collectedAt(Instant.now())
                    .durationMillis(Duration.between(startedAt, Instant.now()).toMillis())
                    .build()
                    .normalize();
        } catch (RuntimeException exception) {
            return CollectionExecutionResult.builder()
                    .taskPackageKey(taskPackage.getPackageKey())
                    .targetIndex(taskPackage.getTargetIndex())
                    .executorType(executorType())
                    .success(false)
                    .status("FAILED")
                    .resourceLocator(taskPackage.getResourceLocator())
                    .sourceUrls(taskPackage.getSourceUrls() == null ? List.of() : taskPackage.getSourceUrls())
                    .errorMessage(exception.getMessage())
                    .failureKind(CollectionFailureKind.RUNTIME_FAILURE.name())
                    .qualitySignals(List.of("FEED_COLLECTION_FAILED"))
                    .qualityScore(0.0D)
                    .structuredBlocks(List.of())
                    .collectedAt(Instant.now())
                    .durationMillis(Duration.between(startedAt, Instant.now()).toMillis())
                    .build()
                    .normalize();
        }
    }
}
