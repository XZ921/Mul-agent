package cn.bugstack.competitoragent.collection.application;

import cn.bugstack.competitoragent.model.dto.ReportResponse;

import java.util.List;

/**
 * collection-intelligence 对外暴露的稳定证据读取门面。
 * <p>
 * phase3b 先把 task / report / conversation 后续会共用的证据查询入口固定下来，
 * 避免继续把 report 包内的实现细节直接泄露给跨模块调用方。
 */
public interface CollectionEvidenceFacade {

    List<ReportResponse.EvidenceInfo> listTaskEvidence(Long taskId);

    List<ReportResponse.EvidenceInfo> listNodeEvidence(Long taskId, String nodeName);

    ReportResponse.EvidenceEntryPointInfo getEvidenceEntryPoint(Long taskId);
}
