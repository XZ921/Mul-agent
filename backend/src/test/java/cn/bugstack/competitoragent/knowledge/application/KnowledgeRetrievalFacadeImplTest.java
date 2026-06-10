package cn.bugstack.competitoragent.knowledge.application;

import cn.bugstack.competitoragent.knowledge.KnowledgeDocumentQueryService;
import cn.bugstack.competitoragent.model.dto.KnowledgeDocumentResponse;
import cn.bugstack.competitoragent.rag.TaskRetrievalService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeRetrievalFacadeImplTest {

    private static final String FACADE_IMPL_CLASS =
            "cn.bugstack.competitoragent.knowledge.application.KnowledgeRetrievalFacadeImpl";

    private final KnowledgeDocumentQueryService knowledgeDocumentQueryService = mock(KnowledgeDocumentQueryService.class);
    private final TaskRetrievalService taskRetrievalService = mock(TaskRetrievalService.class);

    @Test
    void should_list_task_knowledge_through_facade() {
        KnowledgeDocumentResponse response = KnowledgeDocumentResponse.builder()
                .id(1L)
                .title("治理手册")
                .sourceUrls(List.of("https://docs.example.com/guide"))
                .build();
        when(knowledgeDocumentQueryService.listByTaskId(5L)).thenReturn(List.of(response));

        Object facade = instantiateFacade();
        List<KnowledgeDocumentResponse> actual = invokeListTaskKnowledge(facade, 5L);

        assertEquals(1, actual.size());
        assertEquals("治理手册", actual.get(0).getTitle());
        verify(knowledgeDocumentQueryService).listByTaskId(5L);
    }

    @Test
    void should_expose_retrieval_result_through_knowledge_facade() {
        TaskRetrievalService.RetrievedChunk retrievedChunk = TaskRetrievalService.RetrievedChunk.builder()
                .documentKey("TASK-DOC-001")
                .retrievalScope("TASK")
                .snippet("检索到治理与发布规范")
                .content("检索到治理与发布规范，并保留来源链接。")
                .sourceUrls(List.of("https://docs.example.com/guide"))
                .build();
        TaskRetrievalService.RetrievalResult result = TaskRetrievalService.RetrievalResult.builder()
                .sourceUrls(List.of("https://docs.example.com/guide"))
                .gapSummary("仍缺少 SLA 细节")
                .chunks(List.of(retrievedChunk))
                .build();
        when(taskRetrievalService.retrieve(5L, "治理规范", "analyze_competitors")).thenReturn(result);

        Object facade = instantiateFacade();
        Object actual = invokeRetrieveForTask(facade, 5L, "治理规范", "analyze_competitors");

        assertEquals(List.of("https://docs.example.com/guide"), readListAccessor(actual, "sourceUrls"));
        assertEquals("仍缺少 SLA 细节", readStringAccessor(actual, "gapSummary"));
        assertEquals(List.of("TASK-DOC-001"), readListAccessor(actual, "hitDocumentIds"));
        assertTrue(readStringAccessor(actual, "answer").contains("检索到治理与发布规范"));
    }

    @Test
    void should_summarize_task_rag_context_through_facade() {
        TaskRetrievalService.RetrievedChunk retrievedChunk = TaskRetrievalService.RetrievedChunk.builder()
                .documentKey("TASK-DOC-009")
                .snippet("任务资料覆盖企业治理主线")
                .content("任务资料覆盖企业治理主线，并保留可追溯来源。")
                .sourceUrls(List.of("https://docs.example.com/task"))
                .build();
        when(taskRetrievalService.retrieve(9L, "企业治理", "write_report")).thenReturn(
                TaskRetrievalService.RetrievalResult.builder()
                        .sourceUrls(List.of("https://docs.example.com/task"))
                        .gapSummary("当前任务资料已覆盖企业治理主线。")
                        .chunks(List.of(retrievedChunk))
                        .build()
        );

        Object facade = instantiateFacade();
        String summary = invokeSummarizeTaskRagContext(facade, 9L, "企业治理", "write_report");

        assertTrue(summary.contains("任务资料覆盖企业治理主线"));
    }

    private Object instantiateFacade() {
        try {
            Class<?> type = Class.forName(FACADE_IMPL_CLASS);
            Constructor<?> constructor = type.getDeclaredConstructor(
                    KnowledgeDocumentQueryService.class,
                    TaskRetrievalService.class
            );
            constructor.setAccessible(true);
            return constructor.newInstance(knowledgeDocumentQueryService, taskRetrievalService);
        } catch (ReflectiveOperationException e) {
            fail("phase4a Task 1 要求存在 KnowledgeRetrievalFacadeImpl，并通过 KnowledgeDocumentQueryService 与 TaskRetrievalService 包装知识读取入口", e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<KnowledgeDocumentResponse> invokeListTaskKnowledge(Object facade, Long taskId) {
        try {
            Method method = facade.getClass().getMethod("listTaskKnowledge", Long.class);
            return (List<KnowledgeDocumentResponse>) method.invoke(facade, taskId);
        } catch (ReflectiveOperationException e) {
            fail("phase4a Task 1 缺少 listTaskKnowledge(Long) facade 入口", e);
            return List.of();
        }
    }

    private Object invokeRetrieveForTask(Object facade, Long taskId, String query, String nodeName) {
        try {
            Method method = facade.getClass().getMethod("retrieveForTask", Long.class, String.class, String.class);
            return method.invoke(facade, taskId, query, nodeName);
        } catch (ReflectiveOperationException e) {
            fail("phase4a Task 1 缺少 retrieveForTask(Long, String, String) facade 入口", e);
            return null;
        }
    }

    private String invokeSummarizeTaskRagContext(Object facade, Long taskId, String query, String nodeName) {
        try {
            Method method = facade.getClass().getMethod("summarizeTaskRagContext", Long.class, String.class, String.class);
            return String.valueOf(method.invoke(facade, taskId, query, nodeName));
        } catch (ReflectiveOperationException e) {
            fail("phase4a Task 1 缺少 summarizeTaskRagContext(Long, String, String) facade 入口", e);
            return "";
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> readListAccessor(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            return (List<String>) method.invoke(target);
        } catch (ReflectiveOperationException e) {
            fail("phase4a Task 1 缺少 RetrievalResultView 访问器：" + methodName, e);
            return List.of();
        }
    }

    private String readStringAccessor(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            return String.valueOf(method.invoke(target));
        } catch (ReflectiveOperationException e) {
            fail("phase4a Task 1 缺少 RetrievalResultView 访问器：" + methodName, e);
            return "";
        }
    }
}
