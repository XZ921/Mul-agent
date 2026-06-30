package cn.bugstack.competitoragent.integration;

import cn.bugstack.competitoragent.workflow.coverage.AnalysisDimensionMappingCatalog;
import cn.bugstack.competitoragent.workflow.coverage.CoverageBlockingLevel;
import cn.bugstack.competitoragent.workflow.coverage.CoverageContract;
import cn.bugstack.competitoragent.workflow.coverage.CoverageContractResolver;
import cn.bugstack.competitoragent.workflow.coverage.CoverageEvidencePath;
import cn.bugstack.competitoragent.workflow.coverage.DimensionEvidencePlan;
import cn.bugstack.competitoragent.workflow.coverage.DimensionEvidencePlanFactory;
import cn.bugstack.competitoragent.workflow.coverage.FieldEvidenceQuery;
import cn.bugstack.competitoragent.workflow.coverage.FieldEvidenceQueryPlanner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class Task66FieldFirstEvidenceLoopSystemTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final CoverageContractResolver coverageContractResolver =
            new CoverageContractResolver(new AnalysisDimensionMappingCatalog());
    private final DimensionEvidencePlanFactory dimensionEvidencePlanFactory =
            new DimensionEvidencePlanFactory(new FieldEvidenceQueryPlanner());

    @Test
    void capabilityIntroSampleShouldCollectFieldFirstWithoutPricingWeaknessBlockers() throws Exception {
        Task66Request request = readRequest("task66/field-first-capability-intro-request.json");
        CoverageContract contract = coverageContractResolver.resolve(
                request.reportTemplate(),
                request.analysisDimensions(),
                request.sourceScope(),
                null);
        DimensionEvidencePlan plan = dimensionEvidencePlanFactory.create(
                request.competitorNames().get(0),
                contract,
                List.of("open.bilibili.com"));

        assertThat(contract.findField("pricing").orElseThrow().getBlockingLevel())
                .isEqualTo(CoverageBlockingLevel.NONE);
        assertThat(contract.findField("weaknesses").orElseThrow().getBlockingLevel())
                .isEqualTo(CoverageBlockingLevel.NONE);
        assertThat(plan.findField("coreFeatures").orElseThrow().getPlannedQueries())
                .hasSizeGreaterThanOrEqualTo(2);
        assertThat(plan.findField("pricing")).isEmpty();
    }

    @Test
    void standardBilibiliShallowEntryShouldDeepenCoreFeaturesAndPricing() throws Exception {
        Task66Request request = readRequest("task66/field-first-standard-bilibili-shallow-request.json");
        CoverageContract contract = coverageContractResolver.resolve(
                request.reportTemplate(),
                request.analysisDimensions(),
                request.sourceScope(),
                null);
        DimensionEvidencePlan plan = dimensionEvidencePlanFactory.create(
                request.competitorNames().get(0),
                contract,
                List.of("app.bilibili.com", "open.bilibili.com"));

        assertThat(contract.findField("pricing").orElseThrow().getBlockingLevel())
                .isEqualTo(CoverageBlockingLevel.BLOCKER);
        assertThat(plan.findField("coreFeatures").orElseThrow().getPlannedQueries())
                .extracting(FieldEvidenceQuery::getEvidencePathKey)
                .contains("DOCS_API_GUIDE");
        assertThat(plan.findField("pricing").orElseThrow().getEvidencePaths())
                .extracting(CoverageEvidencePath::getPathKey)
                .contains("OFFICIAL_PRICING_PAGE", "DOCS_BILLING_OR_LIMITS");
        assertThat(plan.findField("pricing").orElseThrow().getPlannedQueries())
                .extracting(FieldEvidenceQuery::getQuery)
                .anySatisfy(query -> assertThat(query).contains("定价"))
                .anySatisfy(query -> assertThat(query).contains("计费"))
                .anySatisfy(query -> assertThat(query).contains("服务协议"));
    }

    private Task66Request readRequest(String classpathResource) throws IOException {
        try (InputStream input = Thread.currentThread()
                .getContextClassLoader()
                .getResourceAsStream(classpathResource)) {
            assertThat(input).as(classpathResource).isNotNull();
            return objectMapper.readValue(input, Task66Request.class);
        }
    }

    private record Task66Request(String reportTemplate,
                                 List<String> competitorNames,
                                 List<String> competitorUrls,
                                 String reportLanguage,
                                 String subjectProduct,
                                 List<String> sourceScope,
                                 List<String> analysisDimensions,
                                 String taskName) {
    }
}
