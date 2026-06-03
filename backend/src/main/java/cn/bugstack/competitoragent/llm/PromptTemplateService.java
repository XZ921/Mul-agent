package cn.bugstack.competitoragent.llm;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.AbstractMap;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

@Slf4j
@Service
public class PromptTemplateService {

    private final Map<String, String> templates = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final YAMLMapper yamlMapper = new YAMLMapper();
    private final Map<String, String> englishSearchTemplates = new ConcurrentHashMap<>();

    private static final Map<String, String> DEFAULT_TEMPLATES = Map.ofEntries(
            new AbstractMap.SimpleEntry<>("writer", """
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

                    # 修订重点
                    {revisionFocus}

                    # 证据列表
                    {evidenceList}

                    请输出一份完整的 Markdown 竞品分析报告。
                    要求：
                    1. 全文使用 {reportLanguage}，不要中英混写。
                    2. 标题、章节名、总结、对比结论全部使用中文表达。
                    3. 必须基于证据列表撰写，涉及事实判断时优先引用证据编号。
                    4. 如果 revisionMode 为 true，需要在当前草稿基础上按修订计划优先修正问题。
                    5. 如果 revisionMode 为 true，所有被指出“证据不足”或“结论缺少支撑”的章节，都必须补上 [证据：EID] 形式引用；如果做不到，就把语气改为保守表述。
                    """),
            new AbstractMap.SimpleEntry<>("reviewer", """
                    你是一名严格的中文质量评审专家。
                    请用中文进行判断与说明，但 JSON 字段名保持固定格式。
                    # 评审模式
                    {reviewMode}

                    # 报告正文
                    {reportContent}

                    # 证据列表
                    {evidenceList}

                    # 结构化证据覆盖摘要
                    {evidenceCoverageSummary}

                    # 关键结论审查清单
                    {claimAuditChecklist}

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
                      "nextActions": [
                        {
                          "title": "补充外部证据",
                          "description": "为问题章节补充公开文档、定价页、客户案例或技术白皮书等可验证来源",
                          "actionType": "SUPPLEMENT_EVIDENCE",
                          "targetNode": "collect_sources",
                          "priority": "HIGH"
                        }
                      ],
                      "summary": "简短中文总结"
                    }

                    要求：
                    1. summary、issues[].type、issues[].section、issues[].suggestion 全部使用中文。
                    2. severity 只能使用 ERROR、WARNING、INFO。
                    3. 如果报告需要修订，passed=false，并给出可执行的中文修改建议。
                    4. 如果评审模式是 final 且 passed=false，必须填写 nextActions，明确告诉用户下一步该补什么证据、应从哪个节点重跑，或哪些结论需要删除/降级。
                    5. nextActions[].actionType 只能使用 SUPPLEMENT_EVIDENCE、RERUN_NODE、REWRITE_CLAIM、MANUAL_REVIEW。
                    6. nextActions[].priority 只能使用 HIGH、MEDIUM、LOW。
                    7. 如果结构化证据覆盖摘要显示某个章节为“缺证据章节”，且报告正文对该章节给出了明确判断，应优先标记为 ERROR 或 WARNING，而不是直接通过。
                    8. 必须逐条检查“关键结论审查清单”中的每一条结论，判断该条结论是否有证据编号可回指；如果没有，就至少输出一条 issues，type 使用“结论缺少支撑”或“证据不足”。
                    9. 如果同一章节存在多个缺证据结论，不要只给章节级泛泛意见，至少在 suggestion 中指出是哪一类判断缺少证据。
                    """),
            new AbstractMap.SimpleEntry<>("extractor", """
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
                    """),
            new AbstractMap.SimpleEntry<>("analyzer", """
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
                    """),
            new AbstractMap.SimpleEntry<>("search-official", "{competitorName} official website"),
            new AbstractMap.SimpleEntry<>("search-official-domain", "site:{domainHint} {competitorName}"),
            new AbstractMap.SimpleEntry<>("search-docs-primary", "{competitorName} documentation api reference"),
            new AbstractMap.SimpleEntry<>("search-docs-secondary", "{competitorName} developer docs guide"),
            new AbstractMap.SimpleEntry<>("search-pricing-primary", "{competitorName} pricing plans"),
            new AbstractMap.SimpleEntry<>("search-pricing-secondary", "{competitorName} enterprise pricing quote"),
            new AbstractMap.SimpleEntry<>("search-news-primary", "{competitorName} product updates changelog"),
            new AbstractMap.SimpleEntry<>("search-news-secondary", "{competitorName} release notes blog"),
            new AbstractMap.SimpleEntry<>("search-review-primary", "{competitorName} reviews g2 capterra"),
            new AbstractMap.SimpleEntry<>("search-review-secondary", "{competitorName} alternatives comparison g2 capterra"),
            new AbstractMap.SimpleEntry<>("search-review-zhihu", "site:zhihu.com {competitorName} 评测 对比")
    );

    @Autowired
    public PromptTemplateService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        for (String name : DEFAULT_TEMPLATES.keySet()) {
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
        loadEnglishSearchTemplates();
        loadSearchQueryTemplates();
    }

    public String getTemplate(String templateName) {
        if (templates.isEmpty()) {
            init();
        }
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

    /**
     * 搜索 Query 本质上也是模板渲染，只是在输出前额外做一次空白折叠，
     * 便于后续把搜索模板和现有 Prompt 模板共用同一套服务。
     */
    public String buildSearchQuery(String templateName, Map<String, String> variables) {
        return render(templateName, variables)
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * 搜索 query 统一走模板渲染，保证规划期与运行期补源共用同一套生成规则。
     */
    public List<String> buildSearchQueries(String competitorName,
                                           String sourceType,
                                           String domainHint) {
        if (containsChinese(competitorName)) {
            return buildChineseSearchQueries(competitorName, sourceType, domainHint);
        }
        return buildEnglishSearchQueries(competitorName, sourceType, domainHint);
    }

    private List<String> buildChineseSearchQueries(String competitorName,
                                                   String sourceType,
                                                   String domainHint) {
        Map<String, String> variables = Map.of(
                "competitorName", safe(competitorName),
                "domainHint", safe(domainHint)
        );
        String normalizedType = sourceType == null ? "OFFICIAL" : sourceType.toUpperCase(Locale.ROOT);
        List<String> queries = new ArrayList<>();
        switch (normalizedType) {
            case "DOCS" -> {
                queries.add(buildSearchQuery("search-docs-primary", variables));
                queries.add(buildSearchQuery("search-docs-secondary", variables));
            }
            case "PRICING" -> {
                queries.add(buildSearchQuery("search-pricing-primary", variables));
                queries.add(buildSearchQuery("search-pricing-secondary", variables));
            }
            case "NEWS" -> {
                queries.add(buildSearchQuery("search-news-primary", variables));
                queries.add(buildSearchQuery("search-news-secondary", variables));
            }
            case "REVIEW" -> {
                queries.add(buildSearchQuery("search-review-primary", variables));
                queries.add(buildSearchQuery("search-review-secondary", variables));
                queries.add(buildSearchQuery("search-review-zhihu", variables));
            }
            default -> queries.add(buildSearchQuery("search-official", variables));
        }
        if (!safe(domainHint).isBlank()) {
            queries.add(buildSearchQuery("search-official-domain", variables));
        }
        return queries.stream()
                .filter(query -> !query.isBlank())
                .distinct()
                .toList();
    }

    private List<String> buildEnglishSearchQueries(String competitorName,
                                                   String sourceType,
                                                   String domainHint) {
        Map<String, String> variables = Map.of(
                "competitorName", safe(competitorName),
                "domainHint", safe(domainHint)
        );
        String normalizedType = sourceType == null ? "OFFICIAL" : sourceType.toUpperCase(Locale.ROOT);
        List<String> queries = new ArrayList<>();
        switch (normalizedType) {
            case "DOCS" -> {
                queries.add(renderEnglishSearchQuery("search-docs-primary", variables));
                queries.add(renderEnglishSearchQuery("search-docs-secondary", variables));
            }
            case "PRICING" -> {
                queries.add(renderEnglishSearchQuery("search-pricing-primary", variables));
                queries.add(renderEnglishSearchQuery("search-pricing-secondary", variables));
            }
            case "NEWS" -> {
                queries.add(renderEnglishSearchQuery("search-news-primary", variables));
                queries.add(renderEnglishSearchQuery("search-news-secondary", variables));
            }
            case "REVIEW" -> {
                queries.add(renderEnglishSearchQuery("search-review-primary", variables));
                queries.add(renderEnglishSearchQuery("search-review-secondary", variables));
                queries.add(renderEnglishSearchQuery("search-review-zhihu", variables));
            }
            default -> queries.add(renderEnglishSearchQuery("search-official", variables));
        }
        if (!safe(domainHint).isBlank()) {
            queries.add(renderEnglishSearchQuery("search-official-domain", variables));
        }
        return queries.stream()
                .filter(query -> !query.isBlank())
                .distinct()
                .toList();
    }

    private void loadSearchQueryTemplates() {
        try {
            ClassPathResource resource = new ClassPathResource("prompts/search-queries.yml");
            if (!resource.exists()) {
                log.warn("search query template file prompts/search-queries.yml not found, keep default templates");
                return;
            }
            Map<String, String> queryTemplates = yamlMapper.readValue(
                    resource.getInputStream(),
                    new TypeReference<Map<String, String>>() {}
            );
            if (queryTemplates == null || queryTemplates.isEmpty()) {
                log.warn("search query template file prompts/search-queries.yml is empty, keep default templates");
                return;
            }
            templates.putAll(queryTemplates);
        } catch (IOException e) {
            log.warn("load search query templates failed, keep default templates", e);
        }
    }

    private void loadEnglishSearchTemplates() {
        DEFAULT_TEMPLATES.forEach((key, value) -> {
            if (key.startsWith("search-")) {
                englishSearchTemplates.put(key, value);
            }
        });
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean containsChinese(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (char ch : value.toCharArray()) {
            if (Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN) {
                return true;
            }
        }
        return false;
    }

    private String renderEnglishSearchQuery(String templateName, Map<String, String> variables) {
        String template = englishSearchTemplates.get(templateName);
        if (template == null) {
            return buildSearchQuery(templateName, variables);
        }
        String result = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result.replaceAll("\\s+", " ").trim();
    }
}
