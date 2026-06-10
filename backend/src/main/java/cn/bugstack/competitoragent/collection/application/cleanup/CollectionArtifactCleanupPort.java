package cn.bugstack.competitoragent.collection.application.cleanup;

import cn.bugstack.competitoragent.repository.EvidenceSourceRepository;
import cn.bugstack.competitoragent.task.application.cleanup.TaskArtifactCleanupPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * collection 模块证据清理端口。
 * <p>
 * task-orchestration 只感知统一 cleanup coordinator，
 * 具体到 evidence 表的任务级/节点级删除，都由 collection 自己通过端口承接。
 */
@Component
@RequiredArgsConstructor
public class CollectionArtifactCleanupPort implements TaskArtifactCleanupPort {

    private final EvidenceSourceRepository evidenceSourceRepository;

    @Override
    public String moduleName() {
        return "collection";
    }

    @Override
    public void cleanupTaskArtifacts(Long taskId) {
        evidenceSourceRepository.deleteByTaskId(taskId);
    }

    @Override
    public void cleanupNodeArtifacts(Long taskId, String nodeName) {
        evidenceSourceRepository.deleteByTaskIdAndEvidenceIdStartingWith(
                taskId,
                EvidenceIdPrefixContract.build(taskId, nodeName)
        );
    }
}
