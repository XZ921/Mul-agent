package cn.bugstack.competitoragent.extractor.input;

import cn.bugstack.competitoragent.workflow.contract.DownstreamEvidenceView;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 单个竞品在 extractor 运行期的证据输入快照。
 * 这里既保留“实际进入 Prompt 的证据”，也保留“被跳过的证据与预算状态”，
 * 便于后续解释本轮到底用了什么、为什么没用某些证据。
 */
@Data
@Builder
public class ExtractorCompetitorInput {

    private String competitorName;

    private List<DownstreamEvidenceView> evidenceCatalog;

    private List<DownstreamEvidenceView> structuredEvidence;

    private List<DownstreamEvidenceView> readableEvidence;

    private List<DownstreamEvidenceView> skippedEvidence;

    private List<String> sourceUrls;

    private List<String> issueFlags;

    private Map<String, Object> budget;
}
