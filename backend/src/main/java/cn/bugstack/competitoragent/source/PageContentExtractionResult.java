package cn.bugstack.competitoragent.source;

import cn.bugstack.competitoragent.collection.StructuredContentBlock;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.List;

/**
 * 页面内容提取结果。
 * 当前先建立正式对象骨架，让第五轮后续双路径采集与结构块测试能共享同一份结果协议。
 */
@Value
@Builder
public class PageContentExtractionResult {

    boolean success;
    String title;
    String mainContent;
    String failureKind;
    String errorMessage;
    List<String> qualitySignals;
    Double qualityScore;
    List<StructuredContentBlock> structuredBlocks;
    Instant collectedAt;
    Long durationMillis;

    /**
     * 先提供最小可用判断，后续 Task 5 会进一步细化质量评分阈值。
     */
    public boolean isUsable() {
        return success
                && ((mainContent != null && !mainContent.isBlank())
                || (structuredBlocks != null && !structuredBlocks.isEmpty()));
    }
}
