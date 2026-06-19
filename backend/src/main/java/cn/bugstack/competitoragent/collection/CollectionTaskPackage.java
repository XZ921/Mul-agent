package cn.bugstack.competitoragent.collection;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * 最小采集任务包。
 * 该对象专门承接“已选中的候选 -> 可执行采集任务”的收口职责，
 * 避免 CollectorAgent 继续直接操纵 URL 并把路由判断写死在主循环里。
 */
@Value
@Builder
public class CollectionTaskPackage {

    Long taskId;
    String nodeName;
    Long planVersionId;
    String packageKey;
    Integer targetIndex;
    String competitorName;
    String sourceFamilyKey;
    String sourceType;
    String primaryTool;
    String url;
    String resourceLocator;
    WebPageRenderHint renderHint;
    List<String> expectedBlockTypes;
    List<String> targetFields;
    Integer priority;
    /**
     * 发现深度用于区分“入口页采集”和“站内递归采集”。
     * 入口页固定从 0 开始，子页面每下钻一层加 1，供递归限深与进度文案统一消费。
     */
    Integer discoveryDepth;
    List<String> sourceUrls;
}
