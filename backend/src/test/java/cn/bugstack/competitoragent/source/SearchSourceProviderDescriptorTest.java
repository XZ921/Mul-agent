package cn.bugstack.competitoragent.source;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearchSourceProviderDescriptorTest {

    @Test
    void shouldUseDescriptorDefaultsWhenProviderRouteConfigMissing() {
        SearchProviderProperties properties = new SearchProviderProperties();
        SearchSourceProviderDescriptor descriptor = SearchSourceProviderDescriptor.builder()
                .providerKey("qianfan")
                .displayName("千帆搜索")
                .capabilities(List.of("WEB_SEARCH", "CHINESE_RESULTS"))
                .defaultEnabled(true)
                .defaultFailOpen(true)
                .build();

        assertThat(descriptor.isEnabled(properties)).isTrue();
        assertThat(descriptor.isFailOpen(properties)).isTrue();
        assertThat(descriptor.getCapabilities()).containsExactly("WEB_SEARCH", "CHINESE_RESULTS");
    }

    @Test
    void shouldRespectExplicitProviderRouteOverrides() {
        SearchProviderProperties properties = new SearchProviderProperties();
        properties.getProviders().put("serpapi", SearchProviderProperties.ProviderRouteProperties.builder()
                .enabled(false)
                .failOpen(false)
                .build());
        SearchSourceProviderDescriptor descriptor = SearchSourceProviderDescriptor.builder()
                .providerKey("serpapi")
                .displayName("SerpApi")
                .capabilities(List.of("WEB_SEARCH"))
                .defaultEnabled(true)
                .defaultFailOpen(true)
                .build();

        assertThat(descriptor.isEnabled(properties)).isFalse();
        assertThat(descriptor.isFailOpen(properties)).isFalse();
    }
}
