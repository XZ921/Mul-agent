package cn.bugstack.competitoragent.llm;

/**
 * 统一模型网关支持的能力类型。
 * <p>
 * Task 5.1 的首要目标不是扩展更多能力，而是先把现有
 * Chat / Embedding / Rerank 三条调用链收口到同一个治理入口。
 */
public enum AiCapability {

    CHAT,
    EMBEDDING,
    RERANK
}
