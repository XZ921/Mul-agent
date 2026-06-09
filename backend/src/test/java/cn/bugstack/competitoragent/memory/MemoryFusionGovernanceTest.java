package cn.bugstack.competitoragent.memory;

import cn.bugstack.competitoragent.agent.AgentContext;
import cn.bugstack.competitoragent.context.TaskRagContextBundle;
import cn.bugstack.competitoragent.model.entity.CompetitorKnowledge;
import cn.bugstack.competitoragent.model.entity.MemoryReuseRecord;
import cn.bugstack.competitoragent.model.entity.MemorySnapshot;
import cn.bugstack.competitoragent.repository.CompetitorKnowledgeRepository;
import cn.bugstack.competitoragent.repository.MemoryReuseRecordRepository;
import cn.bugstack.competitoragent.repository.MemorySnapshotRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Task 5.4.c 需要同时锁住“读侧治理边界”。
 * <p>
 * 也就是说，复用出来的记忆不能只返回一段摘要，
 * 还必须显式带出版本来源、失效边界和复用说明，
 * 否则下游无法判断这是不是一条已经过期的旧结论。
 */
class MemoryFusionGovernanceTest {

    private final MemorySnapshotRepository memorySnapshotRepository = mock(MemorySnapshotRepository.class);
    private final CompetitorKnowledgeRepository competitorKnowledgeRepository = mock(CompetitorKnowledgeRepository.class);
    private final MemoryReuseRecordRepository memoryReuseRecordRepository = mock(MemoryReuseRecordRepository.class);

    @Test
    void shouldKeepOriginalReuseReasonAcrossWritebackAndReadChain() {
        // Task 5.4.e 要锁定“写回时声明的复用说明，在后续读取复用时不能被悄悄改写成通用模板”。
        // 否则虽然版本与失效边界还在，但“为什么这条记忆当时允许复用”的来源说明会在双向链路中丢失。
        when(memorySnapshotRepository.save(any(MemorySnapshot.class))).thenAnswer(invocation -> {
            MemorySnapshot snapshot = invocation.getArgument(0);
            snapshot.setId(901L);
            return snapshot;
        });
        when(memoryReuseRecordRepository.save(any(MemoryReuseRecord.class))).thenAnswer(invocation -> {
            MemoryReuseRecord record = invocation.getArgument(0);
            record.setId(902L);
            return record;
        });

        String governedReuseReason = "来自当前任务已核实结论，仅在同计划版本内复用，计划重跑后失效";
        MemoryWritebackService.WritebackResult writebackResult = new MemoryWritebackService(
                memorySnapshotRepository,
                competitorKnowledgeRepository,
                memoryReuseRecordRepository
        ).writeback(MemoryWritebackService.WritebackRequest.builder()
                .taskId(88L)
                .planVersionId(22L)
                .branchKey("analysis")
                .nodeName("collect_sources")
                .queryText("Notion AI enterprise pricing")
                .summary("当前任务已经核实官网定价页缺少企业价卡。")
                .sourceUrls(List.of("https://example.com/notion-ai/pricing"))
                .writebackCategory("VERIFIED_TASK_CONCLUSION")
                .qualitySignal("VERIFIED")
                .reuseReason(governedReuseReason)
                .build());

        when(memorySnapshotRepository.findByTaskIdOrderByIdDesc(88L)).thenReturn(List.of(writebackResult.getMemorySnapshot()));
        when(memoryReuseRecordRepository.findByTaskIdOrderByIdAsc(88L)).thenReturn(List.of(writebackResult.getMemoryReuseRecord()));
        when(competitorKnowledgeRepository.findByMemoryLayerOrderByIdAsc("DOMAIN")).thenReturn(List.of());

        AgentContext context = AgentContext.builder()
                .taskId(88L)
                .planVersionId(22L)
                .branchKey("analysis")
                .currentNodeName("write_report")
                .build();

        TaskRagContextBundle fused = new MemoryFusionService(
                memorySnapshotRepository,
                competitorKnowledgeRepository,
                memoryReuseRecordRepository
        ).fuse(context, TaskRagContextBundle.builder()
                .query("Notion AI enterprise governance")
                .retrievalSummary("当前任务命中企业治理资料。")
                .gapSummary("仍缺企业定价公开证据。")
                .sourceUrls(List.of("https://example.com/task-knowledge"))
                .build());

        assertEquals(governedReuseReason, fused.getReusableMemoryItems().get(0).getReuseReason());
        assertEquals("collect_sources", fused.getReusableMemoryItems().get(0).getSourceNodeName());
        assertEquals("TASK_RAG@PLAN-22:analysis", fused.getReusableMemoryItems().get(0).getVersionSource());
        assertEquals("PLAN_VERSION_CHANGED", fused.getReusableMemoryItems().get(0).getInvalidationReason());
        assertTrue(fused.toPromptText().contains(governedReuseReason));
    }

    @Test
    void shouldExposeVersionOriginInvalidationRuleAndReuseReasonForReusableMemory() {
        AgentContext context = AgentContext.builder()
                .taskId(88L)
                .planVersionId(22L)
                .branchKey("analysis")
                .currentNodeName("write_report")
                .build();

        when(memorySnapshotRepository.findByTaskIdOrderByIdDesc(88L)).thenReturn(List.of(
                MemorySnapshot.builder()
                        .id(701L)
                        .taskId(88L)
                        .nodeName("collect_sources")
                        .memoryLayer("SHORT_TERM")
                        .summary("当前任务已经核实官网定价页缺少企业价卡。")
                        .sourceUrls(List.of("https://example.com/notion-ai/pricing"))
                        .versionSource("TASK_RAG@PLAN-22:analysis")
                        .invalidationScope("TASK_RERUN")
                        .invalidationReason("PLAN_VERSION_CHANGED")
                        .build()
        ));
        when(competitorKnowledgeRepository.findByMemoryLayerOrderByIdAsc("DOMAIN")).thenReturn(List.of(
                CompetitorKnowledge.builder()
                        .id(801L)
                        .taskId(11L)
                        .competitorName("Notion AI")
                        .summary("跨任务领域记忆指出 Notion AI 长期强调权限治理与审计。")
                        .memoryLayer("DOMAIN")
                        .sourceUrls("[\"https://example.com/domain-memory\"]")
                        .versionSource("TASK_RAG@PLAN-15:knowledge")
                        .invalidationScope("DOMAIN_REFRESH")
                        .invalidationReason("SOURCE_EVIDENCE_CHANGED")
                        .build()
        ));

        TaskRagContextBundle bundle = TaskRagContextBundle.builder()
                .query("Notion AI enterprise governance")
                .retrievalSummary("当前任务命中企业治理相关资料。")
                .gapSummary("仍缺企业定价公开证据。")
                .sourceUrls(List.of("https://example.com/task-knowledge"))
                .build();

        TaskRagContextBundle fused = new MemoryFusionService(
                memorySnapshotRepository,
                competitorKnowledgeRepository,
                memoryReuseRecordRepository
        ).fuse(context, bundle);

        assertEquals("TASK_RAG@PLAN-22:analysis", fused.getReusableMemoryItems().get(0).getVersionSource());
        assertEquals("TASK_RERUN", fused.getReusableMemoryItems().get(0).getInvalidationScope());
        assertEquals("同计划版本内可复用，计划重跑后失效", fused.getReusableMemoryItems().get(0).getReuseReason());

        String promptText = fused.toPromptText();
        assertTrue(promptText.contains("versionSource=TASK_RAG@PLAN-22:analysis"));
        assertTrue(promptText.contains("invalidationScope=TASK_RERUN"));
        assertTrue(promptText.contains("reuseReason=同计划版本内可复用，计划重跑后失效"));
        assertTrue(promptText.contains("https://example.com/domain-memory"));
    }
}
