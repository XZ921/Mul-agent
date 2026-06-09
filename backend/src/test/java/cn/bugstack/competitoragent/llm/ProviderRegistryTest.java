package cn.bugstack.competitoragent.llm;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderRegistryTest {

    @Test
    void shouldExposeUnifiedDispatchApiWithoutLeakingConcreteProviderAdapter() {
        // Task 5.1.a 的完成标志不是“只多了一个配置解析器”，
        // 而是 Chat / Embedding / Rerank 已经能统一挂到注册表入口，
        // 同时上层不再通过 RegisteredProvider 直接拿到底层 ModelProvider 适配器。
        List<String> registryMethodNames = Arrays.stream(ProviderRegistry.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)
                .toList();

        assertTrue(registryMethodNames.containsAll(List.of("chat", "embed", "rerank")),
                "ProviderRegistry 应提供统一三类能力分发入口，作为后续治理策略的挂载点");

        boolean exposesConcreteAdapter = Arrays.stream(ProviderRegistry.RegisteredProvider.class.getMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .anyMatch(method -> ModelProvider.class.equals(method.getReturnType()));

        assertFalse(exposesConcreteAdapter,
                "RegisteredProvider 不应继续向上暴露具体 ModelProvider，避免业务层感知 Provider 类型");
    }
}
