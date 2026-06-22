package cn.bugstack.competitoragent.extractor.input;

import cn.bugstack.competitoragent.agent.AgentContext;

/**
 * extractor 正式输入提供者。
 * 这里负责把任务上下文、schema 配置和证据仓储统一装配成运行态输入包，
 * 让 SchemaExtractorAgent 只消费稳定输入契约，而不是直接依赖 repository。
 */
public interface ExtractorInputProvider {

    ExtractorInputPackage provide(AgentContext context);
}
