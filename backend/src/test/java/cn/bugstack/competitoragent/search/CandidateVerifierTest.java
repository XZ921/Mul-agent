package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.source.SourceCandidate;
import cn.bugstack.competitoragent.source.SourceCollector;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
