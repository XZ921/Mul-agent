package cn.bugstack.competitoragent.collection;

/**
 * 采集执行器统一接口。
 * 让“怎么采集”成为可路由的子域能力，而不是 CollectorAgent 内部的 if/else 分支。
 */
public interface CollectionExecutor {

    /**
     * 执行器类型标识，用于审计、日志和兼容映射。
     */
    String executorType();

    /**
     * 当前执行器是否支持指定任务包。
     */
    boolean supports(CollectionTaskPackage taskPackage);

    /**
     * 执行采集任务。
     */
    CollectionExecutionResult execute(CollectionTaskPackage taskPackage);
}
