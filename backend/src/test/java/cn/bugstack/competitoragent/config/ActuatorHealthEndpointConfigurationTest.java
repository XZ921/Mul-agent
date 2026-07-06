package cn.bugstack.competitoragent.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.ClassUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class ActuatorHealthEndpointConfigurationTest {

    @Test
    void shouldExposeActuatorHealthEndpointForRuntimeProbes() throws IOException {
        Properties defaultProperties = loadYamlDocumentProperties(0);

        assertThat(ClassUtils.isPresent(
                "org.springframework.boot.actuate.health.HealthEndpoint",
                getClass().getClassLoader()
        )).isTrue();
        assertThat(defaultProperties.getProperty("management.endpoints.web.exposure.include[0]"))
                .isEqualTo("health");
        assertThat(defaultProperties.getProperty("management.endpoints.web.exposure.include[1]"))
                .isEqualTo("info");
        assertThat(defaultProperties.getProperty("management.endpoint.health.probes.enabled"))
                .isEqualTo("true");
    }

    private Properties loadYamlDocumentProperties(int documentIndex) throws IOException {
        String applicationYaml = new ClassPathResource("application.yml")
                .getContentAsString(StandardCharsets.UTF_8);
        List<String> documents = List.of(applicationYaml.split("(?m)^---\\s*$"));
        YamlPropertiesFactoryBean yamlFactory = new YamlPropertiesFactoryBean();
        yamlFactory.setResources(new ByteArrayResource(documents.get(documentIndex).getBytes(StandardCharsets.UTF_8)));
        return yamlFactory.getObject();
    }
}
