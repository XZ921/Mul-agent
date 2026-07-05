package cn.bugstack.competitoragent.common.http;

import cn.bugstack.competitoragent.testsupport.NeverCompletingHttpClient;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HardTimeoutHttpClientTest {

    @Test
    void shouldCancelFutureWhenResponseNeverCompletes() {
        NeverCompletingHttpClient httpClient = new NeverCompletingHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://example.com"))
                .timeout(Duration.ofMillis(100))
                .GET()
                .build();

        assertThatThrownBy(() -> HardTimeoutHttpClient.send(
                httpClient,
                request,
                HttpResponse.BodyHandlers.ofString(),
                Duration.ofMillis(100)
        )).isInstanceOf(TimeoutException.class);

        assertThat(httpClient.asyncAttemptCount()).isEqualTo(1);
        assertThat(httpClient.cancelled()).isTrue();
    }

    @Test
    void shouldAddBoundedGraceToProtocolTimeout() {
        assertThat(HardTimeoutHttpClient.resolveHardTimeout(Duration.ofMillis(100))).isEqualTo(Duration.ofMillis(200));
        assertThat(HardTimeoutHttpClient.resolveHardTimeout(Duration.ofSeconds(30))).isEqualTo(Duration.ofSeconds(32));
    }

    @Test
    void shouldPreserveInterruptFlagWhenCallerThreadIsInterrupted() {
        NeverCompletingHttpClient httpClient = new NeverCompletingHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://example.com"))
                .GET()
                .build();

        try {
            Thread.currentThread().interrupt();

            assertThatThrownBy(() -> HardTimeoutHttpClient.send(
                    httpClient,
                    request,
                    HttpResponse.BodyHandlers.ofString(),
                    Duration.ofMillis(100)
            )).isInstanceOf(InterruptedException.class);

            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(httpClient.cancelled()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
