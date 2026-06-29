package cn.bugstack.competitoragent.workflow.coverage;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 字段级证据路径定义。
 * 这里记录某个字段要通过哪些来源类型、查询意图和期望信号去补齐证据，
 * 后续 Collector 和 repair 链路都会依赖这一层结构，而不是只盯着 sourceType。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CoverageEvidencePath {

    private String pathKey;

    @Builder.Default
    private List<String> sourceTypes = new ArrayList<>();

    @Builder.Default
    private List<String> queryIntents = new ArrayList<>();

    @Builder.Default
    private List<String> expectedSignals = new ArrayList<>();

    @Builder.Default
    private boolean required = false;

    private String successCriteria;
    private String failureStatus;
}
