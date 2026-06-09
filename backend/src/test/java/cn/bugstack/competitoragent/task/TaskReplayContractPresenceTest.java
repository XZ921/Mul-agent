package cn.bugstack.competitoragent.task;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Task 5.6.a 回放契约存在性测试。
 * <p>
 * 这个测试只覆盖当前子任务的完成标志：
 * 1. 回放时间线已有正式 DTO 承载对象；
 * 2. 恢复点已有正式 Service 与表结构；
 * 3. 回放对象能够显式关联计划版本，并保留 sourceUrls 追溯字段。
 */
class TaskReplayContractPresenceTest {

    @Test
    void shouldProvideFormalReplayContractsForTimelineCheckpointAndPlanVersionAssociation() throws Exception {
        SoftAssertions softly = new SoftAssertions();

        Class<?> replayResponseClass = assertClassPresent(
                "cn.bugstack.competitoragent.model.dto.TaskReplayResponse",
                softly);
        assertFieldPresent(replayResponseClass, "timeline", softly);
        assertFieldPresent(replayResponseClass, "recoveryCheckpoints", softly);
        assertFieldPresent(replayResponseClass, "planVersions", softly);
        assertFieldPresent(replayResponseClass, "sourceUrls", softly);

        Class<?> timelineEventClass = assertClassPresent(
                "cn.bugstack.competitoragent.model.dto.ReplayTimelineEvent",
                softly);
        assertFieldPresent(timelineEventClass, "planVersionId", softly);
        assertFieldPresent(timelineEventClass, "sourceUrls", softly);

        Class<?> checkpointServiceClass = assertClassPresent(
                "cn.bugstack.competitoragent.task.RecoveryCheckpointService",
                softly);
        assertMethodPresent(checkpointServiceClass, "listTaskCheckpoints", Long.class, softly);

        Path migrationPath = Path.of("src", "main", "resources", "db", "migration",
                "V22__create_recovery_checkpoint_table.sql");
        softly.assertThat(Files.exists(migrationPath))
                .as("应新增恢复点表迁移脚本")
                .isTrue();

        if (Files.exists(migrationPath)) {
            String migrationContent = Files.readString(migrationPath);
            softly.assertThat(migrationContent)
                    .as("恢复点表脚本应创建 recovery_checkpoint 正式表")
                    .contains("CREATE TABLE IF NOT EXISTS recovery_checkpoint");
            softly.assertThat(migrationContent)
                    .as("恢复点表脚本应包含计划版本关联字段")
                    .contains("plan_version_id");
            softly.assertThat(migrationContent)
                    .as("恢复点表脚本应包含 source_urls 追溯字段")
                    .contains("source_urls");
        }

        softly.assertAll();
    }

    /**
     * 使用反射断言类型存在，避免因为类尚未实现导致测试编译阶段直接中断，
     * 这样更符合 TDD 的 Red 阶段诉求：先看到“能力缺失”的明确失败信息。
     */
    private Class<?> assertClassPresent(String className, SoftAssertions softly) {
        try {
            Class<?> targetClass = Class.forName(className);
            softly.assertThat(targetClass)
                    .as("应存在类型 %s", className)
                    .isNotNull();
            return targetClass;
        } catch (ClassNotFoundException exception) {
            softly.fail("应存在类型 %s，但当前尚未实现: %s", className, exception.getMessage());
            return null;
        }
    }

    /**
     * DTO / Service 契约只验证当前任务要求的关键字段是否已经显式建模，
     * 不提前约束后续子任务的完整结构，避免测试越界。
     */
    private void assertFieldPresent(Class<?> targetClass, String fieldName, SoftAssertions softly) {
        if (targetClass == null) {
            return;
        }
        try {
            Field field = targetClass.getDeclaredField(fieldName);
            softly.assertThat(field)
                    .as("%s 应声明字段 %s", targetClass.getSimpleName(), fieldName)
                    .isNotNull();
        } catch (NoSuchFieldException exception) {
            softly.fail("%s 应声明字段 %s", targetClass.getSimpleName(), fieldName);
        }
    }

    /**
     * Service 层在 5.6.a 先只要求暴露“按任务读取恢复点”的最小入口，
     * 后续投影拼装与控制接口将在 5.6.b / 5.6.c 再继续展开。
     */
    private void assertMethodPresent(Class<?> targetClass,
                                     String methodName,
                                     Class<?> parameterType,
                                     SoftAssertions softly) {
        if (targetClass == null) {
            return;
        }
        try {
            Method method = targetClass.getDeclaredMethod(methodName, parameterType);
            softly.assertThat(method)
                    .as("%s 应声明方法 %s", targetClass.getSimpleName(), methodName)
                    .isNotNull();
        } catch (NoSuchMethodException exception) {
            softly.fail("%s 应声明方法 %s(%s)",
                    targetClass.getSimpleName(),
                    methodName,
                    parameterType.getSimpleName());
        }
    }
}
