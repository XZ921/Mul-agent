package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.agent.collector.CollectorNodeConfig;
import cn.bugstack.competitoragent.source.SearchSourceProvider;
import cn.bugstack.competitoragent.source.SourceCandidate;
import cn.bugstack.competitoragent.source.SourceCandidateRanker;
import cn.bugstack.competitoragent.source.SourceCollector;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 第四轮 Task 1 的候选元数据标准化红灯测试。
 * 当前先用反射锁死 normalizeCandidates(...) 的最小契约，
 * 后续 Task 2 完成后需要切换为公开执行链路断言，不保留反射验收形态。
 */
class SearchCandidateNormalizationMetadataTest {

    @Test
    void shouldFillProviderFamilyAndSourceUrlsDuringNormalization() throws Exception {
        SearchExecutionCoordinator coordinator = new SearchExecutionCoordinator(
                new CandidateVerifier(mock(SourceCollector.class)),
                mock(BrowserSearchRuntimeService.class),
                mock(SearchSourceProvider.class),
                new SourceCandidateRanker(),
                new CollectionTargetSelector(),
                new SearchPolicyResolver()
        );
        Method method = SearchExecutionCoordinator.class.getDeclaredMethod(
                "normalizeCandidates", List.class, String.class, CollectorNodeConfig.class);
        method.setAccessible(true);

        CollectorNodeConfig config = new CollectorNodeConfig();
        config.setSourceType("GITHUB");
        SourceCandidate candidate = SourceCandidate.builder()
                .url("https://github.com/acme/rocket")
                .sourceType("GITHUB")
                .providerKey("github")
                .build();

        @SuppressWarnings("unchecked")
        List<SourceCandidate> normalized = (List<SourceCandidate>) method.invoke(
                coordinator, List.of(candidate), "HTTP", config);

        assertThat(normalized).hasSize(1);
        assertThat(normalized.get(0).getSourceFamilyKey()).isEqualTo("github");
        assertThat(normalized.get(0).getProviderKey()).isEqualTo("github");
        assertThat(normalized.get(0).getSourceUrls()).containsExactly("https://github.com/acme/rocket");
    }
}
