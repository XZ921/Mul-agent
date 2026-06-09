package cn.bugstack.competitoragent.context;

import java.util.ArrayList;
import java.util.List;

/**
 * Task RAG 对外摘要格式化器。
 * <p>
 * Agent 内部 Prompt 可以保留完整的检索命中细节，但对话与报告对外暴露时，
 * 需要稳定说明“知识上下文 / 可复用记忆 / 任务即时上下文”的边界，
 * 同时去掉切片键、sourceUrls 等底层噪音字段。
 */
public final class TaskRagContextSummaryFormatter {

    private static final String SECTION_KNOWLEDGE = "知识上下文";
    private static final String SECTION_CHUNKS = "命中片段：";
    private static final String SECTION_REUSABLE_MEMORY = "可复用记忆";
    private static final String SECTION_RUNTIME_CONTEXT = "任务即时上下文";
    private static final String EMPTY_PLACEHOLDER = "无";

    private TaskRagContextSummaryFormatter() {
    }

    /**
     * 统一把内部 Prompt 级 Task RAG 文本收敛成对外可解释摘要。
     * 这里使用分段状态机而不是简单正则删除，避免把“可复用记忆 / 任务即时上下文”的编号行误删。
     */
    public static String format(String rawContext) {
        if (rawContext == null || rawContext.isBlank()) {
            return rawContext;
        }
        List<String> summaryLines = new ArrayList<>();
        Section currentSection = Section.NONE;
        for (String rawLine : rawContext.split("\\R")) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.isBlank()) {
                continue;
            }
            Section nextSection = resolveSection(line);
            if (nextSection != Section.NONE) {
                currentSection = nextSection;
                if (nextSection != Section.CHUNKS) {
                    summaryLines.add(line);
                }
                continue;
            }
            String sanitizedLine = sanitizeLine(currentSection, line);
            if (sanitizedLine == null || sanitizedLine.isBlank()) {
                continue;
            }
            summaryLines.add(sanitizedLine);
        }
        return summaryLines.isEmpty() ? rawContext.trim() : String.join("\n", summaryLines);
    }

    /**
     * 按当前分段决定保留什么内容：
     * 1. 知识上下文保留查询、摘要、缺口、来源，以及去噪后的命中条目；
     * 2. 可复用记忆保留编号条目，但移除行尾 sourceUrls；
     * 3. 任务即时上下文保留编号条目，帮助解释当前任务确认了什么。
     */
    private static String sanitizeLine(Section currentSection, String line) {
        return switch (currentSection) {
            case NONE, KNOWLEDGE -> sanitizeKnowledgeLine(line);
            case CHUNKS -> sanitizeChunkLine(line);
            case REUSABLE_MEMORY, RUNTIME_CONTEXT -> sanitizeContextBoundaryLine(line);
        };
    }

    private static Section resolveSection(String line) {
        if (SECTION_KNOWLEDGE.equals(line)) {
            return Section.KNOWLEDGE;
        }
        if (SECTION_CHUNKS.equals(line)) {
            return Section.CHUNKS;
        }
        if (SECTION_REUSABLE_MEMORY.equals(line)) {
            return Section.REUSABLE_MEMORY;
        }
        if (SECTION_RUNTIME_CONTEXT.equals(line)) {
            return Section.RUNTIME_CONTEXT;
        }
        return Section.NONE;
    }

    private static String sanitizeKnowledgeLine(String line) {
        if (line.startsWith("检索查询：")
                || line.startsWith("检索摘要：")
                || line.startsWith("缺口说明：")
                || line.startsWith("来源链接：")
                || EMPTY_PLACEHOLDER.equals(line)) {
            return line;
        }
        if (line.matches("^\\d+\\..*")) {
            return sanitizeChunkLine(line);
        }
        return null;
    }

    /**
     * 命中片段对外仍然保留“证据摘要 / 召回层级 / 知识文档 / 命中原因”，
     * 这样报告侧还能解释命中了哪类知识，但不会暴露切片键和 sourceUrls= 这类底层索引细节。
     */
    private static String sanitizeChunkLine(String line) {
        return stripSegments(line, "切片键：", "sourceUrls=");
    }

    /**
     * 记忆与运行时上下文要继续保留编号结构，只去掉行尾 sourceUrls，
     * 让用户仍能区分“这是复用记忆”还是“这是本轮任务现场确认的信息”。
     */
    private static String sanitizeContextBoundaryLine(String line) {
        if (EMPTY_PLACEHOLDER.equals(line)) {
            return line;
        }
        if (line.matches("^\\d+\\..*")) {
            return stripSegments(line, "sourceUrls=");
        }
        return null;
    }

    /**
     * Task RAG Prompt 采用“| 字段”拼接格式，这里按段过滤掉不该对外暴露的部分。
     */
    private static String stripSegments(String line, String... hiddenPrefixes) {
        String[] segments = line.split("\\s*\\|\\s*");
        List<String> keptSegments = new ArrayList<>();
        for (String rawSegment : segments) {
            String segment = rawSegment == null ? "" : rawSegment.trim();
            if (segment.isBlank() || shouldHideSegment(segment, hiddenPrefixes)) {
                continue;
            }
            keptSegments.add(segment);
        }
        return keptSegments.isEmpty() ? null : String.join(" | ", keptSegments);
    }

    private static boolean shouldHideSegment(String segment, String... hiddenPrefixes) {
        for (String hiddenPrefix : hiddenPrefixes) {
            if (segment.startsWith(hiddenPrefix)) {
                return true;
            }
        }
        return false;
    }

    private enum Section {
        NONE,
        KNOWLEDGE,
        CHUNKS,
        REUSABLE_MEMORY,
        RUNTIME_CONTEXT
    }
}
