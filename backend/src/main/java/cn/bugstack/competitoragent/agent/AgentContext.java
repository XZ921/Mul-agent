package cn.bugstack.competitoragent.agent;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import cn.bugstack.competitoragent.task.SharedNodeOutputEnvelope;
import cn.bugstack.competitoragent.context.TaskRagContextBundle;

/**
 * Agent 运行上下文。
 * Phase 1 将它冻结为最小运行时边界，只承载任务执行所需的稳定字段与节点共享输出，
 * 不允许继续膨胀为承载业务实体、Repository 或临时视图对象的大容器。
 */
@Data
@Builder(toBuilder = true)
public class AgentContext {

    private Long taskId;
    private String taskName;
    private String subjectProduct;
    private String competitorNames;
    private String competitorUrls;
    private String analysisDimensions;
    private String sourceScope;
    private String reportLanguage;
    private String reportTemplate;
    private String currentNodeName;
    private String currentNodeConfig;
    private String traceId;
    private Long planVersionId;
    private String branchKey;
    private TaskRagContextBundle taskRagContextBundle;

    /**
     * sharedState 以 nodeName 为键保存节点输出，供下游节点继续消费。
     * 这里只保留字符串级共享结果，避免 runtime 基线重新耦合复杂业务对象。
     */
    @Builder.Default
    private Map<String, String> sharedState = new ConcurrentHashMap<>();

    /**
     * 共享输出信封用于承接稳定投影元数据。
     * sharedState 继续保留 payloadJson，保证历史调用方无需立刻改造。
     */
    @Builder.Default
    private Map<String, SharedNodeOutputEnvelope> sharedOutputEnvelopes = new ConcurrentHashMap<>();

    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    /**
     * 获取某个上游节点写入的共享输出。
     */
    public String getSharedOutput(String nodeName) {
        return sharedState.get(nodeName);
    }

    /**
     * 保存当前节点输出，供后续依赖节点读取。
     */
    public void putSharedOutput(String nodeName, String output) {
        sharedState.put(nodeName, output);
    }

    /**
     * 获取某个上游节点写入的共享信封。
     */
    public SharedNodeOutputEnvelope getSharedOutputEnvelope(String nodeName) {
        return sharedOutputEnvelopes.get(nodeName);
    }

    /**
     * 保存共享信封，并同步把 payloadJson 回填到 sharedState，
     * 保证旧消费者继续读取字符串投影时仍然可用。
     */
    public void putSharedOutputEnvelope(String nodeName, SharedNodeOutputEnvelope envelope) {
        if (nodeName != null && !nodeName.isBlank() && envelope != null) {
            sharedOutputEnvelopes.put(nodeName, envelope);
            // 兼容恢复历史缓存时可能出现“只有 envelope 元数据、暂时没有 payloadJson”的场景。
            // sharedState 基于 ConcurrentHashMap，写入 null 会直接触发 NPE，
            // 因此这里只在 payloadJson 非空时回填字符串视图，避免恢复链路被空值打断。
            if (envelope.getPayloadJson() != null) {
                sharedState.put(nodeName, envelope.getPayloadJson());
            }
        }
    }

    /**
     * 统一获取当前节点已经装配好的任务级 RAG 文本。
     * 这样各类 Agent 都从同一个入口消费检索摘要，避免在业务 Agent 中重复拼装上下文。
     */
    public String getTaskRagPromptContext() {
        if (taskRagContextBundle == null) {
            return "当前暂无检索上下文。";
        }
        return taskRagContextBundle.toPromptText();
    }
}
