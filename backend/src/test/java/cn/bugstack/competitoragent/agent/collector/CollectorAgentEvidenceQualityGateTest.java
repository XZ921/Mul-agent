package cn.bugstack.competitoragent.agent.collector;

import cn.bugstack.competitoragent.collection.CollectionExecutionResult;
import cn.bugstack.competitoragent.collection.quality.EvidenceQualityGate;
import cn.bugstack.competitoragent.collection.quality.EvidenceQualityGateProperties;
import cn.bugstack.competitoragent.search.EvidenceRepairState;
import cn.bugstack.competitoragent.source.SourceCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CollectorAgentEvidenceQualityGateTest {

    @Test
    void shouldAttachEvidenceQualityVerdictToCollectionResult() {
        EvidenceQualityGate gate = new EvidenceQualityGate(new EvidenceQualityGateProperties());
        CollectorNodeConfig config = CollectorNodeConfig.builder()
                .requiredCoverageFields(List.of("coreFeatures"))
                .blockingCoverageFields(List.of("coreFeatures"))
                .coverageQueryIntents(List.of("OFFICIAL_DOCS", "API_DOCS"))
                .build();
        SourceCandidate candidate = SourceCandidate.builder()
                .url("https://open.bilibili.com")
                .sourceType("OFFICIAL")
                .totalScore(0.92D)
                .build();
        CollectionExecutionResult result = CollectionExecutionResult.builder()
                .executorType("WEB_SCRAPER")
                .success(true)
                .status("SUCCESS")
                .resourceLocator("https://open.bilibili.com")
                .content("开放平台 登录 注册 智能验证检测中 由极验提供技术支持")
                .qualitySignals(List.of("OFFICIAL_DOMAIN_MATCHED"))
                .qualityScore(0.70D)
                .build();

        CollectionExecutionResult gated = CollectorAgent.applyEvidenceQualityGateForTest(gate, config, candidate, result);

        assertThat(gated.getEvidenceQualityVerdict()).isNotNull();
        assertThat(gated.getQualitySignals()).contains("AUTH_GATE_DETECTED", "SCORE_CONTRADICTION_DETECTED");
        assertThat(gated.getQualityScore()).isLessThanOrEqualTo(0.20D);
    }

    @Test
    void shouldAttachRepairPlanMetadataWhenWeakEvidenceRequiresRepair() {
        EvidenceQualityGate gate = new EvidenceQualityGate(new EvidenceQualityGateProperties());
        CollectorNodeConfig config = CollectorNodeConfig.builder()
                .competitorName("哔哩哔哩")
                .requiredCoverageFields(List.of("coreFeatures"))
                .blockingCoverageFields(List.of("coreFeatures"))
                .coverageQueryIntents(List.of("API_DOCS", "SDK_GUIDE"))
                .recoveryFieldName("coreFeatures")
                .recoveryEvidencePathKey("DOCS_API_GUIDE")
                .recoveryQueryIntents(List.of("API_DOCS", "SDK_GUIDE"))
                .build();
        SourceCandidate candidate = SourceCandidate.builder()
                .url("https://open.bilibili.com")
                .sourceType("DOCS")
                .sourceUrls(List.of("https://open.bilibili.com"))
                .totalScore(0.92D)
                .build();
        CollectionExecutionResult result = CollectionExecutionResult.builder()
                .executorType("WEB_SCRAPER")
                .success(true)
                .status("SUCCESS")
                .resourceLocator("https://open.bilibili.com")
                .content("开放平台 登录 注册 智能验证检测中 由极验提供技术支持")
                .sourceUrls(List.of("https://open.bilibili.com"))
                .qualitySignals(List.of("OFFICIAL_DOMAIN_MATCHED"))
                .qualityScore(0.70D)
                .build();

        CollectionExecutionResult gated = CollectorAgent.applyEvidenceQualityGateForTest(gate, config, candidate, result);

        assertThat(gated.getEvidenceRepairPlan()).isNotNull();
        assertThat(gated.getEvidenceRepairPlan().getState()).isEqualTo(EvidenceRepairState.REPAIR_QUERY_PROPOSED);
        assertThat(gated.getEvidenceRepairPlan().getRepairQueries())
                .contains("https://open.bilibili.com/docs", "https://open.bilibili.com/api");
        assertThat(gated.getPublicEvidenceRecoveryFieldName()).isEqualTo("coreFeatures");
        assertThat(gated.getPublicEvidenceRecoveryEvidencePathKey()).isEqualTo("DOCS_API_GUIDE");
        assertThat(gated.getPublicEvidenceRecoveryQueryIntents()).containsExactly("API_DOCS", "SDK_GUIDE");
        assertThat(gated.getQualitySignals()).contains("REPAIR_QUERY_PROPOSED");
    }
}
