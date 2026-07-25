package cn.bugstack.competitoragent.workflow;

/**
 * 权威 mutation 无法物化为完整动态计划时抛出的确定性异常。
 * 上层事务必须整体回滚，并在独立失败事务中转入人工接管。
 */
public class DynamicPlanMaterializationException extends RuntimeException {

    public DynamicPlanMaterializationException(String message) {
        super(message);
    }

    public DynamicPlanMaterializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
