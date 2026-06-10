package cn.bugstack.competitoragent.collection.application.cleanup;

import cn.bugstack.competitoragent.repository.EvidenceSourceRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CollectionArtifactCleanupPortTest {

    private static final String CLEANUP_PORT_CLASS =
            "cn.bugstack.competitoragent.collection.application.cleanup.CollectionArtifactCleanupPort";
    private static final String PREFIX_CONTRACT_CLASS =
            "cn.bugstack.competitoragent.collection.application.cleanup.EvidenceIdPrefixContract";

    private final EvidenceSourceRepository evidenceSourceRepository = mock(EvidenceSourceRepository.class);

    @Test
    void should_delete_task_evidence_by_task_id() {
        Object cleanupPort = instantiateCleanupPort();

        invokeVoid(cleanupPort, "cleanupTaskArtifacts", new Class<?>[] {Long.class}, 12L);

        verify(evidenceSourceRepository).deleteByTaskId(12L);
    }

    @Test
    void should_delete_node_evidence_by_encoded_prefix() {
        Object cleanupPort = instantiateCleanupPort();

        invokeVoid(cleanupPort, "cleanupNodeArtifacts", new Class<?>[] {Long.class, String.class}, 12L, "collect_sources_01_01");

        verify(evidenceSourceRepository)
                .deleteByTaskIdAndEvidenceIdStartingWith(12L, "T0012-COLLECT_SOURCES_01_01-");
    }

    @Test
    void should_keep_same_evidence_prefix_contract_as_task_runtime_cleanup() {
        String prefix = invokePrefixBuild(12L, "collect_sources");

        assertEquals("T0012-COLLECT_SOURCES-", prefix);
    }

    private Object instantiateCleanupPort() {
        try {
            Class<?> type = Class.forName(CLEANUP_PORT_CLASS);
            Constructor<?> constructor = type.getDeclaredConstructor(EvidenceSourceRepository.class);
            constructor.setAccessible(true);
            return constructor.newInstance(evidenceSourceRepository);
        } catch (ReflectiveOperationException e) {
            fail("phase3b Task 2 要求存在 CollectionArtifactCleanupPort，并通过 EvidenceSourceRepository 承接证据删除", e);
            return null;
        }
    }

    private void invokeVoid(Object target, String methodName, Class<?>[] parameterTypes, Object... args) {
        try {
            Method method = target.getClass().getMethod(methodName, parameterTypes);
            method.invoke(target, args);
        } catch (ReflectiveOperationException e) {
            fail("phase3b Task 2 缺少方法：" + methodName, e);
        }
    }

    private String invokePrefixBuild(Long taskId, String nodeName) {
        try {
            Class<?> type = Class.forName(PREFIX_CONTRACT_CLASS);
            Method method = type.getDeclaredMethod("build", Long.class, String.class);
            method.setAccessible(true);
            return String.valueOf(method.invoke(null, taskId, nodeName));
        } catch (ReflectiveOperationException e) {
            fail("phase3b Task 2 要求存在 EvidenceIdPrefixContract.build(Long, String) 锁定证据前缀算法", e);
            return null;
        }
    }
}
