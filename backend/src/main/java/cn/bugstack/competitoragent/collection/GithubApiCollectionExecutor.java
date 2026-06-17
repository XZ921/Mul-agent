package cn.bugstack.competitoragent.collection;

import cn.bugstack.competitoragent.source.GithubApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GitHub API 结构化采集执行器。
 * 当前先把 repo locator 直接映射为结构化证据，避免再退回“GitHub URL -> HTML 页面 -> 再提取”的旧路径。
 */
@Component
public class GithubApiCollectionExecutor extends ApiDataCollectionExecutor {

    private final GithubApiClient githubApiClient;

    public GithubApiCollectionExecutor(GithubApiClient githubApiClient) {
        this.githubApiClient = githubApiClient;
    }

    @Override
    public boolean supports(CollectionTaskPackage taskPackage) {
        return taskPackage != null && "GITHUB_API".equalsIgnoreCase(taskPackage.getPrimaryTool());
    }

    @Override
    public CollectionExecutionResult execute(CollectionTaskPackage taskPackage) {
        String[] repoRef = parseRepoLocator(taskPackage == null ? null : taskPackage.getResourceLocator());
        if (repoRef == null) {
            return CollectionExecutionResult.builder()
                    .executorType(executorType())
                    .success(false)
                    .resourceLocator(taskPackage == null ? null : taskPackage.getResourceLocator())
                    .sourceUrls(taskPackage == null ? List.of() : taskPackage.getSourceUrls())
                    .errorMessage("invalid github resource locator")
                    .build();
        }
        if (githubApiClient == null) {
            return CollectionExecutionResult.builder()
                    .executorType(executorType())
                    .success(false)
                    .resourceLocator(taskPackage.getResourceLocator())
                    .sourceUrls(taskPackage.getSourceUrls())
                    .errorMessage("github api client unavailable")
                    .build();
        }
        try {
            String owner = repoRef[0];
            String repo = repoRef[1];
            JsonNode repository = githubApiClient.fetchRepository(owner, repo);
            JsonNode readme = githubApiClient.fetchReadme(owner, repo);
            JsonNode latestRelease = tryFetchLatestRelease(owner, repo);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("repository", repository.path("full_name").asText());
            payload.put("stars", repository.path("stargazers_count").asInt());
            payload.put("defaultBranch", repository.path("default_branch").asText());
            payload.put("htmlUrl", repository.path("html_url").asText());
            payload.put("readme", decodeBase64(readme.path("content").asText()));
            payload.put("latestReleaseTag", latestRelease == null ? null : latestRelease.path("tag_name").asText());

            String sourceUrl = repository.path("html_url").asText();
            return CollectionExecutionResult.builder()
                    .executorType(executorType())
                    .success(true)
                    .resourceLocator(taskPackage.getResourceLocator())
                    .title(repository.path("full_name").asText())
                    .content(repository.path("description").asText())
                    .sourceUrls(StringUtils.hasText(sourceUrl) ? List.of(sourceUrl) : taskPackage.getSourceUrls())
                    .structuredPayload(payload)
                    .build();
        } catch (RuntimeException exception) {
            return CollectionExecutionResult.builder()
                    .executorType(executorType())
                    .success(false)
                    .resourceLocator(taskPackage.getResourceLocator())
                    .sourceUrls(taskPackage.getSourceUrls())
                    .errorMessage(exception.getMessage())
                    .build();
        }
    }

    private String[] parseRepoLocator(String locator) {
        if (!StringUtils.hasText(locator) || !locator.startsWith("github://repo/")) {
            return null;
        }
        String[] segments = locator.substring("github://repo/".length()).split("/");
        if (segments.length != 2) {
            return null;
        }
        return segments;
    }

    private JsonNode tryFetchLatestRelease(String owner, String repo) {
        try {
            return githubApiClient.fetchLatestRelease(owner, repo);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String decodeBase64(String encoded) {
        if (!StringUtils.hasText(encoded)) {
            return null;
        }
        return new String(Base64.getMimeDecoder().decode(encoded), StandardCharsets.UTF_8);
    }
}
