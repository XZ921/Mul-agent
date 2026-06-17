package cn.bugstack.competitoragent.collection;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 第六轮 Task 1 的 collection DTO 构造与序列化红灯测试。
 * 这里显式锁定 sourceUrls、summary、replayTimeline、recoveryCheckpoint 不能在正式对象里丢失，
 * 防止后续 replay / checkpoint / insight 多条链路各自复制一份不兼容的结构。
 */
class CollectionAuditSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void shouldSerializeFormalCollectionAuditObjectsWithoutDroppingSourceUrls() throws Exception {
        Class<?> auditClass = loadRequiredClass("cn.bugstack.competitoragent.collection.CollectionAuditSnapshot");
        Class<?> reportClass = loadRequiredClass("cn.bugstack.competitoragent.collection.CollectionExecutionReport");

        Object audit = objectMapper.convertValue(Map.of(
                "summary", Map.of(
                        "totalPackages", 1,
                        "successCount", 1,
                        "status", "SUCCESS",
                        "recoveryCheckpoint", "collect_sources_docs#001",
                        "sourceUrls", List.of("https://docs.example.com/reference")
                ),
                "status", "SUCCESS",
                "results", List.of(Map.of(
                        "taskPackageKey", "collect_sources_docs#001",
                        "targetIndex", 1,
                        "executorType", "WEB_PAGE",
                        "success", true,
                        "status", "SUCCESS",
                        "sourceUrls", List.of("https://docs.example.com/reference")
                )),
                "replayTimeline", List.of(Map.of(
                        "taskPackageKey", "collect_sources_docs#001",
                        "targetIndex", 1,
                        "status", "SUCCESS",
                        "executorType", "WEB_PAGE",
                        "sourceUrls", List.of("https://docs.example.com/reference")
                )),
                "recoveryCheckpoint", "collect_sources_docs#001",
                "sourceUrls", List.of("https://docs.example.com/reference")
        ), auditClass);

        Object report = objectMapper.convertValue(Map.of(
                "status", "SUCCESS",
                "results", List.of(Map.of(
                        "taskPackageKey", "collect_sources_docs#001",
                        "targetIndex", 1,
                        "executorType", "WEB_PAGE",
                        "success", true,
                        "status", "SUCCESS",
                        "sourceUrls", List.of("https://docs.example.com/reference")
                )),
                "auditSnapshot", Map.of(
                        "summary", Map.of(
                                "totalPackages", 1,
                                "successCount", 1,
                                "status", "SUCCESS",
                                "recoveryCheckpoint", "collect_sources_docs#001",
                                "sourceUrls", List.of("https://docs.example.com/reference")
                        ),
                        "status", "SUCCESS",
                        "results", List.of(),
                        "replayTimeline", List.of(),
                        "recoveryCheckpoint", "collect_sources_docs#001",
                        "sourceUrls", List.of("https://docs.example.com/reference")
                ),
                "sourceUrls", List.of("https://docs.example.com/reference")
        ), reportClass);

        String auditJson = objectMapper.writeValueAsString(audit);
        String reportJson = objectMapper.writeValueAsString(report);

        assertThat(auditJson).contains("\"summary\"");
        assertThat(auditJson).contains("\"replayTimeline\"");
        assertThat(auditJson).contains("\"recoveryCheckpoint\":\"collect_sources_docs#001\"");
        assertThat(auditJson).contains("\"sourceUrls\":[\"https://docs.example.com/reference\"]");

        assertThat(reportJson).contains("\"auditSnapshot\"");
        assertThat(reportJson).contains("\"status\":\"SUCCESS\"");
        assertThat(reportJson).contains("\"sourceUrls\":[\"https://docs.example.com/reference\"]");
    }

    private Class<?> loadRequiredClass(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException exception) {
            fail("应存在类型 %s，但当前尚未实现: %s".formatted(className, exception.getMessage()));
            return null;
        }
    }
}
