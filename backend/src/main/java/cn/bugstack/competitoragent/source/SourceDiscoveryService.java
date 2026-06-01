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
}
