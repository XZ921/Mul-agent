package cn.bugstack.competitoragent.collection;

import cn.bugstack.competitoragent.search.SearchCollectionTarget;
import cn.bugstack.competitoragent.source.SourceCandidate;
import cn.bugstack.competitoragent.source.SourceCollector;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 递归采集协调器红灯测试。
 * 这里锁定“入口页发现 child candidate 后会继续入队执行，并进入 replay/audit”这一核心语义，
 * 防止后续实现只在单页结果里挂 discoveredCandidates，却没有真正把子页面采下来。
 */
class CollectionExecutionCoordinatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void shouldCollectDiscoveredInternalPagesAndIncludeThemInReplayTimeline() {
        CollectionExecutor executor = mock(CollectionExecutor.class);
        when(executor.supports(any())).thenReturn(true);
        when(executor.execute(argThat(pkg ->
                "collect_sources_docs#001".equals(readStringAccessor(pkg, "packageKey")))))
                .thenReturn(objectMapper.convertValue(Map.of(
                        "executorType", "WEB_PAGE",
                        "success", true,
                        "status", "SUCCESS",
                        "resourceLocator", "https://docs.example.com/open/doc",
                        "sourceUrls", List.of("https://docs.example.com/open/doc"),
                        "content", "[账户授权](https://docs.example.com/open/doc/auth)",
                        "discoveryDepth", 0,
                        "discoveredCandidates", List.of(Map.of(
                                "url", "https://docs.example.com/open/doc/auth",
                                "title", "账户授权",
                                "sourceType", "DOCS",
                                "discoveryMethod", "INTERNAL_LINK_DISCOVERY",
                                "sourceUrls", List.of(
                                        "https://docs.example.com/open/doc",
                                        "https://docs.example.com/open/doc/auth"
                                )
                        ))
                ), CollectionExecutionResult.class));
        when(executor.execute(argThat(pkg ->
                "collect_sources_docs#002".equals(readStringAccessor(pkg, "packageKey")))))
                .thenReturn(objectMapper.convertValue(Map.of(
                        "executorType", "WEB_PAGE",
                        "success", true,
                        "status", "SUCCESS",
                        "resourceLocator", "https://docs.example.com/open/doc/auth",
                        "sourceUrls", List.of(
                                "https://docs.example.com/open/doc",
                                "https://docs.example.com/open/doc/auth"
                        ),
                        "discoveryDepth", 1
                ), CollectionExecutionResult.class));

        CollectionExecutionCoordinator coordinator = new CollectionExecutionCoordinator(
                new CollectionTaskPackageBuilder(),
                new CollectionExecutorRegistry(List.of(executor))
        );

        SearchCollectionTarget target = SearchCollectionTarget.builder()
                .candidate(SourceCandidate.builder()
                        .url("https://docs.example.com/open/doc")
                        .title("Open Docs")
                        .sourceType("DOCS")
                        .sourceFamilyKey("official")
                        .providerKey("http")
                        .sourceUrls(List.of("https://docs.example.com/open/doc"))
                        .build())
                .build();

        CollectionExecutionReport report = coordinator.execute(
                41L,
                "collect_sources_docs",
                9L,
                "Acme AI",
                List.of(target)
        );

        assertThat(report.getResults()).hasSize(2);
        assertThat(report.getAuditSnapshot().getReplayTimeline()).hasSize(2);
        assertThat(readStringAccessor(report.getResults().get(1), "resourceLocator"))
                .isEqualTo("https://docs.example.com/open/doc/auth");
        assertThat(readIntegerAccessor(report.getResults().get(1), "discoveryDepth"))
                .isEqualTo(1);

        verify(executor, times(1)).execute(argThat(pkg ->
                "collect_sources_docs#002".equals(readStringAccessor(pkg, "packageKey"))));
    }

    @Test
    void shouldReusePrefetchedSearchVerificationPageWithoutCallingExecutor() {
        CollectionExecutor executor = mock(CollectionExecutor.class);
        when(executor.supports(any())).thenReturn(true);
        CollectionExecutionProperties properties = new CollectionExecutionProperties();
        properties.setReusePrefetchedPage(true);

        CollectionExecutionCoordinator coordinator = new CollectionExecutionCoordinator(
                new CollectionTaskPackageBuilder(),
                new CollectionExecutorRegistry(List.of(executor)),
                new cn.bugstack.competitoragent.search.CanonicalUrlResolver(),
                new InternalLinkDiscoveryProperties(),
                properties
        );

        SearchCollectionTarget target = SearchCollectionTarget.builder()
                .candidate(SourceCandidate.builder()
                        .url("https://docs.example.com/open/doc")
                        .title("Open Docs")
                        .sourceType("DOCS")
                        .sourceFamilyKey("official")
                        .sourceUrls(List.of("https://docs.example.com/open/doc"))
                        .build())
                .collectedPage(SourceCollector.CollectedPage.builder()
                        .url("https://docs.example.com/open/doc")
                        .title("Open Docs")
                        .content("Open API 文档 OAuth SDK guide reference")
                        .success(true)
                        .build())
                .build();

        CollectionExecutionReport report = coordinator.execute(41L, "collect_sources_docs", 9L, "Acme AI", List.of(target));

        assertThat(report.getResults()).hasSize(1);
        assertThat(report.getResults().get(0).getQualitySignals()).contains("SEARCH_VERIFICATION_PAGE_REUSED");
        assertThat(report.getResults().get(0).getContent()).contains("Open API 文档");
        assertThat(report.getResults().get(0).getCheckpointSource()).isEqualTo("searchVerification");
        assertThat(report.getResults().get(0).getReusedFromCheckpoint()).isFalse();
        assertThat(report.getStats().getTotalPackageCount()).isEqualTo(1);
        assertThat(report.getStats().getPrefetchedReuseCount()).isEqualTo(1);
        assertThat(report.getStats().getExecutorCallCount()).isEqualTo(0);
        assertThat(report.getStats().getElapsedMillis()).isNotNull();
        verify(executor, never()).execute(any());
    }

    @Test
    void shouldCallExecutorWhenPrefetchedReuseDisabled() {
        CollectionExecutor executor = mock(CollectionExecutor.class);
        when(executor.supports(any())).thenReturn(true);
        when(executor.execute(any())).thenReturn(CollectionExecutionResult.builder()
                .executorType("WEB_PAGE")
                .success(true)
                .status("SUCCESS")
                .resourceLocator("https://docs.example.com/open/doc")
                .content("fresh collection result")
                .sourceUrls(List.of("https://docs.example.com/open/doc"))
                .build());
        CollectionExecutionProperties properties = new CollectionExecutionProperties();
        properties.setReusePrefetchedPage(false);

        CollectionExecutionCoordinator coordinator = new CollectionExecutionCoordinator(
                new CollectionTaskPackageBuilder(),
                new CollectionExecutorRegistry(List.of(executor)),
                new cn.bugstack.competitoragent.search.CanonicalUrlResolver(),
                new InternalLinkDiscoveryProperties(),
                properties
        );

        SearchCollectionTarget target = SearchCollectionTarget.builder()
                .candidate(SourceCandidate.builder()
                        .url("https://docs.example.com/open/doc")
                        .sourceType("DOCS")
                        .sourceFamilyKey("official")
                        .sourceUrls(List.of("https://docs.example.com/open/doc"))
                        .build())
                .collectedPage(SourceCollector.CollectedPage.builder()
                        .url("https://docs.example.com/open/doc")
                        .content("prefetched")
                        .success(true)
                        .build())
                .build();

        CollectionExecutionReport report = coordinator.execute(41L, "collect_sources_docs", 9L, "Acme AI", List.of(target));

        assertThat(report.getResults().get(0).getContent()).isEqualTo("fresh collection result");
        verify(executor, times(1)).execute(any());
    }

    @Test
    void shouldCollectSameDepthTargetsConcurrentlyWithoutChangingResultOrder() {
        CollectionExecutor executor = mock(CollectionExecutor.class);
        when(executor.supports(any())).thenReturn(true);
        when(executor.execute(any())).thenAnswer(invocation -> {
            CollectionTaskPackage taskPackage = invocation.getArgument(0);
            Thread.sleep(200);
            return CollectionExecutionResult.builder()
                    .executorType("WEB_PAGE")
                    .success(true)
                    .status("SUCCESS")
                    .resourceLocator(taskPackage.getResourceLocator())
                    .content("content " + taskPackage.getTargetIndex())
                    .sourceUrls(taskPackage.getSourceUrls())
                    .build();
        });
        CollectionExecutionProperties properties = new CollectionExecutionProperties();
        properties.setConcurrency(3);

        CollectionExecutionCoordinator coordinator = new CollectionExecutionCoordinator(
                new CollectionTaskPackageBuilder(),
                new CollectionExecutorRegistry(List.of(executor)),
                new cn.bugstack.competitoragent.search.CanonicalUrlResolver(),
                new InternalLinkDiscoveryProperties(),
                properties
        );

        List<SearchCollectionTarget> targets = List.of(
                target("https://docs.example.com/a"),
                target("https://docs.example.com/b"),
                target("https://docs.example.com/c")
        );

        long startedAt = System.currentTimeMillis();
        CollectionExecutionReport report = coordinator.execute(41L, "collect_sources_docs", 9L, "Acme AI", targets);
        long elapsedMillis = System.currentTimeMillis() - startedAt;

        assertThat(report.getResults()).extracting(CollectionExecutionResult::getResourceLocator)
                .containsExactly("https://docs.example.com/a", "https://docs.example.com/b", "https://docs.example.com/c");
        assertThat(report.getStats().getConfiguredConcurrency()).isEqualTo(3);
        assertThat(elapsedMillis).isLessThan(550L);
    }

    @Test
    void shouldPrioritizePrefetchedPackagesBeforeSlowWebPackages() {
        CollectionExecutor executor = mock(CollectionExecutor.class);
        when(executor.supports(any())).thenReturn(true);
        List<String> invocationOrder = new CopyOnWriteArrayList<>();
        when(executor.execute(any())).thenAnswer(invocation -> {
            CollectionTaskPackage taskPackage = invocation.getArgument(0);
            invocationOrder.add(taskPackage.getPrimaryTool());
            return CollectionExecutionResult.builder()
                    .executorType(taskPackage.getPrimaryTool())
                    .success(true)
                    .status("SUCCESS")
                    .resourceLocator(taskPackage.getResourceLocator())
                    .sourceUrls(taskPackage.getSourceUrls())
                    .build();
        });

        CollectionExecutionProperties properties = new CollectionExecutionProperties();
        properties.setConcurrency(1);
        properties.setPrioritizePrefetchedPackages(true);

        CollectionExecutionCoordinator coordinator = new CollectionExecutionCoordinator(
                new CollectionTaskPackageBuilder(),
                new CollectionExecutorRegistry(List.of(executor)),
                new cn.bugstack.competitoragent.search.CanonicalUrlResolver(),
                new InternalLinkDiscoveryProperties(),
                properties
        );

        coordinator.execute(1L, "collect_test", null, "抖音", List.of(
                target("https://open.douyin.com/docs/1", true),
                target("https://www.douyin.com/slow-page", false)
        ));

        assertThat(invocationOrder.get(0)).isEqualTo("TAVILY_PREFETCHED");
    }

    private SearchCollectionTarget target(String url) {
        return target(url, false);
    }

    private SearchCollectionTarget target(String url, boolean prefetched) {
        return SearchCollectionTarget.builder()
                .candidate(SourceCandidate.builder()
                        .url(url)
                        .sourceType("DOCS")
                        .sourceFamilyKey("official")
                        .fastLaneUsable(prefetched)
                        .hasPrefetchedContent(prefetched)
                        .prefetchedContentRef(prefetched ? "prefetch-" + Math.abs(url.hashCode()) : null)
                        .sourceUrls(List.of(url))
                        .build())
                .build();
    }

    private Object readAccessor(Object target, String fieldName) {
        if (target == null) {
            return null;
        }
        try {
            String suffix = Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
            try {
                return target.getClass().getMethod("get" + suffix).invoke(target);
            } catch (NoSuchMethodException ignored) {
                try {
                    return target.getClass().getMethod("is" + suffix).invoke(target);
                } catch (NoSuchMethodException ignoredAgain) {
                    java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
                    field.setAccessible(true);
                    return field.get(target);
                }
            }
        } catch (Exception exception) {
            throw new IllegalStateException("read accessor failed: " + target.getClass().getSimpleName() + "." + fieldName, exception);
        }
    }

    private String readStringAccessor(Object target, String fieldName) {
        Object value = readAccessor(target, fieldName);
        return value == null ? null : String.valueOf(value);
    }

    private Integer readIntegerAccessor(Object target, String fieldName) {
        Object value = readAccessor(target, fieldName);
        return value instanceof Number number ? number.intValue() : null;
    }
}
