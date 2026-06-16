package cn.bugstack.competitoragent.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class PlaywrightConfigTest {

    @Test
    void shouldBindDefaultDocumentPlaywrightPolicy() throws IOException {
        PlaywrightConfig.PlaywrightProperties properties = bindDefaultDocumentProperties();

        assertThat(properties.getBrowser()).isEqualTo("chromium");
        assertThat(properties.getChannel()).isBlank();
        assertThat(properties.getExecutablePath()).isEqualTo("D:/Aplaywright/chromium-1117/chrome-win/chrome.exe");
        assertThat(properties.isStartupWarmupEnabled()).isFalse();
        assertThat(properties.isHealthCheckWarmupEnabled()).isFalse();
    }

    private PlaywrightConfig.PlaywrightProperties bindDefaultDocumentProperties() throws IOException {
        String applicationYaml = new ClassPathResource("application.yml")
                .getContentAsString(StandardCharsets.UTF_8);
        List<String> documents = List.of(applicationYaml.split("(?m)^---\\s*$"));
        Properties properties = loadYamlProperties(
                new ByteArrayResource(documents.get(0).getBytes(StandardCharsets.UTF_8))
        );

        MutablePropertySources propertySources = new MutablePropertySources();
        propertySources.addFirst(new PropertiesPropertySource("defaultApplicationYaml", properties));
        PropertySourcesPropertyResolver propertyResolver = new PropertySourcesPropertyResolver(propertySources);
        Properties resolvedProperties = new Properties();
        properties.forEach((key, value) -> resolvedProperties.put(
                key,
                value instanceof String text ? propertyResolver.resolvePlaceholders(text) : value
        ));

        MutablePropertySources resolvedPropertySources = new MutablePropertySources();
        resolvedPropertySources.addFirst(new PropertiesPropertySource("resolvedDefaultApplicationYaml", resolvedProperties));
        Binder binder = new Binder(org.springframework.boot.context.properties.source.ConfigurationPropertySources.from(resolvedPropertySources));
        return binder.bind("playwright", Bindable.of(PlaywrightConfig.PlaywrightProperties.class))
                .orElseThrow(() -> new IllegalStateException("playwright properties should exist in default application.yml"));
    }

    private Properties loadYamlProperties(org.springframework.core.io.Resource resource) {
        YamlPropertiesFactoryBean yamlFactory = new YamlPropertiesFactoryBean();
        yamlFactory.setResources(resource);
        return yamlFactory.getObject();
    }
}
