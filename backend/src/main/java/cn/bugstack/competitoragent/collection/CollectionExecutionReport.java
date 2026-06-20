package cn.bugstack.competitoragent.collection;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 一次 collector 节点 collection 执行的正式聚合结果。
 * 与单包的 CollectionExecutionResult 区分开，专门表达整轮采集执行的状态与审计快照。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class CollectionExecutionReport {

    private String status;
    private List<CollectionExecutionResult> results;
    private CollectionAuditSnapshot auditSnapshot;
    private List<String> sourceUrls;
    private CollectionExecutionStats stats;
}
