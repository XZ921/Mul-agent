package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.testsupport.NeverCompletingHttpClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class DomainVerificationClientTest {

    @Test
    void shouldReturnFalseWhenHttpFutureNeverCompletes() {
        DomainDiscoveryProperties properties = new DomainDiscoveryProperties();
        properties.setVerificationTimeoutMillis(1000);
        properties.setMaxRetries(1);
        NeverCompletingHttpClient httpClient = new NeverCompletingHttpClient();
        DomainVerificationClient client = new DomainVerificationClient(properties, httpClient);

        boolean reachable = assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> client.isReachable("https://example.com"));

        assertThat(reachable).isFalse();
        assertThat(httpClient.cancelled()).isTrue();
        assertThat(httpClient.asyncAttemptCount()).isEqualTo(1);
    }
}
