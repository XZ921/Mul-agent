package cn.bugstack.competitoragent.collection;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 采集执行器注册表。
 * 集中负责“任务包该由谁执行”的决策，避免上层协同器和 Agent 重复维护路由分支。
 */
@Component
public class CollectionExecutorRegistry {

    private final List<CollectionExecutor> executors;

    public CollectionExecutorRegistry(List<CollectionExecutor> executors) {
        this.executors = executors == null ? List.of() : List.copyOf(executors);
    }

    public CollectionExecutor resolve(CollectionTaskPackage taskPackage) {
        return executors.stream()
                .filter(executor -> executor != null && executor.supports(taskPackage))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no collection executor matched task package"));
    }
}
