package cn.bugstack.competitoragent.collection;

import cn.bugstack.competitoragent.search.SearchPolicyResolver;
import cn.bugstack.competitoragent.source.SourceCandidate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URLEncoder;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
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
        return build(taskId, nodeName, planVersionId, competitorName, candidate, priority, 0);
    }

    /**
     * 递归采集场景需要显式透传 discoveryDepth。
     * 这样执行器、协调器与 CollectorAgent 才能共享统一的“入口页/内部发现页”语义，
     * 避免各层各自重新推断当前页面来自第几层发现。
     */
    public CollectionTaskPackage build(Long taskId,
                                       String nodeName,
                                       Long planVersionId,
                                       String competitorName,
                                       SourceCandidate candidate,
                                       int priority,
                                       int discoveryDepth) {
        String sourceFamilyKey = candidate == null ? null : candidate.getSourceFamilyKey();
        String sourceType = candidate == null ? null : candidate.getSourceType();
        String url = candidate == null ? null : candidate.getUrl();
        WebPageRenderHint renderHint = resolveRenderHint(sourceFamilyKey, sourceType);
        String primaryTool = resolvePrimaryTool(sourceFamilyKey, sourceType, url, renderHint);
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
                .discoveryDepth(Math.max(0, discoveryDepth))
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
                                      String url,
                                      WebPageRenderHint renderHint) {
        if ("github".equalsIgnoreCase(sourceFamilyKey)
                || "GITHUB".equalsIgnoreCase(sourceType)) {
            return "GITHUB_API";
        }
        if (isExplicitFeedUrl(sourceFamilyKey, sourceType, url)) {
            return "RSS";
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
        LinkedHashSet<String> sourceUrls = new LinkedHashSet<>();
        if (candidate != null && candidate.getSourceUrls() != null) {
            for (String sourceUrl : candidate.getSourceUrls()) {
                if (StringUtils.hasText(sourceUrl)) {
                    sourceUrls.add(sourceUrl.trim());
                }
            }
        }
        // 候选来源可能只记录了 LLM/规划阶段证据；正式采集包必须额外保留真实 URL，保证 collection audit 可回溯到实际页面。
        if (StringUtils.hasText(url)) {
            sourceUrls.add(url.trim());
        }
        return new ArrayList<>(sourceUrls);
    }

    /**
     * 对 API 型来源额外生成稳定 resourceLocator，
     * 让 collection owner 直接消费仓库定位符，而不是重新按竞品名称搜索一遍。
     */
    private String resolveResourceLocator(String primaryTool, String url) {
        if ("RSS".equalsIgnoreCase(primaryTool) && StringUtils.hasText(url)) {
            return "rss://feed/" + URLEncoder.encode(url.trim(), StandardCharsets.UTF_8);
        }
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

    /**
     * 只有“显式就是 feed 的 URL”才允许走 RSS 专项执行器。
     * 普通新闻正文 URL 即使 sourceType=NEWS，也必须继续留在网页采集主链路。
     */
    private boolean isExplicitFeedUrl(String sourceFamilyKey, String sourceType, String url) {
        if (!StringUtils.hasText(url)) {
            return false;
        }
        boolean newsFamily = "news".equalsIgnoreCase(sourceFamilyKey) || "NEWS".equalsIgnoreCase(sourceType);
        if (!newsFamily) {
            return false;
        }
        try {
            URI uri = URI.create(url.trim());
            String path = uri.getPath();
            if (!StringUtils.hasText(path)) {
                return false;
            }
            String normalizedPath = path.trim().toLowerCase(java.util.Locale.ROOT);
            String[] segments = normalizedPath.split("/");
            for (String segment : segments) {
                if (!StringUtils.hasText(segment)) {
                    continue;
                }
                // 只接受明确的 feed 段名，避免把 feedback 之类普通路径误判成 RSS。
                if ("feed".equals(segment) || "rss".equals(segment) || "atom".equals(segment)) {
                    return true;
                }
            }
            int lastSlashIndex = normalizedPath.lastIndexOf('/');
            String filename = lastSlashIndex >= 0 ? normalizedPath.substring(lastSlashIndex + 1) : normalizedPath;
            // .xml 后缀只有在文件名本身就是 feed/rss/atom.xml 时才算显式 feed 信号。
            return "feed.xml".equals(filename)
                    || "rss.xml".equals(filename)
                    || "atom.xml".equals(filename);
        } catch (Exception ignored) {
            return false;
        }
    }
}
