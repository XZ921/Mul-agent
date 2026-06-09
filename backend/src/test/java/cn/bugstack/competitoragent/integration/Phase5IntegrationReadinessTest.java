package cn.bugstack.competitoragent.integration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 5.9.a 最小黑盒回归测试。
 * <p>
 * 这条测试只验证第五阶段联调准备是否具备“可被后续测试消费”的最小入口：
 * 1. Phase 5 联调 profile 已存在；
 * 2. 主链路与治理链路的场景矩阵已落文；
 * 3. 场景矩阵中已经提前声明后续自动化测试入口，避免 Task 5.9.b / 5.9.c 联调时再临时补口径。
 */
class Phase5IntegrationReadinessTest {

    @Test
    void shouldProvidePhase5ScenarioMatrixAndIntegrationProfile() throws IOException {
        String integrationProfile = readClasspathResource("application-phase5-integration.yml");
        assertTrue(integrationProfile.contains("spring:"), "Task 5.9.a 需要提供 Phase 5 联调 profile。");
        assertTrue(integrationProfile.contains("rocketmq:"), "联调 profile 需要声明消息链路相关配置。");
        assertTrue(integrationProfile.contains("redis:"), "联调 profile 需要声明快照/恢复相关配置。");

        String scenarioMatrix = readClasspathResource("phase5-integration-scenarios.md");
        assertTrue(scenarioMatrix.contains("组织资料接入 -> 跨层召回 -> 记忆复用 -> 完整对话动作预览 / 确认 -> 回放与恢复 -> 正式导出"),
                "场景矩阵必须明确主链路。");
        assertTrue(scenarioMatrix.contains("组织级配额阻断 / 连接器忙碌 -> 用户可读提示 -> 占位释放后恢复执行或重试导出"),
                "场景矩阵必须明确治理链路。");
        assertTrue(scenarioMatrix.contains("Phase5EnterpriseDeliveryIntegrationTest"),
                "场景矩阵必须声明主链路后端测试入口。");
        assertTrue(scenarioMatrix.contains("Phase5ConversationRoutingIntegrationTest"),
                "场景矩阵必须声明治理或对话相关测试入口。");
    }

    /**
     * 统一用类路径读取测试资源，确保这条黑盒测试只依赖最终交付物，
     * 不与具体实现类或未来的测试代码结构耦合。
     */
    private String readClasspathResource(String resourcePath) throws IOException {
        InputStream inputStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath);
        assertNotNull(inputStream, "缺少任务交付物：" + resourcePath);
        return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    }
}
