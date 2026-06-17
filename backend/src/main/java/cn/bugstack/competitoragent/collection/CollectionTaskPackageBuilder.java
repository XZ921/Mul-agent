package cn.bugstack.competitoragent.collection;

import cn.bugstack.competitoragent.search.SearchPolicyResolver;
import cn.bugstack.competitoragent.source.SourceCandidate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.List;

/**
 * 采集任务包构建器。
 * 这里负责把候选元数据翻译成稳定任务包，确保后续 executor 不必重复推断 source family、
 * primary tool 和 resource locator。
 */
@Component
public class CollectionTaskPackageBuilder {

    private final SearchPolicyResolver searchPolicyResolver;

    public CollectionTaskPackageBuilder() {
        this(new SearchPolicyResolver());
    }

    @Autowired
    public CollectionTaskPackageBuilder(SearchPolicyResolver searchPolicyResolver) {
        this.searchPolicyResolver = searchPolicyResolver == null ? new SearchPolicyResolver() : searchPolicyResolver;
    }

    public CollectionTaskPackage build(Long taskId,
                                       String nodeName,
                                       Long planVersionId,
                                       String competitorName,
                                       SourceCandidate candidate,
                                       int priority) {
        String sourceFamilyKey = candidate == null ? null : candidate.getSourceFamilyKey();
        String sourceType = candidate == null ? null : candidate.getSourceType();
        String url = candidate == null ? null : candidate.getUrl();
        WebPageRenderHint renderHint = resolveRenderHint(sourceFamilyKey, sourceType);
        String primaryTool = resolvePrimaryTool(sourceFamilyKey, sourceType, renderHint);
        return CollectionTaskPackage.builder()
                .taskId(taskId)
                .nodeName(nodeName)
                .planVersionId(planVersionId)
                .packageKey(buildPackageKey(nodeName, priority))
                .targetIndex(priority)
                .competitorName(competitorName)
                .sourceFamilyKey(sourceFamilyKey)
                .sourceType(sourceType)
                .primaryTool(primaryTool)
                .url(url)
                .resourceLocator(resolveResourceLocator(primaryTool, url))
                .renderHint(renderHint)
                .expectedBlockTypes(searchPolicyResolver.resolveExpectedBlockTypes(sourceFamilyKey, sourceType))
                .targetFields(List.of())
                .priority(priority)
                .sourceUrls(resolveSourceUrls(candidate, url))
                .build();
    }

    /**
     * 包级身份必须在构建阶段稳定生成，后续 replay / checkpoint / rerun-resume 都依赖它作为锚点。
     */
    private String buildPackageKey(String nodeName, int targetIndex) {
        String safeNodeName = StringUtils.hasText(nodeName) ? nodeName.trim() : "collection";
        return safeNodeName + "#" + String.format("%03d", Math.max(targetIndex, 0));
    }

    /**
     * 当前阶段先用最小规则判断执行器主工具。
     * GitHub 走 API_DATA，其余网页型来源继续复用网页采集执行器。
     */
    private String resolvePrimaryTool(String sourceFamilyKey,
                                      String sourceType,
                                      WebPageRenderHint renderHint) {
        if ("github".equalsIgnoreCase(sourceFamilyKey)
                || "GITHUB".equalsIgnoreCase(sourceType)) {
            return "GITHUB_API";
        }
        return renderHint == WebPageRenderHint.FULL_RENDER ? "WEB_SCRAPER" : "JINA_READER";
    }

    /**
     * 统一通过 SearchPolicyResolver 解释网页采集偏好。
     * 当 family/sourceType 缺失时回退为 LIGHTWEIGHT，避免候选为空时构建阶段直接异常。
     */
    private WebPageRenderHint resolveRenderHint(String sourceFamilyKey, String sourceType) {
        try {
            return searchPolicyResolver.resolveWebRenderHint(sourceFamilyKey, sourceType);
        } catch (Exception ignored) {
            return WebPageRenderHint.LIGHTWEIGHT;
        }
    }

    private List<String> resolveSourceUrls(SourceCandidate candidate, String url) {
        if (candidate != null && candidate.getSourceUrls() != null && !candidate.getSourceUrls().isEmpty()) {
            return candidate.getSourceUrls();
        }
        return StringUtils.hasText(url) ? List.of(url) : List.of();
    }

    /**
     * 对 API 型来源额外生成稳定 resourceLocator，
     * 让 collection owner 直接消费仓库定位符，而不是重新按竞品名称搜索一遍。
     */
    private String resolveResourceLocator(String primaryTool, String url) {
        if (!"GITHUB_API".equalsIgnoreCase(primaryTool) || !StringUtils.hasText(url)) {
            return url;
        }
        try {
            URI uri = URI.create(url);
            String[] segments = uri.getPath().split("/");
            if (segments.length >= 3
                    && "github.com".equalsIgnoreCase(uri.getHost())
                    && StringUtils.hasText(segments[1])
                    && StringUtils.hasText(segments[2])) {
                return "github://repo/" + segments[1] + "/" + segments[2];
            }
        } catch (Exception ignored) {
            // 解析失败时保留原始 URL，避免任务包构建阶段直接中断主链路。
        }
        return url;
    }
}
