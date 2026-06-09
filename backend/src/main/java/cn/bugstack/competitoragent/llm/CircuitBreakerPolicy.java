package cn.bugstack.competitoragent.llm;

import cn.bugstack.competitoragent.config.AiProviderProperties;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 熔断策略骨架。
 * <p>
 * Task 5.1.a 先提供统一接口，让网关具备后续挂载熔断治理的稳定位置；
 * 具体熔断阈值与打开窗口会在后续 TDD 循环中补齐。
 */
@Component
public class CircuitBreakerPolicy {

    private final int failureThreshold;
    private final long openWindowMillis;
    private final Map<String, FailureState> failureStates = new ConcurrentHashMap<>();

    public CircuitBreakerPolicy(AiProviderProperties aiProviderProperties) {
        // 当前先使用保守默认值：连续失败达到 maxRetries 后短暂熔断 30 秒。
        this.failureThreshold = Math.max(1, aiProviderProperties.getMaxRetries());
        this.openWindowMillis = 30_000L;
    }

    public boolean isOpen(String providerKey, AiCapability capability) {
        FailureState failureState = failureStates.get(buildKey(providerKey, capability));
        if (failureState == null) {
            return false;
        }
        if (failureState.openedUntilMillis() <= Instant.now().toEpochMilli()) {
            failureStates.remove(buildKey(providerKey, capability));
            return false;
        }
        return true;
    }

    public void recordSuccess(String providerKey, AiCapability capability) {
        failureStates.remove(buildKey(providerKey, capability));
    }

    public void recordFailure(String providerKey, AiCapability capability) {
        String key = buildKey(providerKey, capability);
        FailureState currentState = failureStates.get(key);
        int nextFailureCount = currentState == null ? 1 : currentState.failureCount() + 1;
        long openedUntilMillis = nextFailureCount >= failureThreshold
                ? Instant.now().toEpochMilli() + openWindowMillis
                : 0L;
        failureStates.put(key, new FailureState(nextFailureCount, openedUntilMillis));
    }

    private String buildKey(String providerKey, AiCapability capability) {
        return providerKey + "::" + capability.name();
    }

    private record FailureState(int failureCount, long openedUntilMillis) {
    }
}
