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
                  "content": "## 核心功能\\nNotion AI 的核心能力应该覆盖企业级工作区 AI 协作。"
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
                  "content": "## 核心功能\\nNotion AI 提供统一工作台能力。[证据：E999]"
                }
                """);

        AgentResult result = agent.execute(context);
        JsonNode output = objectMapper.readTree(result.getOutputData());

        assertEquals("SUCCESS", result.getStatus().name());
        assertEquals("UNKNOWN_EVIDENCE_ID", output.path("citationIssues").get(0).path("issueType").asText());
        assertEquals("MISSING_SOURCE", output.path("citationIssues").get(0).path("evidenceState").asText());
    }

    @Test
    void shouldExcludeOptionalAndGeneratedClaimsFromInternalDeliveryCoverageRate() throws Exception {
        when(evidenceSourceRepository.findByTaskIdOrderByEvidenceIdAsc(4L)).thenReturn(List.of(
                EvidenceSource.builder()
                        .taskId(4L)
                        .competitorName("Notion AI")
                        .evidenceId("E001")
                        .title("Product Overview")
                        .url("https://www.notion.so/product/ai")
                        .sourceDomain("www.notion.so")
                        .sourceType("OFFICIAL")
                        .sourceCategory("OFFICIAL")
                        .sourceScore(0.93)
                        .contentSnippet("Notion AI provides workspace intelligence features.")
                        .build()
        ));

        AgentContext context = AgentContext.builder()
                .taskId(4L)
                .taskName("citation-stage1-delivery-scope")
                .analysisDimensions("[\"产品概述\",\"市场定位\",\"目标用户\",\"核心功能\",\"价格策略\"]")
                .currentNodeName("citation_check")
                .currentNodeConfig("{\"sourceNode\":\"write_report\",\"minCoverageRate\":0.85}")
                .build();
        context.putSharedOutput("write_report", """
                {
                  "content": "## 核心功能\\nNotion AI 提供统一工作台能力。[证据：E001]\\n\\n## 定价策略\\nNotion AI 企业版适合大团队采购。[证据：E999]\\n\\n## 报告结论\\n建议优先评估 Notion AI 作为统一工作台。",
                  "sourceUrls": ["https://www.notion.so/product/ai", "https://www.notion.so/pricing"]
                }
                """);

        AgentResult result = agent.execute(context);
        JsonNode output = objectMapper.readTree(result.getOutputData());

        assertEquals("SUCCESS", result.getStatus().name());
        assertTrue(output.path("citationCoverageRate").asDouble() < 1.0d, result.getOutputData());
        assertTrue(output.path("citationCoverageRate").asDouble() > 0.0d, result.getOutputData());
        assertTrue(!"ERROR".equals(output.path("citationRiskSeverity").asText()), result.getOutputData());
        assertTrue(!"MISSING_SOURCE".equals(output.path("citationEvidenceState").asText()), result.getOutputData());
        assertTrue(output.path("issueFlags").toString().contains("STAGE1_DELIVERY_CITATION_READY"), result.getOutputData());
        assertTrue(output.path("issueFlags").toString().contains("OPTIONAL_CITATION_GAP"), result.getOutputData());
        assertTrue(output.path("issueFlags").toString().contains("GENERATED_SECTION_REWRITE_ONLY"), result.getOutputData());
    }
}
