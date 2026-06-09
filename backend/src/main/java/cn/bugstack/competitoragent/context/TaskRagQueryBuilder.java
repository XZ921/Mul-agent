package cn.bugstack.competitoragent.context;

import cn.bugstack.competitoragent.agent.AgentContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 负责把节点上下文压缩为稳定的检索查询。
 * <p>
 * 当前阶段先采用可解释的规则拼接，避免一开始就把 Query 生成责任交给另一个黑盒模型。
 */
@Component
public class TaskRagQueryBuilder {

    public String buildQuery(AgentContext context) {
        if (context == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        addIfPresent(parts, context.getSubjectProduct());
        addIfPresent(parts, context.getCompetitorNames());
        addIfPresent(parts, context.getAnalysisDimensions());
        addIfPresent(parts, context.getCurrentNodeName());
        return String.join(" ", parts).trim();
    }

    private void addIfPresent(List<String> parts, String value) {
        if (value != null && !value.isBlank()) {
            parts.add(value.trim());
        }
    }
}
