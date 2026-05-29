package cn.bugstack.competitoragent.agent.reviewer;

import cn.bugstack.competitoragent.agent.AgentContext;
import cn.bugstack.competitoragent.agent.AgentResult;
import cn.bugstack.competitoragent.agent.BaseAgent;
import cn.bugstack.competitoragent.llm.LlmClient;
import cn.bugstack.competitoragent.llm.PromptTemplateService;
import cn.bugstack.competitoragent.model.dto.RevisionPlan;
import cn.bugstack.competitoragent.model.entity.EvidenceSource;
import cn.bugstack.competitoragent.model.entity.Report;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.repository.AgentExecutionLogRepository;
import cn.bugstack.competitoragent.repository.EvidenceSourceRepository;
import cn.bugstack.competitoragent.repository.ReportRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 质检 Agent。
 * 负责对报告做质量评分，生成问题列表和修订计划，驱动 Writer / Reviewer 闭环。
 */
@Slf4j
@Component
public class QualityReviewAgent extends BaseAgent {

    private final ReportRepository reportRepository;
    private final EvidenceSourceRepository evidenceRepository;
    private final LlmClient llmClient;
    private final PromptTemplateService promptService;
    private final ObjectMapper objectMapper;

    public QualityReviewAgent(AgentExecutionLogRepository logRepository,
                              ReportRepository reportRepository,
                              EvidenceSourceRepository evidenceRepository,
                              LlmClient llmClient,
                              PromptTemplateService promptService,
                              ObjectMapper objectMapper) {
        super(logRepository);
        this.reportRepository = reportRepository;
        this.evidenceRepository = evidenceRepository;
        this.llmClient = llmClient;
        this.promptService = promptService;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentType getType() {
        return AgentType.REVIEWER;
    }

    @Override
    public String getName() {
        return "QualityReviewAgent";
    }

    @Override
    protected AgentResult doExecute(AgentContext context) {
        // Reviewer 只评审当前任务已生成的报告正文，没有报告则无法进入质检。
        Report report = reportRepository.findByTaskId(context.getTaskId()).orElse(null);
        if (report == null || report.getContent() == null || report.getContent().isBlank()) {
            return AgentResult.failed("No report content available for review");
        }

        List<EvidenceSource> evidences = evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(context.getTaskId());
        String evidenceList = buildEvidenceList(evidences);
        boolean finalPass = isFinalReview(context.getCurrentNodeConfig());

        String prompt = promptService.render("reviewer", Map.of(
                "reportContent", report.getContent(),
                "evidenceList", evidenceList,
                "reviewMode", finalPass ? "final" : "initial"
        ));

        try {
            // Reviewer 同样要求模型输出 JSON，便于系统提取 score / issues / revisionPlan。
            String llmResponse = llmClient.chatForJson(
                    "You are a strict quality reviewer. Return JSON only.",
                    prompt,
                    "QualityReview"
            );

            JsonNode reviewJson = objectMapper.readTree(cleanJson(llmResponse));
            int score = reviewJson.path("score").asInt(0);
            boolean passed = reviewJson.path("passed").asBoolean(false);

            // issues 会被标准化成 RevisionItem，后续既能前端展示，也能供 Writer 重写消费。
            List<RevisionPlan.RevisionItem> items = new ArrayList<>();
            if (reviewJson.has("issues") && reviewJson.get("issues").isArray()) {
                for (JsonNode issueNode : reviewJson.get("issues")) {
                    items.add(new RevisionPlan.RevisionItem(
                            issueNode.path("type").asText("UNKNOWN"),
                            issueNode.path("section").asText("general"),
                            issueNode.path("severity").asText("WARNING"),
                            issueNode.path("suggestion").asText("Please improve this section")
                    ));
                }
            }

            String summary = reviewJson.path("summary").asText("");
            RevisionPlan revisionPlan = RevisionPlan.builder()
                    .rewriteRequired(!passed)
                    .summary(summary)
                    .items(items)
                    .rewriteGuidelines(buildGuidelines(items))
                    .build();

            String revisionPlanJson = objectMapper.writeValueAsString(revisionPlan);

            // 报告主表同步回写最新评分与问题列表，方便报告页直接展示当前质量状态。
            report.setQualityScore(score);
            report.setQualityPassed(passed);
            report.setQualityIssues(reviewJson.has("issues") ? reviewJson.get("issues").toString() : "[]");
            reportRepository.save(report);

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("reviewStage", finalPass ? "final" : "initial");
            output.put("score", score);
            output.put("passed", passed);
            output.put("issues", reviewJson.has("issues") ? reviewJson.get("issues") : objectMapper.createArrayNode());
            output.put("summary", summary);
            output.put("revisionPlan", objectMapper.readTree(revisionPlanJson));

            String outputJson = objectMapper.writeValueAsString(output);
            String outputSummary = passed
                    ? "quality check passed, report can be closed"
                    : "quality check failed, revision plan generated";

            return AgentResult.success(outputJson, outputSummary,
                    System.currentTimeMillis(),
                    llmClient.getModelName(),
                    llmClient.getLastTokenUsage().toJson());
        } catch (Exception e) {
            log.error("quality review failed", e);
            return AgentResult.failed("quality review failed: " + e.getMessage());
        }
    }

    // 修订指南是给 Writer 的重写输入，按 section 聚合为可执行语句。
    private List<String> buildGuidelines(List<RevisionPlan.RevisionItem> items) {
        List<String> guidelines = new ArrayList<>();
        for (RevisionPlan.RevisionItem item : items) {
            guidelines.add(item.getSection() + ": " + item.getSuggestion());
        }
        if (guidelines.isEmpty()) {
            guidelines.add("Strengthen evidence citations and tighten section structure.");
        }
        return guidelines;
    }

    // 最终质检节点通过特定 qualityPolicy 标记，用于区分初审与终审。
    private boolean isFinalReview(String nodeConfig) {
        if (nodeConfig == null || nodeConfig.isBlank()) {
            return false;
        }
        try {
            JsonNode config = objectMapper.readTree(nodeConfig);
            return "final pass after revision".equalsIgnoreCase(config.path("qualityPolicy").asText());
        } catch (Exception e) {
            return nodeConfig.contains("final pass after revision");
        }
    }

    // 把证据列表压缩成 reviewer prompt 可读格式，要求质检严格对照来源审查报告。
    private String buildEvidenceList(List<EvidenceSource> evidences) {
        StringBuilder evidenceList = new StringBuilder();
        for (EvidenceSource ev : evidences) {
            evidenceList.append(String.format("- [%s] %s (%s)\n", ev.getEvidenceId(), ev.getTitle(), ev.getUrl()));
        }
        return evidenceList.toString();
    }

    // 清理 markdown code fence，避免模型返回的 JSON 被额外包装导致解析失败。
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
