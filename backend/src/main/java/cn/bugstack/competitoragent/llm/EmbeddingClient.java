package cn.bugstack.competitoragent.llm;

import java.util.List;

/**
 * 向量嵌入客户端抽象。
 * <p>
 * 业务层只依赖统一接口，不直接绑定某一家供应商 SDK，
 * 这样检索服务才能在失败时安全降级，而不是把底层实现细节泄漏到业务代码。
 */
public interface EmbeddingClient {

    List<Float> embed(String text);
}
