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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 组织级召回服务。
 * <p>
 * Task 5.3.c 需要把“组织公共知识底座”正式纳入检索主链，
 * 因此这里单独沉淀组织级召回入口，负责：
 * 1. 只在 `ORGANIZATION` 作用域内检索；
 * 2. 保留 evidenceId、sourceUrls 等可追溯字段；
 * 3. 在 embedding 不可用时显式降级到可解释的词法召回。
 */
@Service
public class OrganizationRetrievalService {

    private static final String ORGANIZATION_SCOPE = "ORGANIZATION";

    private final RetrievalIndexRepository retrievalIndexRepository;
    private final RetrievalChunkRepository retrievalChunkRepository;
    private final EmbeddingClient embeddingClient;
    private final int topK;
    private final int candidatePoolSize;

    public OrganizationRetrievalService(RetrievalIndexRepository retrievalIndexRepository,
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
     * 组织级资料只在任务级、领域级都不足时作为最后一层公共补充，
     * 因此这里返回的结果本身不做“越层提升”，只负责提供稳定的组织级候选。
     */
    public TaskRetrievalService.RetrievalResult retrieve(String query, String nodeName) {
        List<String> issueFlags = new ArrayList<>();
        List<RetrievalIndex> readyIndexes = retrievalIndexRepository
                .findByRetrievalScopeAndScopeRefKeyOrderByIdAsc(ORGANIZATION_SCOPE, ORGANIZATION_SCOPE).stream()
                .filter(index -> index != null && "READY".equalsIgnoreCase(index.getStatus()))
                .toList();
        if (readyIndexes.isEmpty()) {
            issueFlags.add("NO_READY_ORGANIZATION_RETRIEVAL_INDEX");
            return TaskRetrievalService.RetrievalResult.builder()
                    .query(query)
                    .gapSummary("当前没有可用的组织级公共知识索引，暂时无法补充组织级召回。")
                    .issueFlags(issueFlags)
                    .build();
        }

        Set<String> readyDocumentKeys = readyIndexes.stream()
                .map(RetrievalIndex::getDocumentKey)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());

        List<RetrievalChunk> readyChunks = retrievalChunkRepository
                .findByRetrievalScopeAndScopeRefKeyOrderByKnowledgeDocumentIdAscChunkIndexAsc(
                        ORGANIZATION_SCOPE,
                        ORGANIZATION_SCOPE
                ).stream()
                .filter(chunk -> chunk != null && readyDocumentKeys.contains(chunk.getDocumentKey()))
                .toList();
        if (readyChunks.isEmpty()) {
            issueFlags.add("NO_READY_ORGANIZATION_RETRIEVAL_CHUNK");
            return TaskRetrievalService.RetrievalResult.builder()
                    .query(query)
                    .gapSummary("当前组织级公共知识没有可用切片，暂时无法补充组织级召回。")
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
     * embedding 失败时仍然必须把组织级资料作为兜底候选返回，
     * 因此这里只记录降级标记，不中断主链路。
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
                .retrievalScope(chunk.getRetrievalScope())
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
     * 组织级召回仍然优先使用可解释的词法打分，
     * 避免“命中是有了，但无法解释为什么命中”的黑盒结果。
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
                firstNonBlank(chunk.getKnowledgeDomainKey(), "")
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
     * 组织级摘要要明确说明这是公共知识补充，
     * 避免下游把它误读为“当前任务已经亲自采集到的证据”。
     */
    private String buildGapSummary(List<TaskRetrievalService.RetrievedChunk> topChunks, String nodeName) {
        if (topChunks == null || topChunks.isEmpty()) {
            return "当前没有命中可用的组织级公共知识片段。";
        }
        if (topChunks.size() == 1) {
            return "任务级与领域级上下文仍不足，已回退到组织级公共知识，可为"
                    + firstNonBlank(nodeName, "当前节点") + "补充 1 条组织级片段。";
        }
        return "任务级与领域级上下文仍不足，已回退到组织级公共知识，可为"
                + firstNonBlank(nodeName, "当前节点") + "补充 " + topChunks.size() + " 条组织级片段。";
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
