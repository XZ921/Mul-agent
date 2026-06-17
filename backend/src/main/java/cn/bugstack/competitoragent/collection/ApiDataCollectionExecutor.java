package cn.bugstack.competitoragent.collection;

/**
 * API 型结构化采集执行器抽象基类。
 * 先统一 API_DATA 的 executorType，后续 GitHub / News / RSS 可以在同一层扩展。
 */
public abstract class ApiDataCollectionExecutor implements CollectionExecutor {

    @Override
    public String executorType() {
        return "API_DATA";
    }
}
