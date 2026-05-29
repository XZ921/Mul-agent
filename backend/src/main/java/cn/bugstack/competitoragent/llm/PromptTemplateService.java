package cn.bugstack.competitoragent.llm;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class PromptTemplateService {

    private final Map<String, String> templates = new ConcurrentHashMap<>();

    private static final Map<String, String> DEFAULT_TEMPLATES = Map.of(
            "writer", """
                    你是一名专业的竞品分析报告撰写专家。
                    请严格使用 {reportLanguage} 输出完整报告。
                    # 任务名称
                    {taskName}

                    # 本方产品
                    {subjectProduct}

                    # 分析结果
                    {analysisResult}

                    # 当前报告草稿
                    {currentReport}

                    # 是否修订模式
                    {revisionMode}

                    # 修订计划
                    {revisionPlan}

                    # 证据列表
                    {evidenceList}

                    请输出一份完整的 Markdown 竞品分析报告。
                    要求：
                    1. 全文使用 {reportLanguage}，不要中英混写。
                    2. 标题、章节名、总结、对比结论全部使用中文表达。
                    3. 必须基于证据列表撰写，涉及事实判断时优先引用证据编号。
                    4. 如果 revisionMode 为 true，需要在当前草稿基础上按修订计划优先修正问题。
                    """,
            "reviewer", """
                    你是一名严格的中文质量评审专家。
                    请用中文进行判断与说明，但 JSON 字段名保持固定格式。
                    # 评审模式
                    {reviewMode}

                    # 报告正文
                    {reportContent}

                    # 证据列表
                    {evidenceList}

                    请只返回 JSON，结构如下：
                    {
                      "score": 85,
                      "passed": true,
                      "issues": [
                        {
                          "type": "证据不足",
                          "section": "某个章节",
                          "severity": "ERROR",
                          "suggestion": "请补充对应证据并重写结论"
                        }
                      ],
                      "summary": "简短中文总结"
                    }

                    要求：
                    1. summary、issues[].type、issues[].section、issues[].suggestion 全部使用中文。
                    2. severity 只能使用 ERROR、WARNING、INFO。
                    3. 如果报告需要修订，passed=false，并给出可执行的中文修改建议。
                    """,
            "extractor", """
                    你是一名竞品信息结构化抽取专家。
                    你必须只返回 JSON。

                    # 竞品名称
                    {competitorName}

                    # 证据目录
                    {evidenceCatalog}

                    # 已采集内容
                    {collectedContent}

                    请按如下结构抽取竞品画像：
                    {
                      "officialUrl": "https://...",
                      "summary": "简短总结",
                      "positioning": "市场定位",
                      "targetUsers": ["用户群体 A"],
                      "coreFeatures": [
                        {
                          "name": "功能名称",
                          "description": "功能说明",
                          "evidenceIds": ["T0001-COLLECT-001"],
                          "sourceUrls": ["https://..."]
                        }
                      ],
                      "pricing": {
                        "model": "定价模式",
                        "plans": ["免费版", "专业版"],
                        "evidenceIds": ["T0001-COLLECT-002"],
                        "sourceUrls": ["https://..."]
                      },
                      "strengths": [
                        {
                          "point": "优势点",
                          "evidenceIds": ["T0001-COLLECT-003"],
                          "sourceUrls": ["https://..."]
                        }
                      ],
                      "weaknesses": [
                        {
                          "point": "不足点",
                          "evidenceIds": ["T0001-COLLECT-004"],
                          "sourceUrls": ["https://..."]
                        }
                      ],
                      "sources": [
                        {
                          "evidenceId": "T0001-COLLECT-001",
                          "title": "来源标题",
                          "url": "https://..."
                        }
                      ],
                      "sourceUrls": ["https://..."]
                    }

                    规则：
                    1. sourceUrls 为必填，且只能使用证据目录中真实出现过的 URL。
                    2. 任何可以支撑的结构化字段，都要尽量补上 evidenceIds 和 sourceUrls。
                    3. 不要编造价格、功能、目标用户等信息。
                    4. 不确定的字段宁可留空，也不要猜测。
                    """,
            "analyzer", """
                    你是一名资深竞品分析专家，请只返回 JSON。

                    # 本方产品
                    {subjectProduct}

                    # 分析维度
                    {analysisDimensions}

                    # 竞品数据
                    {competitorData}

                    请输出 JSON，至少包含以下字段：
                    {
                      "overview": "整体概述",
                      "featureComparison": "功能对比",
                      "positioningComparison": "定位对比",
                      "pricingComparison": "定价对比",
                      "targetUserComparison": "目标用户对比",
                      "strengthsSummary": "主要优势总结",
                      "weaknessesSummary": "主要不足总结",
                      "opportunities": ["机会点1"],
                      "risks": ["风险点1"],
                      "recommendations": ["建议1"]
                    }

                    要求：
                    1. 全部内容使用中文。
                    2. 不要输出 markdown，只输出 JSON。
                    3. 结论尽量基于输入竞品数据，不要凭空扩展。
                    """
    );

    @PostConstruct
    public void init() {
        for (String name : List.of("writer", "reviewer", "extractor", "analyzer")) {
            try {
                ClassPathResource resource = new ClassPathResource("prompts/" + name + ".txt");
                if (resource.exists()) {
                    templates.put(name, resource.getContentAsString(StandardCharsets.UTF_8));
                } else {
                    templates.put(name, DEFAULT_TEMPLATES.get(name));
                }
            } catch (IOException e) {
                templates.put(name, DEFAULT_TEMPLATES.get(name));
                log.warn("load prompt template {} failed, fallback to default", name, e);
            }
        }
    }

    public String getTemplate(String templateName) {
        String template = templates.get(templateName);
        if (template == null) {
            throw new IllegalArgumentException("Unknown template name: " + templateName);
        }
        return template;
    }

    public String render(String templateName, Map<String, String> variables) {
        String template = getTemplate(templateName);
        String result = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }
}
