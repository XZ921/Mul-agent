package cn.bugstack.competitoragent.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Mock LLM 客户端 — 默认启用的模拟 LLM，用于开发和演示
 * <p>
 * 当 llm.mock=true（默认）时启用，返回模拟数据，保证主流程无需 API Key 即可跑通。
 * 设置 llm.mock=false 并配置真实 API Key 将使用 LangChain4jClientImpl。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "llm.mock", havingValue = "true", matchIfMissing = true)
public class MockLlmClient implements LlmClient {

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        log.info("[Mock LLM] chat 被调用, prompt 长度={}", userPrompt.length());
        sleep(200);
        return "【Mock 模式】这是一段模拟的 LLM 回复。实际部署时请配置 ANTHROPIC_API_KEY 环境变量并设置 llm.mock=false。";
    }

    @Override
    public String chatForJson(String systemPrompt, String userPrompt, String responseSchema) {
        log.info("[Mock LLM] chatForJson 被调用, prompt 长度={}, schema={}",
                userPrompt.length(), responseSchema);
        sleep(300);

        // 根据 schema 描述返回不同的模拟 JSON
        if (responseSchema.contains("ExtractedSchema")) {
            return """
                {
                  "competitorName": "示例竞品",
                  "officialUrl": "https://example.com",
                  "summary": "这是一个示例竞品的产品简介（Mock 数据）",
                  "positioning": "面向XX用户的XX解决方案",
                  "targetUsers": ["个人用户", "中小团队"],
                  "coreFeatures": [
                    {"name": "示例功能1", "description": "功能描述（Mock）", "evidenceIds": ["E001"]}
                  ],
                  "pricing": {
                    "model": "Freemium + 订阅制",
                    "plans": ["免费版", "专业版 $12/月"],
                    "evidenceIds": ["E002"]
                  },
                  "strengths": [
                    {"point": "示例优势（Mock 数据）", "evidenceIds": ["E001"]}
                  ],
                  "weaknesses": [
                    {"point": "示例劣势（Mock 数据）", "evidenceIds": ["E002"]}
                  ],
                  "sources": [
                    {"id": "E001", "title": "来源页面标题", "url": "https://example.com", "contentSnippet": "引用片段...", "collectedAt": "2026-05-26 10:30:00"}
                  ]
                }
                """;
        } else if (responseSchema.contains("Analysis")) {
            return """
                {
                  "overview": "本次竞品分析覆盖了 N 个主要竞品（Mock 数据）",
                  "featureComparison": "功能对比如下：...",
                  "positioningComparison": "各竞品市场定位存在差异：...",
                  "pricingComparison": "定价策略对比：...",
                  "targetUserComparison": "各竞品目标用户存在差异",
                  "strengthsSummary": "各竞品优势汇总（Mock）",
                  "weaknessesSummary": "各竞品劣势汇总（Mock）",
                  "opportunities": ["机会点1（Mock）", "机会点2（Mock）"],
                  "risks": ["风险点1（Mock）", "风险点2（Mock）"],
                  "recommendations": ["建议1（Mock）", "建议2（Mock）"]
                }
                """;
        } else if (responseSchema.contains("QualityReview")) {
            return """
                {
                  "score": 85,
                  "passed": true,
                  "issues": [],
                  "summary": "报告整体质量良好，章节完整，证据引用充分（Mock 数据）"
                }
                """;
        }
        return "{}";
    }

    @Override
    public String getModelName() {
        return "mock-model";
    }

    @Override
    public TokenUsage getLastTokenUsage() {
        return TokenUsage.builder()
                .inputTokens(100)
                .outputTokens(200)
                .totalTokens(300)
                .build();
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
