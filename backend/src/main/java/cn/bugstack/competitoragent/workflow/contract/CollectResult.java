package cn.bugstack.competitoragent.workflow.contract;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 采集节点输出契约，传递给抽取节点。
 */
@Data
@Builder
public class CollectResult {

    private final String contractVersion = "1.0";

    /** 本次采集的文档总数 */
    private int totalCollected;

    /** 生成的证据编号总数 */
    private int totalEvidenceIds;

    /** 采集结果明细 */
    private List<CollectedDocument> documents;
}
