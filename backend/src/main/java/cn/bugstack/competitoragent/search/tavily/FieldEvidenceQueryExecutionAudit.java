package cn.bugstack.competitoragent.search.tavily;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 字段证据 query 的逐条执行审计。
 * 只记录指纹、状态、耗时、结果数和失败/跳过原因，避免把 query 返回正文塞进审计链路。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class FieldEvidenceQueryExecutionAudit {

    private String queryFingerprint;
    private String fieldName;
    private String sourceType;
    private String evidencePathKey;
    private String queryIntent;
    private String query;
    private String queryMode;
    private String profileStage;
    private String searchDepth;
    private Boolean includeRawContent;
    private String status;
    private Long elapsedMillis;
    private Integer resultCount;
    private String tavilyRequestId;
    private String skipReason;
    private String failureReason;
}
