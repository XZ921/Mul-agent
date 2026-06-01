package cn.bugstack.competitoragent.search;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 采集节点的搜索执行计划。
 * 使用强类型模型承接计划与进度，避免运行期依赖自由 JSON 字符串。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class SearchExecutionPlan {

    private String stage;
    private List<SearchExecutionStep> steps;
}
