package cn.bugstack.competitoragent.source;

import java.util.List;

/**
 * 搜索式补源提供者。
 * 当前阶段先通过可替换适配层补齐搜索候选来源，后续可无缝替换为真实搜索 API。
 */
public interface SearchSourceProvider {

    /**
     * 返回 Provider 的能力声明。
     * 默认给出一个最小描述，便于老实现平滑过渡到声明式路由结构。
     */
    default SearchSourceProviderDescriptor descriptor() {
        return SearchSourceProviderDescriptor.builder()
                .providerKey(getClass().getSimpleName())
                .displayName(getClass().getSimpleName())
                .capabilities(List.of("WEB_SEARCH"))
                .defaultEnabled(true)
                .defaultFailOpen(true)
                .build();
    }

    /**
     * 返回当前 Provider 是否具备参与路由的基本条件。
     * 例如 API Key、Endpoint 或浏览器预览开关是否满足。
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * 基于完整上下文执行补源搜索。
     * 新 provider 可以直接读取 query、域名提示与种子候选；旧 provider 默认回退到老入口，保持兼容。
     */
    default List<SourceCandidate> search(SearchSourceRequest request) {
        if (request == null) {
            return List.of();
        }
        return search(request.getCompetitorName(), request.getRequestedScopes());
    }

    /**
     * 根据竞品与来源范围返回搜索候选项。
     */
    List<SourceCandidate> search(String competitorName, List<String> requestedScopes);
}
