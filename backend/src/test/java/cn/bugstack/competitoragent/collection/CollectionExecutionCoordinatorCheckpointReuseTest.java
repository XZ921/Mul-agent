package cn.bugstack.competitoragent.collection;

import cn.bugstack.competitoragent.search.SearchCollectionTarget;
import cn.bugstack.competitoragent.source.SourceCandidate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 第六轮 Task 1 的包级 checkpoint 复用红灯测试。
 * 测试目标是先锁定“成功包复用、未完成包继续执行”的恢复语义，
 * 防止后续 rerun / resume 仍然只能按整个 collector 节点粗粒度重跑。
 */
class CollectionExecutionCoordinatorCheckpointReuseTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void shouldReuseSuccessfulPackageAndOnlyRerunUnfinishedPackage() throws Exception {
        CollectionExecutor executor = mock(CollectionExecutor.class);
        when(executor.supports(argThat(pkg -> "JINA_READER".equalsIgnoreCase(pkg.getPrimaryTool())))).thenReturn(true);
        when(executor.execute(argThat(pkg -> "collect_sources_docs#002".equals(readStringAccessor(pkg, "packageKey")))))
                .thenReturn(objectMapper.convertValue(Map.of(
                        "executorType", "WEB_PAGE",
                        "success", true,
                        "resourceLocator", "https://docs.example.com/pricing",
                        "sourceUrls", List.of("https://docs.example.com/pricing")
                ), CollectionExecutionResult.class));

        CollectionExecutionCoordinator coordinator = new CollectionExecutionCoordinator(
                new CollectionTaskPackageBuilder(),
                new CollectionExecutorRegistry(List.of(executor))
        );

        SearchCollectionTarget target1 = SearchCollectionTarget.builder()
                .candidate(SourceCandidate.builder()
                        .url("https://docs.example.com/reference")
                        .sourceType("DOCS")
                        .sourceFamilyKey("official")
                        .providerKey("serpapi")
                        .sourceUrls(List.of("https://docs.example.com/reference"))
                        .build())
                .build();
        SearchCollectionTarget target2 = SearchCollectionTarget.builder()
                .candidate(SourceCandidate.builder()
                        .url("https://docs.example.com/pricing")
                        .sourceType("PRICING")
                        .sourceFamilyKey("official")
                        .providerKey("serpapi")
                        .sourceUrls(List.of("https://docs.example.com/pricing"))
                        .build())
                .build();

        Class<?> checkpointClass = loadRequiredClass("cn.bugstack.competitoragent.collection.CollectionAuditSnapshot");
        Method executeMethod = resolveCheckpointAwareExecuteMethod(checkpointClass);
        Object checkpoint = objectMapper.convertValue(Map.of(
                "results", List.of(Map.of(
                        "taskPackageKey", "collect_sources_docs#001",
                        "targetIndex", 1,
                        "executorType", "WEB_PAGE",
                        "success", true,
                        "status", "SUCCESS",
                        "sourceUrls", List.of("https://docs.example.com/reference")
                )),
                "recoveryCheckpoint", "collect_sources_docs#002",
                "sourceUrls", List.of("https://docs.example.com/reference")
        ), checkpointClass);

        Object report = executeMethod.invoke(
                coordinator,
                41L,
                "collect_sources_docs",
                9L,
                "Acme AI",
                List.of(target1, target2),
                checkpoint
        );

        @SuppressWarnings("unchecked")
        List<Object> results = (List<Object>) readAccessor(report, "results");
        assertThat(results).hasSize(2);
        assertThat(readBooleanAccessor(results.get(0), "reusedFromCheckpoint")).isTrue();
        assertThat(readStringAccessor(results.get(0), "taskPackageKey")).isEqualTo("collect_sources_docs#001");

        Object auditSnapshot = readAccessor(report, "auditSnapshot");
        assertThat(readStringAccessor(auditSnapshot, "recoveryCheckpoint")).isEqualTo("collect_sources_docs#002");

        verify(executor, times(1)).execute(argThat(pkg ->
                "collect_sources_docs#002".equals(readStringAccessor(pkg, "packageKey"))));
    }

    @Test
    void shouldReuseSuccessfulCheckpointByStableSourceIdentityWhenTargetOrderChanges() throws Exception {
        CollectionExecutor executor = mock(CollectionExecutor.class);
        when(executor.supports(argThat(pkg -> "JINA_READER".equalsIgnoreCase(pkg.getPrimaryTool())))).thenReturn(true);
        when(executor.execute(argThat(pkg -> "collect_sources_docs#001".equals(readStringAccessor(pkg, "packageKey")))))
                .thenReturn(objectMapper.convertValue(Map.of(
                        "executorType", "WEB_PAGE",
                        "success", true,
                        "resourceLocator", "https://docs.example.com/reference",
                        "sourceUrls", List.of("https://docs.example.com/reference")
                ), CollectionExecutionResult.class));

        CollectionExecutionCoordinator coordinator = new CollectionExecutionCoordinator(
                new CollectionTaskPackageBuilder(),
                new CollectionExecutorRegistry(List.of(executor))
        );

        SearchCollectionTarget docsTarget = SearchCollectionTarget.builder()
                .candidate(SourceCandidate.builder()
                        .url("https://docs.example.com/reference")
                        .sourceType("DOCS")
                        .sourceFamilyKey("official")
                        .providerKey("serpapi")
                        .sourceUrls(List.of("https://docs.example.com/reference"))
                        .build())
                .build();
        SearchCollectionTarget helpTarget = SearchCollectionTarget.builder()
                .candidate(SourceCandidate.builder()
                        .url("https://docs.example.com/help")
                        .sourceType("DOCS")
                        .sourceFamilyKey("official")
                        .providerKey("serpapi")
                        .sourceUrls(List.of("https://docs.example.com/help"))
                        .build())
                .build();

        Class<?> checkpointClass = loadRequiredClass("cn.bugstack.competitoragent.collection.CollectionAuditSnapshot");
        Method executeMethod = resolveCheckpointAwareExecuteMethod(checkpointClass);
        Object checkpoint = objectMapper.convertValue(Map.of(
                "results", List.of(Map.of(
                        "taskPackageKey", "collect_sources_docs#001",
                        "targetIndex", 1,
                        "executorType", "WEB_PAGE",
                        "success", true,
                        "status", "SUCCESS",
                        "resourceLocator", "https://docs.example.com/help",
                        "sourceUrls", List.of("https://docs.example.com/help")
                )),
                "recoveryCheckpoint", "collect_sources_docs#002",
                "sourceUrls", List.of("https://docs.example.com/help")
        ), checkpointClass);

        Object report = executeMethod.invoke(
                coordinator,
                41L,
                "collect_sources_docs",
                9L,
                "Acme AI",
                List.of(docsTarget, helpTarget),
                checkpoint
        );

        @SuppressWarnings("unchecked")
        List<Object> results = (List<Object>) readAccessor(report, "results");
        assertThat(results).hasSize(2);
        assertThat(readStringAccessor(results.get(0), "resourceLocator")).isEqualTo("https://docs.example.com/reference");
        assertThat(readBooleanAccessor(results.get(0), "reusedFromCheckpoint")).isFalse();
        assertThat(readStringAccessor(results.get(1), "resourceLocator")).isEqualTo("https://docs.example.com/help");
        assertThat(readBooleanAccessor(results.get(1), "reusedFromCheckpoint")).isTrue();

        verify(executor, times(1)).execute(argThat(pkg ->
                "collect_sources_docs#001".equals(readStringAccessor(pkg, "packageKey"))));
    }

    private Method resolveCheckpointAwareExecuteMethod(Class<?> checkpointClass) {
        return java.util.Arrays.stream(CollectionExecutionCoordinator.class.getMethods())
                .filter(method -> "execute".equals(method.getName()))
                .filter(method -> method.getParameterCount() == 6)
                .filter(method -> method.getParameterTypes()[5].equals(checkpointClass))
                .findFirst()
                .orElseGet(() -> failMissingMethod(checkpointClass));
    }

    private Method failMissingMethod(Class<?> checkpointClass) {
        fail("CollectionExecutionCoordinator 应声明 execute(Long, String, Long, String, List, %s)".formatted(
                checkpointClass.getSimpleName()));
        return null;
    }

    private Class<?> loadRequiredClass(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException exception) {
            fail("应存在类型 %s，但当前尚未实现: %s".formatted(className, exception.getMessage()));
            return null;
        }
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
                    try {
                        java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
                        field.setAccessible(true);
                        return field.get(target);
                    } catch (NoSuchFieldException fieldMissing) {
                        return null;
                    }
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

    private Boolean readBooleanAccessor(Object target, String fieldName) {
        Object value = readAccessor(target, fieldName);
        return value instanceof Boolean booleanValue ? booleanValue : null;
    }
}
