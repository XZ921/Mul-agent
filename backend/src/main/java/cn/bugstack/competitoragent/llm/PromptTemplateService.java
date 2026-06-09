package cn.bugstack.competitoragent.llm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class PromptTemplateService {

    private static final List<String> TASK_RAG_TEMPLATE_NAMES = List.of("analyzer", "extractor", "writer", "reviewer");
    private static final List<String> CONVERSATION_TEMPLATE_NAMES = List.of(
            "conversation-agent",
            "intent-router",
            "task-action-translator"
    );
    /**
     * Task 4.6 要求统一对话入口相关 Prompt 必须共享同一套运行时状态汇报格式，
     * 避免解释、意图路由和动作翻译出现多套口径。
     */
    private static final String RUNTIME_STATUS_CONTRACT = """
            当前阶段：请结合当前真实上下文填写
            [x] 信息采集：已完成
            [ ] 数据分析：执行中
            [ ] 报告撰写：待执行
            """;
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
                    """),
            new AbstractMap.SimpleEntry<>("analyzer", """
                    你是一名资深竞品分析专家，请只返回 JSON。
                    # 本方产品
                    {subjectProduct}

                    # 分析维度
                    {analysisDimensions}

                    # 竞品数据
                    {competitorData}
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

    private final Map<String, String> templates = new ConcurrentHashMap<>();
    private final Map<String, String> englishSearchTemplates = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final YAMLMapper yamlMapper = new YAMLMapper();

    @Autowired
    public PromptTemplateService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        DEFAULT_TEMPLATES.forEach(this::registerTemplate);
        loadConversationPromptTemplates();
        loadTaskRagContextTemplate();
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
        Map<String, String> safeVariables = variables == null ? Map.of() : variables;
        String result = getTemplate(templateName);
        for (Map.Entry<String, String> entry : safeVariables.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", safe(entry.getValue()));
        }
        result = appendRuntimeStatusContract(templateName, result);
        if (shouldAppendTaskRagContext(templateName, safeVariables, result)) {
            result = result + "\n\n" + render("task-rag-context", Map.of(
                    "taskRagContext", safe(safeVariables.get("taskRagContext"))
            ));
        }
        return result;
    }

    /**
     * Task 4.6 中统一对话入口相关模板优先从 resources/prompts 目录加载。
     * 若仓库里尚未覆盖文件，也必须保底注册内联模板，避免运行时出现 Unknown template。
     */
    private void loadConversationPromptTemplates() {
        registerTemplate("conversation-agent", """
                你是 AI 竞品分析 Agent 协作系统的统一对话入口助手。
                你的职责是先解释当前上下文，再给出安全、可审计、可回指来源的回答。

                # 当前上下文摘要
                {contextSummary}

                # 用户消息
                {userMessage}
                """);
        registerTemplate("intent-router", """
                你是统一对话入口的意图识别器。
                你需要先理解上下文，再判断这条消息更适合解释、填表、动作预览还是研究补证。

                # 当前上下文摘要
                {contextSummary}

                # 用户消息
                {userMessage}
                """);
        registerTemplate("task-action-translator", """
                你是任务动作翻译器。
                你的职责是把自然语言动作翻译成安全的动作预览，而不是直接执行高风险命令。

                # 动作上下文
                {actionContext}

                # 用户消息
                {userMessage}
                """);
    }

    /**
     * Task 4.5 为任务级检索上下文提供独立模板入口，
     * 即使仓库里暂时没有覆盖文件，也要保证运行时有稳定默认模板可用。
     */
    private void loadTaskRagContextTemplate() {
        registerTemplate("task-rag-context", """
                任务级检索上下文
                {taskRagContext}
                """);
    }

    private void registerTemplate(String templateName, String fallbackTemplate) {
        if (templates.containsKey(templateName)) {
            return;
        }
        try {
            ClassPathResource resource = new ClassPathResource("prompts/" + templateName + ".txt");
            if (resource.exists()) {
                templates.put(templateName, resource.getContentAsString(StandardCharsets.UTF_8));
                return;
            }
        } catch (IOException e) {
            log.warn("load prompt template {} failed, fallback to inline default", templateName, e);
        }
        templates.put(templateName, fallbackTemplate);
    }

    private boolean shouldAppendTaskRagContext(String templateName,
                                               Map<String, String> variables,
                                               String renderedTemplate) {
        if (templateName == null || "task-rag-context".equals(templateName) || variables == null) {
            return false;
        }
        String taskRagContext = variables.get("taskRagContext");
        if (taskRagContext == null || taskRagContext.isBlank()) {
            return false;
        }
        if (!TASK_RAG_TEMPLATE_NAMES.contains(templateName)) {
            return false;
        }
        return renderedTemplate == null || !renderedTemplate.contains(taskRagContext);
    }

    /**
     * 只对统一对话入口相关模板补齐状态汇报契约，避免污染既有分析 / 撰写 Prompt。
     */
    private String appendRuntimeStatusContract(String templateName, String renderedTemplate) {
        if (templateName == null || renderedTemplate == null) {
            return renderedTemplate;
        }
        if (!CONVERSATION_TEMPLATE_NAMES.contains(templateName)) {
            return renderedTemplate;
        }
        if (renderedTemplate.contains("当前阶段：")) {
            return renderedTemplate;
        }
        return renderedTemplate + "\n\n" + RUNTIME_STATUS_CONTRACT;
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
                    new TypeReference<Map<String, String>>() {
                    }
            );
            if (queryTemplates == null || queryTemplates.isEmpty()) {
                log.warn("search query template file prompts/search-queries.yml is empty, keep default templates");
                return;
            }
            templates.putAll(queryTemplates);
            queryTemplates.forEach((key, value) -> {
                if (key.startsWith("search-")) {
                    englishSearchTemplates.put(key, value);
                }
            });
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
            result = result.replace("{" + entry.getKey() + "}", safe(entry.getValue()));
        }
        return result.replaceAll("\\s+", " ").trim();
    }
}
