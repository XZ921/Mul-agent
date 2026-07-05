package cn.bugstack.competitoragent.source;

import cn.bugstack.competitoragent.testsupport.NeverCompletingHttpClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class GithubApiClientTest {

    @Test
    void shouldFailFastWhenHttpFutureNeverCompletes() {
        GithubApiProperties properties = new GithubApiProperties();
        properties.setEnabled(true);
        properties.setEndpoint("https://api.github.com");
        properties.setApiToken("test-token");
        properties.setTimeoutSeconds(1);
        properties.setMaxRetries(0);
        NeverCompletingHttpClient httpClient = new NeverCompletingHttpClient();
        GithubApiClient client = new GithubApiClient(properties, new ObjectMapper(), httpClient);

        assertThatThrownBy(() -> assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> client.fetchRepository("openai", "openai-java")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("github api request failed");

        assertThat(httpClient.cancelled()).isTrue();
        assertThat(httpClient.asyncAttemptCount()).isEqualTo(1);
    }
}
