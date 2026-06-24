package cn.bugstack.competitoragent.architecture;

import cn.bugstack.competitoragent.agent.Agent;
import cn.bugstack.competitoragent.agent.AgentContext;
import cn.bugstack.competitoragent.agent.AgentResult;
import cn.bugstack.competitoragent.agent.capability.AgentCapabilityRegistry;
import cn.bugstack.competitoragent.agent.capability.SpringAgentCapabilityRegistry;

import java.util.List;

/**
 * Phase 2 Task 1 先把“逻辑模块 -> 当前真实 package / 类”映射沉淀成独立测试资产。
 * 这样后续 ArchUnit 规则不需要继续散落字符串常量，也能明确看到当前阶段的真实落点。
 */
final class ArchitecturePackageMapping {

    /**
     * task-orchestration 当前由任务用例、工作流编排与运行事件三部分共同组成。
     * phase3a 开始即便继续收口 facade，也必须先基于这组真实包映射补规则，而不是重新发明命名。
     */
    static final List<String> TASK_PACKAGES = List.of(
            "cn.bugstack.competitoragent.task..",
            "cn.bugstack.competitoragent.workflow..",
            "cn.bugstack.competitoragent.orchestration..",
            "cn.bugstack.competitoragent.event.."
    );

    /**
     * collection-intelligence 当前还没有独立 collection 包，
     * 因此先按搜索、来源以及采集 Agent 的真实位置登记，供后续 phase3b 统一收口。
     */
    static final List<String> COLLECTION_PACKAGES = List.of(
            "cn.bugstack.competitoragent.collection..",
            "cn.bugstack.competitoragent.search..",
            "cn.bugstack.competitoragent.source..",
            "cn.bugstack.competitoragent.agent.collector.."
    );

    /**
     * knowledge-intelligence 当前横跨 knowledge / rag / memory / context，
     * 同时还包含抽取与分析 Agent，因此这里显式保留真实分布，避免规则遗漏历史热点。
     */
    static final List<String> KNOWLEDGE_PACKAGES = List.of(
            "cn.bugstack.competitoragent.knowledge..",
            "cn.bugstack.competitoragent.rag..",
            "cn.bugstack.competitoragent.memory..",
            "cn.bugstack.competitoragent.context..",
            "cn.bugstack.competitoragent.agent.extractor..",
            "cn.bugstack.competitoragent.agent.analyzer.."
    );

    /**
     * report-delivery 当前既包含 report 服务，也包含 writer / reviewer Agent。
     * 先把真实位置统一挂在这里，后续 phase4b 只允许在消费者视角继续收口，而不是回退直连 collection。
     */
    static final List<String> REPORT_PACKAGES = List.of(
            "cn.bugstack.competitoragent.report..",
            "cn.bugstack.competitoragent.agent.writer..",
            "cn.bugstack.competitoragent.agent.reviewer.."
    );

    /**
     * conversation-entry 当前由对话应用服务与 conversation Agent 共同组成。
     * phase4b 需要基于这组映射判断它是否仍在越过 facade 直连内部实现。
     */
    static final List<String> CONVERSATION_PACKAGES = List.of(
            "cn.bugstack.competitoragent.conversation..",
            "cn.bugstack.competitoragent.agent.conversation.."
    );

    /**
     * runtime baseline 不是一个完整 package，而是一组已经在 phase1 被锁定的最小运行时类。
     * 这里按 FQCN 固化，避免后续规则错误地把整个 agent 包都当成 runtime baseline。
     */
    static final List<String> RUNTIME_BASELINE_CLASS_NAMES = List.of(
            Agent.class.getName(),
            AgentContext.class.getName(),
            AgentResult.class.getName(),
            AgentCapabilityRegistry.class.getName(),
            SpringAgentCapabilityRegistry.class.getName()
    );

    private ArchitecturePackageMapping() {
    }
}
