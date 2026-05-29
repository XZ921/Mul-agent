package cn.bugstack.competitoragent.workflow;

import cn.bugstack.competitoragent.agent.Agent;
import cn.bugstack.competitoragent.agent.AgentContext;
import cn.bugstack.competitoragent.agent.AgentResult;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.AnalysisTaskStatus;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.repository.AnalysisTaskRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DAG 执行器，负责按节点顺序驱动各类 Agent，并处理依赖、重试、条件分支和任务收口。
 */
@Slf4j
@Component
public class DagExecutor {

    private final TaskNodeRepository nodeRepository;
    private final AnalysisTaskRepository taskRepository;
    private final Map<AgentType, Agent> agentRegistry;
    private final ObjectMapper objectMapper;

    public DagExecutor(TaskNodeRepository nodeRepository,
                       AnalysisTaskRepository taskRepository,
                       List<Agent> agents,
                       ObjectMapper objectMapper) {
        this.nodeRepository = nodeRepository;
        this.taskRepository = taskRepository;
        this.agentRegistry = buildAgentRegistry(agents);
        this.objectMapper = objectMapper;
    }

    public void execute(Long taskId, AgentContext context) {
        log.info("start dag execution, taskId={}", taskId);
        if (!markTaskRunning(taskId)) {
            log.info("skip dag execution because task is not in runnable state, taskId={}", taskId);
            return;
        }

        List<TaskNode> nodes = nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId);
        if (nodes.isEmpty()) {
            failTask(taskId, "No executable DAG nodes");
            return;
        }

        seedSharedOutputs(context, nodes);
        Map<String, TaskNode> nodeMap = buildNodeMap(nodes);
        validateExecutableNodes(taskId, nodes, nodeMap);

        boolean hasFailedRequiredNode = false;
        for (TaskNode node : nodes) {
            if (isTaskStopped(taskId)) {
                stopRemainingNodes(taskId, nodes, node);
                return;
            }

            // 续跑场景下，已成功节点直接跳过，避免覆盖已产出的稳定结果。
            if (node.getStatus() == TaskNodeStatus.SUCCESS) {
                continue;
            }

            if (hasFailedRequiredNode && node.isRequired()) {
                skipNode(node, "Upstream required node failed");
                continue;
            }

            if (!shouldExecuteNode(node, context, nodes)) {
                skipNode(node, buildConditionalSkipReason(node, context, nodes));
                continue;
            }

            if (!dependenciesSatisfied(node, nodeMap)) {
                skipNode(node, buildDependencyFailureReason(node, nodeMap));
                if (node.isRequired()) {
                    hasFailedRequiredNode = true;
                }
                continue;
            }

            Agent agent = agentRegistry.get(node.getAgentType());
            if (agent == null) {
                failNode(node, "Missing agent implementation: " + node.getAgentType());
                if (node.isRequired()) {
                    hasFailedRequiredNode = true;
                }
                continue;
            }

            context.setCurrentNodeName(node.getNodeName());
            context.setCurrentNodeConfig(node.getNodeConfig());
            markNodeRunning(node, context);

            AgentResult result = executeNodeWithRetry(agent, context, node);
            node.setStatus(result.getStatus());
            node.setOutputData(result.getOutputData());
            node.setCompletedAt(LocalDateTime.now());

            if (result.getStatus() == TaskNodeStatus.SUCCESS) {
                // 成功节点的输出要立刻回写共享上下文，供下游节点在同一轮执行中读取。
                node.setErrorMessage(null);
                context.putSharedOutput(node.getNodeName(), result.getOutputData());
            } else {
                node.setErrorMessage(result.getErrorMessage());
                if (node.isRequired()) {
                    hasFailedRequiredNode = true;
                }
            }

            nodeRepository.save(node);

            if (isTaskStopped(taskId)) {
                stopRemainingNodes(taskId, nodes, node);
                return;
            }
        }

        updateTaskFinalStatus(taskId);
    }

    /**
     * 续跑时重新灌入已成功节点输出，让后续节点能像在同一条执行链中一样复用历史结果。
     */
    private void seedSharedOutputs(AgentContext context, List<TaskNode> nodes) {
        for (TaskNode node : nodes) {
            if (node.getStatus() == TaskNodeStatus.SUCCESS
                    && node.getOutputData() != null
                    && !node.getOutputData().isBlank()) {
                context.putSharedOutput(node.getNodeName(), node.getOutputData());
            }
        }
    }

    private AgentResult executeNodeWithRetry(Agent agent, AgentContext context, TaskNode node) {
        int maxAttempts = node.isRetryable() ? Math.max(1, node.getMaxRetries() + 1) : 1;
        AgentResult lastResult = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            node.setRetryCount(attempt - 1);
            try {
                lastResult = agent.execute(context);
                if (lastResult.getStatus() == TaskNodeStatus.SUCCESS) {
                    return lastResult;
                }
                // 只有显式允许重试且次数未耗尽时，才进入下一轮尝试。
                if (!node.isRetryable() || attempt >= maxAttempts) {
                    return lastResult;
                }
                log.warn("node retry scheduled, nodeName={}, attempt={}/{}",
                        node.getNodeName(), attempt, maxAttempts);
            } catch (Exception e) {
                lastResult = AgentResult.failed(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                if (!node.isRetryable() || attempt >= maxAttempts) {
                    return lastResult;
                }
                log.warn("node retry after exception, nodeName={}, attempt={}/{}",
                        node.getNodeName(), attempt, maxAttempts, e);
            }
        }

        return lastResult != null ? lastResult : AgentResult.failed("Unknown node execution failure");
    }

    private Map<AgentType, Agent> buildAgentRegistry(List<Agent> agents) {
        EnumMap<AgentType, Agent> registry = new EnumMap<>(AgentType.class);
        for (Agent agent : agents) {
            Agent existing = registry.putIfAbsent(agent.getType(), agent);
            if (existing != null) {
                throw new IllegalStateException("Duplicate Agent implementation for type " + agent.getType());
            }
        }
        log.info("agent registry initialized: {}", registry.keySet());
        return registry;
    }

    private boolean markTaskRunning(Long taskId) {
        return taskRepository.findById(taskId).map(task -> {
            if (task.getStatus() == AnalysisTaskStatus.STOPPED) {
                return false;
            }
            task.setStatus(AnalysisTaskStatus.RUNNING);
            if (task.getStartedAt() == null) {
                task.setStartedAt(LocalDateTime.now());
            }
            task.setCompletedAt(null);
            task.setErrorMessage(null);
            taskRepository.save(task);
            return true;
        }).orElse(false);
    }

    private void markNodeRunning(TaskNode node, AgentContext context) {
        node.setStatus(TaskNodeStatus.RUNNING);
        node.setInputData(buildNodeInput(node, context));
        node.setErrorMessage(null);
        node.setStartedAt(LocalDateTime.now());
        node.setCompletedAt(null);
        nodeRepository.save(node);
    }

    /**
     * 固化每个节点的运行输入，便于节点 trace 和失败排障时回放上下文。
     */
    private String buildNodeInput(TaskNode node, AgentContext context) {
        return """
                {"taskId":%d,"taskName":"%s","nodeName":"%s","agentType":"%s","dependsOn":%s,"nodeConfig":%s}
                """.formatted(
                context.getTaskId(),
                escapeJson(context.getTaskName()),
                escapeJson(node.getNodeName()),
                node.getAgentType(),
                node.getDependsOn() == null || node.getDependsOn().isBlank() ? "[]" : node.getDependsOn(),
                node.getNodeConfig() == null ? "null" : node.getNodeConfig()
        ).trim();
    }

    private void skipNode(TaskNode node, String reason) {
        node.setStatus(TaskNodeStatus.SKIPPED);
        node.setInputData(null);
        node.setErrorMessage(reason);
        node.setStartedAt(node.getStartedAt() == null ? LocalDateTime.now() : node.getStartedAt());
        node.setCompletedAt(LocalDateTime.now());
        nodeRepository.save(node);
        log.warn("node skipped: {}, reason={}", node.getNodeName(), reason);
    }

    private void failNode(TaskNode node, String errorMessage) {
        node.setStatus(TaskNodeStatus.FAILED);
        node.setErrorMessage(errorMessage);
        node.setStartedAt(node.getStartedAt() == null ? LocalDateTime.now() : node.getStartedAt());
        node.setCompletedAt(LocalDateTime.now());
        nodeRepository.save(node);
        log.error("node failed: {}, reason={}", node.getNodeName(), errorMessage);
    }

    /**
     * 依赖判断除了 SUCCESS 外，还要兼容 allowFailedDependency 的可选放行语义。
     */
    private boolean dependenciesSatisfied(TaskNode node, Map<String, TaskNode> nodeMap) {
        List<String> dependencyNames = parseDependencyNames(node.getDependsOn());
        if (dependencyNames.isEmpty()) {
            return true;
        }

        for (String dependencyName : dependencyNames) {
            TaskNode dependencyNode = nodeMap.get(dependencyName);
            if (dependencyNode == null) {
                return false;
            }

            if (dependencyNode.getStatus() == TaskNodeStatus.SUCCESS) {
                continue;
            }

            if (node.isAllowFailedDependency()
                    && (dependencyNode.getStatus() == TaskNodeStatus.FAILED
                    || dependencyNode.getStatus() == TaskNodeStatus.SKIPPED)) {
                continue;
            }
            return false;
        }
        return true;
    }

    private List<String> parseDependencyNames(String dependsOn) {
        if (dependsOn == null || dependsOn.isBlank() || "[]".equals(dependsOn)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(dependsOn, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("failed to parse dependencies: {}", dependsOn, e);
            return List.of();
        }
    }

    /**
     * V2 的任务成败不仅取决于节点是否跑完，还取决于质量闭环是否最终通过。
     */
    private void updateTaskFinalStatus(Long taskId) {
        taskRepository.findById(taskId).ifPresent(task -> {
            List<TaskNode> nodes = nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId);

            boolean hasFailedOrSkippedRequiredNode = nodes.stream()
                    .filter(TaskNode::isRequired)
                    .anyMatch(node -> node.getStatus() == TaskNodeStatus.FAILED
                            || node.getStatus() == TaskNodeStatus.SKIPPED);
            boolean allRequiredSuccess = nodes.stream()
                    .filter(TaskNode::isRequired)
                    .allMatch(node -> node.getStatus() == TaskNodeStatus.SUCCESS);

            boolean initialReviewPresent = nodes.stream()
                    .anyMatch(node -> "quality_check".equals(node.getNodeName()));
            boolean initialReviewPassed = nodes.stream()
                    .filter(node -> "quality_check".equals(node.getNodeName()))
                    .anyMatch(node -> node.getStatus() == TaskNodeStatus.SUCCESS && isPassedReview(node.getOutputData()));
            boolean finalReviewPassed = nodes.stream()
                    .filter(node -> "quality_check_final".equals(node.getNodeName()))
                    .anyMatch(node -> node.getStatus() == TaskNodeStatus.SUCCESS && isPassedReview(node.getOutputData()));
            boolean revisionFlowUsed = nodes.stream()
                    .anyMatch(node -> "rewrite_report".equals(node.getNodeName())
                            && node.getStatus() == TaskNodeStatus.SUCCESS);

            if (hasFailedOrSkippedRequiredNode) {
                task.setStatus(AnalysisTaskStatus.FAILED);
                task.setErrorMessage("任务执行失败，请检查节点日志。");
            } else if (finalReviewPassed || (!initialReviewPresent && allRequiredSuccess) || initialReviewPassed) {
                task.setStatus(AnalysisTaskStatus.SUCCESS);
                task.setErrorMessage(null);
            } else if (initialReviewPresent || revisionFlowUsed) {
                task.setStatus(AnalysisTaskStatus.FAILED);
                task.setErrorMessage("质量闭环未达到通过状态，请检查评审反馈。");
            }

            task.setCompletedAt(LocalDateTime.now());
            taskRepository.save(task);
        });
    }

    private void failTask(Long taskId, String errorMessage) {
        taskRepository.findById(taskId).ifPresent(task -> {
            task.setStatus(AnalysisTaskStatus.FAILED);
            task.setErrorMessage(errorMessage);
            task.setCompletedAt(LocalDateTime.now());
            taskRepository.save(task);
        });
    }

    private boolean isTaskStopped(Long taskId) {
        return taskRepository.findById(taskId)
                .map(task -> task.getStatus() == AnalysisTaskStatus.STOPPED)
                .orElse(false);
    }

    private void stopRemainingNodes(Long taskId, List<TaskNode> nodes, TaskNode currentNode) {
        boolean afterCurrent = false;
        for (TaskNode candidate : nodes) {
            if (!afterCurrent) {
                if (candidate.getId().equals(currentNode.getId())) {
                    afterCurrent = true;
                }
                continue;
            }
            if (candidate.getStatus() == TaskNodeStatus.SUCCESS
                    || candidate.getStatus() == TaskNodeStatus.FAILED
                    || candidate.getStatus() == TaskNodeStatus.SKIPPED) {
                continue;
            }
            candidate.setStatus(TaskNodeStatus.SKIPPED);
            candidate.setErrorMessage("任务已被用户主动停止");
            candidate.setCompletedAt(LocalDateTime.now());
            nodeRepository.save(candidate);
        }

        taskRepository.findById(taskId).ifPresent(task -> {
            task.setStatus(AnalysisTaskStatus.STOPPED);
            task.setErrorMessage("任务已由用户主动停止");
            task.setCompletedAt(LocalDateTime.now());
            taskRepository.save(task);
        });
        log.info("task stopped during dag execution, taskId={}, currentNode={}", taskId, currentNode.getNodeName());
    }

    /**
     * 执行前的兜底校验，确保数据库中的 DAG 仍然满足“无环 + 依赖存在 + 顺序正确”。
     */
    private void validateExecutableNodes(Long taskId, List<TaskNode> nodes, Map<String, TaskNode> nodeMap) {
        for (TaskNode node : nodes) {
            for (String dependencyName : parseDependencyNames(node.getDependsOn())) {
                TaskNode dependencyNode = nodeMap.get(dependencyName);
                if (dependencyNode == null) {
                    failTask(taskId, "Missing dependency node: " + node.getNodeName() + " -> " + dependencyName);
                    throw new IllegalStateException("Missing dependency node: " + dependencyName);
                }
                if (dependencyNode.getExecutionOrder() >= node.getExecutionOrder()) {
                    failTask(taskId, "Invalid dependency order: " + node.getNodeName() + " -> " + dependencyName);
                    throw new IllegalStateException("Invalid dependency order");
                }
            }
        }
        ensureAcyclic(taskId, nodes);
    }

    private Map<String, TaskNode> buildNodeMap(List<TaskNode> nodes) {
        Map<String, TaskNode> nodeMap = new HashMap<>();
        for (TaskNode node : nodes) {
            nodeMap.put(node.getNodeName(), node);
        }
        return nodeMap;
    }

    /**
     * 再次拓扑检查，避免环路把任务卡死在未完成状态。
     */
    private void ensureAcyclic(Long taskId, List<TaskNode> nodes) {
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> adjacency = new HashMap<>();

        for (TaskNode node : nodes) {
            indegree.put(node.getNodeName(), 0);
            adjacency.put(node.getNodeName(), new java.util.ArrayList<>());
        }

        for (TaskNode node : nodes) {
            for (String dependencyName : parseDependencyNames(node.getDependsOn())) {
                adjacency.get(dependencyName).add(node.getNodeName());
                indegree.put(node.getNodeName(), indegree.get(node.getNodeName()) + 1);
            }
        }

        ArrayDeque<String> queue = new ArrayDeque<>();
        indegree.forEach((nodeName, degree) -> {
            if (degree == 0) {
                queue.add(nodeName);
            }
        });

        int visited = 0;
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            visited++;
            for (String next : adjacency.getOrDefault(current, List.of())) {
                int degree = indegree.computeIfPresent(next, (key, value) -> value - 1);
                if (degree == 0) {
                    queue.add(next);
                }
            }
        }

        if (visited != nodes.size()) {
            failTask(taskId, "Cyclic workflow dependencies detected");
            throw new IllegalStateException("Cyclic workflow dependencies detected");
        }
    }

    private String buildDependencyFailureReason(TaskNode node, Map<String, TaskNode> nodeMap) {
        List<String> dependencyNames = parseDependencyNames(node.getDependsOn());
        if (dependencyNames.isEmpty()) {
            return "Dependencies not satisfied";
        }

        StringBuilder reason = new StringBuilder("Dependencies not satisfied: ");
        boolean appended = false;
        for (String dependencyName : dependencyNames) {
            TaskNode dependencyNode = nodeMap.get(dependencyName);
            if (dependencyNode == null) {
                if (appended) {
                    reason.append("; ");
                }
                reason.append(dependencyName).append(" missing");
                appended = true;
                continue;
            }
            if (dependencyNode.getStatus() == TaskNodeStatus.SUCCESS) {
                continue;
            }
            if (node.isAllowFailedDependency()
                    && (dependencyNode.getStatus() == TaskNodeStatus.FAILED
                    || dependencyNode.getStatus() == TaskNodeStatus.SKIPPED)) {
                continue;
            }
            if (appended) {
                reason.append("; ");
            }
            reason.append(dependencyName).append("=").append(dependencyNode.getStatus());
            appended = true;
        }
        return appended ? reason.toString() : "Dependencies not satisfied";
    }

    private boolean isPassedReview(String outputData) {
        JsonNode json = readJson(outputData);
        return json != null && json.path("passed").asBoolean(false);
    }

    /**
     * 条件节点根据 trigger 决定是否执行，例如“质检失败才重写”“重写成功后才终审”。
     */
    private boolean shouldExecuteNode(TaskNode node, AgentContext context, List<TaskNode> allNodes) {
        String trigger = resolveTrigger(node.getNodeConfig());
        if (trigger == null || trigger.isBlank()) {
            return true;
        }
        return switch (trigger) {
            case "review_failed" -> {
                JsonNode reviewOutput = readJson(context.getSharedOutput("quality_check"));
                yield reviewOutput != null && !reviewOutput.path("passed").asBoolean(true);
            }
            case "rewrite_executed" -> allNodes.stream()
                    .anyMatch(current -> "rewrite_report".equals(current.getNodeName())
                            && current.getStatus() == TaskNodeStatus.SUCCESS);
            default -> true;
        };
    }

    private String buildConditionalSkipReason(TaskNode node, AgentContext context, List<TaskNode> allNodes) {
        String trigger = resolveTrigger(node.getNodeConfig());
        if ("review_failed".equals(trigger)) {
            JsonNode reviewOutput = readJson(context.getSharedOutput("quality_check"));
            if (reviewOutput == null) {
                return "跳过修订：缺少有效的评审结果";
            }
            if (!reviewOutput.has("passed")) {
                return "跳过修订：评审输出不完整";
            }
            return reviewOutput.path("passed").asBoolean(false)
                    ? "初审已通过，无需修订"
                    : "初审未通过，应触发修订";
        }

        if ("rewrite_executed".equals(trigger)) {
            TaskNode rewriteNode = allNodes.stream()
                    .filter(current -> "rewrite_report".equals(current.getNodeName()))
                    .findFirst()
                    .orElse(null);
            if (rewriteNode == null) {
                return "跳过终审：未找到改写节点";
            }
            return switch (rewriteNode.getStatus()) {
                case SKIPPED -> "跳过终审：本轮未触发改写";
                case FAILED -> "跳过终审：改写执行失败";
                case PENDING, RUNNING -> "跳过终审：改写尚未完成";
                default -> "跳过终审：改写结果不可用";
            };
        }

        return "未满足节点执行条件";
    }

    private String resolveTrigger(String nodeConfig) {
        JsonNode config = readJson(nodeConfig);
        if (config == null) {
            return null;
        }
        String trigger = config.path("trigger").asText("");
        return trigger.isBlank() ? null : trigger;
    }

    private JsonNode readJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String cleaned = raw.trim();
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }

        try {
            return objectMapper.readTree(cleaned.trim());
        } catch (Exception e) {
            log.warn("failed to parse node output json", e);
            return null;
        }
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
