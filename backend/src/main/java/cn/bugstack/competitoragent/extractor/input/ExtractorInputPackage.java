package cn.bugstack.competitoragent.extractor.input;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * extractor 节点一次执行所消费的正式输入包。
 * 该对象用于显式记录“当前任务、当前 schema、当前竞品证据和预算现场”，
 * 为后续 rerun / replay / analyzer 边界收口提供统一事实源。
 */
@Data
@Builder
public class ExtractorInputPackage {

    private Long taskId;

    private String nodeName;

    private Long planVersionId;

    private String branchKey;

    private Long schemaId;

    private List<String> dimensions;

    private String inputSource;

    private Map<String, Object> auditRefs;

    private List<ExtractorCompetitorInput> competitors;
}
