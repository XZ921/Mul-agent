package cn.bugstack.competitoragent.collection;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * collection 包级回放时间线条目。
 * 每个条目只表达一个采集包在当前 collector 节点中的最终处理结果，
 * 供 replay / insight / checkpoint 统一消费。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class CollectionReplayTimelineItem {

    private String taskPackageKey;
    private Integer targetIndex;
    private String status;
    private String executorType;
    private String resourceLocator;
    private String failureKind;
    private String errorMessage;
    private Boolean reusedFromCheckpoint;
    private String checkpointSource;
    private List<String> sourceUrls;
    private Instant collectedAt;
    private Long durationMillis;
}
