package cn.bugstack.competitoragent.collection;

import cn.bugstack.competitoragent.search.SearchCollectionTarget;
import cn.bugstack.competitoragent.source.SourceCandidate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 采集执行协调器。
 * 职责只保留三件事：把 selected targets 转成任务包、找到合适执行器、收集最小执行结果。
 */
@Component
public class CollectionExecutionCoordinator {

    private final CollectionTaskPackageBuilder packageBuilder;
    private final CollectionExecutorRegistry executorRegistry;

    public CollectionExecutionCoordinator(CollectionTaskPackageBuilder packageBuilder,
                                          CollectionExecutorRegistry executorRegistry) {
        this.packageBuilder = packageBuilder;
        this.executorRegistry = executorRegistry;
    }

    public List<CollectionExecutionResult> execute(Long taskId,
                                                   String nodeName,
                                                   Long planVersionId,
                                                   String competitorName,
                                                   List<SearchCollectionTarget> targets) {
        if (targets == null || targets.isEmpty()) {
            return List.of();
        }
        List<CollectionExecutionResult> results = new ArrayList<>();
        for (int index = 0; index < targets.size(); index++) {
            SearchCollectionTarget target = targets.get(index);
            SourceCandidate candidate = target == null ? null : target.getCandidate();
            if (candidate == null) {
                continue;
            }
            CollectionTaskPackage taskPackage = packageBuilder.build(
                    taskId,
                    nodeName,
                    planVersionId,
                    competitorName,
                    candidate,
                    index + 1
            );
            CollectionExecutor executor = executorRegistry.resolve(taskPackage);
            results.add(executor.execute(taskPackage));
        }
        return results;
    }
}
