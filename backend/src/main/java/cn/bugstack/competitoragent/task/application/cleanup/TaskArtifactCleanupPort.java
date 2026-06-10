package cn.bugstack.competitoragent.task.application.cleanup;

/**
 * 任务附属数据清理模块端口。
 * <p>
 * phase3a 先把 task 侧清理入口统一成可扩展端口，
 * 后续 collection / knowledge / report / conversation 等模块都通过同一接口接入。
 */
public interface TaskArtifactCleanupPort {

    /**
     * 返回当前清理端口代表的模块名，便于后续排查是哪个模块参与了清理编排。
     */
    String moduleName();

    /**
     * 清理整个任务维度的附属产物。
     */
    void cleanupTaskArtifacts(Long taskId);

    /**
     * 清理节点重跑时需要失效的附属产物。
     * <p>
     * 默认实现保持 no-op，允许尚未支持节点级清理的模块先只接入任务级清理。
     */
    default void cleanupNodeArtifacts(Long taskId, String nodeName) {
    }
}
