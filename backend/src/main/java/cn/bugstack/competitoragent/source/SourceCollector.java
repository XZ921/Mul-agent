package cn.bugstack.competitoragent.source;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 信息采集器接口
 * <p>
 * 第一版使用 Playwright-Java 实现，后续可扩展 HTTP 轻量采集等方案。
 */
public interface SourceCollector {

    /**
     * 采集单个 URL 的页面内容
     *
     * @param url         目标 URL
     * @param competitorName 所属竞品名称
     * @param sourceType  信息源类型（官网/文档/定价页等）
     * @return 采集结果
     */
    CollectedPage collect(String url, String competitorName, String sourceType);

    /**
     * 批量采集多个 URL
     */
    List<CollectedPage> collectBatch(List<String> urls, String competitorName, String sourceType);

    // ==================== 数据类 ====================

    /**
     * 采集到的页面信息
     */
    @Data
    @Builder
    class CollectedPage {
        /** 页面 URL */
        private String url;
        /** 页面标题 */
        private String title;
        /** 采集到的正文内容（清洗后） */
        private String content;
        /** 原文引用片段（前 500 字符） */
        private String snippet;
        /** 页面元数据 (JSON) */
        private String metadata;
        /** 所属竞品名称 */
        private String competitorName;
        /** 信息源类型 */
        private String sourceType;
        /** 采集时间 */
        private String collectedAt;
        /** 是否采集成功 */
        private boolean success;
        /** 失败原因 */
        private String errorMessage;
    }
}
