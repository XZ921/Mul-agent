package cn.bugstack.competitoragent.source;

import java.util.List;

/**
 * 信息源发现服务。
 */
public interface SourceDiscoveryService {

    /**
     * 根据竞品名称、用户提供 URL 和采集范围生成候选采集计划。
     */
    List<SourcePlan> discover(String competitorName, List<String> providedUrls, List<String> requestedScopes);

    /**
     * 预览阶段只生成轻量规划结果，不阻塞在实时搜索或浏览器补源上。
     * 默认回退到正式 discover，确保现有实现兼容。
     */
    default List<SourcePlan> discoverForPreview(String competitorName,
                                                List<String> providedUrls,
                                                List<String> requestedScopes) {
        return discover(competitorName, providedUrls, requestedScopes);
    }
}
