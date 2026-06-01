package cn.bugstack.competitoragent.source;

import java.util.List;

/**
 * 搜索式补源提供者。
 * 当前阶段先通过可替换适配层补齐搜索候选来源，后续可无缝替换为真实搜索 API。
 */
public interface SearchSourceProvider {

    /**
     * 根据竞品与来源范围返回搜索候选项。
     */
    List<SourceCandidate> search(String competitorName, List<String> requestedScopes);
}
