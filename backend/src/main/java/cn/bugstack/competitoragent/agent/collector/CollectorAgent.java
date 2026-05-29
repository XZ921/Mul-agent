package cn.bugstack.competitoragent.agent.collector;

import cn.bugstack.competitoragent.agent.AgentContext;
import cn.bugstack.competitoragent.agent.AgentResult;
import cn.bugstack.competitoragent.agent.BaseAgent;
import cn.bugstack.competitoragent.model.entity.EvidenceSource;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.repository.AgentExecutionLogRepository;
import cn.bugstack.competitoragent.repository.EvidenceSourceRepository;
import cn.bugstack.competitoragent.source.SourceCollector;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 采集 Agent。
 * 负责按照节点配置抓取页面内容，并把结果持久化为可溯源的 EvidenceSource。
 */
@Slf4j
@Component
public class CollectorAgent extends BaseAgent {

    private final SourceCollector sourceCollector;
    private final EvidenceSourceRepository evidenceRepository;
    private final ObjectMapper objectMapper;

    public CollectorAgent(AgentExecutionLogRepository logRepository,
                          SourceCollector sourceCollector,
                          EvidenceSourceRepository evidenceRepository,
                          ObjectMapper objectMapper) {
        super(logRepository);
        this.sourceCollector = sourceCollector;
        this.evidenceRepository = evidenceRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentType getType() {
        return AgentType.COLLECTOR;
    }

    @Override
    public String getName() {
        return "CollectorAgent";
    }

    @Override
    protected AgentResult doExecute(AgentContext context) {
        // 采集节点依赖动态 DAG 注入的 competitorName / competitorUrls / sourceType 配置。
        CollectorNodeConfig config = parseConfig(context.getCurrentNodeConfig());
        if (config == null || config.competitorName == null || config.competitorName.isBlank()) {
            return AgentResult.failed("Missing collector node config");
        }

        List<String> urls = config.competitorUrls == null ? List.of() : config.competitorUrls;
        if (urls.isEmpty()) {
            return AgentResult.failed("No URL provided for competitor: " + config.competitorName);
        }

        List<Map<String, Object>> results = new ArrayList<>();
        int evidenceCounter = 0;
        String sourceType = config.sourceType == null || config.sourceType.isBlank() ? "OFFICIAL" : config.sourceType;

        for (String url : urls) {
            // 每个 URL 都会形成一条独立证据，后续抽取、报告、质检都依赖 evidenceId 做串联。
            SourceCollector.CollectedPage page = sourceCollector.collect(url, config.competitorName, sourceType);
            evidenceCounter++;
            String evidenceId = generateEvidenceId(context.getTaskId(), context.getCurrentNodeName(), evidenceCounter);

            EvidenceSource evidence = EvidenceSource.builder()
                    .taskId(context.getTaskId())
                    .competitorName(config.competitorName)
                    .evidenceId(evidenceId)
                    .title(page.getTitle() != null ? page.getTitle() : url)
                    .url(url)
                    .contentSnippet(page.getSnippet())
                    .fullContent(page.getContent())
                    .pageMetadata(page.getMetadata())
                    .collectedAt(LocalDateTime.now())
                    .build();
            evidenceRepository.save(evidence);

            Map<String, Object> resultEntry = new LinkedHashMap<>();
            resultEntry.put("competitor", config.competitorName);
            resultEntry.put("sourceType", sourceType);
            resultEntry.put("url", url);
            resultEntry.put("evidenceId", evidenceId);
            resultEntry.put("success", page.isSuccess());
            resultEntry.put("title", page.getTitle());
            resultEntry.put("contentLength", page.getContent() != null ? page.getContent().length() : 0);
            resultEntry.put("errorMessage", page.getErrorMessage());
            results.add(resultEntry);
        }

        try {
            String outputJson = objectMapper.writeValueAsString(Map.of(
                    "competitor", config.competitorName,
                    "sourceType", sourceType,
                    "discoveryNotes", config.discoveryNotes,
                    "totalCollected", results.size(),
                    "results", results
            ));
            return AgentResult.success(outputJson,
                    "Collected " + sourceType + " pages for " + config.competitorName + ": " + results.size());
        } catch (JsonProcessingException e) {
            return AgentResult.failed("serialize collection result failed: " + e.getMessage());
        }
    }

    // evidenceId 同时编码 taskId、nodeName 和序号，方便跨页面回查来源。
    private String generateEvidenceId(Long taskId, String nodeName, int sequence) {
        long safeTaskId = taskId == null ? 0L : taskId;
        String safeNodeName = nodeName == null || nodeName.isBlank()
                ? "NODE"
                : nodeName.toUpperCase().replaceAll("[^A-Z0-9]+", "_");
        return String.format("T%04d-%s-%03d", safeTaskId % 10000, safeNodeName, sequence);
    }

    // 节点配置来自数据库 JSON，解析失败时返回 null，由上层统一判定为节点配置缺失。
    private CollectorNodeConfig parseConfig(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            CollectorNodeConfig config = new CollectorNodeConfig();
            config.competitorName = node.hasNonNull("competitorName") ? node.get("competitorName").asText() : null;
            config.competitorUrls = objectMapper.convertValue(
                    node.has("competitorUrls") ? node.get("competitorUrls") : null,
                    new TypeReference<List<String>>() {});
            config.sourceType = node.hasNonNull("sourceType") ? node.get("sourceType").asText() : "OFFICIAL";
            config.discoveryNotes = node.hasNonNull("discoveryNotes") ? node.get("discoveryNotes").asText() : null;
            return config;
        } catch (Exception e) {
            log.warn("parse collector node config failed: {}", json, e);
            return null;
        }
    }

    /**
     * 仅作为当前节点 JSON 配置的解析载体，不向外暴露。
     */
    private static class CollectorNodeConfig {
        private String competitorName;
        private List<String> competitorUrls;
        private String sourceType;
        private String discoveryNotes;
    }
}
