package cn.bugstack.competitoragent.workflow.contract;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 抽取节点输出契约，传递给分析节点。
 */
@Data
@Builder
public class ExtractResult {

    private final String contractVersion = "1.0";

    /** 成功抽取的竞品数量 */
    private int totalCompetitors;

    /** 每个竞品的结构化知识草稿 */
    private List<CompetitorKnowledgeDraft> drafts;
}
