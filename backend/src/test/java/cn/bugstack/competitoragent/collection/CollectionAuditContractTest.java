package cn.bugstack.competitoragent.collection;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 第六轮 Task 1 的 collection 正式契约红灯测试。
 * 这里先把 collection 审计快照、回放时间线、摘要对象以及包级稳定身份字段显式钉住，
 * 避免后续实现时继续把采集阶段维持在“只有结果列表、没有可回放语义”的临时状态。
 */
class CollectionAuditContractTest {

    @Test
    void shouldProvideFormalCollectionAuditContracts() {
        SoftAssertions softly = new SoftAssertions();

        assertFieldPresent(CollectionTaskPackage.class, "packageKey", softly);
        assertFieldPresent(CollectionTaskPackage.class, "targetIndex", softly);
        assertFieldPresent(CollectionTaskPackage.class, "discoveryDepth", softly);

        assertFieldPresent(CollectionExecutionResult.class, "taskPackageKey", softly);
        assertFieldPresent(CollectionExecutionResult.class, "targetIndex", softly);
        assertFieldPresent(CollectionExecutionResult.class, "status", softly);
        assertFieldPresent(CollectionExecutionResult.class, "reusedFromCheckpoint", softly);
        assertFieldPresent(CollectionExecutionResult.class, "checkpointSource", softly);
        assertFieldPresent(CollectionExecutionResult.class, "discoveredCandidates", softly);
        assertFieldPresent(CollectionExecutionResult.class, "discoveryDepth", softly);

        Class<?> reportClass = assertClassPresent(
                "cn.bugstack.competitoragent.collection.CollectionExecutionReport",
                softly);
        assertFieldPresent(reportClass, "status", softly);
        assertFieldPresent(reportClass, "results", softly);
        assertFieldPresent(reportClass, "auditSnapshot", softly);
        assertFieldPresent(reportClass, "sourceUrls", softly);

        Class<?> auditClass = assertClassPresent(
                "cn.bugstack.competitoragent.collection.CollectionAuditSnapshot",
                softly);
        assertFieldPresent(auditClass, "summary", softly);
        assertFieldPresent(auditClass, "status", softly);
        assertFieldPresent(auditClass, "results", softly);
        assertFieldPresent(auditClass, "replayTimeline", softly);
        assertFieldPresent(auditClass, "recoveryCheckpoint", softly);
        assertFieldPresent(auditClass, "sourceUrls", softly);

        Class<?> timelineClass = assertClassPresent(
                "cn.bugstack.competitoragent.collection.CollectionReplayTimelineItem",
                softly);
        assertFieldPresent(timelineClass, "taskPackageKey", softly);
        assertFieldPresent(timelineClass, "targetIndex", softly);
        assertFieldPresent(timelineClass, "status", softly);
        assertFieldPresent(timelineClass, "executorType", softly);
        assertFieldPresent(timelineClass, "sourceUrls", softly);

        Class<?> summaryClass = assertClassPresent(
                "cn.bugstack.competitoragent.model.dto.CollectionAuditSummary",
                softly);
        assertFieldPresent(summaryClass, "totalPackages", softly);
        assertFieldPresent(summaryClass, "successCount", softly);
        assertFieldPresent(summaryClass, "status", softly);
        assertFieldPresent(summaryClass, "recoveryCheckpoint", softly);
        assertFieldPresent(summaryClass, "sourceUrls", softly);

        if (summaryClass != null && auditClass != null) {
            assertMethodPresent(summaryClass, "from", auditClass, softly);
        }

        softly.assertAll();
    }

    /**
     * 这里使用反射锁定正式字段，避免类尚未创建时测试直接编译失败，
     * 让红灯明确落在“契约缺失”而不是“测试文件无法编译”。
     */
    private Class<?> assertClassPresent(String className, SoftAssertions softly) {
        try {
            Class<?> targetClass = Class.forName(className);
            softly.assertThat(targetClass)
                    .as("应存在类 %s", className)
                    .isNotNull();
            return targetClass;
        } catch (ClassNotFoundException exception) {
            softly.fail("应存在类 %s，但当前尚未实现: %s", className, exception.getMessage());
            return null;
        }
    }

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

    private void assertMethodPresent(Class<?> targetClass,
                                     String methodName,
                                     Class<?> parameterType,
                                     SoftAssertions softly) {
        if (targetClass == null || parameterType == null) {
            return;
        }
        try {
            Method method = targetClass.getDeclaredMethod(methodName, parameterType);
            softly.assertThat(method)
                    .as("%s 应声明方法 %s(%s)", targetClass.getSimpleName(), methodName, parameterType.getSimpleName())
                    .isNotNull();
        } catch (NoSuchMethodException exception) {
            softly.fail("%s 应声明方法 %s(%s)",
                    targetClass.getSimpleName(),
                    methodName,
                    parameterType.getSimpleName());
        }
    }
}
