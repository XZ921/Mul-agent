package cn.bugstack.competitoragent.collection;

import cn.bugstack.competitoragent.search.tavily.TavilyPrefetchedContentRegistry;
import cn.bugstack.competitoragent.source.GithubApiClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 第四轮 Task 1 的执行器注册表红灯测试。
 * 目标是先把“primaryTool 决定执行器路由”的最小规则固定下来，
 * 后续新增 API / Web 两类执行器时不再把 CollectorAgent 变成路由中心。
 */
class CollectionExecutorRegistryTest {

    @Test
    void shouldResolveGithubApiExecutorByPrimaryTool() {
        GithubApiClient githubApiClient = mock(GithubApiClient.class);
        when(githubApiClient.isReady()).thenReturn(true);
        CollectionExecutor githubExecutor = new GithubApiCollectionExecutor(githubApiClient);
        CollectionExecutor webExecutor = new WebPageCollectionExecutor(null);
        CollectionExecutorRegistry registry = new CollectionExecutorRegistry(List.of(githubExecutor, webExecutor));

        CollectionTaskPackage taskPackage = CollectionTaskPackage.builder()
                .primaryTool("GITHUB_API")
                .resourceLocator("github://repo/acme/rocket")
                .build();

        assertThat(registry.resolve(taskPackage).executorType()).isEqualTo("API_DATA");
    }

    @Test
    void shouldIgnoreGithubApiExecutorWhenItDoesNotSupportTheTaskPackage() {
        CollectionExecutor githubExecutor = new CollectionExecutor() {
            @Override
            public String executorType() {
                return "API_DATA";
            }

            @Override
            public boolean supports(CollectionTaskPackage taskPackage) {
                return false;
            }

            @Override
            public CollectionExecutionResult execute(CollectionTaskPackage taskPackage) {
                throw new IllegalStateException("should not be called");
            }
        };
        CollectionExecutor webExecutor = new WebPageCollectionExecutor(null);
        CollectionExecutorRegistry registry = new CollectionExecutorRegistry(List.of(githubExecutor, webExecutor));

        CollectionTaskPackage taskPackage = CollectionTaskPackage.builder()
                .primaryTool("GITHUB_API")
                .resourceLocator("github://repo/acme/rocket")
                .build();

        assertThatThrownBy(() -> registry.resolve(taskPackage))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no collection executor matched task package");
    }

    @Test
    void shouldResolveWebPageExecutorForJinaReaderPrimaryTool() {
        GithubApiClient githubApiClient = mock(GithubApiClient.class);
        when(githubApiClient.isReady()).thenReturn(false);
        CollectionExecutor githubExecutor = new GithubApiCollectionExecutor(githubApiClient);
        CollectionExecutor webExecutor = new WebPageCollectionExecutor(null, null);
        CollectionExecutorRegistry registry = new CollectionExecutorRegistry(List.of(githubExecutor, webExecutor));

        CollectionTaskPackage taskPackage = CollectionTaskPackage.builder()
                .primaryTool("JINA_READER")
                .renderHint(WebPageRenderHint.LIGHTWEIGHT)
                .resourceLocator("https://docs.example.com/api/reference")
                .build();

        assertThat(registry.resolve(taskPackage).executorType()).isEqualTo("WEB_PAGE");
    }

    @Test
    void shouldResolveRssExecutorForRssPrimaryTool() {
        GithubApiClient githubApiClient = mock(GithubApiClient.class);
        when(githubApiClient.isReady()).thenReturn(false);
        CollectionExecutor githubExecutor = new GithubApiCollectionExecutor(githubApiClient);
        CollectionExecutor rssExecutor = new RssFeedCollectionExecutor(null, new cn.bugstack.competitoragent.source.RssFeedProperties());
        CollectionExecutor webExecutor = new WebPageCollectionExecutor(null, null);
        CollectionExecutorRegistry registry = new CollectionExecutorRegistry(List.of(githubExecutor, rssExecutor, webExecutor));

        CollectionTaskPackage taskPackage = CollectionTaskPackage.builder()
                .primaryTool("RSS")
                .resourceLocator("rss://feed/aGVsbG8")
                .build();

        assertThat(registry.resolve(taskPackage).executorType()).isEqualTo("API_DATA");
    }

    @Test
    void shouldResolveTavilyPrefetchedExecutorByPrimaryTool() {
        TavilyPrefetchedContentRegistry registryStore = new TavilyPrefetchedContentRegistry();
        CollectionExecutor tavilyExecutor = new TavilyPrefetchedExecutor(registryStore);
        CollectionExecutor webExecutor = new WebPageCollectionExecutor(null, null);
        CollectionExecutorRegistry registry = new CollectionExecutorRegistry(List.of(tavilyExecutor, webExecutor));

        CollectionTaskPackage taskPackage = CollectionTaskPackage.builder()
                .primaryTool("TAVILY_PREFETCHED")
                .prefetchedContentRef("tavily:req-1:0")
                .build();

        assertThat(registry.resolve(taskPackage).executorType()).isEqualTo("TAVILY_PREFETCHED");
    }
}
