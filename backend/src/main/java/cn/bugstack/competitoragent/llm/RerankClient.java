package cn.bugstack.competitoragent.llm;

import java.util.List;

/**
 * 重排客户端抽象。
 * <p>
 * 当前阶段主要服务于 Task RAG 的片段重排，
 * 统一接口可以把超时、重试和降级控制收口在运行时服务，而不是散落在 Agent 中。
 */
public interface RerankClient {

    List<RerankRecord> rerank(String query, List<String> documents);

    record RerankRecord(int index, double score) {
    }
}
