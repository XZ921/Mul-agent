package cn.bugstack.competitoragent.agent.citation;

import cn.bugstack.competitoragent.agent.AgentContext;
import cn.bugstack.competitoragent.agent.AgentResult;
import cn.bugstack.competitoragent.context.AgentContextAssembler;
import cn.bugstack.competitoragent.repository.AgentExecutionLogRepository;
import cn.bugstack.competitoragent.repository.EvidenceSourceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CitationAgentRepairabilityTest {

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
    void shouldKeepWriterSourceUrlsWhenCitationIssuesAreStillRepairable() throws Exception {
        when(evidenceSourceRepository.findByTaskIdOrderByEvidenceIdAsc(4L)).thenReturn(List.of());

        AgentContext context = AgentContext.builder()
                .taskId(4L)
                .taskName("citation-repair-test")
                .currentNodeName("citation_check")
                .currentNodeConfig("{\"sourceNode\":\"write_report\",\"minCoverageRate\":0.85}")
                .build();
        context.putSharedOutput("write_report", """
                {
                  "content": "## 方法论与数据完整性声明\\n本次分析主要基于 `PARTIAL_SOURCE` 的官方公开资料 [证据：ANALYZE]。\\n## 策略性建议\\n*   **推测，当前公开资料未能验证，需补充证据。** 可能存在审核机制复杂、竞争激烈导致流量成本上升、规则变动快等通用平台风险。",
                  "sourceUrls": ["https://open.douyin.com", "https://open.douyin.com/docs"],
                  "writerEvidenceState": "PARTIAL_SOURCE",
                  "sectionCitationGaps": [{
                    "targetSection": "strategy",
                    "summary": "策略性建议需要结合现有官网来源收紧表述",
                    "severity": "HIGH",
                    "sourceUrls": ["https://open.douyin.com/docs"],
                    "evidenceState": "PARTIAL_SOURCE"
                  }]
                }
                """);

        AgentResult result = agent.execute(context);
        JsonNode output = objectMapper.readTree(result.getOutputData());

        assertThat(result.getStatus().name()).isEqualTo("SUCCESS");
        assertThat(output.path("citationEvidenceState").asText()).isEqualTo("PARTIAL_SOURCE");
        assertThat(output.path("citationIssues")).isNotEmpty();
        assertThat(output.path("citationIssues").get(0).path("sourceUrls")).isNotEmpty();
        assertThat(output.path("citationIssues").get(0).path("evidenceState").asText()).isEqualTo("PARTIAL_SOURCE");
    }
}
