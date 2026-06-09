package cn.bugstack.competitoragent.rag;

import cn.bugstack.competitoragent.llm.EmbeddingClient;
import cn.bugstack.competitoragent.model.entity.RetrievalChunk;
import cn.bugstack.competitoragent.model.entity.RetrievalIndex;
import cn.bugstack.competitoragent.repository.RetrievalChunkRepository;
import cn.bugstack.competitoragent.repository.RetrievalIndexRepository;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 任务级召回服务。
 * <p>
 * 当前阶段采用“词法召回打底 + embedding 打分增强”的最小正式链路：
 * embedding 失败时明确降级回词法召回，而不是中断主链路或静默产出空结果。
 */
@Service
public class TaskRetrievalService {

    private static final String TASK_SCOPE = "TASK";

    private final RetrievalIndexRepository retrievalIndexRepository;
    private final RetrievalChunkRepository retrievalChunkRepository;
    private final EmbeddingClient embeddingClient;
    private final DomainRetrievalService domainRetrievalService;
    private final OrganizationRetrievalService organizationRetrievalService;
    private final int topK;
    private final int candidatePoolSize;

    /**
     * 运行时必须显式选择完整依赖构造器，
     * 避免在保留轻量测试构造器后让 Spring 对主链装配产生歧义。
     */
    @Autowired
    public TaskRetrievalService(RetrievalIndexRepository retrievalIndexRepository,
                                RetrievalChunkRepository retrievalChunkRepository,
                                EmbeddingClient embeddingClient,
                                DomainRetrievalService domainRetrievalService,
                                OrganizationRetrievalService organizationRetrievalService,
                                @Value("${task.rag.retrieve-top-k:5}") int topK,
                                @Value("${task.rag.retrieve-candidate-pool-size:8}") int candidatePoolSize) {
        this.retrievalIndexRepository = retrievalIndexRepository;
        this.retrievalChunkRepository = retrievalChunkRepository;
        this.embeddingClient = embeddingClient;
        this.domainRetrievalService = domainRetrievalService;
        this.organizationRetrievalService = organizationRetrievalService;
        this.topK = Math.max(1, topK);
        this.candidatePoolSize = Math.max(this.topK, candidatePoolSize);
    }

    /**
     * 保留轻量构造器给当前单测直接 new 的场景使用，
     * 避免为了验证默认拼装链路而额外组装完整 Spring 容器。
     */
    public TaskRetrievalService(RetrievalIndexRepository retrievalIndexRepository,
                                RetrievalChunkRepository retrievalChunkRepository,
                                EmbeddingClient embeddingClient,
                                int topK,
                                int candidatePoolSize) {
        this(
                retrievalIndexRepository,
                retrievalChunkRepository,
                embeddingClient,
                new DomainRetrievalService(
                        retrievalIndexRepository,
                        retrievalChunkRepository,
                        embeddingClient,
                        topK,
                        candidatePoolSize
                ),
                new OrganizationRetrievalService(
                        retrievalIndexRepository,
                        retrievalChunkRepository,
                        embeddingClient,
                        topK,
                        candidatePoolSize
                ),
                topK,
                candidatePoolSize
        );
    }

    public RetrievalResult retrieve(Long taskId, String query, String nodeName) {
        List<String> issueFlags = new ArrayList<>();
        if (taskId == null) {
            issueFlags.add("RETRIEVAL_TASK_ID_MISSING");
            return RetrievalResult.builder()
                    .query(query)
                    .gapSummary("缺少任务 ID，无法执行任务级检索。")
                    .issueFlags(issueFlags)
                    .build();
        }

        RetrievalResult taskScopedResult = retrieveTaskScoped(taskId, query, nodeName);
        return fillRemainingHitsByScopeOrder(query, nodeName, taskScopedResult);
    }

    /**
     * 任务级召回始终作为第一层入口。
     * 即使任务级命中不足，也先把当前层结果稳定产出出来，再交给后续层按顺序补齐。
     */
    private RetrievalResult retrieveTaskScoped(Long taskId, String query, String nodeName) {
        List<String> issueFlags = new ArrayList<>();
        List<RetrievalIndex> readyIndexes = retrievalIndexRepository.findByTaskIdOrderByIdAsc(taskId).stream()
                .filter(index -> index != null && "READY".equalsIgnoreCase(index.getStatus()))
                .toList();
        if (readyIndexes.isEmpty()) {
            issueFlags.add("NO_READY_RETRIEVAL_INDEX");
            return RetrievalResult.builder()
                    .query(query)
                    .gapSummary("当前任务还没有可用检索索引，需先补充采集证据或等待索引完成。")
                    .issueFlags(issueFlags)
                    .build();
        }

        Set<String> readyDocumentKeys = readyIndexes.stream()
                .map(RetrievalIndex::getDocumentKey)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());

        List<RetrievalChunk> readyChunks = retrievalChunkRepository.findByTaskIdOrderByKnowledgeDocumentIdAscChunkIndexAsc(taskId).stream()
                .filter(chunk -> chunk != null && readyDocumentKeys.contains(chunk.getDocumentKey()))
                .toList();
        if (readyChunks.isEmpty()) {
            issueFlags.add("NO_READY_RETRIEVAL_CHUNK");
            return RetrievalResult.builder()
                    .query(query)
                    .gapSummary("当前任务没有可用切片，需先补源或重新建立知识索引。")
                    .issueFlags(issueFlags)
                    .build();
        }

        List<RetrievedChunk> rankedChunks = readyChunks.stream()
                .map(chunk -> toRetrievedChunk(chunk, lexicalScore(query, chunk)))
                .sorted((left, right) -> Double.compare(safeScore(right.getScore()), safeScore(left.getScore())))
                .limit(candidatePoolSize)
                .collect(Collectors.toCollection(ArrayList::new));

        enrichSemanticScoreIfPossible(query, rankedChunks, issueFlags);
        rankedChunks.sort((left, right) -> Double.compare(safeScore(right.getScore()), safeScore(left.getScore())));

        List<RetrievedChunk> topChunks = rankedChunks.stream()
                .limit(topK)
                .toList();

        return RetrievalResult.builder()
                .query(query)
                .chunks(topChunks)
                .sourceUrls(collectSourceUrls(topChunks, readyIndexes))
                .gapSummary(buildGapSummary(topChunks, nodeName))
                .issueFlags(issueFlags)
                .build();
    }

    /**
     * Task 5.3.c 要求明确三层召回顺序。
     * 因此这里统一把“任务级先命中，不足时再按领域级、组织级补齐”的回退策略收口到一个地方。
     */
    private RetrievalResult fillRemainingHitsByScopeOrder(String query,
                                                          String nodeName,
                                                          RetrievalResult taskScopedResult) {
        RetrievalResult baseResult = taskScopedResult == null ? RetrievalResult.builder().query(query).build() : taskScopedResult;
        LinkedHashSet<String> mergedSourceUrls = new LinkedHashSet<>();
        LinkedHashSet<String> mergedIssueFlags = new LinkedHashSet<>();
        List<RetrievedChunk> mergedChunks = new ArrayList<>();

        mergeSourceUrls(mergedSourceUrls, baseResult.getSourceUrls());
        mergeIssueFlags(mergedIssueFlags, baseResult.getIssueFlags());
        appendChunksWithinLimit(mergedChunks, mergedSourceUrls, baseResult.getChunks(), topK);

        RetrievalResult domainResult = null;
        if (mergedChunks.size() < topK) {
            domainResult = domainRetrievalService.retrieve(query, nodeName);
            mergeIssueFlags(mergedIssueFlags, domainResult == null ? List.of() : domainResult.getIssueFlags());
            if (domainResult != null && domainResult.getChunks() != null && !domainResult.getChunks().isEmpty()) {
                mergeSourceUrls(mergedSourceUrls, domainResult.getSourceUrls());
                appendChunksWithinLimit(mergedChunks, mergedSourceUrls, domainResult.getChunks(), topK);
                if (mergedChunks.stream().anyMatch(chunk -> "DOMAIN".equalsIgnoreCase(chunk.getRetrievalScope()))) {
                    mergedIssueFlags.add("DOMAIN_RETRIEVAL_FALLBACK_USED");
                }
            }
        }

        RetrievalResult organizationResult = null;
        if (mergedChunks.size() < topK) {
            organizationResult = organizationRetrievalService.retrieve(query, nodeName);
            mergeIssueFlags(mergedIssueFlags, organizationResult == null ? List.of() : organizationResult.getIssueFlags());
            if (organizationResult != null && organizationResult.getChunks() != null && !organizationResult.getChunks().isEmpty()) {
                mergeSourceUrls(mergedSourceUrls, organizationResult.getSourceUrls());
                appendChunksWithinLimit(mergedChunks, mergedSourceUrls, organizationResult.getChunks(), topK);
                if (mergedChunks.stream().anyMatch(chunk -> "ORGANIZATION".equalsIgnoreCase(chunk.getRetrievalScope()))) {
                    mergedIssueFlags.add("ORGANIZATION_RETRIEVAL_FALLBACK_USED");
                }
            }
        }

        return RetrievalResult.builder()
                .query(query)
                .chunks(mergedChunks)
                .sourceUrls(new ArrayList<>(mergedSourceUrls))
                .gapSummary(resolveGapSummary(baseResult, domainResult, organizationResult, mergedChunks, nodeName))
                .issueFlags(new ArrayList<>(mergedIssueFlags))
                .build();
    }

    /**
     * 这里按层级顺序追加结果，而不是把三层片段全量混排。
     * 这样可以保证默认顺序始终是 Task -> Domain -> Organization。
     */
    private void appendChunksWithinLimit(List<RetrievedChunk> mergedChunks,
                                         LinkedHashSet<String> mergedSourceUrls,
                                         List<RetrievedChunk> candidateChunks,
                                         int maxHits) {
        if (candidateChunks == null || candidateChunks.isEmpty()) {
            return;
        }
        LinkedHashSet<String> existedChunkKeys = mergedChunks.stream()
                .map(RetrievedChunk::getChunkKey)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (RetrievedChunk candidateChunk : candidateChunks) {
            if (candidateChunk == null) {
                continue;
            }
            if (mergedChunks.size() >= maxHits) {
                return;
            }
            if (StringUtils.hasText(candidateChunk.getChunkKey()) && existedChunkKeys.contains(candidateChunk.getChunkKey())) {
                continue;
            }
            mergedChunks.add(candidateChunk);
            if (StringUtils.hasText(candidateChunk.getChunkKey())) {
                existedChunkKeys.add(candidateChunk.getChunkKey());
            }
            mergeSourceUrls(mergedSourceUrls, candidateChunk.getSourceUrls());
        }
    }

    private void mergeSourceUrls(LinkedHashSet<String> mergedSourceUrls, List<String> sourceUrls) {
        if (sourceUrls == null || sourceUrls.isEmpty()) {
            return;
        }
        mergedSourceUrls.addAll(sourceUrls);
    }

    private void mergeIssueFlags(LinkedHashSet<String> mergedIssueFlags, List<String> issueFlags) {
        if (issueFlags == null || issueFlags.isEmpty()) {
            return;
        }
        mergedIssueFlags.addAll(issueFlags);
    }

    /**
     * 缺口摘要优先复用最深一层的回退说明，
     * 若没有发生跨层回退，则继续沿用任务级摘要。
     */
    private String resolveGapSummary(RetrievalResult taskScopedResult,
                                     RetrievalResult domainResult,
                                     RetrievalResult organizationResult,
                                     List<RetrievedChunk> mergedChunks,
                                     String nodeName) {
        if (organizationResult != null && organizationResult.getChunks() != null && !organizationResult.getChunks().isEmpty()) {
            return organizationResult.getGapSummary();
        }
        if (domainResult != null && domainResult.getChunks() != null && !domainResult.getChunks().isEmpty()) {
            if (taskScopedResult != null && taskScopedResult.getChunks() != null && !taskScopedResult.getChunks().isEmpty()) {
                return "任务级命中不足，已按 Task -> Domain 顺序补齐 " + mergedChunks.size()
                        + " 条片段，可为" + firstNonBlank(nodeName, "当前节点") + "继续提供参考。";
            }
            return domainResult.getGapSummary();
        }
        if (taskScopedResult != null && StringUtils.hasText(taskScopedResult.getGapSummary())) {
            return taskScopedResult.getGapSummary();
        }
        return mergedChunks == null || mergedChunks.isEmpty()
                ? "当前没有召回到任何片段，建议先补充公开证据来源。"
                : "当前已召回 " + mergedChunks.size() + " 条片段，可作为 " + firstNonBlank(nodeName, "当前节点") + " 的跨层检索参考。";
    }

    /**
     * embedding 增强只作为排序增强层存在，
     * 任何异常都不应该直接打断召回主链路。
     */
    private void enrichSemanticScoreIfPossible(String query,
                                               List<RetrievedChunk> chunks,
                                               List<String> issueFlags) {
        if (!StringUtils.hasText(query) || chunks == null || chunks.isEmpty()) {
            return;
        }
        try {
            // 网关层已经承担外部模型的重试与故障转移，这里只做一次语义增强尝试。
            List<Float> queryVector = embedOnce(query);
            for (RetrievedChunk chunk : chunks) {
                List<Float> chunkVector = embedOnce(firstNonBlank(chunk.getSnippet(), chunk.getContent()));
                double semanticScore = cosineSimilarity(queryVector, chunkVector);
                double lexicalScore = safeScore(chunk.getScore());
                chunk.setScore((lexicalScore + semanticScore) / 2.0D);
            }
        } catch (Exception e) {
            if (!issueFlags.contains("EMBEDDING_RETRIEVAL_DEGRADED")) {
                issueFlags.add("EMBEDDING_RETRIEVAL_DEGRADED");
            }
        }
    }

    private List<Float> embedOnce(String text) {
        return embeddingClient.embed(text);
    }

    private RetrievedChunk toRetrievedChunk(RetrievalChunk chunk, double lexicalScore) {
        return RetrievedChunk.builder()
                .chunkKey(chunk.getChunkKey())
                .documentKey(chunk.getDocumentKey())
                .retrievalScope(StringUtils.hasText(chunk.getRetrievalScope()) ? chunk.getRetrievalScope() : TASK_SCOPE)
                .competitorName(chunk.getCompetitorName())
                .evidenceId(chunk.getEvidenceId())
                .sourceCategory(chunk.getSourceCategory())
                .snippet(chunk.getSnippet())
                .content(chunk.getContent())
                .score(lexicalScore)
                .sourceUrls(chunk.getSourceUrls() == null ? List.of() : chunk.getSourceUrls())
                .issueFlags(chunk.getIssueFlags() == null ? List.of() : chunk.getIssueFlags())
                .build();
    }

    /**
     * 先用可解释的关键词重叠做粗召回，
     * 这样即使 embedding 层完全不可用，也仍有稳定可审计的保底结果。
     */
    private double lexicalScore(String query, RetrievalChunk chunk) {
        if (!StringUtils.hasText(query) || chunk == null) {
            return 0D;
        }
        Set<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty()) {
            return 0D;
        }
        Set<String> chunkTokens = tokenize(String.join(" ",
                firstNonBlank(chunk.getCompetitorName(), ""),
                firstNonBlank(chunk.getSnippet(), ""),
                firstNonBlank(chunk.getContent(), ""),
                firstNonBlank(chunk.getSourceCategory(), "")
        ));
        long matched = queryTokens.stream().filter(chunkTokens::contains).count();
        return matched / (double) queryTokens.size();
    }

    private Set<String> tokenize(String rawText) {
        if (!StringUtils.hasText(rawText)) {
            return Set.of();
        }
        return List.of(rawText.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")).stream()
                .filter(token -> token != null && !token.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * 召回结果的来源链接优先保留切片级 sourceUrls，
     * 同时回退合并 READY 索引上的来源链接，避免切片明细缺失时整体结果变成“无来源”。
     */
    private List<String> collectSourceUrls(List<RetrievedChunk> chunks,
                                           List<RetrievalIndex> readyIndexes) {
        LinkedHashSet<String> sourceUrls = new LinkedHashSet<>();
        if (chunks != null) {
            for (RetrievedChunk chunk : chunks) {
                if (chunk != null && chunk.getSourceUrls() != null) {
                    sourceUrls.addAll(chunk.getSourceUrls());
                }
            }
        }
        if (readyIndexes != null) {
            for (RetrievalIndex readyIndex : readyIndexes) {
                if (readyIndex != null && readyIndex.getSourceUrls() != null) {
                    sourceUrls.addAll(readyIndex.getSourceUrls());
                }
            }
        }
        return new ArrayList<>(sourceUrls);
    }

    /**
     * 缺口说明始终保留，提醒下游 Agent 当前上下文是“稳定”还是“偏薄”。
     */
    private String buildGapSummary(List<RetrievedChunk> topChunks, String nodeName) {
        if (topChunks == null || topChunks.isEmpty()) {
            return "当前没有召回到任何片段，建议先补充公开证据来源。";
        }
        if (topChunks.size() == 1) {
            return "当前仅召回 1 条片段，可用于" + firstNonBlank(nodeName, "当前节点") + "初步参考，但仍建议继续补充证据。";
        }
        return "当前已召回 " + topChunks.size() + " 条片段，可作为 " + firstNonBlank(nodeName, "当前节点") + " 的任务级上下文参考。";
    }

    private double cosineSimilarity(List<Float> left, List<Float> right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty() || left.size() != right.size()) {
            return 0D;
        }
        double dot = 0D;
        double leftNorm = 0D;
        double rightNorm = 0D;
        for (int index = 0; index < left.size(); index++) {
            double leftValue = left.get(index);
            double rightValue = right.get(index);
            dot += leftValue * rightValue;
            leftNorm += leftValue * leftValue;
            rightNorm += rightValue * rightValue;
        }
        if (leftNorm == 0D || rightNorm == 0D) {
            return 0D;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private double safeScore(Double score) {
        return score == null ? 0D : score;
    }

    private String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return fallback;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RetrievalResult {
        private String query;
        @Builder.Default
        private List<RetrievedChunk> chunks = new ArrayList<>();
        @Builder.Default
        private List<String> sourceUrls = new ArrayList<>();
        private String gapSummary;
        @Builder.Default
        private List<String> issueFlags = new ArrayList<>();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RetrievedChunk {
        private String chunkKey;
        private String documentKey;
        private String retrievalScope;
        private String competitorName;
        private String evidenceId;
        private String sourceCategory;
        private String snippet;
        private String content;
        private Double score;
        @Builder.Default
        private List<String> sourceUrls = new ArrayList<>();
        @Builder.Default
        private List<String> issueFlags = new ArrayList<>();
    }
}
