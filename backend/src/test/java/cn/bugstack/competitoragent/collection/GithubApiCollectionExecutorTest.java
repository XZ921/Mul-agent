package cn.bugstack.competitoragent.collection;

import cn.bugstack.competitoragent.source.GithubApiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GithubApiCollectionExecutorTest {

    @Test
    void shouldReturnStructuredGithubEvidenceFromRepoLocator() throws Exception {
        GithubApiClient client = mock(GithubApiClient.class);
        ObjectMapper objectMapper = new ObjectMapper();
        when(client.fetchRepository("acme", "rocket")).thenReturn(objectMapper.readTree("""
                {
                  "full_name": "acme/rocket",
                  "stargazers_count": 42,
                  "description": "Acme AI agent platform",
                  "html_url": "https://github.com/acme/rocket",
                  "default_branch": "main"
                }
                """));
        when(client.fetchReadme("acme", "rocket")).thenReturn(objectMapper.readTree("""
                {
                  "content": "%s"
                }
                """.formatted(Base64.getEncoder().encodeToString("Acme README".getBytes(StandardCharsets.UTF_8)))));
        when(client.fetchLatestRelease("acme", "rocket")).thenReturn(objectMapper.readTree("""
                {
                  "tag_name": "v1.2.3"
                }
                """));

        GithubApiCollectionExecutor executor = new GithubApiCollectionExecutor(client);
        CollectionExecutionResult result = executor.execute(CollectionTaskPackage.builder()
                .primaryTool("GITHUB_API")
                .resourceLocator("github://repo/acme/rocket")
                .sourceUrls(List.of("https://github.com/acme/rocket"))
                .build());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getTitle()).isEqualTo("acme/rocket");
        assertThat(result.getSourceUrls()).containsExactly("https://github.com/acme/rocket");
        assertThat(result.getStructuredPayload()).containsEntry("repository", "acme/rocket");
        assertThat(result.getStructuredPayload()).containsEntry("latestReleaseTag", "v1.2.3");
        assertThat(result.getStructuredPayload()).containsEntry("defaultBranch", "main");
        assertThat(result.getStructuredPayload().get("readme")).isEqualTo("Acme README");
    }
}
