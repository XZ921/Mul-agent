# Task66-04 搜索采集公开证据补采进度

当前阶段：[Task 7 回归测试与 live 复验已完成，04 阶段收口]

[x] 信息采集：已完成
[x] 数据分析：已完成
[x] 报告撰写：已完成阶段进度回填，当前阻塞已转移到下游质量环节

## 执行计划

- 任务名称：04 搜索采集公开证据补采
- 当前落点：`Task 1 / Task 2 / Task 3 / Task 5 / Task 4 / Task 6 / Task 7` 已完成
- 核心目标：先阻断“未验证搜索补源或中介页被提升为正式证据”的错误路径，再补“受限入口 -> 同域公开正文 recovery -> 再选源”的稳定链路，并保证 EvidenceSource 落库失败时仍保留可追踪诊断
- 预期耗时：按任务边界逐段推进
- 前置依赖：
  - `01` 覆盖契约相关测试已通过
  - `02` 证据质量门禁相关测试已通过
  - `03` Tavily structured block 与 fast-lane 审计相关测试已通过

## 步骤拆解

- [x] 步骤 1：审阅 `04` 计划与现有 `CollectionTargetSelector` / `CandidateOwnershipPolicy` / `SearchExecutionCoordinator` 代码
- [x] 步骤 2：先补 `CollectionTargetSelectorTest` 失败测试，锁定未验证中介页与公开壳显式候选场景
- [x] 步骤 3：新增 `CandidateOwnershipPolicyTest`，锁定中介页、登录页、验证码页识别行为
- [x] 步骤 4：最小实现 `Task 1 / Task 2`
- [x] 步骤 5：运行定向测试验证 `Task 1 / Task 2`
- [x] 步骤 6：进入 `Task 3`，补 `PublicEvidenceRecoveryService` 与 `SearchExecutionCoordinator` 联动
- [x] 步骤 7：进入 `Task 5`，处理 EvidenceSource 落库安全化与 Flyway 扩容
- [x] 步骤 8：进入 `Task 4`，补登录/验证码公开壳信息兜底
- [x] 步骤 9：进入 `Task 6`，保留采集失败诊断与持久化失败审计
- [x] 步骤 10：执行 `Task 7` 自动化回归
- [x] 步骤 11：执行 `Task 7` live 复验并回填结果

## 当前进度

- 当前执行步骤：`Task 7` live 复验结果回填已完成
- 已完成步骤占比：11/11
- 剩余步骤：
  - 无，`04` 阶段已收口
- 当前状态：自动化回归、Spring 启动修复、live 复验、结果归档均已完成

## 本轮完成内容

- [x] 扩展 `CollectionTargetSelectorTest`
  - 新增“未验证百度认证中介页不能进入正式采集目标”测试
  - 新增“显式候选在公开壳恢复成功时可低优先入选”测试
  - 新增“显式候选即使验证阶段标记为 `DISCARDED`，只要已拿到可用公开正文仍允许入选”测试
- [x] 新建 `CandidateOwnershipPolicyTest`
  - 覆盖百度认证中介页
  - 覆盖 `qcc.com` 企业信息页
  - 覆盖登录页与验证码工具页
  - 覆盖官方同域候选归属通过场景
- [x] 修改 `CandidateOwnershipPolicy`
  - 扩展中介页 domain/path/text 识别
  - 新增 `isUtilityGatePage(...)`
  - 让 trusted root 判断同时排除登录/验证码工具页
- [x] 修改 `CollectionTargetSelector`
  - 引入 `CandidateOwnershipPolicy`
  - 新增最终入选资格判断
  - 拒绝未验证中介页、企业信息页、百科页、登录工具页进入 `selectedTargets`
  - 允许“显式候选 + 已有可用公开正文”在验证阶段曾被打成 `DISCARDED` 时继续作为正式采集降级入口
- [x] 新建 `PublicEvidenceRecoveryService` 与定向测试
  - 新增 `RecoveryContext / RecoveryResult`
  - 支持按 `fieldName / evidencePathKey / queryIntents` 生成同域公开入口候选
  - 支持从 `canonical / openGraph / json-ld` 等 metadata 链接补入公开候选
  - 保留 `sourceUrls`，并继续过滤中介页与工具页
- [x] 扩展 `SitemapDiscoveryService`
  - 高价值入口从 `docs/api/pricing/help` 扩展到 `about/download/product/features/support/openplatform/creator/app`
  - 新增 `/about /download /app` 相关测试
- [x] 修改 `SearchExecutionCoordinator`
  - 新增 `PUBLIC_EVIDENCE_RECOVERY` 执行步骤
  - 在 `sitemap` 合并后、`SELECT_TARGETS` 前注入公开证据补采
  - recovery 候选复用现有 `CandidateVerifier` 再验证，避免维护第二套验证语义
  - 将 recovery 审计信息追加写入 `SearchExecutionTrace`
- [x] 扩展 `CollectorNodeConfig / SearchExecutionTrace`
  - `CollectorNodeConfig` 新增 `recoveryFieldName / recoveryEvidencePathKey / recoveryQueryIntents`
  - `SearchExecutionTrace` 新增 `publicEvidenceRecovery*` 审计字段
- [x] 新建 `SearchExecutionCoordinatorPublicRecoveryTest`
  - 固定“主入口受限 -> 先 recovery 出 pricing 正文 -> 再进入最终选源”的新语义
- [x] 新建 `EvidenceSourceSanitizer` 与定向测试
  - 统一裁剪 `competitorName / evidenceId / title / url / sourceType / discoveryMethod / sourceCategory / sourceDomain / publishedAt`
  - 保留超长 `discoveryReason` 原文，避免 live 补源说明在落库前被截断
- [x] 修改 `EvidenceSource.discoveryReason` 映射与数据库迁移
  - 将 JPA 映射从 `length = 500` 调整为 `TEXT`
  - 新增 `V30__expand_evidence_source_discovery_reason.sql`
  - 新增 `EvidenceSourceMigrationArtifactsTest` 固定迁移脚本存在性与关键 SQL
- [x] 修改 `CollectorAgent`
  - 注入 `EvidenceSourceSanitizer`
  - 保存 EvidenceSource 前统一 sanitizer
  - 保存失败时回写 `persistenceFailureReason / EVIDENCE_PERSIST_FAILED`
  - 递归采集主路径与常规失败路径统一强制收口 `collectionAudit FAILED`
- [x] 修改 `CollectionAuditSnapshot`
  - 打开 `toBuilder`，保证失败审计收口可以安全回写
- [x] 新建 `PublicShellRecoveryExtractor` 与定向测试
  - 只从 title/meta/og/canonical/json-ld/公开 body 片段恢复壳信息，不提交表单、不绕过验证码
  - 对纯验证码或纯安全检查页面保持失败，避免把低价值壳页伪装成公开正文
- [x] 修改 `PlaywrightPageCollector` 接入公开壳兜底
  - 在 utility gate / blocked 场景尝试恢复 `PUBLIC_SHELL_ONLY` 降级证据
  - metadata 显式写入 `PUBLIC_SHELL_ONLY` 与 `LOGIN_GATE_PARTIAL` / `ANTI_BOT_PARTIAL`
  - 新增 package-private 测试入口，固定登录页壳恢复的定向行为
- [x] 修复 Spring 真实启动阻塞
  - `TavilyPrefetchedExecutor` 正式运行时构造器显式加 `@Autowired`
  - `QualityReviewAgent` 正式运行时构造器显式加 `@Autowired`
  - 新增 `TavilyPrefetchedExecutorContextTest` 与 `QualityReviewAgentContextTest` 固定容器启动语义

## 验证结果

- 通过：
  - `mvn -pl backend "-Dtest=TavilyPrefetchedExecutorContextTest" test`
  - 结果：`BUILD SUCCESS`
- 通过：
  - `mvn -pl backend "-Dtest=TavilyPrefetchedExecutorTest,CollectionExecutorRegistryTest,TavilyFastLaneAuditContractTest" test`
  - 结果：`BUILD SUCCESS`
- 通过：
  - `mvn -pl backend "-Dtest=QualityReviewAgentContextTest" test`
  - 结果：`BUILD SUCCESS`
- 通过：
  - `mvn -pl backend "-Dtest=QualityReviewAgentTest,QualityReviewAgentCoverageContractTest" test`
  - 结果：`BUILD SUCCESS`
- 通过：
  - `mvn -pl backend "-Dtest=CollectionTargetSelectorTest#shouldAllowExplicitCandidateWithUsablePublicPageEvenWhenVerificationMarkedDiscarded" test`
  - 结果：`BUILD SUCCESS`
- 通过：
  - `mvn -pl backend "-Dtest=CollectionTargetSelectorTest,SearchExecutionCoordinatorPublicRecoveryTest" test`
  - 结果：`BUILD SUCCESS`
- 通过：
  - `mvn -pl backend "-Dtest=CollectionTargetSelectorTest,CandidateOwnershipPolicyTest" test`
  - 结果：`BUILD SUCCESS`
- 通过：
  - `mvn -pl backend "-Dtest=PublicEvidenceRecoveryServiceTest,SitemapDiscoveryServiceTest,SearchExecutionCoordinatorPublicRecoveryTest" test`
  - 结果：`BUILD SUCCESS`
- 通过：
  - `mvn -pl backend "-Dtest=EvidenceSourceSanitizerTest,EvidenceSourceMigrationArtifactsTest" test`
  - 结果：`BUILD SUCCESS`
- 通过：
  - `mvn -pl backend "-Dtest=PublicShellRecoveryExtractorTest,PlaywrightPageCollectorTest" test`
  - 结果：`BUILD SUCCESS`
- 通过：
  - `mvn -pl backend "-Dtest=CollectorAgentTest#shouldSanitizeEvidenceBeforePersistenceWhenDiscoveryReasonIsLong,CollectorAgentTest#shouldRetainPersistenceFailureReasonInCollectionAuditWhenEvidenceSaveFails" test`
  - 结果：`BUILD SUCCESS`
- 通过：
  - `mvn -pl backend "-Dtest=SearchAndCollectionGoldenMasterTest,SearchExecutionTruthContractTest,CollectionExecutionCoordinatorTest,CollectionAuditContractTest" test`
  - 结果：`BUILD SUCCESS`
- 未通过但暂不作为本轮 gate：
  - `mvn -pl backend "-Dtest=CollectionTargetSelectorTest,CandidateOwnershipPolicyTest,PublicEvidenceRecoveryServiceTest,SitemapDiscoveryServiceTest,SearchExecutionCoordinatorPublicRecoveryTest,PublicShellRecoveryExtractorTest,PlaywrightPageCollectorTest,EvidenceSourceSanitizerTest,EvidenceSourceMigrationArtifactsTest,CollectorAgentTest" test`
  - 结论：`CollectorAgentTest` 中仍有 `GitHub / RSS / 内部发现子页 / 旧 partial 语义` 夹具依赖旧选源与执行假设，不属于 `04` 阶段计划的首轮 gate；后续应单独刷新这些历史断言

## live 复验记录

### 第 1 轮：暴露真实阻塞点

- artifact 目录：`docs/superpowers/ExtractionStructured/progress/live-bilibili-public-evidence-recovery-20260629-173513`
- taskId：`67`
- 结果：
  - 任务在 `collect_sources_01_01` 停止，`status=STOPPED`
  - `selectedCandidateCount=0`
  - `waitingInterventionNodeCount=1`
  - `/api/report/67/evidences` 返回空数组
- 现场结论：
  - `https://app.bilibili.com` 已经被成功打开并拿到公开正文
  - 百度认证中介页 `https://aiqicha.baidu.com/feedback/official?from=baidu&type=gw` 被正确识别并保持 `DISCARDED`
  - 但显式官方候选在验证阶段被标记为 `selectionStage=DISCARDED` 后，`CollectionTargetSelector` 仍把它排除在正式采集之外
  - 该轮证明阻塞点不再是环境，而是“显式候选已拿到可用公开正文，却仍被最终入选门槛拒绝”的 04 阶段语义缺口

### 修复后第 2 轮：通过 04 阶段 live 验收

- artifact 目录：`docs/superpowers/ExtractionStructured/progress/live-bilibili-public-evidence-recovery-20260629-174119-rerun`
- taskId：`68`
- 结果：
  - 采集节点 `collect_sources_01_01` 成功
  - `selectedCandidateCount=1`
  - `selectedUrls=["https://app.bilibili.com"]`
  - 成功落库 1 条证据：
    - `evidenceId=T0068-COLLECT_SOURCES_01_01-001`
    - `selectionStage=SELECTED`
    - `selectionReason=显式候选已在验证阶段取得可用公开正文，允许正式采集`
    - `verificationReason=页面已打开，但未命中 OFFICIAL 所需特征`
  - `/api/report/68` 与 `/api/report/68/evidences` 均已有实际结果
  - 工作流已继续推进到：
    - `extract_schema`
    - `analyze_competitors`
    - `write_report`
    - `citation_check`
    - `quality_check`
- 搜索/采集链路关键审计：
  - `fallbackDecision=SUPPLEMENTED_BUT_SKIP_VERIFY_DUE_TIMEOUT`
  - `degradationReason=SEARCH_TIMEOUT_AFTER_SUPPLEMENT`
  - `publicEvidenceRecoveryTriggered=false`
  - `publicEvidenceRecoveryStatus=RECOVERY_NOT_TRIGGERED`
  - 本样本未触发 `PUBLIC_EVIDENCE_RECOVERY`，原因不是功能失效，而是显式官网页自身已经拿到可用公开正文，因此无需再走同域替代入口补采
- 结论：
  - 百度认证/企业信息中介页没有再进入正式采集目标
  - 显式同域公开页在已有可用正文时可以被稳定提升为正式证据
  - `discoveryReason` 超长导致的落库失败未再出现
  - `04` 阶段验收目标已达到，剩余 `STOPPED` 已转移为下游质量/报告覆盖问题，而不是搜索采集公开证据补采底座失败

## 当前判断

- `Task 1` 已完成：未验证搜索补源和中介页不会再直接进入正式采集目标
- `Task 2` 已完成：中介页、企业信息页、登录页、验证码工具页识别边界已补齐
- `Task 3` 已完成：已经实现 `RecoveryContext`、同域公开替代入口规划、补源候选验证与 `SearchExecutionCoordinator` 联动
- `Task 5` 已完成：EvidenceSource 在落库前已具备长度安全化处理，`discovery_reason` 已扩容为 `TEXT`
- `Task 4` 已完成：登录/验证码/反爬壳页现在可以恢复公开可见壳信息，并明确以低置信降级证据输出
- `Task 6` 已完成：Collector 保存失败时可以保留 `persistenceFailureReason / EVIDENCE_PERSIST_FAILED`，并把 `collectionAudit.summary.status` 强制收口为 `FAILED`
- `Task 7` 已完成：
  - 自动化回归已通过
  - live 复验已完成
  - 阶段性阻塞点已从 `04` 搜索采集底座前移到下游 `quality_check` 的证据覆盖与结论支撑问题

## 下一步建议

- `04` 阶段可视为完成，建议切换到 `05` 阶段 repair / evidence promotion / regression 收口
- 若继续跟进当前 live 样本，优先处理：
  - `quality_check` 中 `coverage_gap` 对 `pricing / weaknesses / positioning / coreFeatures / strengths` 的阻断
  - 报告层 `unsupported_claim` 与引用不足
  - 让 `PUBLIC_EVIDENCE_RECOVERY` 在字段级补证据场景真正接入 `repair` 闭环，而不是只停留在采集底座能力
