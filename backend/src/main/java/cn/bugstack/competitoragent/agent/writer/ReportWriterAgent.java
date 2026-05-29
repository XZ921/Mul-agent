package cn.bugstack.competitoragent.agent.writer;

import cn.bugstack.competitoragent.agent.AgentContext;
import cn.bugstack.competitoragent.agent.AgentResult;
import cn.bugstack.competitoragent.agent.BaseAgent;
import cn.bugstack.competitoragent.llm.LlmClient;
import cn.bugstack.competitoragent.llm.PromptTemplateService;
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

import java.util.List;
import java.util.Map;

/**
 * Writer Agent，负责把分析结果与证据列表组织成 Markdown 报告。
 * V2 中同一个 Writer 同时承担首稿生成与按修订计划重写两种模式。
 */
@Slf4j
@Component
public class ReportWriterAgent extends BaseAgent {

    private final ReportRepository reportRepository;
    private final EvidenceSourceRepository evidenceRepository;
    private final LlmClient llmClient;
    private final PromptTemplateService promptService;
    private final ObjectMapper objectMapper;

    public ReportWriterAgent(AgentExecutionLogRepository logRepository,
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
        return AgentType.WRITER;
    }

    @Override
    public String getName() {
        return "ReportWriterAgent";
    }

    @Override
    protected AgentResult doExecute(AgentContext context) {
        // Writer 必须消费 Analyzer 的结构化分析结果，否则没有可写入的业务内容。
        String analysisResult = context.getSharedOutput("analyze_competitors");
        if (analysisResult == null || analysisResult.isBlank()) {
            return AgentResult.failed("No analysis result available, run analyzer first");
        }

        // 当节点被配置为 revision 模式时，必须显式带上 Reviewer 产出的修订计划。
        boolean revisionMode = isRevisionMode(context.getCurrentNodeConfig());
        JsonNode reviewOutput = readJson(context.getSharedOutput("quality_check"));
        JsonNode revisionPlan = extractRevisionPlan(reviewOutput);
        if (revisionMode && (revisionPlan == null || !revisionPlan.path("rewriteRequired").asBoolean(false))) {
            return AgentResult.failed("Revision mode requires a quality-check revision plan");
        }

        // 证据列表直接注入提示词，约束 Writer 输出必须围绕已采集证据展开。
        List<EvidenceSource> evidences = evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(context.getTaskId());
        StringBuilder evidenceList = new StringBuilder();
        for (EvidenceSource ev : evidences) {
            evidenceList.append(String.format("- [%s] %s (%s)\n", ev.getEvidenceId(), ev.getTitle(), ev.getUrl()));
        }

        // 如果任务已经有旧版报告，revision 模式会把它作为“待修订底稿”传给模型。
        String currentReport = reportRepository.findByTaskId(context.getTaskId())
                .map(Report::getContent)
                .orElse("");

        // 同一套 writer 模板通过 revisionMode 与 revisionPlan 控制“首稿/重写”分支。
        String prompt = promptService.render("writer", Map.of(
                "taskName", safe(context.getTaskName()),
                "subjectProduct", safe(context.getSubjectProduct()),
                "analysisResult", analysisResult,
                "currentReport", currentReport,
                "evidenceList", evidenceList.toString(),
                "revisionMode", String.valueOf(revisionMode),
                "revisionPlan", revisionPlan == null ? "[]" : revisionPlan.toPrettyString()
        ));

        try {
            // 统一走 LLM 生成报告正文，便于后续在日志中追踪 token 使用与模型名称。
            String reportContent = llmClient.chat(
                    "You are a professional business report writer. Generate a high-quality Markdown competitor analysis report.",
                    prompt
            );

            // 报告记录是幂等更新：首次创建，后续重写直接覆盖正文和摘要。
            Report report = reportRepository.findByTaskId(context.getTaskId())
                    .orElse(Report.builder().taskId(context.getTaskId()).build());
            report.setTitle(context.getTaskName() + " - Competitor Analysis Report");
            report.setContent(reportContent);
            report.setEvidenceCount(evidences.size());
            report.setSummary(buildSummary(reportContent, revisionMode));
            reportRepository.save(report);

            log.info("report writing done, taskId={}, revisionMode={}, length={}",
                    context.getTaskId(), revisionMode, reportContent.length());

            return AgentResult.success(reportContent,
                    String.format("report generated, chars=%d, evidences=%d", reportContent.length(), evidences.size()),
                    System.currentTimeMillis(),
                    llmClient.getModelName(),
                    llmClient.getLastTokenUsage().toJson());
        } catch (Exception e) {
            log.error("report writing failed", e);
            return AgentResult.failed("report writing failed: " + e.getMessage());
        }
    }

    // 节点配置里通过 mode=revision 显式切换到重写模式。
    private boolean isRevisionMode(String nodeConfig) {
        if (nodeConfig == null || nodeConfig.isBlank()) {
            return false;
        }
        try {
            Map<?, ?> config = objectMapper.readValue(nodeConfig, Map.class);
            Object mode = config.get("mode");
            return "revision".equalsIgnoreCase(String.valueOf(mode));
        } catch (Exception e) {
            return nodeConfig.contains("\"mode\":\"revision\"");
        }
    }

    // 摘要只保留报告前部内容，便于列表页和概览页快速展示。
    private String buildSummary(String content, boolean revisionMode) {
        String prefix = revisionMode ? "Revision report: " : "Initial report: ";
        if (content == null) {
            return prefix;
        }
        String trimmed = content.replace("\n", " ");
        return prefix + (trimmed.length() > 300 ? trimmed.substring(0, 300) + "..." : trimmed);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    // Reviewer 结果既可能是对象，也可能被序列化成字符串，这里统一兜底解析。
    private JsonNode extractRevisionPlan(JsonNode reviewOutput) {
        if (reviewOutput == null || !reviewOutput.has("revisionPlan")) {
            return null;
        }
        JsonNode revisionPlan = reviewOutput.get("revisionPlan");
        if (revisionPlan.isTextual()) {
            return readJson(revisionPlan.asText());
        }
        return revisionPlan;
    }

    // 安全 JSON 解析，避免单个脏字段让 Writer 整体失败。
    private JsonNode readJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }
}
