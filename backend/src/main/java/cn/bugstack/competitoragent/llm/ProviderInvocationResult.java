package cn.bugstack.competitoragent.llm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Provider 调用结果。
 * <p>
 * 统一返回模型名、Token 用量和业务负载，方便网关后续叠加预算、审计和降级治理。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProviderInvocationResult<T> {

    private String providerKey;
    private String modelName;
    private TokenUsage tokenUsage;
    private T payload;
}
