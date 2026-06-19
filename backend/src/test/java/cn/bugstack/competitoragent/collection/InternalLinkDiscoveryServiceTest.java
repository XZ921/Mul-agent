package cn.bugstack.competitoragent.collection;

import cn.bugstack.competitoragent.search.CanonicalUrlResolver;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 内部链接发现服务红灯测试。
 * 这里先锁定 same-domain 高价值链接抽取、相对地址解析、外链过滤和最大深度降级，
 * 防止后续实现只把链接“扫出来”，却没有落到可执行的 SourceCandidate 契约上。
 */
class InternalLinkDiscoveryServiceTest {

    @Test
    void shouldExtractSameDomainHighValueLinksFromMarkdownAndHtmlContent() throws Exception {
        Object service = newService(2);
        CollectionTaskPackage sourcePackage = CollectionTaskPackage.builder()
                .competitorName("Acme AI")
                .sourceFamilyKey("official")
                .sourceType("DOCS")
                .url("https://docs.example.com/open/doc")
                .resourceLocator("https://docs.example.com/open/doc")
                .sourceUrls(List.of("https://docs.example.com/open/doc"))
                .build();
        CollectionExecutionResult result = CollectionExecutionResult.builder()
                .success(true)
                .status("SUCCESS")
                .resourceLocator("https://docs.example.com/open/doc")
                .content("""
                        [账户授权](/open/doc/auth)
                        [外部帮助](https://outside.example.net/help)
                        <a href="https://docs.example.com/sdk/android">Android SDK</a>
                        <a href="/pricing">Pricing</a>
                        """)
                .sourceUrls(List.of("https://docs.example.com/open/doc"))
                .build()
                .normalize();

        @SuppressWarnings("unchecked")
        List<Object> discoveredCandidates = (List<Object>) invokeDiscover(service, sourcePackage, result, 0);

        assertThat(discoveredCandidates).hasSize(2);
        assertThat(discoveredCandidates)
                .extracting(candidate -> readStringAccessor(candidate, "url"))
                .containsExactly(
                        "https://docs.example.com/open/doc/auth",
                        "https://docs.example.com/sdk/android"
                );
        assertThat(discoveredCandidates)
                .extracting(candidate -> readStringAccessor(candidate, "discoveryMethod"))
                .containsOnly("INTERNAL_LINK_DISCOVERY");
        assertThat(readListAccessor(discoveredCandidates.get(0), "sourceUrls"))
                .contains("https://docs.example.com/open/doc", "https://docs.example.com/open/doc/auth");
    }

    @Test
    void shouldPreferApiAuthAndSdkLinksOverContactUtilityDocs() throws Exception {
        Object service = newService(2);
        CollectionTaskPackage sourcePackage = CollectionTaskPackage.builder()
                .competitorName("哔哩哔哩")
                .sourceFamilyKey("official")
                .sourceType("DOCS")
                .url("https://open.bilibili.com/doc")
                .resourceLocator("https://open.bilibili.com/doc")
                .sourceUrls(List.of("https://open.bilibili.com/doc"))
                .build();
        CollectionExecutionResult result = CollectionExecutionResult.builder()
                .success(true)
                .status("SUCCESS")
                .resourceLocator("https://open.bilibili.com/doc")
                .content("""
                        [联系我们](https://open.bilibili.com/doc/4/contact-page)
                        [账号授权](https://open.bilibili.com/doc/4/auth-page)
                        [OPEN API](https://open.bilibili.com/doc/4/open-api-page)
                        [Android SDK](https://open.bilibili.com/doc/4/android-sdk-page)
                        """)
                .sourceUrls(List.of("https://open.bilibili.com/doc"))
                .build()
                .normalize();

        @SuppressWarnings("unchecked")
        List<Object> discoveredCandidates = (List<Object>) invokeDiscover(service, sourcePackage, result, 0);

        assertThat(discoveredCandidates)
                .extracting(candidate -> readStringAccessor(candidate, "url"))
                .containsExactly(
                        "https://open.bilibili.com/doc/4/open-api-page",
                        "https://open.bilibili.com/doc/4/auth-page",
                        "https://open.bilibili.com/doc/4/android-sdk-page"
                )
                .doesNotContain("https://open.bilibili.com/doc/4/contact-page");
    }

    @Test
    void shouldSkipDiscoveryWhenCurrentDepthAlreadyReachedConfiguredLimit() throws Exception {
        Object service = newService(1);
        CollectionTaskPackage sourcePackage = CollectionTaskPackage.builder()
                .competitorName("Acme AI")
                .sourceFamilyKey("official")
                .sourceType("DOCS")
                .url("https://docs.example.com/open/doc")
                .resourceLocator("https://docs.example.com/open/doc")
                .sourceUrls(List.of("https://docs.example.com/open/doc"))
                .build();
        CollectionExecutionResult result = CollectionExecutionResult.builder()
                .success(true)
                .status("SUCCESS")
                .resourceLocator("https://docs.example.com/open/doc")
                .content("[账户授权](/open/doc/auth)")
                .sourceUrls(List.of("https://docs.example.com/open/doc"))
                .build()
                .normalize();

        @SuppressWarnings("unchecked")
        List<Object> discoveredCandidates = (List<Object>) invokeDiscover(service, sourcePackage, result, 1);

        assertThat(discoveredCandidates).isEmpty();
    }

    private Object newService(int maxDepth) throws Exception {
        Class<?> propertiesClass = loadRequiredClass(
                "cn.bugstack.competitoragent.collection.InternalLinkDiscoveryProperties");
        Object properties = propertiesClass.getDeclaredConstructor().newInstance();
        propertiesClass.getMethod("setEnabled", boolean.class).invoke(properties, true);
        propertiesClass.getMethod("setMaxDepth", int.class).invoke(properties, maxDepth);
        propertiesClass.getMethod("setMaxLinksPerEntry", int.class).invoke(properties, 10);
        propertiesClass.getMethod("setMaxLinksPerNode", int.class).invoke(properties, 30);

        Class<?> serviceClass = loadRequiredClass(
                "cn.bugstack.competitoragent.collection.InternalLinkDiscoveryService");
        return serviceClass
                .getConstructor(propertiesClass, CanonicalUrlResolver.class)
                .newInstance(properties, new CanonicalUrlResolver());
    }

    private Object invokeDiscover(Object service,
                                  CollectionTaskPackage sourcePackage,
                                  CollectionExecutionResult result,
                                  int depth) throws Exception {
        Method method = service.getClass().getMethod(
                "discover",
                CollectionTaskPackage.class,
                CollectionExecutionResult.class,
                int.class
        );
        return method.invoke(service, sourcePackage, result, depth);
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
                java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("read accessor failed: " + target.getClass().getSimpleName() + "." + fieldName, exception);
        }
    }

    private String readStringAccessor(Object target, String fieldName) {
        Object value = readAccessor(target, fieldName);
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private List<String> readListAccessor(Object target, String fieldName) {
        Object value = readAccessor(target, fieldName);
        return value instanceof List<?> list ? (List<String>) list : List.of();
    }
}
