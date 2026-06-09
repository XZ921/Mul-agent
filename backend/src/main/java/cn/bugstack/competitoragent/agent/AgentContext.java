package cn.bugstack.competitoragent.agent;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import cn.bugstack.competitoragent.context.TaskRagContextBundle;

/**
 * Agent 运行上下文。
 * 它是任务实体向运行态的投影，既包含稳定的任务参数，也包含节点之间共享的中间输出。
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
     * 使用并发容器是为了兼容异步执行和恢复执行场景。
     */
    @Builder.Default
    private Map<String, String> sharedState = new ConcurrentHashMap<>();

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
     * 统一获取当前节点已经装配好的任务级 RAG 文本，
     * 让各类 Agent 都从同一个入口消费检索摘要，而不是各自重新拼接。
     */
    public String getTaskRagPromptContext() {
        if (taskRagContextBundle == null) {
            return "当前暂无检索上下文。";
        }
        return taskRagContextBundle.toPromptText();
    }
}
