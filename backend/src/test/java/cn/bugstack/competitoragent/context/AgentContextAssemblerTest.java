package cn.bugstack.competitoragent.context;

import cn.bugstack.competitoragent.agent.AgentContext;
import cn.bugstack.competitoragent.model.entity.CompetitorKnowledge;
import cn.bugstack.competitoragent.model.entity.MemorySnapshot;
import cn.bugstack.competitoragent.rag.TaskRetrievalService;
import cn.bugstack.competitoragent.rag.TaskRerankService;
import cn.bugstack.competitoragent.repository.CompetitorKnowledgeRepository;
import cn.bugstack.competitoragent.repository.MemoryReuseRecordRepository;
import cn.bugstack.competitoragent.repository.MemorySnapshotRepository;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentContextAssemblerTest {

    private final TaskRagQueryBuilder queryBuilder = mock(TaskRagQueryBuilder.class);
    private final TaskRetrievalService retrievalService = mock(TaskRetrievalService.class);
    private final TaskRerankService rerankService = mock(TaskRerankService.class);
    private final MemorySnapshotRepository memorySnapshotRepository = mock(MemorySnapshotRepository.class);
    private final CompetitorKnowledgeRepository competitorKnowledgeRepository = mock(CompetitorKnowledgeRepository.class);
    private final MemoryReuseRecordRepository memoryReuseRecordRepository = mock(MemoryReuseRecordRepository.class);

    @Test
    void shouldAssembleTraceableRagContextAndPersistMemorySnapshot() {
        // 首次装配时应走正式检索链路，并把最终采用的上下文沉淀为可追溯快照。
        AgentContext context = AgentContext.builder()
                .taskId(1L)
                .taskName("Notion AI 分析")
                .subjectProduct("我们的产品")
                .analysisDimensions("定价,企业治理")
                .currentNodeName("analyze_competitors")
                .build();
        when(queryBuilder.buildQuery(context)).thenReturn("Notion AI enterprise pricing governance");

        TaskRetrievalService.RetrievedChunk retrievedChunk = TaskRetrievalService.RetrievedChunk.builder()
                .chunkKey("DOC-001#CHUNK-001")
                .documentKey("DOC-001")
                .competitorName("Notion AI")
                .evidenceId("E001")
                .sourceCategory("AI_DISCOVERED")
                .snippet("Notion AI 提供企业安全与权限治理说明。")
                .content("Notion AI 企业版强调权限治理与企业安全，但公开企业定价信息仍不完整。")
                .sourceUrls(List.of("https://www.notion.so/product/ai"))
                .build();
        TaskRetrievalService.RetrievalResult retrievalResult = TaskRetrievalService.RetrievalResult.builder()
                .query("Notion AI enterprise pricing governance")
                .chunks(List.of(retrievedChunk))
                .sourceUrls(List.of("https://www.notion.so/product/ai"))
                .gapSummary("缺少公开企业定价页，当前只能回答治理与安全能力。")
                .issueFlags(List.of("MISSING_PRICING_EVIDENCE"))
                .build();

        when(retrievalService.retrieve(eq(1L), eq("Notion AI enterprise pricing governance"), eq("analyze_competitors")))
                .thenReturn(retrievalResult);
        when(rerankService.rerank(eq("Notion AI enterprise pricing governance"), eq(List.of(retrievedChunk)), eq("analyze_competitors")))
                .thenReturn(List.of(retrievedChunk));
        when(memorySnapshotRepository.save(any(MemorySnapshot.class))).thenAnswer(invocation -> {
            MemorySnapshot snapshot = invocation.getArgument(0);
            snapshot.setId(99L);
            return snapshot;
        });

        AgentContext assembledContext = newAssembler().assemble(context);
        TaskRagContextBundle ragContext = assembledContext.getTaskRagContextBundle();

        assertNotNull(ragContext);
        assertEquals("Notion AI enterprise pricing governance", ragContext.getQuery());
        assertEquals("https://www.notion.so/product/ai", ragContext.getSourceUrls().get(0));
        assertEquals("AI_DISCOVERED", ragContext.getChunks().get(0).getSourceCategory());
        assertTrue(ragContext.getGapSummary().contains("企业定价"));
        assertEquals(99L, ragContext.getMemorySnapshotId());
        verify(memorySnapshotRepository).save(any(MemorySnapshot.class));
    }

    @Test
    void shouldExposeCrossScopeHitReasonAndTraceableSourcesInPromptContext() {
        // 跨层命中不仅要能被召回，还必须能在提示词上下文中解释“为什么命中”和“证据来自哪里”。
        AgentContext context = AgentContext.builder()
                .taskId(2L)
                .taskName("GitHub Copilot 分析")
                .subjectProduct("我们的产品")
                .analysisDimensions("企业治理,知识来源")
                .currentNodeName("analyze_competitors")
                .build();
        when(queryBuilder.buildQuery(context)).thenReturn("GitHub Copilot enterprise governance");

        TaskRetrievalService.RetrievedChunk retrievedChunk = TaskRetrievalService.RetrievedChunk.builder()
                .chunkKey("DOMAIN-DOC-009#CHUNK-001")
                .documentKey("DOMAIN-DOC-009")
                .retrievalScope("DOMAIN")
                .competitorName("GitHub Copilot")
                .evidenceId("E-DOM-001")
                .sourceCategory("DOMAIN_KNOWLEDGE")
                .snippet("领域知识库命中 GitHub Copilot 的企业治理说明。")
                .content("该片段来自领域级知识文档，说明 GitHub Copilot 在企业治理与权限边界上的公开证据。")
                .sourceUrls(List.of("https://docs.github.com/copilot/enterprise"))
                .build();
        TaskRetrievalService.RetrievalResult retrievalResult = TaskRetrievalService.RetrievalResult.builder()
                .query("GitHub Copilot enterprise governance")
                .chunks(List.of(retrievedChunk))
                .sourceUrls(List.of("https://docs.github.com/copilot/enterprise"))
                .gapSummary("任务级公开资料不足，当前回退到领域知识召回。")
                .issueFlags(List.of("DOMAIN_RETRIEVAL_FALLBACK_USED"))
                .build();

        when(retrievalService.retrieve(eq(2L), eq("GitHub Copilot enterprise governance"), eq("analyze_competitors")))
                .thenReturn(retrievalResult);
        when(rerankService.rerank(eq("GitHub Copilot enterprise governance"), eq(List.of(retrievedChunk)), eq("analyze_competitors")))
                .thenReturn(List.of(retrievedChunk));
        when(memorySnapshotRepository.save(any(MemorySnapshot.class))).thenAnswer(invocation -> {
            MemorySnapshot snapshot = invocation.getArgument(0);
            snapshot.setId(100L);
            return snapshot;
        });

        AgentContext assembledContext = newAssembler().assemble(context);
        TaskRagContextBundle ragContext = assembledContext.getTaskRagContextBundle();

        // 上下文本身必须保留跨层召回边界，避免后续只看到“命中了片段”却看不到来源层级。
        assertEquals("DOMAIN", ragContext.getChunks().get(0).getRetrievalScope());
        assertEquals("DOMAIN-DOC-009", ragContext.getChunks().get(0).getDocumentKey());
        assertEquals("https://docs.github.com/copilot/enterprise", ragContext.getChunks().get(0).getSourceUrls().get(0));

        // Prompt 文本要能直接解释命中层级、知识文档来源与外部 sourceUrls。
        assertTrue(ragContext.toPromptText().contains("召回层级：DOMAIN"));
        assertTrue(ragContext.toPromptText().contains("知识文档：DOMAIN-DOC-009"));
        assertTrue(ragContext.toPromptText().contains("sourceUrls=https://docs.github.com/copilot/enterprise"));
    }

    @Test
    void shouldReuseMatchingMemorySnapshotBeforeRebuildingRetrievalContext() {
        // 命中同任务、同计划、同分支、同节点的快照时，应优先复用，避免不必要的重复召回。
        AgentContext context = AgentContext.builder()
                .taskId(1L)
                .planVersionId(10L)
                .branchKey("main")
                .currentNodeName("analyze_competitors")
                .build();
        when(queryBuilder.buildQuery(context)).thenReturn("Notion AI enterprise pricing governance");
        when(memorySnapshotRepository.findByTaskIdOrderByIdDesc(1L)).thenReturn(List.of(
                MemorySnapshot.builder()
                        .id(101L)
                        .taskId(1L)
                        .planVersionId(10L)
                        .branchKey("main")
                        .nodeName("analyze_competitors")
                        .snapshotType("TASK_RAG")
                        .queryText("Notion AI enterprise pricing governance")
                        .contextPayload("""
                                {
                                  "query": "Notion AI enterprise pricing governance",
                                  "retrievalSummary": "命中企业治理摘要",
                                  "gapSummary": "公开定价页仍不足",
                                  "sourceUrls": ["https://docs.notion.so"]
                                }
                                """)
                        .build()
        ));

        AgentContext assembledContext = newAssembler().assemble(context);

        assertEquals(101L, assembledContext.getTaskRagContextBundle().getMemorySnapshotId());
        assertTrue(assembledContext.getTaskRagContextBundle().getGapSummary().contains("公开定价页仍不足"));
        verify(retrievalService, never()).retrieve(any(), any(), any());
        verify(rerankService, never()).rerank(any(), any(), any());
        verify(memorySnapshotRepository, never()).save(any(MemorySnapshot.class));
    }

    @Test
    void shouldSeparateKnowledgeReusableMemoryAndRuntimeContextInPromptContext() {
        // Task 5.4.b 要求上下文拼装时可以区分“知识、记忆和任务即时上下文”，
        // 避免后续 Agent 只能拿到一段混合文本，却无法判断每段内容的来源和作用。
        AgentContext context = AgentContext.builder()
                .taskId(9L)
                .taskName("Notion AI 报告重写")
                .subjectProduct("我们的产品")
                .analysisDimensions("企业治理,权限审计")
                .currentNodeName("write_report")
                .build();
        context.putSharedOutput("collect_sources", "当前任务已确认对比范围：Notion AI、GitHub Copilot");
        when(queryBuilder.buildQuery(context)).thenReturn("Notion AI enterprise governance rewrite");

        TaskRetrievalService.RetrievedChunk retrievedChunk = TaskRetrievalService.RetrievedChunk.builder()
                .chunkKey("TASK-DOC-001#CHUNK-001")
                .documentKey("TASK-DOC-001")
                .retrievalScope("TASK")
                .competitorName("Notion AI")
                .evidenceId("E-TASK-001")
                .sourceCategory("TASK_KNOWLEDGE")
                .snippet("当前任务资料说明 Notion AI 在企业权限治理上强调审计能力。")
                .content("当前任务资料说明 Notion AI 在企业权限治理上强调审计能力，并保留了 sourceUrls。")
                .sourceUrls(List.of("https://example.com/task-knowledge"))
                .build();
        TaskRetrievalService.RetrievalResult retrievalResult = TaskRetrievalService.RetrievalResult.builder()
                .query("Notion AI enterprise governance rewrite")
                .chunks(List.of(retrievedChunk))
                .sourceUrls(List.of("https://example.com/task-knowledge"))
                .gapSummary("当前任务资料已覆盖企业治理主线。")
                .issueFlags(List.of())
                .build();

        when(retrievalService.retrieve(eq(9L), eq("Notion AI enterprise governance rewrite"), eq("write_report")))
                .thenReturn(retrievalResult);
        when(rerankService.rerank(eq("Notion AI enterprise governance rewrite"), eq(List.of(retrievedChunk)), eq("write_report")))
                .thenReturn(List.of(retrievedChunk));
        when(memorySnapshotRepository.findByTaskIdOrderByIdDesc(9L)).thenReturn(List.of(
                MemorySnapshot.builder()
                        .id(201L)
                        .taskId(9L)
                        .nodeName("collect_sources")
                        .snapshotType("TASK_RAG")
                        .memoryLayer("SHORT_TERM")
                        .queryText("collect source summary")
                        .summary("上一节点确认企业客户更关注权限治理和审计闭环。")
                        .sourceUrls(List.of("https://example.com/task-memory"))
                        .build()
        ));
        when(competitorKnowledgeRepository.findByMemoryLayerOrderByIdAsc("DOMAIN")).thenReturn(List.of(
                CompetitorKnowledge.builder()
                        .id(301L)
                        .taskId(3L)
                        .competitorName("Notion AI")
                        .summary("跨任务记忆显示 Notion AI 企业版长期强调权限、审计与治理。")
                        .memoryLayer("DOMAIN")
                        .sourceUrls("[\"https://example.com/domain-memory\"]")
                        .build()
        ));
        when(memorySnapshotRepository.save(any(MemorySnapshot.class))).thenAnswer(invocation -> {
            MemorySnapshot snapshot = invocation.getArgument(0);
            snapshot.setId(202L);
            return snapshot;
        });

        AgentContext assembledContext = newAssembler().assemble(context);
        String promptContext = assembledContext.getTaskRagPromptContext();

        assertTrue(promptContext.contains("知识上下文"));
        assertTrue(promptContext.contains("可复用记忆"));
        assertTrue(promptContext.contains("任务即时上下文"));
        assertTrue(promptContext.contains("https://example.com/domain-memory"));
        assertTrue(promptContext.contains("collect_sources"));
    }

    @Test
    void shouldOnlyWriteTaskRagContextBundleBackToAgentContext() {
        // Task 2 需要把 AgentContext 固定为最小运行时边界，
        // 即使后续装配了检索与记忆信息，也只能通过 taskRagContextBundle 回写摘要。
        AgentContext context = AgentContext.builder()
                .taskId(6L)
                .taskName("Notion AI 边界锁定")
                .subjectProduct("我们的产品")
                .analysisDimensions("企业治理")
                .currentNodeName("analyze_competitors")
                .traceId("trace-task-2")
                .branchKey("phase4a")
                .planVersionId(3L)
                .build();
        when(queryBuilder.buildQuery(context)).thenReturn("Notion AI boundary lock");

        TaskRetrievalService.RetrievedChunk retrievedChunk = TaskRetrievalService.RetrievedChunk.builder()
                .chunkKey("TASK-DOC-006#CHUNK-001")
                .documentKey("TASK-DOC-006")
                .retrievalScope("TASK")
                .competitorName("Notion AI")
                .evidenceId("E-TASK-006")
                .sourceCategory("TASK_KNOWLEDGE")
                .snippet("Task 2 只允许回写运行时摘要。")
                .content("AgentContextAssembler 仍然只允许把 TaskRagContextBundle 作为运行时摘要回写到 AgentContext。")
                .sourceUrls(List.of("https://docs.example.com/boundary"))
                .build();
        TaskRetrievalService.RetrievalResult retrievalResult = TaskRetrievalService.RetrievalResult.builder()
                .query("Notion AI boundary lock")
                .chunks(List.of(retrievedChunk))
                .sourceUrls(List.of("https://docs.example.com/boundary"))
                .gapSummary("当前边界锁定测试只验证运行时摘要回写。")
                .issueFlags(List.of("BOUNDARY_LOCK"))
                .build();
        when(retrievalService.retrieve(eq(6L), eq("Notion AI boundary lock"), eq("analyze_competitors")))
                .thenReturn(retrievalResult);
        when(rerankService.rerank(eq("Notion AI boundary lock"), eq(List.of(retrievedChunk)), eq("analyze_competitors")))
                .thenReturn(List.of(retrievedChunk));
        when(memorySnapshotRepository.save(any(MemorySnapshot.class))).thenAnswer(invocation -> {
            MemorySnapshot snapshot = invocation.getArgument(0);
            snapshot.setId(301L);
            return snapshot;
        });

        AgentContext assembledContext = newAssembler().assemble(context);

        assertNotNull(assembledContext.getTaskRagContextBundle());
        assertEquals(context.getTaskId(), assembledContext.getTaskId());
        assertEquals(context.getTaskName(), assembledContext.getTaskName());
        assertEquals(context.getSubjectProduct(), assembledContext.getSubjectProduct());
        assertEquals(context.getAnalysisDimensions(), assembledContext.getAnalysisDimensions());
        assertEquals(context.getCurrentNodeName(), assembledContext.getCurrentNodeName());
        assertEquals(context.getTraceId(), assembledContext.getTraceId());
        assertEquals(context.getPlanVersionId(), assembledContext.getPlanVersionId());
        assertEquals(context.getBranchKey(), assembledContext.getBranchKey());
        assertEquals(
                Set.of(
                        "taskId",
                        "taskName",
                        "subjectProduct",
                        "competitorNames",
                        "competitorUrls",
                        "analysisDimensions",
                        "sourceScope",
                        "reportLanguage",
                        "reportTemplate",
                        "currentNodeName",
                        "currentNodeConfig",
                        "traceId",
                        "planVersionId",
                        "branchKey",
                        "taskRagContextBundle",
                        "sharedState",
                        "createdAt"
                ),
                Arrays.stream(AgentContext.class.getDeclaredFields())
                        .map(Field::getName)
                        .collect(Collectors.toSet())
        );
    }

    @Test
    void shouldDeclareAgentContextBoundaryInAssemblerClassComment() throws IOException {
        // Task 2 不仅要靠实现习惯维持边界，还要把禁止事项写入类注释，
        // 防止后续再把 KnowledgeDocument、检索片段或 MemorySnapshot 直接塞回 AgentContext。
        String source = Files.readString(
                Path.of("src/main/java/cn/bugstack/competitoragent/context/AgentContextAssembler.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(source.contains("只允许把 TaskRagContextBundle"));
        assertTrue(source.contains("KnowledgeDocument"));
        assertTrue(source.contains("RetrievalChunk"));
        assertTrue(source.contains("MemorySnapshot"));
        assertTrue(source.contains("AgentContext"));
    }

    /**
     * 通过反射优先适配 5.4.b 新增的构造依赖；
     * 若正式实现尚未加入这些依赖，则退回旧构造器，让测试先以行为缺口形式失败。
     */
    private AgentContextAssembler newAssembler() {
        try {
            Constructor<AgentContextAssembler> constructor = AgentContextAssembler.class.getConstructor(
                    TaskRagQueryBuilder.class,
                    TaskRetrievalService.class,
                    TaskRerankService.class,
                    MemorySnapshotRepository.class,
                    CompetitorKnowledgeRepository.class,
                    MemoryReuseRecordRepository.class
            );
            return constructor.newInstance(
                    queryBuilder,
                    retrievalService,
                    rerankService,
                    memorySnapshotRepository,
                    competitorKnowledgeRepository,
                    memoryReuseRecordRepository
            );
        } catch (ReflectiveOperationException ignored) {
            return new AgentContextAssembler(
                    queryBuilder,
                    retrievalService,
                    rerankService,
                    memorySnapshotRepository
            );
        }
    }
}
