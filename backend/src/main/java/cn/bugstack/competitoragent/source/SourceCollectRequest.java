package cn.bugstack.competitoragent.source;

import cn.bugstack.competitoragent.collection.WebPageRenderHint;
import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * 网页采集请求。
 * 先把 renderHint、结构块预期与 sourceUrls 收口到正式对象中，
 * 避免第五轮后续实现继续把这些提示散落在方法参数外。
 */
@Value
@Builder
public class SourceCollectRequest {

    String url;
    String competitorName;
    String sourceType;
    WebPageRenderHint renderHint;
    List<String> expectedBlockTypes;
    List<String> sourceUrls;
}
