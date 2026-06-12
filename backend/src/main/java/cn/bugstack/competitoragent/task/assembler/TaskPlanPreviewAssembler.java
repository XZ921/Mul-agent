package cn.bugstack.competitoragent.task.assembler;

import cn.bugstack.competitoragent.model.dto.TaskNodeConfigSummary;
import cn.bugstack.competitoragent.model.dto.TaskPlanPreviewNodeResponse;
import cn.bugstack.competitoragent.model.dto.TaskPlanPreviewResponse;
import cn.bugstack.competitoragent.model.dto.TaskPlanPreviewStageResponse;
import cn.bugstack.competitoragent.task.definition.TaskDefinition;
import cn.bugstack.competitoragent.workflow.WorkflowPlan;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 任务计划预览组装器只负责把正式计划快照投影成创建页可消费的 preview 合同。
 * 它和运行态节点响应分离，避免 preview/runtime 共用同一组弱语义字段。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskPlanPreviewAssembler {

    private final ObjectMapper objectMapper;

    /**
     * 预览响应始终优先消费 WorkflowPlan 中已经固化的正式阶段语义，
     * 只有历史或过渡计划未补齐字段时，才允许退回到任务定义做最小兜底说明。
     */
    public TaskPlanPreviewResponse toPreviewResponse(TaskDefinition definition, WorkflowPlan plan) {
        String goal = StringUtils.hasText(plan.getGoal())
                ? plan.getGoal()
                : buildGoal(definition);
        List<TaskPlanPreviewStageResponse> stages = !plan.getStages().isEmpty()
                ? plan.getStages().stream()
                .map(stage -> TaskPlanPreviewStageResponse.builder()
                        .key(toPreviewKey(stage.getStageCode()))
                        .stageCode(stage.getStageCode())
                        .title(stage.getTitle())
                        .summary(stage.getSummary())
                        .detail(stage.getDetail())
                        .sourceUrls(defaultList(stage.getSourceUrls()))
                        .build())
                .toList()
                : buildFallbackStages(definition);

        return TaskPlanPreviewResponse.builder()
                .contractType(StringUtils.hasText(plan.getContractType()) ? plan.getContractType() : "TASK_PLAN_PREVIEW_V1")
                .goal(goal)
                .competitorCount(definition == null || definition.getCompetitors() == null ? 0 : definition.getCompetitors().size())
                .collectorCount((int) plan.getNodes().stream().filter(node -> "COLLECTOR".equals(node.getAgentType())).count())
                .pipelineCount((int) plan.getNodes().stream().filter(node -> !"COLLECTOR".equals(node.getAgentType())).count())
                .lanes(List.of())
                .stages(stages)
                .nodes(plan.getNodes().stream()
                        .map(node -> TaskPlanPreviewNodeResponse.builder()
                                .nodeName(node.getNodeName())
                                .displayName(node.getDisplayName())
                                .agentType(node.getAgentType())
                                .stageCode(node.getStageCode())
                                .goal(node.getGoal())
                                .summary(node.getSummary())
                                .configSummaryData(buildPreviewNodeConfigSummaryData(node))
                                .dependsOn(defaultList(node.getDependsOn()))
                                .required(node.isRequired())
                                .executionOrder(node.getExecutionOrder())
                                .fallbackOrder(defaultList(node.getFallbackOrder()))
                                .sourceUrls(defaultList(node.getSourceUrls()))
                                .build())
                        .toList())
                .sourceUrls(plan.getNodes().stream()
                        .flatMap(node -> defaultList(node.getSourceUrls()).stream())
                        .distinct()
                        .toList())
                .build();
    }

    private List<TaskPlanPreviewStageResponse> buildFallbackStages(TaskDefinition definition) {
        List<TaskPlanPreviewStageResponse> fallback = new ArrayList<>();
        fallback.add(TaskPlanPreviewStageResponse.builder()
                .key("goal")
                .stageCode("GOAL")
                .title("明确任务目标")
                .summary(definition == null ? "待补充分析主题" : defaultIfBlank(definition.getTaskName(), "待补充分析主题"))
                .detail(buildGoal(definition))
                .sourceUrls(definition == null ? List.of() : defaultList(definition.getSourceUrls()))
                .build());
        fallback.add(TaskPlanPreviewStageResponse.builder()
                .key("source-strategy")
                .stageCode("SOURCE_STRATEGY")
                .title("规划来源策略")
                .summary(buildSourceStrategySummary(definition))
                .detail("优先覆盖官网、产品文档，必要时再补充公网搜索")
                .sourceUrls(definition == null ? List.of() : defaultList(definition.getSourceUrls()))
                .build());
        return fallback;
    }

    private String buildGoal(TaskDefinition definition) {
        if (definition == null) {
            return "补充本方产品后，系统会生成正式任务目标";
        }
        if (StringUtils.hasText(definition.getSubjectProduct())) {
            return "围绕 " + definition.getSubjectProduct() + " 展开竞品研究";
        }
        return "补充本方产品后，系统会生成正式任务目标";
    }

    private String buildSourceStrategySummary(TaskDefinition definition) {
        if (definition == null || definition.getSourceScope() == null || definition.getSourceScope().isEmpty()) {
            return "优先覆盖官网、产品文档";
        }
        return "优先覆盖 " + String.join("、", definition.getSourceScope());
    }

    private TaskNodeConfigSummary buildPreviewNodeConfigSummaryData(WorkflowPlan.WorkflowPlanNode node) {
        if (node == null || !StringUtils.hasText(node.getAgentType()) || !StringUtils.hasText(node.getNodeConfig())) {
            return null;
        }
        JsonNode config = readJson(node.getNodeConfig());
        if (config == null) {
            return null;
        }
        String agentType = node.getAgentType().trim().toUpperCase(Locale.ROOT);
        if ("COLLECTOR".equals(agentType)) {
            String competitor = defaultIfBlank(textOrNull(config, "competitorName"), "未命名竞品");
            String sourceType = defaultIfBlank(textOrNull(config, "sourceType"), "OFFICIAL");
            boolean verificationEnabled = config.path("verifyResultPage").asBoolean(config.path("verifyCandidates").asBoolean(false));
            return TaskNodeConfigSummary.builder()
                    .summaryText(node.getSummary())
                    .competitorName(competitor)
                    .sourceType(sourceType)
                    .sourceTypeLabel(sourceTypeLabel(sourceType))
                    .searchMode(defaultIfBlank(textOrNull(config, "searchMode"), "HYBRID"))
                    .searchModeLabel(searchModeLabel(defaultIfBlank(textOrNull(config, "searchMode"), "HYBRID")))
                    .candidateCount(config.path("sourceCandidates").isArray() ? config.path("sourceCandidates").size() : 0)
                    .queryCount(config.path("searchQueries").isArray() ? config.path("searchQueries").size() : 0)
                    .stepCount(config.path("searchExecutionPlan").path("steps").isArray()
                            ? config.path("searchExecutionPlan").path("steps").size()
                            : 0)
                    .browserSearchEnabled(config.path("browserSearchEnabled").asBoolean(false))
                    .verificationEnabled(verificationEnabled)
                    .minVerifiedCandidates(config.path("minVerifiedCandidates").asInt(0) > 0
                            ? config.path("minVerifiedCandidates").asInt(0)
                            : null)
                    .sourceScope(readStringList(config.get("sourceScope")))
                    .preferredDomains(readStringList(config.get("preferredDomains")))
                    .competitorUrls(readStringList(config.get("competitorUrls")))
                    .discoveryNotes(textOrNull(config, "discoveryNotes"))
                    .build();
        }
        if ("EXTRACTOR".equals(agentType)) {
            return TaskNodeConfigSummary.builder()
                    .summaryText(node.getSummary())
                    .dimensions(readStringList(config.get("dimensions")))
                    .build();
        }
        if ("ANALYZER".equals(agentType)) {
            return TaskNodeConfigSummary.builder()
                    .summaryText(node.getSummary())
                    .competitorCount(config.path("competitorCount").asInt(0))
                    .dimensionCount(config.path("dimensionCount").asInt(0))
                    .build();
        }
        if ("WRITER".equals(agentType)) {
            return TaskNodeConfigSummary.builder()
                    .summaryText(node.getSummary())
                    .mode(defaultIfBlank(textOrNull(config, "mode"), "initial"))
                    .reportLanguage(defaultIfBlank(textOrNull(config, "reportLanguage"), "中文"))
                    .reportTemplate(defaultIfBlank(textOrNull(config, "reportTemplate"), "标准版"))
                    .sourceNode(textOrNull(config, "sourceNode"))
                    .build();
        }
        if ("REVIEWER".equals(agentType)) {
            return TaskNodeConfigSummary.builder()
                    .summaryText(node.getSummary())
                    .qualityPolicy(defaultIfBlank(textOrNull(config, "qualityPolicy"), "score>=80 and no ERROR issues"))
                    .sourceNode(textOrNull(config, "sourceNode"))
                    .build();
        }
        return TaskNodeConfigSummary.builder()
                .summaryText(node.getSummary())
                .build();
    }

    private String toPreviewKey(String stageCode) {
        if (!StringUtils.hasText(stageCode)) {
            return "unknown";
        }
        return stageCode.trim().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private List<String> readStringList(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        node.forEach(item -> {
            String value = item == null ? null : item.asText(null);
            if (StringUtils.hasText(value)) {
                values.add(value);
            }
        });
        return values;
    }

    private JsonNode readJson(String raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            log.warn("parse preview node config failed", e);
            return null;
        }
    }

    private String textOrNull(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        String value = node.path(field).asText("");
        return value.isBlank() ? null : value;
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value : defaultValue;
    }

    private List<String> defaultList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private String sourceTypeLabel(String sourceType) {
        if (!StringUtils.hasText(sourceType)) {
            return "官网";
        }
        return switch (sourceType.trim().toUpperCase(Locale.ROOT)) {
            case "DOCS" -> "产品文档";
            case "PRICING" -> "定价";
            case "NEWS" -> "资讯";
            case "REVIEW" -> "测评";
            case "OFFICIAL" -> "官网";
            default -> sourceType;
        };
    }

    private String searchModeLabel(String searchMode) {
        if (!StringUtils.hasText(searchMode)) {
            return "混合";
        }
        return switch (searchMode.trim().toUpperCase(Locale.ROOT)) {
            case "BROWSER_ONLY" -> "仅浏览器";
            case "HTTP_ONLY" -> "仅 HTTP";
            case "HEURISTIC_ONLY" -> "仅规则候选";
            case "HYBRID" -> "混合";
            default -> searchMode;
        };
    }
}
