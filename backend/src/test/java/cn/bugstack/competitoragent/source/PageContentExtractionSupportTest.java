package cn.bugstack.competitoragent.source;

import cn.bugstack.competitoragent.testsupport.NeverCompletingHttpClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class PageContentExtractionSupportTest {

    @Test
    void shouldFailOpenExternalScriptFetchWhenHttpFutureNeverCompletes() {
        NeverCompletingHttpClient httpClient = new NeverCompletingHttpClient();

        String content = assertTimeoutPreemptively(Duration.ofSeconds(3),
                () -> PageContentExtractionSupport.fetchExternalScript("https://example.com/app.js", httpClient));

        assertThat(content).isEmpty();
        assertThat(httpClient.cancelled()).isTrue();
        assertThat(httpClient.asyncAttemptCount()).isEqualTo(1);
    }
}
