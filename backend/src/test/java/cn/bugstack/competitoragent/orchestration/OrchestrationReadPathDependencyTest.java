package cn.bugstack.competitoragent.orchestration;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 编排审计只读路径的源码依赖门。
 * 报告、导出、回放、对话和 projector 只能解释已持久化事件，查看数据绝不能触发模型、Policy 或执行动作。
 */
class OrchestrationReadPathDependencyTest {

    private static final List<String> READ_PATHS = List.of(
            "cn/bugstack/competitoragent/report/ReportService.java",
            "cn/bugstack/competitoragent/report/ReportExportRenderer.java",
            "cn/bugstack/competitoragent/task/TaskReplayProjectionService.java",
            "cn/bugstack/competitoragent/conversation/ConversationOrchestrationDecisionQueryService.java",
            "cn/bugstack/competitoragent/orchestration/OrchestrationDecisionSummaryProjector.java"
    );

    private static final List<String> FORBIDDEN_DEPENDENCIES = List.of(
            "ModelGateway",
            "LlmOrchestratorDecisionBrain",
            "OrchestratorDecisionBrain",
            "OrchestrationDecisionService",
            "OrchestrationRuntimeDecisionService",
            "DecisionPolicyService",
            "DecisionExecutorAdapter"
    );

    @Test
    void readPathsMustOnlyProjectPersistedFacts() throws Exception {
        Path sourceRoot = Path.of("src", "main", "java");
        for (String relativePath : READ_PATHS) {
            Path sourceFile = sourceRoot.resolve(relativePath);
            assertThat(sourceFile).as("只读路径源码应存在: %s", relativePath).exists();
            String source = Files.readString(sourceFile);
            for (String forbiddenDependency : FORBIDDEN_DEPENDENCIES) {
                assertThat(source)
                        .as("%s 不得依赖 %s", relativePath, forbiddenDependency)
                        .doesNotContain(forbiddenDependency);
            }
        }
    }
}
