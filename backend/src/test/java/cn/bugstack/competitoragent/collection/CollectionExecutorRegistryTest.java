package cn.bugstack.competitoragent.collection;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第四轮 Task 1 的执行器注册表红灯测试。
 * 目标是先把“primaryTool 决定执行器路由”的最小规则固定下来，
 * 后续新增 API / Web 两类执行器时不再把 CollectorAgent 变成路由中心。
 */
class CollectionExecutorRegistryTest {

    @Test
    void shouldResolveGithubApiExecutorByPrimaryTool() {
        CollectionExecutor githubExecutor = new GithubApiCollectionExecutor(null);
        CollectionExecutor webExecutor = new WebPageCollectionExecutor(null);
        CollectionExecutorRegistry registry = new CollectionExecutorRegistry(List.of(githubExecutor, webExecutor));

        CollectionTaskPackage taskPackage = CollectionTaskPackage.builder()
                .primaryTool("GITHUB_API")
                .resourceLocator("github://repo/acme/rocket")
                .build();

        assertThat(registry.resolve(taskPackage).executorType()).isEqualTo("API_DATA");
    }

    @Test
    void shouldResolveWebPageExecutorForJinaReaderPrimaryTool() {
        CollectionExecutor githubExecutor = new GithubApiCollectionExecutor(null);
        CollectionExecutor webExecutor = new WebPageCollectionExecutor(null, null);
        CollectionExecutorRegistry registry = new CollectionExecutorRegistry(List.of(githubExecutor, webExecutor));

        CollectionTaskPackage taskPackage = CollectionTaskPackage.builder()
                .primaryTool("JINA_READER")
                .renderHint(WebPageRenderHint.LIGHTWEIGHT)
                .resourceLocator("https://docs.example.com/api/reference")
                .build();

        assertThat(registry.resolve(taskPackage).executorType()).isEqualTo("WEB_PAGE");
    }
}
