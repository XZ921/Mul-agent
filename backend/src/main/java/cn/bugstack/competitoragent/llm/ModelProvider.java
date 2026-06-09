package cn.bugstack.competitoragent.llm;

/**
 * Provider 适配器统一抽象。
 * <p>
 * 业务层不再直接依赖具体 SDK，而是由 ModelGateway 通过本接口调度底层 Provider。
 */
public interface ModelProvider {

    /**
     * 返回当前适配器能够处理的 provider 类型标识。
     */
    String getAdapterType();

    ProviderInvocationResult<String> chat(ProviderInvocationRequest request);

    ProviderInvocationResult<java.util.List<Float>> embed(ProviderInvocationRequest request);

    ProviderInvocationResult<java.util.List<RerankClient.RerankRecord>> rerank(ProviderInvocationRequest request);
}
