package cn.bugstack.competitoragent.agent;

import cn.bugstack.competitoragent.agent.capability.AgentExecutionRequest;
import cn.bugstack.competitoragent.agent.capability.AgentExecutionResponse;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Phase 1 runtime 合同测试。
 * 这里用反射锁定最小字段集，避免后续重构时把业务字段、仓储依赖或实体类型重新塞回 runtime 基线。
 */
class AgentRuntimeContractTest {

    @Test
    void agentContext_should_keep_only_runtime_fields() {
        assertEquals(Set.of(
                "taskId",
                "taskName",
                "subjectProduct",
                "competitorNames",
                "competitorUrls",
                "analysisDimensions",
                "sourceScope",
                "reportLanguage",
                "reportTemplate",
                "currentNodeName",
                "currentNodeConfig",
                "traceId",
                "planVersionId",
                "branchKey",
                "taskRagContextBundle",
                "sharedState",
                "createdAt"
        ), declaredFieldNames(AgentContext.class));
    }

    @Test
    void agentResult_should_keep_only_runtime_result_fields() {
        assertEquals(Set.of(
                "status",
                "outputData",
                "outputSummary",
                "durationMs",
                "reasoningSummary",
                "tokenUsage",
                "modelName",
                "promptUsed",
                "errorMessage"
        ), declaredFieldNames(AgentResult.class));
    }

    @Test
    void agentExecutionRequest_should_only_wrap_context() {
        assertEquals(Set.of("context"), declaredFieldNames(AgentExecutionRequest.class));
    }

    @Test
    void agentExecutionResponse_should_only_wrap_result() {
        assertEquals(Set.of("result"), declaredFieldNames(AgentExecutionResponse.class));
    }

    @Test
    void runtime_contract_should_not_reference_repositories_or_entities() {
        Stream.of(
                        AgentContext.class,
                        AgentResult.class,
                        AgentExecutionRequest.class,
                        AgentExecutionResponse.class
                )
                .flatMap(type -> Arrays.stream(type.getDeclaredFields()))
                .filter(field -> !field.isSynthetic())
                .forEach(this::assertFieldDoesNotDependOnRepositoryOrEntity);
    }

    private Set<String> declaredFieldNames(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .map(Field::getName)
                .collect(Collectors.toSet());
    }

    private void assertFieldDoesNotDependOnRepositoryOrEntity(Field field) {
        Package fieldPackage = field.getType().getPackage();
        String packageName = fieldPackage == null ? "" : fieldPackage.getName();

        assertFalse(packageName.contains(".repository"),
                () -> "runtime 字段不应直接依赖 repository: " + field.getDeclaringClass().getSimpleName() + "." + field.getName());
        assertFalse(packageName.contains(".model.entity"),
                () -> "runtime 字段不应直接依赖 entity: " + field.getDeclaringClass().getSimpleName() + "." + field.getName());
    }
}
