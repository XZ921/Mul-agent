package cn.bugstack.competitoragent.agent.analyzer;

import cn.bugstack.competitoragent.agent.AgentContext;
import cn.bugstack.competitoragent.agent.AgentResult;
import cn.bugstack.competitoragent.agent.BaseAgent;
import cn.bugstack.competitoragent.llm.LlmClient;
import cn.bugstack.competitoragent.llm.PromptTemplateService;
import cn.bugstack.competitoragent.model.entity.CompetitorKnowledge;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.repository.AgentExecutionLogRepository;
import cn.bugstack.competitoragent.repository.CompetitorKnowledgeRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分析 Agent。
 * 负责把抽取后的竞品知识整理成模型可消费的输入，并生成结构化竞品分析结果。
 */
@Slf4j
@Component
public class CompetitorAnalysisAgent extends BaseAgent {

    private final CompetitorKnowledgeRepository knowledgeRepository;
    private final LlmClient llmClient;
    private final PromptTemplateService promptService;
    private final ObjectMapper objectMapper;

    public CompetitorAnalysisAgent(AgentExecutionLogRepository logRepository,
                                   CompetitorKnowledgeRepository knowledgeRepository,
                                   LlmClient llmClient,
                                   PromptTemplateService promptService,
                                   ObjectMapper objectMapper) {
        super(logRepository);
        this.knowledgeRepository = knowledgeRepository;
        this.llmClient = llmClient;
        this.promptService = promptService;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentType getType() {
        return AgentType.ANALYZER;
    }

    @Override
    public String getName() {
        return "竞品分析智能体";
    }

    @Override
    protected AgentResult doExecute(AgentContext context) {
        List<CompetitorKnowledge> knowledges = knowledgeRepository.findByTaskIdOrderByIdAsc(context.getTaskId());
        if (knowledges.isEmpty()) {
            return AgentResult.failed("暂无可分析的竞品知识");
        }

        String competitorData;
        try {
            competitorData = objectMapper.writeValueAsString(knowledges.stream()
                    .map(this::toPromptPayload)
                    .toList());
        } catch (JsonProcessingException e) {
            return AgentResult.failed("竞品知识序列化失败：" + e.getMessage());
        }

        String prompt = promptService.render("analyzer", Map.of(
                "subjectProduct", context.getSubjectProduct(),
                "analysisDimensions", context.getAnalysisDimensions() != null
                        ? context.getAnalysisDimensions() : "产品功能,价格策略,目标用户,市场定位",
                "competitorData", competitorData
        ));

        try {
            String llmResponse = llmClient.chatForJson(
                    "你是一名资深竞品分析专家，请只返回 JSON。",
                    prompt,
                    "Analysis"
            );
            objectMapper.readTree(cleanJson(llmResponse));
            return AgentResult.success(llmResponse,
                    "竞品分析完成：共处理 " + knowledges.size() + " 个竞品",
                    System.currentTimeMillis(),
                    llmClient.getModelName(),
                    llmClient.getLastTokenUsage().toJson());
        } catch (Exception e) {
            log.error("competitor analysis failed", e);
            return AgentResult.failed("竞品分析失败：" + e.getMessage());
        }
    }

    private Map<String, Object> toPromptPayload(CompetitorKnowledge knowledge) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("competitorName", knowledge.getCompetitorName());
        payload.put("summary", knowledge.getSummary());
        payload.put("positioning", knowledge.getPositioning());
        payload.put("targetUsers", parseJsonOrFallback(knowledge.getTargetUsers()));
        payload.put("coreFeatures", parseJsonOrFallback(knowledge.getCoreFeatures()));
        payload.put("pricing", parseJsonOrFallback(knowledge.getPricing()));
        payload.put("strengths", parseJsonOrFallback(knowledge.getStrengths()));
        payload.put("weaknesses", parseJsonOrFallback(knowledge.getWeaknesses()));
        payload.put("sources", parseJsonOrFallback(knowledge.getSources()));
        return payload;
    }

    private Object parseJsonOrFallback(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(rawValue);
        } catch (JsonProcessingException e) {
            return rawValue;
        }
    }

    private String cleanJson(String llmResponse) {
        String cleaned = llmResponse.trim();
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        return cleaned.trim();
    }
}
