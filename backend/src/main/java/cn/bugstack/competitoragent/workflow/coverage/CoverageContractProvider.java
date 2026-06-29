package cn.bugstack.competitoragent.workflow.coverage;

import cn.bugstack.competitoragent.agent.AgentContext;
import cn.bugstack.competitoragent.model.entity.TaskPlan;
import cn.bugstack.competitoragent.repository.TaskPlanRepository;
import cn.bugstack.competitoragent.workflow.WorkflowPlan;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 覆盖契约 Provider。
 * 所有 Agent 都应通过它读取同一份契约，优先消费 TaskPlan 快照中的权威版本，
 * 只有在计划快照缺失或无法解析时，才回退到 resolver 做兼容推断。
 */
@Component
public class CoverageContractProvider {

    private final TaskPlanRepository taskPlanRepository;
    private final ObjectMapper objectMapper;
    private final CoverageContractResolver fallbackResolver;

    public CoverageContractProvider(TaskPlanRepository taskPlanRepository,
                                    ObjectMapper objectMapper,
                                    CoverageContractResolver fallbackResolver) {
        this.taskPlanRepository = taskPlanRepository;
        this.objectMapper = objectMapper;
        this.fallbackResolver = fallbackResolver;
    }

    /**
     * 解析当前 Agent 上下文对应的覆盖契约。
     * 先从激活计划快照读取 coverageContract，保证上下游共享同一个 plan version 下的契约快照；
     * 如果计划尚未持久化或快照不可读，再根据 AgentContext 回退推导。
     */
    public CoverageContract resolve(AgentContext context) {
        if (context != null && context.getTaskId() != null) {
            CoverageContract fromPlan = taskPlanRepository
                    .findFirstByTaskIdAndActiveTrueOrderByPlanVersionDesc(context.getTaskId())
                    .map(TaskPlan::getPlanSnapshot)
                    .map(this::readFromSnapshot)
                    .orElse(null);
            if (fromPlan != null) {
                return fromPlan;
            }
        }
        return fallbackResolver.resolve(
                context == null ? null : context.getReportTemplate(),
                readStringList(context == null ? null : context.getAnalysisDimensions()),
                readStringList(context == null ? null : context.getSourceScope()),
                null
        );
    }

    /**
     * 从 planSnapshot 中反序列化 WorkflowPlan 并读取 coverageContract。
     * 这里吞掉解析异常并回退，是因为旧快照可能还没带 coverageContract，不能让运行时直接崩掉。
     */
    private CoverageContract readFromSnapshot(String planSnapshot) {
        if (planSnapshot == null || planSnapshot.isBlank()) {
            return null;
        }
        try {
            WorkflowPlan plan = objectMapper.readValue(planSnapshot, WorkflowPlan.class);
            return plan.getCoverageContract();
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 兼容 AgentContext 里以 JSON 数组字符串存储的维度和来源范围。
     * 如果不是合法 JSON，就退化成单元素列表，尽量不丢上下文。
     */
    private List<String> readStringList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<List<String>>() {});
        } catch (Exception ignored) {
            return List.of(raw);
        }
    }
}
