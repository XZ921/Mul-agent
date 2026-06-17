package cn.bugstack.competitoragent.collection;

import lombok.Builder;
import lombok.Value;

/**
 * 结构化内容块。
 * 用于在正文之外显式保留价格卡片、文档目录、JSON-LD 等结构信号，
 * 避免后续只能依赖一段大正文做分析。
 */
@Value
@Builder
public class StructuredContentBlock {

    String blockType;
    String title;
    String content;
    String qualitySignal;
}
