package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.source.SourceCandidate;
import cn.bugstack.competitoragent.source.SourceCollector;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 运行期最终要采集的目标。
 * 如果验证阶段已经提前拿到页面内容，会直接复用 collectedPage，避免重复打开同一页面。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchCollectionTarget {

    private SourceCandidate candidate;
    private SourceCollector.CollectedPage collectedPage;
}
