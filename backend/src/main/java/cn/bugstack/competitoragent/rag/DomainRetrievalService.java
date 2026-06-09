package cn.bugstack.competitoragent.rag;

import cn.bugstack.competitoragent.llm.EmbeddingClient;
import cn.bugstack.competitoragent.model.entity.RetrievalChunk;
import cn.bugstack.competitoragent.model.entity.RetrievalIndex;
import cn.bugstack.competitoragent.repository.RetrievalChunkRepository;
import cn.bugstack.competitoragent.repository.RetrievalIndexRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 领域级召回服务。
 * <p>
 * Task 5.3 的目标不是“把所有 DOMAIN 文档混在一起做一次兜底召回”，
 * 而是先判断当前查询更贴近哪个知识域，再在该知识域边界内完成正式召回，
 * 避免不同领域的切片在同一轮回退里相互污染。
 */
@Service
public class DomainRetrievalService {

    private static final String DOMAIN_SCOPE = "DOMAIN";
    private static final double SCORE_EPSILON = 1e-9D;

    private final RetrievalIndexRepository retrievalIndexRepository;
    private final RetrievalChunkRepository retrievalChunkRepository;
    private final EmbeddingClient embeddingClient;
    private final int topK;
    private final int candidatePoolSize;

    public DomainRetrievalService(RetrievalIndexRepository retrievalIndexRepository,
                                  RetrievalChunkRepository retrievalChunkRepository,
                                  EmbeddingClient embeddingClient,
                                  @Value("${task.rag.retrieve-top-k:5}") int topK,
                                  @Value("${task.rag.retrieve-candidate-pool-size:8}") int candidatePoolSize) {
        this.retrievalIndexRepository = retrievalIndexRepository;
        this.retrievalChunkRepository = retrievalChunkRepository;
        this.embeddingClient = embeddingClient;
        this.topK = Math.max(1, topK);
        this.candidatePoolSize = Math.max(this.topK, candidatePoolSize);
    }

    /**
     * 领域级回退必须显式经历两步：
     * 1. 先在 READY 的 DOMAIN 候选中判断当前查询更贴近哪个知识域；
     * 2. 再只在选中的知识域内排序与返回切片。
     * 这样可以满足 5.3 对“知识域边界收束”的治理要求，同时不改变现有调用入口。
     */
    public TaskRetrievalService.RetrievalResult retrieve(String query, String nodeName) {
        List<String> issueFlags = new ArrayList<>();
        List<RetrievalIndex> readyIndexes = retrievalIndexRepository.findAll().stream()
                .filter(index -> index != null
                        && DOMAIN_SCOPE.equalsIgnoreCase(index.getRetrievalScope())
                        && "READY".equalsIgnoreCase(index.getStatus()))
                .toList();
        if (readyIndexes.isEmpty()) {
            issueFlags.add("NO_READY_DOMAIN_RETRIEVAL_INDEX");
            return TaskRetrievalService.RetrievalResult.builder()
                    .query(query)
                    .gapSummary("当前没有可用的领域知识索引，无法补充领域级召回。")
                    .issueFlags(issueFlags)
                    .build();
        }

        Set<String> initialReadyDocumentKeys = readyIndexes.stream()
                .map(RetrievalIndex::getDocumentKey)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());

        List<RetrievalChunk> readyChunks = retrievalChunkRepository.findAll().stream()
                .filter(chunk -> chunk != null
                        && DOMAIN_SCOPE.equalsIgnoreCase(chunk.getRetrievalScope())
                        && initialReadyDocumentKeys.contains(chunk.getDocumentKey()))
                .toList();
        if (readyChunks.isEmpty()) {
            issueFlags.add("NO_READY_DOMAIN_RETRIEVAL_CHUNK");
            return TaskRetrievalService.RetrievalResult.builder()
                    .query(query)
                    .gapSummary("当前领域知识没有可用切片，暂时无法补充领域级召回。")
                    .issueFlags(issueFlags)
                    .build();
        }

        LinkedHashSet<String> relevantDomainKeys = selectRelevantDomainKeys(query, readyChunks);
        readyIndexes = narrowReadyIndexesToRelevantDomains(readyIndexes, relevantDomainKeys);
        Set<String> narrowedReadyDocumentKeys = readyIndexes.stream()
                .map(RetrievalIndex::getDocumentKey)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
        readyChunks = narrowReadyChunksToRelevantDomains(readyChunks, narrowedReadyDocumentKeys, relevantDomainKeys);
        if (readyChunks.isEmpty()) {
            issueFlags.add("NO_READY_DOMAIN_RETRIEVAL_CHUNK");
            return TaskRetrievalService.RetrievalResult.builder()
                    .query(query)
                    .gapSummary("当前查询没有命中可用的领域知识切片。")
                    .issueFlags(issueFlags)
                    .build();
        }

        List<TaskRetrievalService.RetrievedChunk> rankedChunks = readyChunks.stream()
                .map(chunk -> toRetrievedChunk(chunk, lexicalScore(query, chunk)))
                .sorted((left, right) -> Double.compare(safeScore(right.getScore()), safeScore(left.getScore())))
                .limit(candidatePoolSize)
                .collect(Collectors.toCollection(ArrayList::new));

        enrichSemanticScoreIfPossible(query, rankedChunks, issueFlags);
        rankedChunks.sort((left, right) -> Double.compare(safeScore(right.getScore()), safeScore(left.getScore())));

        List<TaskRetrievalService.RetrievedChunk> topChunks = rankedChunks.stream()
                .limit(topK)
                .toList();

        return TaskRetrievalService.RetrievalResult.builder()
                .query(query)
                .chunks(topChunks)
                .sourceUrls(collectSourceUrls(topChunks, readyIndexes))
                .gapSummary(buildGapSummary(topChunks, nodeName))
                .issueFlags(issueFlags)
                .build();
    }

    /**
     * 先按知识域聚合切片分数，再只保留最相关的知识域键。
     * 当查询本身没有给出足够语义信号时，退化为保留所有知识域候选，
     * 避免把“无法判断知识域”误处理成“没有领域知识”。
     */
    private LinkedHashSet<String> selectRelevantDomainKeys(String query, List<RetrievalChunk> readyChunks) {
        LinkedHashMap<String, Double> bestScoresByDomain = new LinkedHashMap<>();
        if (readyChunks != null) {
            for (RetrievalChunk readyChunk : readyChunks) {
                String domainKey = resolveDomainKey(
                        readyChunk == null ? null : readyChunk.getKnowledgeDomainKey(),
                        readyChunk == null ? null : readyChunk.getScopeRefKey(),
                        readyChunk == null ? null : readyChunk.getDocumentKey()
                );
                if (!StringUtils.hasText(domainKey)) {
                    continue;
                }
                double domainScore = lexicalScore(query, readyChunk);
                bestScoresByDomain.merge(domainKey, domainScore, Math::max);
            }
        }
        if (bestScoresByDomain.isEmpty()) {
            return new LinkedHashSet<>();
        }

        double maxScore = bestScoresByDomain.values().stream()
                .mapToDouble(Double::doubleValue)
                .max()
                .orElse(0D);
        if (maxScore <= 0D) {
            return new LinkedHashSet<>(bestScoresByDomain.keySet());
        }

        LinkedHashSet<String> selectedDomainKeys = new LinkedHashSet<>();
        for (var entry : bestScoresByDomain.entrySet()) {
            if (Math.abs(entry.getValue() - maxScore) <= SCORE_EPSILON) {
                selectedDomainKeys.add(entry.getKey());
            }
        }
        return selectedDomainKeys;
    }

    /**
     * 仓储层已经暴露 knowledgeDomainKey 维度的正式查询入口，
     * 这里优先复用该入口收窄边界；若当前测试替身未提供返回值，再回退到内存过滤。
     */
    private List<RetrievalIndex> narrowReadyIndexesToRelevantDomains(List<RetrievalIndex> readyIndexes,
                                                                     Set<String> relevantDomainKeys) {
        if (relevantDomainKeys == null || relevantDomainKeys.isEmpty()) {
            return readyIndexes;
        }

        List<RetrievalIndex> scopedIndexes = loadScopedReadyIndexes(relevantDomainKeys);
        if (!scopedIndexes.isEmpty()) {
            return scopedIndexes;
        }

        return readyIndexes.stream()
                .filter(index -> matchesRelevantDomainKey(
                        resolveDomainKey(index.getKnowledgeDomainKey(), index.getScopeRefKey(), index.getDocumentKey()),
                        relevantDomainKeys
                ))
                .toList();
    }

    /**
     * 切片层与索引层保持同一知识域边界，避免 sourceUrls 和命中文档来源出现跨域混杂。
     */
    private List<RetrievalChunk> narrowReadyChunksToRelevantDomains(List<RetrievalChunk> readyChunks,
                                                                    Set<String> readyDocumentKeys,
                                                                    Set<String> relevantDomainKeys) {
        if (relevantDomainKeys == null || relevantDomainKeys.isEmpty()) {
            return readyChunks.stream()
                    .filter(chunk -> readyDocumentKeys.contains(chunk.getDocumentKey()))
                    .toList();
        }

        List<RetrievalChunk> scopedChunks = loadScopedReadyChunks(relevantDomainKeys);
        if (!scopedChunks.isEmpty()) {
            return scopedChunks.stream()
                    .filter(chunk -> chunk != null && readyDocumentKeys.contains(chunk.getDocumentKey()))
                    .toList();
        }

        return readyChunks.stream()
                .filter(chunk -> chunk != null && readyDocumentKeys.contains(chunk.getDocumentKey()))
                .filter(chunk -> matchesRelevantDomainKey(
                        resolveDomainKey(chunk.getKnowledgeDomainKey(), chunk.getScopeRefKey(), chunk.getDocumentKey()),
                        relevantDomainKeys
                ))
                .toList();
    }

    private List<RetrievalIndex> loadScopedReadyIndexes(Set<String> relevantDomainKeys) {
        List<RetrievalIndex> scopedIndexes = new ArrayList<>();
        for (String relevantDomainKey : relevantDomainKeys) {
            if (!StringUtils.hasText(relevantDomainKey)) {
                continue;
            }
            List<RetrievalIndex> domainIndexes = retrievalIndexRepository
                    .findByRetrievalScopeAndKnowledgeDomainKeyOrderByIdAsc(DOMAIN_SCOPE, relevantDomainKey);
            if (domainIndexes == null || domainIndexes.isEmpty()) {
                continue;
            }
            scopedIndexes.addAll(domainIndexes.stream()
                    .filter(index -> index != null && "READY".equalsIgnoreCase(index.getStatus()))
                    .toList());
        }
        return scopedIndexes;
    }

    private List<RetrievalChunk> loadScopedReadyChunks(Set<String> relevantDomainKeys) {
        List<RetrievalChunk> scopedChunks = new ArrayList<>();
        for (String relevantDomainKey : relevantDomainKeys) {
            if (!StringUtils.hasText(relevantDomainKey)) {
                continue;
            }
            List<RetrievalChunk> domainChunks = retrievalChunkRepository
                    .findByRetrievalScopeAndKnowledgeDomainKeyOrderByKnowledgeDocumentIdAscChunkIndexAsc(
                            DOMAIN_SCOPE,
                            relevantDomainKey
                    );
            if (domainChunks == null || domainChunks.isEmpty()) {
                continue;
            }
            scopedChunks.addAll(domainChunks.stream()
                    .filter(chunk -> chunk != null && DOMAIN_SCOPE.equalsIgnoreCase(chunk.getRetrievalScope()))
                    .toList());
        }
        return scopedChunks;
    }

    private boolean matchesRelevantDomainKey(String domainKey, Set<String> relevantDomainKeys) {
        if (relevantDomainKeys == null || relevantDomainKeys.isEmpty()) {
            return true;
        }
        return StringUtils.hasText(domainKey) && relevantDomainKeys.contains(domainKey.trim());
    }

    private String resolveDomainKey(String knowledgeDomainKey, String scopeRefKey, String fallbackKey) {
        if (StringUtils.hasText(knowledgeDomainKey)) {
            return knowledgeDomainKey.trim();
        }
        if (StringUtils.hasText(scopeRefKey)) {
            return scopeRefKey.trim();
        }
        return StringUtils.hasText(fallbackKey) ? fallbackKey.trim() : null;
    }

    /**
     * 语义增强仍然属于“可降级能力”，
     * 任何异常都只记录降级标记，不阻断领域级保底召回。
     */
    private void enrichSemanticScoreIfPossible(String query,
                                               List<TaskRetrievalService.RetrievedChunk> chunks,
                                               List<String> issueFlags) {
        if (!StringUtils.hasText(query) || chunks == null || chunks.isEmpty()) {
            return;
        }
        try {
            List<Float> queryVector = embeddingClient.embed(query);
            for (TaskRetrievalService.RetrievedChunk chunk : chunks) {
                List<Float> chunkVector = embeddingClient.embed(firstNonBlank(chunk.getSnippet(), chunk.getContent()));
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

    private TaskRetrievalService.RetrievedChunk toRetrievedChunk(RetrievalChunk chunk, double lexicalScore) {
        return TaskRetrievalService.RetrievedChunk.builder()
                .chunkKey(chunk.getChunkKey())
                .documentKey(chunk.getDocumentKey())
                .retrievalScope(StringUtils.hasText(chunk.getRetrievalScope()) ? chunk.getRetrievalScope() : DOMAIN_SCOPE)
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
     * 领域级召回当前仍采用可解释的词法打底，
     * 并把 knowledgeDomainKey 一并纳入匹配，确保“查询语义”和“知识域边界”共同参与收束。
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
                firstNonBlank(chunk.getSourceCategory(), ""),
                firstNonBlank(chunk.getKnowledgeDomainKey(), ""),
                firstNonBlank(chunk.getScopeRefKey(), "")
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

    private List<String> collectSourceUrls(List<TaskRetrievalService.RetrievedChunk> chunks,
                                           List<RetrievalIndex> readyIndexes) {
        LinkedHashSet<String> sourceUrls = new LinkedHashSet<>();
        if (chunks != null) {
            for (TaskRetrievalService.RetrievedChunk chunk : chunks) {
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
     * 领域级补充要在摘要里显式说明来源层级，
     * 避免下游把它误读成“当前任务自己已经采集到的证据”。
     */
    private String buildGapSummary(List<TaskRetrievalService.RetrievedChunk> topChunks, String nodeName) {
        if (topChunks == null || topChunks.isEmpty()) {
            return "当前没有命中可用的领域知识片段。";
        }
        if (topChunks.size() == 1) {
            return "任务级上下文不足，已回退到领域知识召回，可为" + firstNonBlank(nodeName, "当前节点") + "补充 1 条领域片段。";
        }
        return "任务级上下文不足，已回退到领域知识召回，可为" + firstNonBlank(nodeName, "当前节点")
                + "补充 " + topChunks.size() + " 条领域片段。";
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
}
