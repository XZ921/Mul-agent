package cn.bugstack.competitoragent.workflow.coverage;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 字段级 Tavily 查询任务。
 * 这个对象只承载“查哪个字段、走哪条证据路径、为什么查”的轻量规划信息，
 * 不携带搜索结果正文，避免规划层和执行层相互耦合。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class FieldEvidenceQuery {

    private String fieldName;
    private String evidencePathKey;
    private String queryIntent;
    private String sourceType;
    private String query;
    private String reason;
    private String queryFingerprint;
    private Integer priority;

    @Builder.Default
    private List<String> includeDomains = new ArrayList<>();
}