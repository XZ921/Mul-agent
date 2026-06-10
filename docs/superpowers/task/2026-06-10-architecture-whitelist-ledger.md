# Architecture Whitelist Ledger

## 目的

这份台账只登记 `ArchitectureWhitelist` 中已经存在的具名白名单记录，用来追踪：

- 当前是哪条规则在豁免
- 哪个类仍然保留历史耦合
- 为什么暂时不能回收
- 计划在哪个 phase 回收
- 当前由谁负责推动回收

## 使用规则

- 新增白名单必须先改代码清单，再改 ledger。
- 不允许新增整包豁免；只能登记具名规则 + 具名类。
- 如果 `ArchitectureWhitelist` 与本台账不一致，以测试失败为准，必须先修正二者同步关系。

## 当前白名单

| Rule | Class | Reason | Remove By Phase | Owner |
| --- | --- | --- | --- | --- |
| agent_classes_should_not_access_task_repositories | cn.bugstack.competitoragent.agent.BaseAgent | legacy runtime support 仍直接承担日志持久化与上下文增强依赖，phase1 明确不改 BaseAgent 主体。 | phase5-modularization-evaluation-task | A+B |
| agent_classes_should_not_access_task_repositories | cn.bugstack.competitoragent.agent.analyzer.CompetitorAnalysisAgent | knowledge 侧分析 Agent 仍保留历史 repository 直连，phase4a 只收口 facade 与 contract，不提前拆 analysis-intelligence。 | phase5-modularization-evaluation-task | A |
| agent_classes_should_not_access_task_repositories | cn.bugstack.competitoragent.agent.collector.CollectorAgent | phase3b 明确只收口 evidence 边界，不承诺移除 CollectorAgent 继承 BaseAgent 带来的历史 repository 依赖。 | phase5-modularization-evaluation-task | B |
| agent_classes_should_not_access_task_repositories | cn.bugstack.competitoragent.agent.extractor.SchemaExtractorAgent | knowledge 抽取 Agent 当前仍沿用历史 repository 访问方式，phase4a 先收口知识读接口与 contract 归属。 | phase5-modularization-evaluation-task | A |
| agent_classes_should_not_access_task_repositories | cn.bugstack.competitoragent.agent.reviewer.QualityReviewAgent | report 质量复核 Agent 仍在 legacy 结构中直接读取持久化对象，需等待 report facade 与消费视图稳定后再评估回收。 | phase5-modularization-evaluation-task | B |
| agent_classes_should_not_access_task_repositories | cn.bugstack.competitoragent.agent.writer.ReportWriterAgent | report 生成 Agent 当前仍直接读取证据与报告持久化对象，phase4b 之前不在本阶段提前修改其历史实现。 | phase5-modularization-evaluation-task | B |
| workflow_should_not_depend_on_business_agent_implementations | cn.bugstack.competitoragent.workflow.WorkflowFactory | WorkflowFactory 仍承担把搜索规划翻译成 Collector 节点配置的历史职责，需等待 phase3b 的 collection facade 稳定后再回收。 | phase3b-collection-evidence-task | B |
| task_should_not_depend_on_evidence_repository_directly | cn.bugstack.competitoragent.task.TaskArtifactCleanupService | phase3a 仅建立 cleanup coordinator 与 legacy adapter，TaskArtifactCleanupService 仍暂时承接证据清理；待 phase3b 把 evidence 删除迁到 CollectionArtifactCleanupPort 后再回收该豁免。 | phase3b-collection-evidence-task | B |
