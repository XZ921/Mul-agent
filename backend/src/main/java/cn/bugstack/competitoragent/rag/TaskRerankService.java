package cn.bugstack.competitoragent.rag;

import cn.bugstack.competitoragent.llm.RerankClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务级重排服务。
 * <p>
 * 重排只负责在已有召回结果上做顺序优化，任何外部服务失败都必须回退到原始召回排序，
 * 避免因为辅助能力不可用而阻断主流程。
 */
@Service
public class TaskRerankService {

    private static final String TASK_SCOPE = "TASK";
    private static final String DOMAIN_SCOPE = "DOMAIN";
    private static final String ORGANIZATION_SCOPE = "ORGANIZATION";

    private final RerankClient rerankClient;
    public TaskRerankService(RerankClient rerankClient) {
        this.rerankClient = rerankClient;
    }

    public List<TaskRetrievalService.RetrievedChunk> rerank(String query,
                                                            List<TaskRetrievalService.RetrievedChunk> chunks,
                                                            String nodeName) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        if (!StringUtils.hasText(query)) {
            return cloneChunks(chunks);
        }

        try {
            // 网关层已经承担重试、熔断和故障转移，这里只消费统一能力结果。
            List<RerankClient.RerankRecord> rerankRecords = rerankOnce(query, chunks);
            return applyRerankScores(chunks, rerankRecords);
        } catch (RuntimeException e) {
            // 重排失败时只做显式降级标记，不改变原始召回的可追溯信息与兜底排序。
            return markDegraded(chunks, nodeName, e.getMessage());
        }
    }

    private List<RerankClient.RerankRecord> rerankOnce(String query,
                                                       List<TaskRetrievalService.RetrievedChunk> chunks) {
        // 网关层已经承担 Provider 级重试与故障转移，这里只负责把当前候选片段转换成统一重排输入。
        List<String> documents = chunks.stream()
                .map(chunk -> firstNonBlank(chunk.getSnippet(), chunk.getContent()))
                .toList();
        return rerankClient.rerank(query, documents);
    }

    /**
     * 外部重排返回的只是“索引 -> 分数”映射，这里统一投影回原始 chunk，
     * 保证 evidenceId、sourceUrls、sourceCategory 等审计字段不被丢失。
     */
    private List<TaskRetrievalService.RetrievedChunk> applyRerankScores(List<TaskRetrievalService.RetrievedChunk> chunks,
                                                                        List<RerankClient.RerankRecord> rerankRecords) {
        if (rerankRecords == null || rerankRecords.isEmpty()) {
            return markDegraded(chunks, null, "empty rerank result");
        }
        Map<Integer, Double> scoreByIndex = new LinkedHashMap<>();
        for (RerankClient.RerankRecord rerankRecord : rerankRecords) {
            if (rerankRecord == null || rerankRecord.index() < 0 || rerankRecord.index() >= chunks.size()) {
                continue;
            }
            scoreByIndex.putIfAbsent(rerankRecord.index(), rerankRecord.score());
        }
        if (scoreByIndex.isEmpty()) {
            return markDegraded(chunks, null, "invalid rerank result");
        }

        List<TaskRetrievalService.RetrievedChunk> rerankedChunks = new ArrayList<>();
        for (int index = 0; index < chunks.size(); index++) {
            TaskRetrievalService.RetrievedChunk chunk = chunks.get(index);
            rerankedChunks.add(copyChunk(
                    chunk,
                    scoreByIndex.containsKey(index) ? scoreByIndex.get(index) : chunk.getScore(),
                    chunk.getIssueFlags()
            ));
        }
        return sortByScopeThenScore(rerankedChunks);
    }

    private List<TaskRetrievalService.RetrievedChunk> markDegraded(List<TaskRetrievalService.RetrievedChunk> chunks,
                                                                   String nodeName,
                                                                   String failureMessage) {
        List<TaskRetrievalService.RetrievedChunk> degradedChunks = new ArrayList<>();
        for (TaskRetrievalService.RetrievedChunk chunk : chunks) {
            List<String> issueFlags = new ArrayList<>();
            if (chunk.getIssueFlags() != null) {
                issueFlags.addAll(chunk.getIssueFlags());
            }
            if (!issueFlags.contains("RERANK_DEGRADED")) {
                issueFlags.add("RERANK_DEGRADED");
            }
            if (StringUtils.hasText(nodeName) && !issueFlags.contains("RERANK_FALLBACK_" + nodeName.toUpperCase())) {
                issueFlags.add("RERANK_FALLBACK_" + nodeName.toUpperCase());
            }
            degradedChunks.add(copyChunk(chunk, chunk.getScore(), issueFlags));
        }
        return sortByScopeThenScore(degradedChunks);
    }

    private List<TaskRetrievalService.RetrievedChunk> cloneChunks(List<TaskRetrievalService.RetrievedChunk> chunks) {
        List<TaskRetrievalService.RetrievedChunk> copies = new ArrayList<>();
        for (TaskRetrievalService.RetrievedChunk chunk : chunks) {
            copies.add(copyChunk(chunk, chunk.getScore(), chunk.getIssueFlags()));
        }
        return sortByScopeThenScore(copies);
    }

    /**
     * Task 5.3.c 明确要求跨层结果保留 Task -> Domain -> Organization 的默认顺序，
     * 因此重排只能在同层内部按分数调序，不能把低层级资料提升到高层级之前。
     */
    private List<TaskRetrievalService.RetrievedChunk> sortByScopeThenScore(List<TaskRetrievalService.RetrievedChunk> chunks) {
        List<TaskRetrievalService.RetrievedChunk> sortedChunks = new ArrayList<>(chunks);
        sortedChunks.sort(Comparator
                .comparingInt((TaskRetrievalService.RetrievedChunk chunk) -> scopePriority(chunk.getRetrievalScope()))
                .thenComparing((TaskRetrievalService.RetrievedChunk chunk) -> safeScore(chunk.getScore()), Comparator.reverseOrder()));
        return sortedChunks;
    }

    private int scopePriority(String retrievalScope) {
        if (TASK_SCOPE.equalsIgnoreCase(retrievalScope)) {
            return 0;
        }
        if (DOMAIN_SCOPE.equalsIgnoreCase(retrievalScope)) {
            return 1;
        }
        if (ORGANIZATION_SCOPE.equalsIgnoreCase(retrievalScope)) {
            return 2;
        }
        return 99;
    }

    private TaskRetrievalService.RetrievedChunk copyChunk(TaskRetrievalService.RetrievedChunk chunk,
                                                          Double score,
                                                          List<String> issueFlags) {
        return TaskRetrievalService.RetrievedChunk.builder()
                .chunkKey(chunk.getChunkKey())
                .documentKey(chunk.getDocumentKey())
                .retrievalScope(chunk.getRetrievalScope())
                .competitorName(chunk.getCompetitorName())
                .evidenceId(chunk.getEvidenceId())
                .sourceCategory(chunk.getSourceCategory())
                .snippet(chunk.getSnippet())
                .content(chunk.getContent())
                .score(score)
                .sourceUrls(chunk.getSourceUrls() == null ? List.of() : new ArrayList<>(chunk.getSourceUrls()))
                .issueFlags(issueFlags == null ? List.of() : new ArrayList<>(issueFlags))
                .build();
    }

    private double safeScore(Double score) {
        return score == null ? 0D : score;
    }

    private String firstNonBlank(String primary, String fallback) {
        if (StringUtils.hasText(primary)) {
            return primary;
        }
        return fallback == null ? "" : fallback;
    }
}
