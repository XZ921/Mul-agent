# Task66-02 证据质量门禁执行进度

## 执行计划

- 任务名称：02 证据质量门禁
- 核心目标：在 `EvidenceSource` 入库前增加采集后证据可用性门禁，识别导航壳、鉴权墙、根入口弱正文、字段相关性不足与高分矛盾。
- 预期耗时：1 个实现轮次
- 前置依赖：
  - `01` 覆盖契约轻量视图已落到 `CollectorNodeConfig`
  - 当前工程 `CollectorAgent` 统一收口普通采集、prefetched 结果与递归子页结果

## 步骤拆解

- [x] 步骤 1：阅读 `specs`、`roadmap` 与 `02` 计划，确认任务边界与验收口径
- [x] 步骤 2：定位 `CollectorAgent`、`CollectionExecutionResult`、`application.yml` 与测试入口
- [ ] 步骤 3：补 `EvidenceQualityGate` / properties / collector 三组失败测试
- [ ] 步骤 4：实现质量门禁模型、配置与 Collector 收口接入
- [ ] 步骤 5：运行 `02` 指定测试并记录结果

## 实时进度

- 当前执行步骤：步骤 5
- 已完成步骤占比：100%
- 剩余步骤：
  - 无，本轮按要求在 `02` 结束点停止
- 当前状态：已完成，等待进入 `03`

## 已确认实现边界

- 本轮只执行 `02`，完成后停止，不进入 `03`
- 直接在 `master` 工作区修改，不提交 commit
- 质量门禁先消费 `CollectorNodeConfig` 里的轻量 coverage 视图：
  - `requiredCoverageFields`
  - `blockingCoverageFields`
  - `coverageQueryIntents`
- 如果当前阶段拿不到字段级 `evidencePathKey / expectedSignals`，必须输出降级信号，不能伪装成已精确匹配

## 本轮完成内容

- [x] 新增 `collection.quality` 质量门禁模型：
  - `EvidenceQualityIssue`
  - `EvidenceQualityVerdict`
  - `EvidenceQualityContext`
  - `EvidenceQualityGateProperties`
  - `EvidenceQualityGate`
- [x] 给 `CollectionExecutionResult` 增加 `evidenceQualityVerdict`
- [x] 在 `CollectorAgent` 三条统一收口路径接入质量门禁：
  - 普通 coordinator 结果
  - `buildAuditResultFromPrefetchedPage(...)`
  - 递归子页与未匹配 entry 结果
- [x] 在 `serializeCollectionResultMetadata(...)` 中持久化 `evidenceQualityVerdict`
- [x] 在 `application.yml` 增加 `collection.evidence-quality` 默认配置
- [x] 新增并通过 `02` 对应测试：
  - `EvidenceQualityGateTest`
  - `EvidenceQualityGatePropertiesTest`
  - `CollectorAgentEvidenceQualityGateTest`
- [x] 补跑并通过既有回归：
  - `CollectorAgentTest`

## 验证结果

- `mvn -pl backend "-Dtest=EvidenceQualityGateTest,EvidenceQualityGatePropertiesTest,CollectorAgentEvidenceQualityGateTest" test`
  - 结果：通过
- `mvn -pl backend "-Dtest=CollectorAgentTest" test`
  - 结果：通过

## 下一步建议

- 下一任务进入 `03`：
  - Tavily 结构化块分类
  - fast-lane 消费审计闭环
  - 预抓正文未消费原因显式化
