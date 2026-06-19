package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.source.SourceCandidate;
import cn.bugstack.competitoragent.source.SourceCollector;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CandidateVerifierTest {

    private final SourceCollector sourceCollector = mock(SourceCollector.class);
    private final CandidateVerifier candidateVerifier = new CandidateVerifier(sourceCollector);

    @Test
    void shouldVerifyDuplicateCandidateUrlOnlyOnce() {
        when(sourceCollector.collect("https://docs.example.com/guide", "Notion AI", "DOCS"))
                .thenReturn(SourceCollector.CollectedPage.builder()
                        .url("https://docs.example.com/guide")
                        .title("Guide")
                        .content("documentation api reference guide")
                        .snippet("documentation")
                        .competitorName("Notion AI")
                        .sourceType("DOCS")
                        .success(true)
                        .build());

        CandidateVerificationResult result = candidateVerifier.verify(
                "Notion AI",
                "DOCS",
                List.of(
                        SourceCandidate.builder()
                                .url("https://docs.example.com/guide")
                                .title("Guide")
                                .sourceType("DOCS")
                                .domain("docs.example.com")
                                .build(),
                        SourceCandidate.builder()
                                .url("https://docs.example.com/guide/")
                                .title("Guide Duplicate")
                                .sourceType("DOCS")
                                .domain("docs.example.com")
                                .build()
                )
        );

        assertEquals(1, result.getAttemptedTargets().size());
        assertEquals(1, result.getUpdatedCandidates().size());
        assertEquals(1, result.getVerifiedTargets().size());
        verify(sourceCollector, times(1)).collect("https://docs.example.com/guide", "Notion AI", "DOCS");
    }

    @Test
    void shouldFallbackToOriginalSourceUrlWhenCanonicalCandidateUrlHasNoUsablePage() {
        when(sourceCollector.collect("https://acme.ai/docs", "Acme AI", "DOCS"))
                .thenReturn(null);
        when(sourceCollector.collect("https://www.acme.ai/docs", "Acme AI", "DOCS"))
                .thenReturn(SourceCollector.CollectedPage.builder()
                        .url("https://www.acme.ai/docs")
                        .title("Acme Docs")
                        .content("documentation api reference guide")
                        .snippet("documentation")
                        .competitorName("Acme AI")
                        .sourceType("DOCS")
                        .success(true)
                        .build());

        CandidateVerificationResult result = candidateVerifier.verify(
                "Acme AI",
                "DOCS",
                List.of(SourceCandidate.builder()
                        .url("https://acme.ai/docs")
                        .title("Acme Docs")
                        .sourceType("DOCS")
                        .domain("acme.ai")
                        .sourceUrls(List.of("https://www.acme.ai/docs"))
                        .build())
        );

        assertEquals(1, result.getAttemptedTargets().size());
        assertEquals(1, result.getVerifiedTargets().size());
        assertTrue(Boolean.TRUE.equals(result.getUpdatedCandidates().get(0).getVerified()));
        verify(sourceCollector, times(3)).collect("https://acme.ai/docs", "Acme AI", "DOCS");
        verify(sourceCollector, times(1)).collect("https://www.acme.ai/docs", "Acme AI", "DOCS");
    }

    @Test
    void shouldRejectSearchEngineCertificationMediatorPageForOfficialCandidate() {
        when(sourceCollector.collect("https://aiqicha.baidu.com/feedback/official", "哔哩哔哩", "OFFICIAL"))
                .thenReturn(SourceCollector.CollectedPage.builder()
                        .url("https://aiqicha.baidu.com/feedback/official")
                        .title("哔哩哔哩 (゜-゜)つロ 干杯~-bilibili")
                        .content("官网认证是百度对网站展示官方标识的增值服务认证。产品介绍 官网认证可以帮助网民识别官方站点。")
                        .snippet("官网认证 产品介绍")
                        .competitorName("哔哩哔哩")
                        .sourceType("OFFICIAL")
                        .success(true)
                        .build());

        CandidateVerificationResult result = candidateVerifier.verify(
                "哔哩哔哩",
                "OFFICIAL",
                List.of(SourceCandidate.builder()
                        .url("https://aiqicha.baidu.com/feedback/official")
                        .title("哔哩哔哩 (゜-゜)つロ 干杯~-bilibili")
                        .sourceType("OFFICIAL")
                        .domain("aiqicha.baidu.com")
                        .build())
        );

        assertEquals(1, result.getAttemptedTargets().size());
        assertEquals(1, result.getUpdatedCandidates().size());
        assertEquals(0, result.getVerifiedTargets().size());
        assertTrue(Boolean.FALSE.equals(result.getUpdatedCandidates().get(0).getVerified()));
    }
}
