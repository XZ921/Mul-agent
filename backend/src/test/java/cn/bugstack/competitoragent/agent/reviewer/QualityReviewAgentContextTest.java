package cn.bugstack.competitoragent.agent.reviewer;

import cn.bugstack.competitoragent.context.AgentContextAssembler;
import cn.bugstack.competitoragent.llm.LlmClient;
import cn.bugstack.competitoragent.llm.PromptTemplateService;
import cn.bugstack.competitoragent.memory.MemoryWritebackService;
import cn.bugstack.competitoragent.repository.AgentExecutionLogRepository;
import cn.bugstack.competitoragent.repository.CompetitorKnowledgeRepository;
import cn.bugstack.competitoragent.repository.EvidenceSourceRepository;
import cn.bugstack.competitoragent.repository.ReportRepository;
import cn.bugstack.competitoragent.workflow.coverage.CoverageContractProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 回归锁定 Reviewer 在 Spring 容器中的构造注入行为。
 * live 启动链路已经证明：如果这里仍然走不到正式构造器，整个应用会在 Bean 装配阶段失败，
 * 因此必须用最小上下文测试把问题固定住。
 */
class QualityReviewAgentContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void shouldCreateQualityReviewAgentBeanWhenDependenciesExist() {
        contextRunner.run(context -> {
            assertThat(context.getStartupFailure()).isNull();
            assertThat(context).hasSingleBean(QualityReviewAgent.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import(QualityReviewAgent.class)
    static class TestConfiguration {

        @Bean
        AgentExecutionLogRepository agentExecutionLogRepository() {
            return mock(AgentExecutionLogRepository.class);
        }

        @Bean
        ReportRepository reportRepository() {
            return mock(ReportRepository.class);
        }

        @Bean
        EvidenceSourceRepository evidenceSourceRepository() {
            return mock(EvidenceSourceRepository.class);
        }

        @Bean
        CompetitorKnowledgeRepository competitorKnowledgeRepository() {
            return mock(CompetitorKnowledgeRepository.class);
        }

        @Bean
        LlmClient llmClient() {
            return mock(LlmClient.class);
        }

        @Bean
        PromptTemplateService promptTemplateService() {
            return mock(PromptTemplateService.class);
        }

        @Bean
        AgentContextAssembler agentContextAssembler() {
            return mock(AgentContextAssembler.class);
        }

        @Bean
        MemoryWritebackService memoryWritebackService() {
            return mock(MemoryWritebackService.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        CoverageContractProvider coverageContractProvider() {
            return mock(CoverageContractProvider.class);
        }
    }
}
