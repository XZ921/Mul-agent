package cn.bugstack.competitoragent.collection.application;

import cn.bugstack.competitoragent.model.dto.ReportResponse;
import cn.bugstack.competitoragent.report.EvidenceQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * `CollectionEvidenceFacade` 的最小落地实现。
 * <p>
 * phase3b Task 1 先通过包裹 `EvidenceQueryService` 固定跨模块读取入口，
 * 不在这一阶段重写 report 侧既有投影逻辑，先保证行为稳定再继续后续收口。
 */
@Service
@RequiredArgsConstructor
public class CollectionEvidenceFacadeImpl implements CollectionEvidenceFacade {

    private final EvidenceQueryService evidenceQueryService;

    @Override
    public List<ReportResponse.EvidenceInfo> listTaskEvidence(Long taskId) {
        return evidenceQueryService.listTaskEvidence(taskId);
    }

    @Override
    public List<ReportResponse.EvidenceInfo> listNodeEvidence(Long taskId, String nodeName) {
        return evidenceQueryService.listEvidencesByNode(taskId, nodeName);
    }

    @Override
    public ReportResponse.EvidenceEntryPointInfo getEvidenceEntryPoint(Long taskId) {
        List<ReportResponse.EvidenceInfo> evidences = evidenceQueryService.listTaskEvidence(taskId);
        return evidenceQueryService.toEvidenceEntryPointInfo(evidences, List.of());
    }
}
