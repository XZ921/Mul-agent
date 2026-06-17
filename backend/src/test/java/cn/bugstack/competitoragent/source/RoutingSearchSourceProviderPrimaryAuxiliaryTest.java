package cn.bugstack.competitoragent.source;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第四轮 Task 1 的主辅 discovery 路由红灯测试。
 * 这个测试先锁死一个行为：当主力垂直 provider 已满足最小候选阈值时，
 * 且配置声明“不继续跑辅助 provider”，路由层必须停止继续调用公网辅助补源。
 */
class RoutingSearchSourceProviderPrimaryAuxiliaryTest {

    @Test
    void shouldSkipAuxiliaryWhenPrimaryThresholdSatisfied() {
        SearchProviderProperties properties = new SearchProviderProperties();
        properties.setProviderOrder(List.of("github", "qianfan"));
        properties.setPrimaryCandidateThreshold(1);
        properties.setRunAuxiliaryWhenPrimarySatisfied(false);

        List<String> invocations = new ArrayList<>();
        TestProvider github = new TestProvider("github", List.of(SourceCandidate.builder()
                .url("https://github.com/acme/rocket")
                .providerKey("github")
                .sourceUrls(List.of("https://github.com/acme/rocket"))
                .build()), invocations);
        TestProvider qianfan = new TestProvider("qianfan", List.of(), invocations);

        RoutingSearchSourceProvider provider = new RoutingSearchSourceProvider(
                properties,
                List.of(github, qianfan),
                new SourceCandidateRanker()
        );

        provider.search("Acme", List.of("GITHUB"));

        assertThat(invocations).containsExactly("github");
    }

    /**
     * 这里用最小 provider stub 固定调用顺序，
     * 让测试只关注“有没有继续调用辅助 provider”，而不混入真实网络行为。
     */
    private record TestProvider(String providerKey,
                                List<SourceCandidate> candidates,
                                List<String> invocations) implements SearchSourceProvider {

        @Override
        public SearchSourceProviderDescriptor descriptor() {
            return SearchSourceProviderDescriptor.builder()
                    .providerKey(providerKey)
                    .displayName(providerKey)
                    .build();
        }

        @Override
        public List<SourceCandidate> search(String competitorName, List<String> requestedScopes) {
            invocations.add(providerKey);
            return candidates;
        }
    }
}
