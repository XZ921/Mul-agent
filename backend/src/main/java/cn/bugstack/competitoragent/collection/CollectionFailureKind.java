package cn.bugstack.competitoragent.collection;

/**
 * 采集失败类型。
 * 当前先沉淀网页采集最常见的正式失败语义，
 * 便于第五轮之后把 failureKind 挂到统一采集协议上。
 */
public enum CollectionFailureKind {

    CONTENT_UNUSABLE,
    ANTI_BOT_BLOCKED,
    PAGE_TIMEOUT,
    RUNTIME_FAILURE,
    HTTP_STATUS_ERROR,
    EXTRACTION_EMPTY
}
