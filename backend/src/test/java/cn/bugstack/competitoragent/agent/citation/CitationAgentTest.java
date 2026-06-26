package cn.bugstack.competitoragent.agent.citation;

import cn.bugstack.competitoragent.agent.AgentContext;
import cn.bugstack.competitoragent.agent.AgentResult;
import cn.bugstack.competitoragent.context.AgentContextAssembler;
import cn.bugstack.competitoragent.model.entity.EvidenceSource;
import cn.bugstack.competitoragent.repository.AgentExecutionLogRepository;
import cn.bugstack.competitoragent.repository.EvidenceSourceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CitationAgentTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgentExecutionLogRepository logRepository = mock(AgentExecutionLogRepository.class);
    private final AgentContextAssembler agentContextAssembler = mock(AgentContextAssembler.class);
    private final EvidenceSourceRepository evidenceSourceRepository = mock(EvidenceSourceRepository.class);
    private final CitationClaimExtractor claimExtractor = new CitationClaimExtractor();
    private final CitationSourceTrustPolicy trustPolicy = new CitationSourceTrustPolicy();
    private final CitationAgent agent = new CitationAgent(
            logRepository,
            agentContextAssembler,
            evidenceSourceRepository,
            objectMapper,
            claimExtractor,
            trustPolicy
    );

    @Test
    void shouldPassWhenClaimsHaveKnownEvidenceAndTrustedSources() throws Exception {
        when(evidenceSourceRepository.findByTaskIdOrderByEvidenceIdAsc(1L)).thenReturn(List.of(
                EvidenceSource.builder()
                        .taskId(1L)
                        .competitorName("Notion AI")
                        .evidenceId("E001")
                        .title("Pricing")
                        .url("https://www.notion.so/pricing")
                        .sourceDomain("www.notion.so")
                        .sourceType("PRICING")
                        .sourceCategory("OFFICIAL")
                        .sourceScore(0.91)
                        .contentSnippet("Notion pricing plans include Plus, Business and Enterprise.")
                        .build()
        ));

        AgentContext context = AgentContext.builder()
                .taskId(1L)
                .taskName("citation-test")
                .currentNodeName("citation_check")
                .currentNodeConfig("{\"sourceNode\":\"write_report\",\"minCoverageRate\":0.85}")
                .build();
        context.putSharedOutput("write_report", """
                {
                  "content": "## 定价策略\\nNotion AI 采用按席位计费。[证据：E001]",
                  "sourceUrls": ["https://www.notion.so/pricing"]
                }
                """);

        AgentResult result = agent.execute(context);
        JsonNode output = objectMapper.readTree(result.getOutputData());

        assertEquals("SUCCESS", result.getStatus().name());
        assertEquals("NONE", output.path("citationRiskSeverity").asText());
        assertEquals("FULL_SOURCE", output.path("citationEvidenceState").asText());
        assertTrue(output.path("citationIssues").isArray());
        assertEquals(0, output.path("citationIssues").size());
    }

    @Test
    void shouldEmitMissingCitationIssueWhenSensitiveClaimHasNoCitation() throws Exception {
        when(evidenceSourceRepository.findByTaskIdOrderByEvidenceIdAsc(2L)).thenReturn(List.of());

        AgentContext context = AgentContext.builder()
                .taskId(2L)
                .taskName("citation-test")
                .currentNodeName("citation_check")
                .currentNodeConfig("{\"sourceNode\":\"write_report\",\"minCoverageRate\":0.85}")
                .build();
        context.putSharedOutput("write_report", """
                {
                  "content": "## 行动建议\\n建议优先学习 Notion AI 的企业权限设计。"
                }
                """);

        AgentResult result = agent.execute(context);
        JsonNode output = objectMapper.readTree(result.getOutputData());

        assertEquals("SUCCESS", result.getStatus().name());
        assertEquals("ERROR", output.path("citationRiskSeverity").asText());
        assertEquals("MISSING_SOURCE", output.path("citationEvidenceState").asText());
        assertEquals("MISSING_CITATION", output.path("citationIssues").get(0).path("issueType").asText());
    }

    @Test
    void shouldEmitUnknownEvidenceIssueWhenEvidenceIdDoesNotExist() throws Exception {
        when(evidenceSourceRepository.findByTaskIdOrderByEvidenceIdAsc(3L)).thenReturn(List.of());

        AgentContext context = AgentContext.builder()
                .taskId(3L)
                .taskName("citation-test")
                .currentNodeName("citation_check")
                .currentNodeConfig("{\"sourceNode\":\"write_report\",\"minCoverageRate\":0.85}")
                .build();
        context.putSharedOutput("write_report", """
                {
                  "content": "## 结论\\nNotion AI 在企业知识管理场景中更适合作为统一工作台。[证据：E999]"
                }
                """);

        AgentResult result = agent.execute(context);
        JsonNode output = objectMapper.readTree(result.getOutputData());

        assertEquals("SUCCESS", result.getStatus().name());
        assertEquals("UNKNOWN_EVIDENCE_ID", output.path("citationIssues").get(0).path("issueType").asText());
        assertEquals("MISSING_SOURCE", output.path("citationIssues").get(0).path("evidenceState").asText());
    }
}
