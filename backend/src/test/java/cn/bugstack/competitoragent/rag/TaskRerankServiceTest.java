package cn.bugstack.competitoragent.rag;

import cn.bugstack.competitoragent.llm.RerankClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskRerankServiceTest {

    private final RerankClient rerankClient = mock(RerankClient.class);
    private final TaskRerankService rerankService = new TaskRerankService(rerankClient);

    @Test
    void shouldKeepScopeOrderWhenRerankScoresCrossLayerChunks() {
        // Task 5.3.c 要锁定的重排行为是：
        // 外部重排可以在同层候选内调整顺序，但不能把组织级片段排到任务级片段前面，
        // 否则会破坏 Task -> Domain -> Organization 的默认召回顺序与解释边界。
        TaskRetrievalService.RetrievedChunk taskChunk = TaskRetrievalService.RetrievedChunk.builder()
                .chunkKey("TASK-DOC-001#T-001")
                .retrievalScope("TASK")
                .evidenceId("TASK-E-001")
                .snippet("task scoped rollout")
                .score(0.92D)
                .sourceUrls(List.of("https://task.example.com/notion-task"))
                .build();
        TaskRetrievalService.RetrievedChunk domainChunk = TaskRetrievalService.RetrievedChunk.builder()
                .chunkKey("DOMAIN-DOC-001#D-001")
                .retrievalScope("DOMAIN")
                .evidenceId("DOMAIN-E-001")
                .snippet("domain governance")
                .score(0.75D)
                .sourceUrls(List.of("https://domain.example.com/governance"))
                .build();
        TaskRetrievalService.RetrievedChunk organizationChunk = TaskRetrievalService.RetrievedChunk.builder()
                .chunkKey("ORG-DOC-001#O-001")
                .retrievalScope("ORGANIZATION")
                .evidenceId("ORG-E-001")
                .snippet("organization playbook")
                .score(0.66D)
                .sourceUrls(List.of("https://org.example.com/playbook"))
                .build();
        when(rerankClient.rerank(eq("Notion AI governance playbook"), anyList()))
                .thenReturn(List.of(
                        new RerankClient.RerankRecord(2, 0.99D),
                        new RerankClient.RerankRecord(1, 0.88D),
                        new RerankClient.RerankRecord(0, 0.12D)
                ));

        List<TaskRetrievalService.RetrievedChunk> rerankedChunks = rerankService.rerank(
                "Notion AI governance playbook",
                List.of(taskChunk, domainChunk, organizationChunk),
                "analyze_competitors"
        );

        assertEquals(3, rerankedChunks.size());
        assertEquals("TASK-E-001", rerankedChunks.get(0).getEvidenceId());
        assertEquals("DOMAIN-E-001", rerankedChunks.get(1).getEvidenceId());
        assertEquals("ORG-E-001", rerankedChunks.get(2).getEvidenceId());
        assertEquals(0.99D, rerankedChunks.get(2).getScore());
    }

    @Test
    void shouldDegradeToOriginalRankingWithoutBusinessLevelRetryWhenRerankFails() {
        // 当前测试用于锁定 Task 5.1 的治理边界：
        // 一旦网关调用失败，业务层只做降级，不再自带重复重试。
        // 重排失败时不能中断任务主链路，必须在重试后回退到原始召回顺序，并显式标记降级事实。
        TaskRetrievalService.RetrievedChunk firstChunk = TaskRetrievalService.RetrievedChunk.builder()
                .chunkKey("DOC-001#CHUNK-001")
                .evidenceId("E001")
                .snippet("security governance")
                .score(0.92D)
                .sourceUrls(List.of("https://docs.notion.so/security"))
                .build();
        TaskRetrievalService.RetrievedChunk secondChunk = TaskRetrievalService.RetrievedChunk.builder()
                .chunkKey("DOC-001#CHUNK-002")
                .evidenceId("E002")
                .snippet("pricing summary")
                .score(0.65D)
                .sourceUrls(List.of("https://docs.notion.so/pricing"))
                .build();
        when(rerankClient.rerank(eq("Notion AI pricing governance"), anyList()))
                .thenThrow(new RuntimeException("timeout"))
                .thenThrow(new RuntimeException("timeout"));

        List<TaskRetrievalService.RetrievedChunk> rerankedChunks = rerankService.rerank(
                "Notion AI pricing governance",
                List.of(firstChunk, secondChunk),
                "analyze_competitors"
        );

        assertEquals(2, rerankedChunks.size());
        assertEquals("E001", rerankedChunks.get(0).getEvidenceId());
        assertTrue(rerankedChunks.get(0).getIssueFlags().contains("RERANK_DEGRADED"));
        verify(rerankClient, times(1)).rerank(eq("Notion AI pricing governance"), anyList());
    }

    @Test
    void shouldApplyRerankScoreAndKeepTraceabilityFields() {
        // 一旦外部重排成功，服务只调整顺序与分数，不丢失 evidenceId、sourceUrls 等可追溯字段。
        TaskRetrievalService.RetrievedChunk firstChunk = TaskRetrievalService.RetrievedChunk.builder()
                .chunkKey("DOC-001#CHUNK-001")
                .evidenceId("E001")
                .snippet("security governance")
                .score(0.92D)
                .sourceUrls(List.of("https://docs.notion.so/security"))
                .build();
        TaskRetrievalService.RetrievedChunk secondChunk = TaskRetrievalService.RetrievedChunk.builder()
                .chunkKey("DOC-001#CHUNK-002")
                .evidenceId("E002")
                .snippet("pricing summary")
                .score(0.65D)
                .sourceUrls(List.of("https://docs.notion.so/pricing"))
                .build();
        when(rerankClient.rerank(eq("Notion AI pricing governance"), anyList()))
                .thenReturn(List.of(
                        new RerankClient.RerankRecord(1, 0.99D),
                        new RerankClient.RerankRecord(0, 0.20D)
                ));

        List<TaskRetrievalService.RetrievedChunk> rerankedChunks = rerankService.rerank(
                "Notion AI pricing governance",
                List.of(firstChunk, secondChunk),
                "analyze_competitors"
        );

        assertEquals("E002", rerankedChunks.get(0).getEvidenceId());
        assertEquals("https://docs.notion.so/pricing", rerankedChunks.get(0).getSourceUrls().get(0));
        assertEquals(0.99D, rerankedChunks.get(0).getScore());
    }
}
