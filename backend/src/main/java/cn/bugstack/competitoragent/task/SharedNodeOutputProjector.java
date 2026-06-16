package cn.bugstack.competitoragent.task;

/**
 * 节点共享输出投影器。
 * <p>
 * DagExecutor 只依赖这个通用策略接口，不直接写搜索链路特判。
 */
public interface SharedNodeOutputProjector {

    boolean supports(String outputData);

    SharedNodeOutputEnvelope project(Long taskId,
                                     String nodeName,
                                     Long planVersionId,
                                     String outputData);
}
