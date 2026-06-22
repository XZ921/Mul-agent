# 2026-06-22 reviewer JSON retry 与终审复验
## 当前阶段

当前阶段：P2 终审 reviewer 容错补口与 live 复验
- [x] 信息采集：已完成
- [x] 数据分析：已完成
- [x] 报告撰写：已完成
- [x] 质量校验：已完成本轮自动化回归与 live 终审复验

## 这次做了什么

1. 确认 `5432 / 16379 / 9876` 依赖已可用，重新启动 `9093` 上的最新 backend，继续在 master 工作区直接验证。
2. 重跑 task `50` 后定位到新的真实阻塞点：`quality_check_final` 因 reviewer 返回半截 JSON 进入 `WAITING_INTERVENTION`，错误为 `Unexpected end-of-input within/between Array entries`。
3. 先补回归测试 `shouldRetryWhenReviewerReturnsBrokenJsonBeforeSuccessfulQualityReview`，验证旧实现遇到坏 JSON 会直接失败。
4. 在 `QualityReviewAgent` 中补上 reviewer JSON 解析级重试：
   - 首次消费当前响应；
   - 若解析失败，则在 Agent 内部补发有限次数重试；
   - 重试 prompt 明确要求模型只返回完整闭合 JSON、压缩 `issues` 数组、不要输出 Markdown/额外解释。
5. 完成自动化验证：
   - `mvn -pl backend "-Dtest=QualityReviewAgentTest#shouldRetryWhenReviewerReturnsBrokenJsonBeforeSuccessfulQualityReview" test`
   - `mvn -pl backend "-Dtest=QualityReviewAgentTest,ReportWriterAgentTest,DagExecutorTest" test`
   - `mvn -pl backend test`
   - `git diff --check -- . ':(exclude)backend/logs/**'`
6. 使用最新代码仅对 `quality_check_final` 执行节点级 rerun，快照目录为 `docs/superpowers/ExtractionStructured/progress/live-task-50-final-review-rerun-20260622-112437`。
7. live 复验结果：
   - `quality_check_final`: `SUCCESS`
   - `failureCategory`: `DOWNSTREAM_CONSUMPTION_GAP`
   - task `50`: `FAILED`
   - task 错误：`质量闭环未达到通过条件，请检查评审结果`
   - `report/50` 与 `report/50/evidences` 均返回 `200`，`sourceUrls` 继续可追溯。

## 接下来要做什么

1. 继续下钻 `quality_check_final` 的真实问题项，确认当前 `qualityPassed=false`、`qualityScore=15` 的焦点是否仍然集中在建议/结论段落的证据回指不足。
2. 结合最新终审输出，决定下一步是继续收紧 writer/rewrite prompt，还是扩展 reviewer 对保守降级语句的识别边界。
3. 如果继续 live 验证，优先从 `rewrite_report` 或 `quality_check_final` 局部重跑，避免重复消耗已稳定通过的 extractor / analyzer 链路。

## 还剩什么没做

1. 还没有完成 P2 的 `evidenceCoverage` 新状态扩展：`LLM_REFUSED / EVIDENCE_NOT_COVERING / STRUCTURED_BLOCK_DIRECT`。
2. 还没有把 writer / reviewer 的最终证据闭环问题彻底收口到“task 50 终审通过”。
3. 还没有把“模型返回非法 JSON”抽成独立 workflow failure category；当前先通过 reviewer 内部重试规避。
4. 还没有提交代码；按协作约定保留给用户自行提交。
