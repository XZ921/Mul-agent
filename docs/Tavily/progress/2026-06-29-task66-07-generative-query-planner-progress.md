# Task66-07 生成式字段 Query Planner 进度记录

更新时间：2026-06-30 Asia/Shanghai

## 当前状态

- 当前执行计划：`docs/Tavily/plan/2026-06-29-07-task66-generative-field-query-planner-plan.md`
- 当前阶段：Task 7 审计、进度文档与采集链路封板声明完成
- 总体进度：7/7
- 执行状态：成功

## 结构化执行计划与进度

- [x] Task 1：生成式 planner failing test
  - 核心目标：证明非硬编码字段 `positioning` 也必须生成多条互补 query，并包含第三方视角。
  - 预期耗时：20 分钟
  - 依赖前置条件：06 的 `FieldEvidenceQueryPlanner`、`FieldEvidenceQuery`、字段契约模型已存在。
  - 执行状态：成功
- [x] Task 2A：全字段证据路径补第三方 sourceType
  - 核心目标：解除第三方来源只绑定 weaknesses 的限制，让正向字段也具备第三方准入。
  - 预期耗时：30 分钟
  - 依赖前置条件：`AnalysisDimensionMappingCatalog` 与 `CoverageContractResolver` 已集中生成字段契约。
  - 执行状态：成功
- [x] Task 2：`FieldQueryComposition` 组合生成规则
  - 核心目标：用 queryIntent、expectedSignals、sourceTypes、field dimension 组合生成多视角 query。
  - 预期耗时：45 分钟
  - 依赖前置条件：Task 1 红灯测试确认旧 planner 的兜底重复问题。
  - 执行状态：成功
- [x] Task 3：重写 `FieldEvidenceQueryPlanner` 删除 if-field
  - 核心目标：移除 coreFeatures/pricing 的硬编码分支，改为消费组合器输出。
  - 预期耗时：30 分钟
  - 依赖前置条件：Task 2 组合器可产出互补 query。
  - 执行状态：成功
- [x] Task 4：`IncludeDomainPlanner` 自动域名决策
  - 核心目标：官方类 query 主动写入官方域名锚点，第三方类 query 返回空域名约束。
  - 预期耗时：20 分钟
  - 依赖前置条件：planner 输出携带 sourceType。
  - 执行状态：成功
- [x] Task 5：`ContentUsabilityScorer` 可用性分与可信度解耦
  - 核心目标：官方壳页低可用性、高质量第三方正文高可用性，并输出 sourceTier。
  - 预期耗时：45 分钟
  - 依赖前置条件：现有 `EvidenceQualityGate` 已有质量门禁入口。
  - 执行状态：成功
- [x] Task 6：系统测试与 06 回归
  - 核心目标：验证 07 不破坏 06 字段优先执行骨架，并覆盖天花板验收口径。
  - 预期耗时：30 分钟
  - 依赖前置条件：Task 1-5 实现完成。
  - 执行状态：成功
- [x] Task 7：审计、进度文档与采集链路封板声明
  - 核心目标：记录执行结果、验证命令和后续边界。
  - 预期耗时：20 分钟
  - 依赖前置条件：Task 6 回归通过。
  - 执行状态：成功

## 本次变更摘要

- 新增 `FieldQueryComposition`：通过字段自然语言、queryIntent、expectedSignals、sourceType 组合生成互补 query；新增字段只需扩展信号或字段词表，不需要改 planner 分支。
- 新增 `IncludeDomainPlanner`：官方/DOCS/PRICING 类 query 写入官方域名作为优先锚点，REVIEW/NEWS/OPEN_WEB 返回空列表，表示不限制域名。
- 重写 `FieldEvidenceQueryPlanner`：删除 `coreFeatures/pricing` if-field 特例和 `site:` 被动回填逻辑，统一消费组合器与域名规划器。
- 更新 `AnalysisDimensionMappingCatalog` 与 `CoverageContractResolver`：正向字段也声明第三方补充来源，官方路径仍排在前且保持必填，第三方路径作为非阻断补充。
- 新增 `ContentUsabilityScorer`、`CollectedPageView`、`ContentUsabilityScore`：正文可用性与来源可信度解耦，输出 `sourceTier`。
- 更新 `EvidenceQualityGate` / `EvidenceQualityVerdict`：复用正文可用性 scorer，并在质量结论中携带 `sourceTier`。
- 新增系统验收测试 `Task66GenerativeQueryPlannerSystemTest`，覆盖正向字段多 query、includeDomains 自动决策、第三方开放域名和壳页低分。

## 验证记录

- 通过：`mvn -pl backend "-Dtest=FieldEvidenceQueryPlannerGenerativeTest,AnalysisDimensionMappingCatalogTest,IncludeDomainPlannerTest" test`
  - 结果：Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
- 通过：`mvn -pl backend "-Dtest=FieldEvidenceQueryPlannerTest,CoverageContractResolverTest,DimensionEvidencePlanFactoryTest" test`
  - 结果：Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
- 通过：`mvn -pl backend "-Dtest=ContentUsabilityScorerTest,EvidenceQualityGateTest,CollectorAgentEvidenceQualityGateTest" test`
  - 结果：Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
- 通过：`mvn -pl backend "-Dtest=Task66GenerativeQueryPlannerSystemTest" test`
  - 结果：Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
- 通过：`mvn -pl backend "-Dtest=FieldEvidenceQueryPlannerTest,FieldEvidenceQueryPlannerGenerativeTest,IncludeDomainPlannerTest,ContentUsabilityScorerTest,DimensionEvidencePlanFactoryTest,FieldEvidenceCoverageAggregatorTest,TavilyFastLaneProviderTest,SearchExecutionCoordinatorFieldEvidenceTest,SearchExecutionCoordinatorPublicRecoveryTest,EvidenceQualityGateTest,CollectorAgentEvidenceQualityGateTest,CollectorAgentFieldEvidenceLoopTest,Task66FieldFirstEvidenceLoopSystemTest,Task66GenerativeQueryPlannerSystemTest,Task66CoverageContractRegressionTest" test`
  - 结果：Tests run: 35, Failures: 0, Errors: 0, Skipped: 0
  - 备注：Maven settings 仍有既存 `mirrors` 标签 warning；XML 安全解析测试仍会输出 DOCTYPE fatal log，但测试结果为通过。

## 采集链路封板声明

Tavily 目录的目标——让系统跑出 PoC 证明的搜索采集天花板——至此达成：
系统能自动生成互补 query、自动决策 include_domains、按正文可用性独立评分。

采集链路到此停止深修。后续不再新增 Tavily 子阶段修补 query/采集质量。
- 跨字段 evidence graph 等天花板之上的能力，归入 4.x 之后，不在 Tavily 目录扩展。
- 下一步按 `docs/specs/project-evolution-roadmap.md`：把“字段→query→执行→评分”runtime 缺失沉淀为采集链路引擎级诊断，转向补其他空白链路诊断，凑 4.x 收敛点。

## 本次停止总结

- 这次做了什么：完成 Task66-07 生成式字段 query planner，补全全字段第三方准入、includeDomains 主动决策、正文可用性独立评分和系统/回归测试。
- 接下来要做什么：转入 `docs/specs/project-evolution-roadmap.md` 指向的 4.x runtime contract / 链路诊断收敛，不再开 Tavily 08 继续深修采集。
- 还剩什么没做：未做跨字段 evidence graph；未把 `allowOfficialOnly` 技术债重构为连续 source weight；未承诺真实公网每个字段必然命中，这仍由 `NO_PUBLIC_EVIDENCE_AFTER_SEARCH` 与人工终审兜底。
