package cn.bugstack.competitoragent.report;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Task 5.7.a 正式导出契约存在性测试。
 * <p>
 * 这个测试只覆盖当前子任务的完成标志：
 * 1. 已有正式导出记录对象，而不是只保留一次性下载动作；
 * 2. 导出记录已经显式建模导出版本号与 sourceUrls；
 * 3. 基本导出 API、服务骨架与迁移脚本已经具备后续扩展入口。
 */
class ReportExportContractPresenceTest {

    @Test
    void shouldProvideFormalExportRecordVersioningAndBasicDeliveryApiSkeleton() throws Exception {
        SoftAssertions softly = new SoftAssertions();

        Class<?> exportRecordClass = assertClassPresent(
                "cn.bugstack.competitoragent.model.entity.ReportExportRecord",
                softly);
        assertFieldPresent(exportRecordClass, "taskId", softly);
        assertFieldPresent(exportRecordClass, "exportVersion", softly);
        assertFieldPresent(exportRecordClass, "sourceUrls", softly);

        Class<?> exportResponseClass = assertClassPresent(
                "cn.bugstack.competitoragent.model.dto.ReportExportResponse",
                softly);
        assertFieldPresent(exportResponseClass, "taskId", softly);
        assertFieldPresent(exportResponseClass, "exportVersion", softly);
        assertFieldPresent(exportResponseClass, "sourceUrls", softly);

        Class<?> repositoryClass = assertClassPresent(
                "cn.bugstack.competitoragent.repository.ReportExportRecordRepository",
                softly);
        assertMethodPresent(repositoryClass, "findByTaskIdOrderByCreatedAtDesc", Long.class, softly);

        Class<?> serviceClass = assertClassPresent(
                "cn.bugstack.competitoragent.report.ExportPackageService",
                softly);
        assertMethodPresent(serviceClass, "listTaskExports", Long.class, softly);

        Class<?> controllerClass = assertClassPresent(
                "cn.bugstack.competitoragent.controller.DeliveryController",
                softly);
        assertMethodPresent(controllerClass, "listTaskExports", Long.class, softly);

        Path migrationDirectory = Path.of("src", "main", "resources", "db", "migration");
        Path migrationPath = findReportExportMigration(migrationDirectory);
        softly.assertThat(migrationPath)
                .as("应新增正式导出记录表迁移脚本")
                .isNotNull();

        if (migrationPath != null && Files.exists(migrationPath)) {
            String migrationContent = Files.readString(migrationPath);
            softly.assertThat(migrationContent)
                    .as("导出记录脚本应创建 report_export_record 正式表")
                    .contains("report_export_record");
            softly.assertThat(migrationContent)
                    .as("导出记录脚本应包含 export_version 字段")
                    .contains("export_version");
            softly.assertThat(migrationContent)
                    .as("导出记录脚本应包含 source_urls 字段")
                    .contains("source_urls");
        }

        softly.assertAll();
    }

    /**
     * 通过反射判断类是否存在，避免在 Red 阶段因为类型尚未实现导致测试编译直接中断。
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
     * 当前子任务只校验关键字段是否已经显式建模，避免把后续 5.7.b / 5.7.c 的结构提前锁死。
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
     * 服务和控制器在 5.7.a 只要求暴露最小读取入口，导出渲染细节留给后续子任务补齐。
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
                    .as("%s 应声明方法 %s(%s)",
                            targetClass.getSimpleName(),
                            methodName,
                            parameterType.getSimpleName())
                    .isNotNull();
        } catch (NoSuchMethodException exception) {
            softly.fail("%s 应声明方法 %s(%s)",
                    targetClass.getSimpleName(),
                    methodName,
                    parameterType.getSimpleName());
        }
    }

    /**
     * Flyway 版本号可能因为并行任务修复发生调整，因此这里只按“导出记录表语义”查找脚本，
     * 避免把测试错误地绑定到某一个具体版本号。
     */
    private Path findReportExportMigration(Path migrationDirectory) throws Exception {
        if (!Files.isDirectory(migrationDirectory)) {
            return null;
        }
        try (var paths = Files.list(migrationDirectory)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().contains("report_export_record"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .findFirst()
                    .orElseGet(() -> {
                        try {
                            return Arrays.stream(migrationDirectory.toFile().listFiles())
                                    .filter(file -> file.getName().toLowerCase().contains("report_export_record"))
                                    .map(file -> file.toPath())
                                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                                    .findFirst()
                                    .orElse(null);
                        } catch (Exception ignored) {
                            return null;
                        }
                    });
        }
    }
}
