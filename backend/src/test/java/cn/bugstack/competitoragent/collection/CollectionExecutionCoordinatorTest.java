package cn.bugstack.competitoragent.collection;

import cn.bugstack.competitoragent.search.SearchCollectionTarget;
import cn.bugstack.competitoragent.source.SourceCandidate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
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
