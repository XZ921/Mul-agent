package cn.bugstack.competitoragent.search;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 正文可用性评分的轻量页面视图。
 * 它只携带评分所需的 URL、来源类型、正文和结构化块，避免 scorer 依赖采集执行的大对象。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class CollectedPageView {

    private String url;
    private String sourceType;
    private double sourceTrust;
    private String bodyText;

    @Builder.Default
    private List<String> structuredBlocks = new ArrayList<>();
}
