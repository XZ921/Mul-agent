package cn.bugstack.competitoragent.rag;

import cn.bugstack.competitoragent.model.entity.EvidenceSource;
import cn.bugstack.competitoragent.model.entity.KnowledgeDocument;
import cn.bugstack.competitoragent.model.entity.RetrievalChunk;
import cn.bugstack.competitoragent.model.entity.RetrievalIndex;
import cn.bugstack.competitoragent.repository.KnowledgeDocumentRepository;
import cn.bugstack.competitoragent.repository.RetrievalChunkRepository;
import cn.bugstack.competitoragent.repository.RetrievalIndexRepository;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * 统一检索索引沉淀服务。
 * <p>
 * 它负责把单条证据或组织级资料稳定沉淀为：
 * 1. `KnowledgeDocument`
 * 2. `RetrievalChunk`
 * 3. `RetrievalIndex`
 * 同时在重跑时做幂等更新，避免旧切片和旧索引继续污染新结果。
 *
 * <p>
 * Task 5.3 开始，这个服务不再只服务任务级证据，
 * 而是统一承接 Task / Domain / Organization 三层正式检索工件。
 */
@Service
public class TaskRetrievalIndexService {

    private final KnowledgeDocumentRepository knowledgeDocumentRepository;
    private final RetrievalChunkRepository retrievalChunkRepository;
    private final RetrievalIndexRepository retrievalIndexRepository;
    private final KnowledgeDocumentBuilder knowledgeDocumentBuilder;
    private final RetrievalChunkingService retrievalChunkingService;
    private final RetrievalScopePolicy retrievalScopePolicy;

    @Autowired
    public TaskRetrievalIndexService(KnowledgeDocumentRepository knowledgeDocumentRepository,
                                     RetrievalChunkRepository retrievalChunkRepository,
                                     RetrievalIndexRepository retrievalIndexRepository,
                                     KnowledgeDocumentBuilder knowledgeDocumentBuilder,
                                     RetrievalChunkingService retrievalChunkingService,
                                     RetrievalScopePolicy retrievalScopePolicy) {
        this.knowledgeDocumentRepository = knowledgeDocumentRepository;
        this.retrievalChunkRepository = retrievalChunkRepository;
        this.retrievalIndexRepository = retrievalIndexRepository;
        this.knowledgeDocumentBuilder = knowledgeDocumentBuilder;
        this.retrievalChunkingService = retrievalChunkingService;
        this.retrievalScopePolicy = retrievalScopePolicy;
    }

    /**
     * 保留旧构造器，兼容当前单测里直接 new service 的调用方式。
     */
    public TaskRetrievalIndexService(KnowledgeDocumentRepository knowledgeDocumentRepository,
                                     RetrievalChunkRepository retrievalChunkRepository,
                                     RetrievalIndexRepository retrievalIndexRepository,
                                     KnowledgeDocumentBuilder knowledgeDocumentBuilder,
                                     RetrievalChunkingService retrievalChunkingService) {
        this(
                knowledgeDocumentRepository,
                retrievalChunkRepository,
                retrievalIndexRepository,
                knowledgeDocumentBuilder,
                retrievalChunkingService,
                new RetrievalScopePolicy()
        );
    }

    @Transactional
    public TaskRetrievalIndexingResult indexEvidence(EvidenceSource evidence) {
        Optional<KnowledgeDocument> existingDocument = knowledgeDocumentRepository
                .findByTaskIdAndEvidenceId(evidence.getTaskId(), evidence.getEvidenceId());
        int nextVersion = existingDocument.map(document -> document.getDocumentVersion() + 1).orElse(1);

        KnowledgeDocument candidate = knowledgeDocumentBuilder.build(evidence, nextVersion);
        KnowledgeDocument document = mergeDocument(existingDocument.orElse(null), candidate);
        return indexKnowledgeDocument(document);
    }

    /**
     * 统一索引入口。
     * <p>
     * Task 5.3 之后，任务级证据和组织级资料都必须走同一条正式检索主链，
     * 避免组织级知识只落在 `knowledge_document` 旁路，导致后续 Domain / Organization RAG 无法复用正式索引体系。
     */
    @Transactional
    public TaskRetrievalIndexingResult indexKnowledgeDocument(KnowledgeDocument document) {
        document.setStatus("PROCESSING");
        document.setFailureReason(null);
        document = knowledgeDocumentRepository.save(document);

        // 同一份文档重跑时，先清空旧切片和旧索引，再写入新的稳定版本，避免脏知识残留。
        retrievalChunkRepository.deleteByKnowledgeDocumentId(document.getId());
        retrievalIndexRepository.deleteByKnowledgeDocumentId(document.getId());

        try {
            List<RetrievalChunk> baseChunks = retrievalChunkingService.chunk(document);
            if (baseChunks.isEmpty()) {
                throw new IllegalStateException("知识文档缺少可切片正文");
            }

            List<RetrievalScopePolicy.ScopeBinding> scopeBindings = retrievalScopePolicy.resolveBindings(document);
            if (scopeBindings.isEmpty()) {
                throw new IllegalStateException("知识文档缺少可用召回作用域");
            }

            List<RetrievalChunk> scopedChunks = buildScopedChunks(document, baseChunks, scopeBindings);
            List<RetrievalChunk> savedChunks = normalizeSavedChunks(
                    retrievalChunkRepository.saveAll(scopedChunks),
                    scopedChunks
            );

            List<RetrievalIndex> readyIndexes = buildReadyIndexes(document, savedChunks, scopeBindings);
            List<RetrievalIndex> savedIndexes = persistReadyIndexes(readyIndexes);
            RetrievalIndex primaryIndex = selectPrimaryIndex(document, savedIndexes);

            document.setStatus("READY");
            document.setFailureReason(null);
            document.setIssueFlags(removeIssueFlag(document.getIssueFlags(), "KNOWLEDGE_INDEX_FAILED"));
            document = knowledgeDocumentRepository.save(document);
            return TaskRetrievalIndexingResult.success(document, savedChunks, primaryIndex, document.getIssueFlags());
        } catch (Exception e) {
            String failureReason = buildFailureReason(e);
            document.setStatus("FAILED");
            document.setFailureReason(failureReason);
            document.setIssueFlags(appendIssueFlags(document.getIssueFlags(), List.of("KNOWLEDGE_INDEX_FAILED")));
            document = knowledgeDocumentRepository.save(document);

            RetrievalIndex failedIndex = retrievalIndexRepository.save(buildFailedIndex(document, failureReason));
            return TaskRetrievalIndexingResult.failed(
                    document,
                    failedIndex,
                    appendIssueFlags(document.getIssueFlags(), List.of("KNOWLEDGE_INDEX_FAILED")),
                    failureReason
            );
        }
    }

    private KnowledgeDocument mergeDocument(KnowledgeDocument existingDocument, KnowledgeDocument candidate) {
        if (existingDocument == null) {
            return candidate;
        }
        existingDocument.setCompetitorName(candidate.getCompetitorName());
        existingDocument.setDocumentKey(candidate.getDocumentKey());
        existingDocument.setKnowledgeScope(candidate.getKnowledgeScope());
        existingDocument.setKnowledgeDomainId(candidate.getKnowledgeDomainId());
        existingDocument.setKnowledgeDomainKey(candidate.getKnowledgeDomainKey());
        existingDocument.setSourceType(candidate.getSourceType());
        existingDocument.setSourceCategory(candidate.getSourceCategory());
        existingDocument.setDiscoveryMethod(candidate.getDiscoveryMethod());
        existingDocument.setSourceDomain(candidate.getSourceDomain());
        existingDocument.setSourceLifecycle(candidate.getSourceLifecycle());
        existingDocument.setTrustLevel(candidate.getTrustLevel());
        existingDocument.setConnectorKey(candidate.getConnectorKey());
        existingDocument.setTitle(candidate.getTitle());
        existingDocument.setUrl(candidate.getUrl());
        existingDocument.setSnippet(candidate.getSnippet());
        existingDocument.setCleanedText(candidate.getCleanedText());
        existingDocument.setSourceUrls(candidate.getSourceUrls());
        existingDocument.setIssueFlags(candidate.getIssueFlags());
        existingDocument.setDocumentVersion(candidate.getDocumentVersion());
        existingDocument.setCollectedAt(candidate.getCollectedAt());
        return existingDocument;
    }

    /**
     * 同一份组织级文档可能同时进入 `DOMAIN` 和 `ORGANIZATION` 两层正式作用域。
     * 这里显式复制出多份检索切片，而不是让后续检索逻辑去猜这一片到底属于哪一层。
     */
    private List<RetrievalChunk> buildScopedChunks(KnowledgeDocument document,
                                                   List<RetrievalChunk> baseChunks,
                                                   List<RetrievalScopePolicy.ScopeBinding> scopeBindings) {
        List<RetrievalChunk> scopedChunks = new ArrayList<>();
        for (RetrievalScopePolicy.ScopeBinding scopeBinding : scopeBindings) {
            for (RetrievalChunk baseChunk : baseChunks) {
                scopedChunks.add(RetrievalChunk.builder()
                        .taskId(scopeBinding.taskId())
                        .knowledgeDocumentId(baseChunk.getKnowledgeDocumentId())
                        .competitorName(baseChunk.getCompetitorName())
                        .evidenceId(baseChunk.getEvidenceId())
                        .documentKey(baseChunk.getDocumentKey())
                        .retrievalScope(scopeBinding.retrievalScope().name())
                        .scopeRefKey(scopeBinding.scopeRefKey())
                        .knowledgeDomainKey(scopeBinding.knowledgeDomainKey())
                        .chunkKey(buildChunkKey(document, baseChunk.getChunkIndex(), scopeBinding.retrievalScope()))
                        .chunkIndex(baseChunk.getChunkIndex())
                        .startOffset(baseChunk.getStartOffset())
                        .endOffset(baseChunk.getEndOffset())
                        .sourceCategory(baseChunk.getSourceCategory())
                        .documentVersion(baseChunk.getDocumentVersion())
                        .content(baseChunk.getContent())
                        .snippet(baseChunk.getSnippet())
                        .sourceUrls(baseChunk.getSourceUrls())
                        .issueFlags(baseChunk.getIssueFlags())
                        .build());
            }
        }
        return scopedChunks;
    }

    private List<RetrievalIndex> buildReadyIndexes(KnowledgeDocument document,
                                                   List<RetrievalChunk> chunks,
                                                   List<RetrievalScopePolicy.ScopeBinding> scopeBindings) {
        List<RetrievalIndex> indexes = new ArrayList<>();
        for (RetrievalScopePolicy.ScopeBinding scopeBinding : scopeBindings) {
            int chunkCount = (int) chunks.stream()
                    .filter(chunk -> scopeBinding.retrievalScope().name().equalsIgnoreCase(chunk.getRetrievalScope()))
                    .filter(chunk -> safeEquals(scopeBinding.scopeRefKey(), chunk.getScopeRefKey()))
                    .count();
            indexes.add(buildReadyIndex(document, scopeBinding, chunkCount));
        }
        return indexes;
    }

    /**
     * 真实仓储通常会稳定返回 saveAll 的结果，但测试替身或极简实现不一定如此。
     * 这里统一做兜底，保证索引主链的返回对象不会因为批量保存返回 null 而丢失。
     */
    private List<RetrievalIndex> persistReadyIndexes(List<RetrievalIndex> readyIndexes) {
        List<RetrievalIndex> savedIndexes = retrievalIndexRepository.saveAll(readyIndexes);
        if (savedIndexes != null && !savedIndexes.isEmpty()) {
            return savedIndexes;
        }

        List<RetrievalIndex> fallbackIndexes = new ArrayList<>();
        for (RetrievalIndex readyIndex : readyIndexes) {
            fallbackIndexes.add(retrievalIndexRepository.save(readyIndex));
        }
        return fallbackIndexes;
    }

    /**
     * 切片批量保存同样保留一个兜底结果。
     * 这样即便仓储替身没有返回持久化集合，后续索引统计和测试断言仍能基于当前稳定切片工作。
     */
    private List<RetrievalChunk> normalizeSavedChunks(List<RetrievalChunk> savedChunks,
                                                      List<RetrievalChunk> fallbackChunks) {
        if (savedChunks != null && !savedChunks.isEmpty()) {
            return savedChunks;
        }
        return fallbackChunks;
    }

    private RetrievalIndex buildReadyIndex(KnowledgeDocument document,
                                           RetrievalScopePolicy.ScopeBinding scopeBinding,
                                           int chunkCount) {
        return RetrievalIndex.builder()
                .taskId(scopeBinding.taskId())
                .knowledgeDocumentId(document.getId())
                .competitorName(document.getCompetitorName())
                .evidenceId(document.getEvidenceId())
                .documentKey(document.getDocumentKey())
                .retrievalScope(scopeBinding.retrievalScope().name())
                .scopeRefKey(scopeBinding.scopeRefKey())
                .knowledgeDomainKey(scopeBinding.knowledgeDomainKey())
                .indexKey(buildIndexKey(document, scopeBinding.retrievalScope()))
                .indexScope(scopeBinding.retrievalScope().name())
                .sourceCategory(document.getSourceCategory())
                .documentVersion(document.getDocumentVersion())
                .chunkCount(chunkCount)
                .status("READY")
                .failureReason(null)
                .sourceUrls(document.getSourceUrls())
                .issueFlags(document.getIssueFlags())
                .build();
    }

    private RetrievalIndex buildFailedIndex(KnowledgeDocument document, String failureReason) {
        RetrievalScopePolicy.ScopeBinding primaryBinding = selectPrimaryBinding(
                document,
                retrievalScopePolicy.resolveBindings(document)
        );
        String retrievalScope = primaryBinding == null ? RetrievalScope.TASK.name() : primaryBinding.retrievalScope().name();
        String scopeRefKey = primaryBinding == null
                ? defaultTaskScopeRefKey(document.getTaskId())
                : primaryBinding.scopeRefKey();
        String knowledgeDomainKey = primaryBinding == null
                ? document.getKnowledgeDomainKey()
                : primaryBinding.knowledgeDomainKey();

        return RetrievalIndex.builder()
                .taskId(primaryBinding == null ? document.getTaskId() : primaryBinding.taskId())
                .knowledgeDocumentId(document.getId())
                .competitorName(document.getCompetitorName())
                .evidenceId(document.getEvidenceId())
                .documentKey(document.getDocumentKey())
                .retrievalScope(retrievalScope)
                .scopeRefKey(scopeRefKey)
                .knowledgeDomainKey(knowledgeDomainKey)
                .indexKey(buildIndexKey(document, RetrievalScope.fromText(retrievalScope)))
                .indexScope(retrievalScope)
                .sourceCategory(document.getSourceCategory())
                .documentVersion(document.getDocumentVersion())
                .chunkCount(0)
                .status("FAILED")
                .failureReason(failureReason)
                .sourceUrls(document.getSourceUrls())
                .issueFlags(appendIssueFlags(document.getIssueFlags(), List.of("KNOWLEDGE_INDEX_FAILED")))
                .build();
    }

    private String buildFailureReason(Exception e) {
        if (e == null || !StringUtils.hasText(e.getMessage())) {
            return "知识文档切片或索引失败";
        }
        return e.getMessage().trim();
    }

    private List<String> appendIssueFlags(List<String> currentFlags, List<String> extraFlags) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (currentFlags != null) {
            merged.addAll(currentFlags);
        }
        if (extraFlags != null) {
            merged.addAll(extraFlags);
        }
        return new ArrayList<>(merged);
    }

    private List<String> removeIssueFlag(List<String> currentFlags, String issueFlag) {
        List<String> normalized = new ArrayList<>();
        if (currentFlags != null) {
            for (String currentFlag : currentFlags) {
                if (currentFlag != null
                        && !currentFlag.isBlank()
                        && !currentFlag.equalsIgnoreCase(issueFlag)) {
                    normalized.add(currentFlag);
                }
            }
        }
        return normalized;
    }

    private String buildChunkKey(KnowledgeDocument document, Integer chunkIndex, RetrievalScope retrievalScope) {
        int safeChunkIndex = chunkIndex == null ? 0 : chunkIndex;
        if (retrievalScope == RetrievalScope.TASK) {
            return document.getDocumentKey() + "#CHUNK-" + String.format("%03d", safeChunkIndex + 1);
        }
        return document.getDocumentKey()
                + "#"
                + shortScopeCode(retrievalScope)
                + "-"
                + String.format("%03d", safeChunkIndex + 1);
    }

    private String buildIndexKey(KnowledgeDocument document, RetrievalScope retrievalScope) {
        return document.getDocumentKey() + "#" + retrievalScope.name();
    }

    /**
     * 结果对象目前仍只返回一个“主索引”，
     * 这里显式规定优先级，保证旧调用方仍能拿到最符合当前文档归属语义的摘要对象。
     */
    private RetrievalIndex selectPrimaryIndex(KnowledgeDocument document, List<RetrievalIndex> indexes) {
        if (indexes == null || indexes.isEmpty()) {
            return null;
        }
        RetrievalScope preferredScope = preferredScope(document);
        return indexes.stream()
                .filter(index -> preferredScope.name().equalsIgnoreCase(index.getRetrievalScope()))
                .findFirst()
                .orElse(indexes.get(0));
    }

    private RetrievalScopePolicy.ScopeBinding selectPrimaryBinding(KnowledgeDocument document,
                                                                   List<RetrievalScopePolicy.ScopeBinding> bindings) {
        if (bindings == null || bindings.isEmpty()) {
            return null;
        }
        RetrievalScope preferredScope = preferredScope(document);
        return bindings.stream()
                .filter(binding -> binding.retrievalScope() == preferredScope)
                .findFirst()
                .orElse(bindings.get(0));
    }

    private RetrievalScope preferredScope(KnowledgeDocument document) {
        return switch (RetrievalScope.fromText(document.getKnowledgeScope())) {
            case ORGANIZATION -> RetrievalScope.ORGANIZATION;
            case DOMAIN -> RetrievalScope.DOMAIN;
            default -> RetrievalScope.TASK;
        };
    }

    private String shortScopeCode(RetrievalScope retrievalScope) {
        return switch (retrievalScope) {
            case DOMAIN -> "D";
            case ORGANIZATION -> "O";
            default -> "T";
        };
    }

    private String defaultTaskScopeRefKey(Long taskId) {
        return taskId == null ? "ORGANIZATION" : String.valueOf(taskId);
    }

    private boolean safeEquals(String left, String right) {
        if (left == null) {
            return right == null;
        }
        return left.equals(right);
    }
}
