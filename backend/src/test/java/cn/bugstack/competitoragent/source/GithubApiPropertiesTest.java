package cn.bugstack.competitoragent.source;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GithubApiPropertiesTest {

    @Test
    void shouldTreatEnabledGithubApiWithoutTokenAsNotReady() {
        GithubApiProperties properties = new GithubApiProperties();
        properties.setEnabled(true);
        properties.setEndpoint("https://api.github.com");
        properties.setApiToken("");

        assertThat(properties.isConfigured()).isFalse();
        assertThat(properties.isReady()).isFalse();
        assertThat(properties.resolveReadinessFailureMessage()).isEqualTo("github api token missing");
    }

    @Test
    void shouldTreatEnabledGithubApiWithEndpointAndTokenAsReady() {
        GithubApiProperties properties = new GithubApiProperties();
        properties.setEnabled(true);
        properties.setEndpoint("https://api.github.com");
        properties.setApiToken("test-token");

        assertThat(properties.isConfigured()).isTrue();
        assertThat(properties.isReady()).isTrue();
        assertThat(properties.resolveReadinessFailureMessage()).isNull();
    }
}
