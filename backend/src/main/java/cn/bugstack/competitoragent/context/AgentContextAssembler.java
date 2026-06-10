package cn.bugstack.competitoragent.context;

import cn.bugstack.competitoragent.agent.AgentContext;
import cn.bugstack.competitoragent.memory.MemoryFusionService;
import cn.bugstack.competitoragent.model.entity.MemorySnapshot;
import cn.bugstack.competitoragent.rag.TaskRetrievalService;
import cn.bugstack.competitoragent.rag.TaskRerankService;
import cn.bugstack.competitoragent.repository.CompetitorKnowledgeRepository;
import cn.bugstack.competitoragent.repository.MemoryReuseRecordRepository;
import cn.bugstack.competitoragent.repository.MemorySnapshotRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Agent 上下文正式装配器。
 * <p>
 * Task 4.5 先完成了检索知识上下文装配；
 * Task 5.4.b 在此基础上继续接入记忆融合服务，
 * 让上下文输入可以显式区分“知识上下文、可复用记忆、任务即时上下文”。
 * <p>
 * phase4a / Task 2 进一步锁定运行时边界：这里即使会接触 KnowledgeDocument、
 * RetrievalChunk、MemorySnapshot 等知识/检索/记忆对象，也只允许把 TaskRagContextBundle
 * 这种运行时摘要回写到 AgentContext。
 * 禁止把 KnowledgeDocument、RetrievalChunk、MemorySnapshot 或其它业务集合直接塞进 AgentContext，
 * 避免 AgentContext 重新膨胀为跨模块共享的大对象容器。
 */
@Component
public class AgentContextAssembler {

    private final TaskRagQueryBuilder queryBuilder;
    private final TaskRetrievalService retrievalService;
    private final TaskRerankService rerankService;
    private final MemorySnapshotRepository memorySnapshotRepository;
    private final MemoryFusionService memoryFusionService;
    private final ObjectMapper objectMapper;

    /**
     * Spring 运行时使用的正式构造器。
     */
    @Autowired
    public AgentContextAssembler(TaskRagQueryBuilder queryBuilder,
                                 TaskRetrievalService retrievalService,
                                 TaskRerankService rerankService,
                                 MemorySnapshotRepository memorySnapshotRepository,
                                 MemoryFusionService memoryFusionService,
                                 ObjectMapper objectMapper) {
        this.queryBuilder = queryBuilder;
        this.retrievalService = retrievalService;
        this.rerankService = rerankService;
        this.memorySnapshotRepository = memorySnapshotRepository;
        this.memoryFusionService = memoryFusionService;
        this.objectMapper = objectMapper;
    }

    /**
     * 为当前阶段测试提供的轻量构造器。
     * <p>
     * 这里显式传入仓储依赖并在内部组装 MemoryFusionService，
     * 让 TDD 测试可以只围绕上下文装配行为本身展开。
     */
    public AgentContextAssembler(TaskRagQueryBuilder queryBuilder,
                                 TaskRetrievalService retrievalService,
                                 TaskRerankService rerankService,
                                 MemorySnapshotRepository memorySnapshotRepository,
                                 CompetitorKnowledgeRepository competitorKnowledgeRepository,
                                 MemoryReuseRecordRepository memoryReuseRecordRepository) {
        this(queryBuilder,
                retrievalService,
                rerankService,
                memorySnapshotRepository,
                new MemoryFusionService(
                        memorySnapshotRepository,
                        competitorKnowledgeRepository,
                        memoryReuseRecordRepository,
                        new ObjectMapper()
                ),
                new ObjectMapper());
    }

    /**
     * 保留给旧测试的最小构造器。
     * <p>
     * 当没有领域记忆与留痕仓储时，仍然允许装配基础知识上下文；
     * 新的 5.4.b 测试会优先走更完整的构造器。
     */
    public AgentContextAssembler(TaskRagQueryBuilder queryBuilder,
                                 TaskRetrievalService retrievalService,
                                 TaskRerankService rerankService,
                                 MemorySnapshotRepository memorySnapshotRepository) {
        this(queryBuilder,
                retrievalService,
                rerankService,
                memorySnapshotRepository,
                new MemoryFusionService(memorySnapshotRepository, null, null, new ObjectMapper()),
                new ObjectMapper());
    }

    public AgentContext assemble(AgentContext context) {
        if (context == null) {
            return null;
        }

        String query = queryBuilder.buildQuery(context);
        TaskRagContextBundle reusedBundle = loadReusableSnapshot(context, query);
        if (reusedBundle != null) {
            return context.toBuilder()
                    .taskRagContextBundle(memoryFusionService.fuse(context, reusedBundle))
                    .build();
        }

        TaskRetrievalService.RetrievalResult retrievalResult = retrievalService.retrieve(
                context.getTaskId(),
                query,
                context.getCurrentNodeName()
        );
        List<TaskRetrievalService.RetrievedChunk> rerankedChunks = rerankService.rerank(
                query,
                retrievalResult.getChunks(),
                context.getCurrentNodeName()
        );

        TaskRagContextBundle knowledgeContext = TaskRagContextBundle.builder()
                .query(query)
                .retrievalSummary(buildRetrievalSummary(rerankedChunks))
                .gapSummary(retrievalResult.getGapSummary())
                .sourceUrls(collectSourceUrls(retrievalResult, rerankedChunks))
                .issueFlags(collectIssueFlags(retrievalResult, rerankedChunks))
                .chunks(toContextChunks(rerankedChunks))
                .build();

        TaskRagContextBundle fusedContext = memoryFusionService.fuse(context, knowledgeContext);
        MemorySnapshot snapshot = saveSnapshot(context, fusedContext);
        fusedContext.setMemorySnapshotId(snapshot == null ? null : snapshot.getId());

        return context.toBuilder()
                .taskRagContextBundle(fusedContext)
                .build();
    }

    /**
     * 命中同任务、同计划、同分支、同节点且查询一致的快照时，直接复用已有上下文。
     */
    private TaskRagContextBundle loadReusableSnapshot(AgentContext context, String query) {
        if (context.getTaskId() == null || !StringUtils.hasText(query)) {
            return null;
        }
        for (MemorySnapshot snapshot : memorySnapshotRepository.findByTaskIdOrderByIdDesc(context.getTaskId())) {
            if (!matchesSnapshot(context, query, snapshot)) {
                continue;
            }
            TaskRagContextBundle bundle = readPayload(snapshot.getContextPayload());
            if (bundle == null) {
                continue;
            }
            bundle.setMemorySnapshotId(snapshot.getId());
            return bundle;
        }
        return null;
    }

    private boolean matchesSnapshot(AgentContext context, String query, MemorySnapshot snapshot) {
        if (snapshot == null) {
            return false;
        }
        if (!"TASK_RAG".equalsIgnoreCase(snapshot.getSnapshotType())) {
            return false;
        }
        if (!safe(snapshot.getNodeName()).equals(safe(context.getCurrentNodeName()))) {
            return false;
        }
        if (!safe(snapshot.getBranchKey()).equals(safe(context.getBranchKey()))) {
            return false;
        }
        if (!safe(snapshot.getQueryText()).equals(safe(query))) {
            return false;
        }
        return equalsNullable(snapshot.getPlanVersionId(), context.getPlanVersionId());
    }

    /**
     * 检索摘要只说明“命中了什么、覆盖了什么”，
     * 不把检索片段直接提升为已经确认的业务结论。
     */
    private String buildRetrievalSummary(List<TaskRetrievalService.RetrievedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return "当前未召回到可用片段。";
        }
        List<String> summaries = new ArrayList<>();
        for (TaskRetrievalService.RetrievedChunk chunk : chunks) {
            if (chunk == null) {
                continue;
            }
            String snippet = StringUtils.hasText(chunk.getSnippet()) ? chunk.getSnippet() : chunk.getContent();
            if (StringUtils.hasText(snippet)) {
                summaries.add("[" + firstNonBlank(chunk.getRetrievalScope(), "TASK") + "] "
                        + snippet.trim()
                        + "（知识文档：" + firstNonBlank(chunk.getDocumentKey(), "无") + "）");
            }
            if (summaries.size() >= 3) {
                break;
            }
        }
        return summaries.isEmpty() ? "当前未召回到可用片段。" : String.join("；", summaries);
    }

    private List<String> collectSourceUrls(TaskRetrievalService.RetrievalResult retrievalResult,
                                           List<TaskRetrievalService.RetrievedChunk> rerankedChunks) {
        LinkedHashSet<String> sourceUrls = new LinkedHashSet<>();
        if (retrievalResult != null && retrievalResult.getSourceUrls() != null) {
            sourceUrls.addAll(retrievalResult.getSourceUrls());
        }
        if (rerankedChunks != null) {
            for (TaskRetrievalService.RetrievedChunk chunk : rerankedChunks) {
                if (chunk != null && chunk.getSourceUrls() != null) {
                    sourceUrls.addAll(chunk.getSourceUrls());
                }
            }
        }
        return new ArrayList<>(sourceUrls);
    }

    private List<String> collectIssueFlags(TaskRetrievalService.RetrievalResult retrievalResult,
                                           List<TaskRetrievalService.RetrievedChunk> rerankedChunks) {
        LinkedHashSet<String> issueFlags = new LinkedHashSet<>();
        if (retrievalResult != null && retrievalResult.getIssueFlags() != null) {
            issueFlags.addAll(retrievalResult.getIssueFlags());
        }
        if (rerankedChunks != null) {
            for (TaskRetrievalService.RetrievedChunk chunk : rerankedChunks) {
                if (chunk != null && chunk.getIssueFlags() != null) {
                    issueFlags.addAll(chunk.getIssueFlags());
                }
            }
        }
        return new ArrayList<>(issueFlags);
    }

    private List<TaskRagContextBundle.ContextChunk> toContextChunks(List<TaskRetrievalService.RetrievedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        List<TaskRagContextBundle.ContextChunk> contextChunks = new ArrayList<>();
        for (TaskRetrievalService.RetrievedChunk chunk : chunks) {
            if (chunk == null) {
                continue;
            }
            contextChunks.add(TaskRagContextBundle.ContextChunk.builder()
                    .chunkKey(chunk.getChunkKey())
                    .documentKey(chunk.getDocumentKey())
                    .retrievalScope(chunk.getRetrievalScope())
                    .competitorName(chunk.getCompetitorName())
                    .evidenceId(chunk.getEvidenceId())
                    .sourceCategory(chunk.getSourceCategory())
                    .snippet(chunk.getSnippet())
                    .content(chunk.getContent())
                    .score(chunk.getScore())
                    .sourceUrls(chunk.getSourceUrls() == null ? List.of() : chunk.getSourceUrls())
                    .issueFlags(chunk.getIssueFlags() == null ? List.of() : chunk.getIssueFlags())
                    .build());
        }
        return contextChunks;
    }

    /**
     * 继续把当前节点最终采用的融合上下文沉淀为快照，
     * 为后续复用、回放和解释保留稳定输入。
     */
    private MemorySnapshot saveSnapshot(AgentContext context, TaskRagContextBundle ragContext) {
        MemorySnapshot snapshot = MemorySnapshot.builder()
                .taskId(context.getTaskId())
                .planVersionId(context.getPlanVersionId())
                .branchKey(context.getBranchKey())
                .nodeName(context.getCurrentNodeName())
                .snapshotType("TASK_RAG")
                .queryText(ragContext.getQuery())
                .summary(ragContext.getRetrievalSummary())
                .gapSummary(ragContext.getGapSummary())
                .sourceUrls(ragContext.getSourceUrls())
                .issueFlags(ragContext.getIssueFlags())
                .contextPayload(writePayload(ragContext))
                .build();
        return memorySnapshotRepository.save(snapshot);
    }

    private String writePayload(TaskRagContextBundle ragContext) {
        try {
            return objectMapper.writeValueAsString(ragContext);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private TaskRagContextBundle readPayload(String contextPayload) {
        if (!StringUtils.hasText(contextPayload)) {
            return null;
        }
        try {
            return objectMapper.readValue(contextPayload, TaskRagContextBundle.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String firstNonBlank(String primary, String fallback) {
        return StringUtils.hasText(primary) ? primary : fallback;
    }

    private boolean equalsNullable(Long left, Long right) {
        if (left == null) {
            return right == null;
        }
        return left.equals(right);
    }
}
