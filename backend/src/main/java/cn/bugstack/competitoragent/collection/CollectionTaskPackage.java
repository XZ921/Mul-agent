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
    List<String> sourceUrls;
}
