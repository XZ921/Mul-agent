package cn.bugstack.competitoragent.rag;

import cn.bugstack.competitoragent.model.entity.KnowledgeDocument;
import cn.bugstack.competitoragent.model.entity.RetrievalChunk;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 把知识文档切成可检索分片。
 * <p>
 * 当前阶段采用稳定的字符窗口切片策略，不绑定具体向量库。
 * 重点是保证每个切片都能回指到文档与来源，而不是追求复杂的语义切分算法。
 */
@Component
public class RetrievalChunkingService {

    private final int chunkSize;
    private final int chunkOverlap;

    public RetrievalChunkingService(@Value("${task.rag.chunk-size:600}") int chunkSize,
                                    @Value("${task.rag.chunk-overlap:80}") int chunkOverlap) {
        this.chunkSize = Math.max(chunkSize, 1);
        this.chunkOverlap = Math.max(0, Math.min(chunkOverlap, this.chunkSize - 1));
    }

    public List<RetrievalChunk> chunk(KnowledgeDocument document) {
        if (document == null || !StringUtils.hasText(document.getCleanedText())) {
            return List.of();
        }

        List<RetrievalChunk> chunks = new ArrayList<>();
        String text = document.getCleanedText().trim();
        int start = 0;
        int index = 0;

        while (start < text.length()) {
            int end = Math.min(text.length(), start + chunkSize);
            if (end < text.length()) {
                end = extendToReadableBoundary(text, end);
            }

            String chunkContent = text.substring(start, end).trim();
            if (!chunkContent.isBlank()) {
                chunks.add(RetrievalChunk.builder()
                        .taskId(document.getTaskId())
                        .knowledgeDocumentId(document.getId())
                        .competitorName(document.getCompetitorName())
                        .evidenceId(document.getEvidenceId())
                        .documentKey(document.getDocumentKey())
                        .chunkKey(document.getDocumentKey() + "#CHUNK-" + String.format("%03d", index + 1))
                        .chunkIndex(index)
                        .startOffset(start)
                        .endOffset(end)
                        .sourceCategory(document.getSourceCategory())
                        .documentVersion(document.getDocumentVersion())
                        .content(chunkContent)
                        .snippet(chunkContent.length() <= 180 ? chunkContent : chunkContent.substring(0, 180))
                        .sourceUrls(document.getSourceUrls())
                        .issueFlags(document.getIssueFlags())
                        .build());
                index++;
            }

            if (end >= text.length()) {
                break;
            }

            // 下一片回退一个重叠窗口，既保证上下文连续，又避免起点不前进导致死循环。
            int nextStart = Math.max(end - chunkOverlap, start + 1);
            while (nextStart < text.length() && Character.isWhitespace(text.charAt(nextStart))) {
                nextStart++;
            }
            start = nextStart;
        }
        return chunks;
    }

    /**
     * 优先把切片边界扩展到空白符，减少把单词硬截断造成的检索噪音。
     */
    private int extendToReadableBoundary(String text, int boundary) {
        int cursor = boundary;
        while (cursor < text.length() && !Character.isWhitespace(text.charAt(cursor))) {
            cursor++;
        }
        return cursor <= boundary ? boundary : cursor;
    }
}
