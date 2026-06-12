package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.source.SourceCandidate;
import cn.bugstack.competitoragent.source.SourceCollector;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SearchAndCollectionGoldenMasterTest {

    private final SourceCollector sourceCollector = mock(SourceCollector.class);
    private final CandidateVerifier candidateVerifier = new CandidateVerifier(sourceCollector);

    @Test
    void shouldRejectOfficialMarketingPageEvenWhenDomainAuthorityIsHigh() {
        when(sourceCollector.collect("https://www.aliyun.com/product/ecs", "阿里云", "OFFICIAL"))
                .thenReturn(SourceCollector.CollectedPage.builder()
                        .url("https://www.aliyun.com/product/ecs")
                        .title("阿里云新客特惠 - 云服务器限时秒杀")
                        .content("立即购买 优惠 秒杀 活动页 新客专享")
                        .snippet("新客优惠")
                        .competitorName("阿里云")
                        .sourceType("OFFICIAL")
                        .success(true)
                        .build());

        CandidateVerificationResult result = candidateVerifier.verify(
                "阿里云",
                "OFFICIAL",
                List.of(SourceCandidate.builder()
                        .url("https://www.aliyun.com/product/ecs")
                        .title("阿里云 ECS")
                        .sourceType("OFFICIAL")
                        .domain("www.aliyun.com")
                        .relevanceScore(0.95)
                        .freshnessScore(0.80)
                        .qualityScore(0.99)
                        .build())
        );

        assertEquals(0, result.getVerifiedTargets().size());
        assertEquals("DISCARDED", result.getUpdatedCandidates().get(0).getSelectionStage());
        assertTrue(result.getUpdatedCandidates().get(0).getVerificationReason().contains("营销"));
    }

    @Test
    void shouldAcceptHighValueChinesePricingDocument() {
        when(sourceCollector.collect("https://cloud.tencent.com/document/product/1234/5678", "腾讯云", "PRICING"))
                .thenReturn(SourceCollector.CollectedPage.builder()
                        .url("https://cloud.tencent.com/document/product/1234/5678")
                        .title("云数据库价格说明")
                        .content("本页说明计费方式、套餐差异、价格与收费规则。")
                        .snippet("计费方式与价格说明")
                        .competitorName("腾讯云")
                        .sourceType("PRICING")
                        .success(true)
                        .build());

        CandidateVerificationResult result = candidateVerifier.verify(
                "腾讯云",
                "PRICING",
                List.of(SourceCandidate.builder()
                        .url("https://cloud.tencent.com/document/product/1234/5678")
                        .title("云数据库价格说明")
                        .sourceType("PRICING")
                        .domain("cloud.tencent.com")
                        .build())
        );

        assertEquals(1, result.getVerifiedTargets().size());
        assertTrue(Boolean.TRUE.equals(result.getUpdatedCandidates().get(0).getVerified()));
        assertFalse(result.getUpdatedCandidates().get(0).getMatchedSignals().isEmpty());
    }
}
