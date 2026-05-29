package cn.bugstack.competitoragent.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Mock LLM 客户端。
 * 默认启用，用于本地开发和演示，避免没有 API Key 时主流程无法跑通。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "llm.mock", havingValue = "true", matchIfMissing = true)
public class MockLlmClient implements LlmClient {

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        log.info("[Mock LLM] chat 被调用，prompt 长度={}", userPrompt.length());
        sleep(200);
        return """
                # 竞品分析报告（Mock）

                ## 一、总体结论
                本次报告为模拟输出，用于验证中文界面、报告生成链路和导出功能是否正常。

                ## 二、竞品概览
                当前竞品在产品能力、市场定位和目标用户上均有差异，但整体仍围绕同类需求展开竞争。

                ## 三、核心对比
                1. 产品功能：各竞品在功能覆盖面上存在侧重点差异。
                2. 目标用户：部分竞品偏向个人用户，部分更强调团队协作。
                3. 定价策略：不同方案在免费额度、付费门槛和企业能力上区分明显。

                ## 四、建议
                建议结合证据继续完善关键功能对比、定价拆解与差异化表达。
                """;
    }

    @Override
    public String chatForJson(String systemPrompt, String userPrompt, String responseSchema) {
        log.info("[Mock LLM] chatForJson 被调用，prompt 长度={}, schema={}", userPrompt.length(), responseSchema);
        sleep(300);

        if (responseSchema.contains("ExtractedSchema")) {
            return """
                {
                  "competitorName": "示例竞品",
                  "officialUrl": "https://example.com",
                  "summary": "这是一个用于本地演示的示例竞品摘要。",
                  "positioning": "面向知识管理与团队协作场景的产品方案",
                  "targetUsers": ["个人用户", "中小团队"],
                  "coreFeatures": [
                    {
                      "name": "示例功能一",
                      "description": "支持基础协作与知识沉淀。",
                      "evidenceIds": ["E001"],
                      "sourceUrls": ["https://example.com"]
                    }
                  ],
                  "pricing": {
                    "model": "免费版加订阅制",
                    "plans": ["免费版", "专业版"],
                    "evidenceIds": ["E002"],
                    "sourceUrls": ["https://example.com/pricing"]
                  },
                  "strengths": [
                    {
                      "point": "上手简单，协作体验较顺畅。",
                      "evidenceIds": ["E001"],
                      "sourceUrls": ["https://example.com"]
                    }
                  ],
                  "weaknesses": [
                    {
                      "point": "高级能力需要进一步验证。",
                      "evidenceIds": ["E002"],
                      "sourceUrls": ["https://example.com/pricing"]
                    }
                  ],
                  "sources": [
                    {
                      "evidenceId": "E001",
                      "title": "来源页面标题",
                      "url": "https://example.com"
                    }
                  ],
                  "sourceUrls": ["https://example.com"]
                }
                """;
        } else if (responseSchema.contains("Analysis")) {
            return """
                {
                  "overview": "本次竞品分析覆盖多个核心竞品，用于验证分析链路。",
                  "featureComparison": "各竞品在功能覆盖、协作体验与知识沉淀能力上存在明显差异。",
                  "positioningComparison": "部分竞品强调易用性，部分竞品强调企业级能力。",
                  "pricingComparison": "定价策略从免费切入到企业订阅不等。",
                  "targetUserComparison": "目标用户从个人创作者延伸到中大型团队。",
                  "strengthsSummary": "竞品普遍重视易用性与协作能力。",
                  "weaknessesSummary": "在高阶分析、闭环评审和深度集成方面仍有提升空间。",
                  "opportunities": ["强化证据链展示", "提升报告可追溯能力"],
                  "risks": ["同质化竞争明显", "价格战风险提升"],
                  "recommendations": ["突出差异化能力", "加强企业场景落地"]
                }
                """;
        } else if (responseSchema.contains("QualityReview")) {
            return """
                {
                  "score": 85,
                  "passed": true,
                  "issues": [],
                  "summary": "报告整体结构完整，结论清晰，可作为演示版本直接使用。"
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
