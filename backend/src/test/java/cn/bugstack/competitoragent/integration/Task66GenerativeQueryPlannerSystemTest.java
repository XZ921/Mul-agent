package cn.bugstack.competitoragent.integration;

import cn.bugstack.competitoragent.collection.quality.EvidenceQualityContext;
import cn.bugstack.competitoragent.collection.quality.EvidenceQualityGate;
import cn.bugstack.competitoragent.collection.quality.EvidenceQualityGateProperties;
import cn.bugstack.competitoragent.workflow.coverage.AnalysisDimensionMappingCatalog;
import cn.bugstack.competitoragent.workflow.coverage.CoverageContract;
import cn.bugstack.competitoragent.workflow.coverage.CoverageContractResolver;
import cn.bugstack.competitoragent.workflow.coverage.CoverageFieldContract;
import cn.bugstack.competitoragent.workflow.coverage.FieldEvidenceQuery;
import cn.bugstack.competitoragent.workflow.coverage.FieldEvidenceQueryPlanner;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class Task66GenerativeQueryPlannerSystemTest {

    @Test
    void shouldGenerateFieldQueriesDomainHintsAndUsabilityScoresForCapabilityIntro() {
        CoverageContractResolver resolver = new CoverageContractResolver(new AnalysisDimensionMappingCatalog());
        FieldEvidenceQueryPlanner planner = new FieldEvidenceQueryPlanner();
        EvidenceQualityGate qualityGate = new EvidenceQualityGate(new EvidenceQualityGateProperties());

        CoverageContract contract = resolver.resolve(
                null,
                List.of("开放平台", "开发者生态", "产品功能"),
                List.of("官网", "产品文档"),
                null);
        CoverageFieldContract positioning = contract.findField("positioning").orElseThrow();

        List<FieldEvidenceQuery> queries = planner.plan("哔哩哔哩", positioning, List.of("open.bilibili.com"));

        // 07 天花板验收：非 coreFeatures/pricing 的正向字段也进入多 query，并且包含第三方补充视角。
        assertThat(queries).hasSizeGreaterThanOrEqualTo(2);
        assertThat(queries)
                .filteredOn(query -> !"OPEN_WEB".equals(query.getSourceType()))
                .allSatisfy(query -> assertThat(query.getIncludeDomains()).contains("open.bilibili.com"));
        assertThat(queries)
                .anySatisfy(query -> {
                    assertThat(query.getSourceType()).isIn("OPEN_WEB", "REVIEW", "NEWS");
                    assertThat(query.getIncludeDomains()).isEmpty();
                    assertThat(query.getQuery()).containsAnyOf("评测", "用户反馈", "行业分析", "教程");
                });

        var verdict = qualityGate.evaluate(
                EvidenceQualityContext.builder()
                        .url("https://app.bilibili.com")
                        .sourceType("OFFICIAL")
                        .fieldName("positioning")
                        .evidencePathKey("OFFICIAL_PUBLIC_PROFILE")
                        .expectedSignals(List.of("市场定位", "品牌定位"))
                        .build(),
                "下载 安卓 iOS TV PC 车机 扫码下载 首页 登录 注册 帮助中心 联系我们",
                List.of("OFFICIAL_DOMAIN_MATCHED"),
                0.95D);

        assertThat(verdict.getContentUsabilityScore()).isLessThan(0.4D);
        assertThat(verdict.getSourceTier()).isEqualTo("OFFICIAL");
        assertThat(verdict.getQualitySignals()).contains("NAVIGATION_SHELL_DETECTED");
    }
}
