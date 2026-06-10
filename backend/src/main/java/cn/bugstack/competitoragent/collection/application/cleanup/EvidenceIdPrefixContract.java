package cn.bugstack.competitoragent.collection.application.cleanup;

/**
 * 证据前缀契约。
 * <p>
 * phase3b 先把“taskId + nodeName -> evidenceId 前缀”的编码规则收口到 collection 内部，
 * 通过 contract test 锁定当前算法，避免节点级删除与采集侧 evidenceId 编码逐步漂移。
 */
public final class EvidenceIdPrefixContract {

    private EvidenceIdPrefixContract() {
    }

    public static String build(Long taskId, String nodeName) {
        long safeTaskId = taskId == null ? 0L : taskId;
        String safeNodeName = nodeName == null || nodeName.isBlank()
                ? "NODE"
                : nodeName.toUpperCase().replaceAll("[^A-Z0-9]+", "_");
        return String.format("T%04d-%s-", safeTaskId % 10000, safeNodeName);
    }
}
