package cn.bugstack.competitoragent.context;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * 统一的任务级上下文包。
 * <p>
 * 它把知识检索结果、可复用记忆和任务即时上下文集中为一个稳定结构，
 * 让下游 Agent 能明确判断每段信息来自哪里、受什么边界约束。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskRagContextBundle {

    private String query;

    private String retrievalSummary;

    private String gapSummary;

    @Builder.Default
    private List<String> sourceUrls = new ArrayList<>();

    @Builder.Default
    private List<String> issueFlags = new ArrayList<>();

    @Builder.Default
    private List<ContextChunk> chunks = new ArrayList<>();

    /**
     * 可复用记忆必须独立呈现，避免知识检索结果和历史记忆混成一段不可解释的文本。
     */
    @Builder.Default
    private List<ReusableMemoryItem> reusableMemoryItems = new ArrayList<>();

    /**
     * 任务即时上下文承接当前任务现场已经确认的输出，不应与知识或历史记忆混淆。
     */
    @Builder.Default
    private List<RuntimeContextItem> runtimeContextItems = new ArrayList<>();

    private Long memorySnapshotId;

    /**
     * 生成可直接注入 Prompt 的结构化文本。
     */
    public String toPromptText() {
        StringJoiner joiner = new StringJoiner("\n");

        joiner.add("知识上下文");
        joiner.add("检索查询：" + safe(query));
        joiner.add("检索摘要：" + safe(retrievalSummary));
        joiner.add("缺口说明：" + safe(gapSummary));
        joiner.add("来源链接：" + joinValues(sourceUrls));
        if (chunks != null && !chunks.isEmpty()) {
            joiner.add("命中片段：");
            for (int index = 0; index < chunks.size(); index++) {
                ContextChunk chunk = chunks.get(index);
                if (chunk == null) {
                    continue;
                }
                joiner.add((index + 1) + ". [" + safe(chunk.getEvidenceId()) + "] "
                        + safe(chunk.getSnippet())
                        + " | 召回层级：" + safe(chunk.getRetrievalScope())
                        + " | 知识文档：" + safe(chunk.getDocumentKey())
                        + " | 切片键：" + safe(chunk.getChunkKey())
                        + " | 命中原因：" + safe(chunk.getSourceCategory())
                        + " | sourceUrls=" + joinValues(chunk.getSourceUrls()));
            }
        }

        joiner.add("可复用记忆");
        if (reusableMemoryItems == null || reusableMemoryItems.isEmpty()) {
            joiner.add("无");
        } else {
            for (int index = 0; index < reusableMemoryItems.size(); index++) {
                ReusableMemoryItem item = reusableMemoryItems.get(index);
                if (item == null) {
                    continue;
                }
                joiner.add((index + 1) + ". "
                        + safe(item.getSummary())
                        + " | 记忆层级：" + safe(item.getMemoryLayer())
                        + " | 来源对象：" + safe(item.getSourceObjectType())
                        + " | 来源节点/对象：" + safe(item.getSourceNodeName())
                        + " | versionSource=" + safe(item.getVersionSource())
                        + " | invalidationScope=" + safe(item.getInvalidationScope())
                        + " | invalidationReason=" + safe(item.getInvalidationReason())
                        + " | reuseReason=" + safe(item.getReuseReason())
                        + " | sourceUrls=" + joinValues(item.getSourceUrls()));
            }
        }

        joiner.add("任务即时上下文");
        if (runtimeContextItems == null || runtimeContextItems.isEmpty()) {
            joiner.add("无");
        } else {
            for (int index = 0; index < runtimeContextItems.size(); index++) {
                RuntimeContextItem item = runtimeContextItems.get(index);
                if (item == null) {
                    continue;
                }
                joiner.add((index + 1) + ". "
                        + safe(item.getSourceNodeName())
                        + " -> " + safe(item.getSummary()));
            }
        }
        return joiner.toString();
    }

    /**
     * 统一处理列表输出，避免 Prompt 中直接暴露 null 或空数组。
     */
    private String joinValues(List<String> values) {
        return values == null || values.isEmpty() ? "无" : String.join("；", values);
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "无" : value;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ContextChunk {
        private String chunkKey;
        private String documentKey;

        /**
         * 跨层召回时必须显式保留命中层级，避免后续上下文无法解释边界。
         */
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

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReusableMemoryItem {
        private String memoryLayer;
        private String sourceObjectType;
        private String sourceNodeName;
        private Long sourceRecordId;
        private Long sourceTaskId;
        private String summary;
        private String versionSource;
        private String invalidationScope;
        private String invalidationReason;
        private String reuseReason;

        @Builder.Default
        private List<String> sourceUrls = new ArrayList<>();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RuntimeContextItem {
        private String sourceNodeName;
        private String summary;
    }
}
