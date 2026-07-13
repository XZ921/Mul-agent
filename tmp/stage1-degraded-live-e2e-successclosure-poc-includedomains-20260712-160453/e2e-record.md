# E2E 记录：task 109 include_domains 降级收口验证

## 基本信息

- 时间：2026-07-12 16:04:53 创建运行目录，16:12:44 任务终态
- 后端：9093，启动后健康检查通过
- 运行目录：`E:\java_study\Mul-agnet\tmp\stage1-degraded-live-e2e-successclosure-poc-includedomains-20260712-160453`
- latest 指针：`tmp/stage1-degraded-live-e2e-successclosure-poc-includedomains-latest.txt`
- taskId：109
- 用例：抖音 vs 哔哩哔哩，POC 样例，加 include_domains 验证降级收口

## 请求要点

- subjectProduct：短视频内容平台推荐算法与内容分发机制
- competitors：抖音、哔哩哔哩
- competitorUrls：`https://www.douyin.com`、`https://www.bilibili.com`
- dimensions：推荐算法/内容分发机制、创作者规则/流量规则、开放平台/API文档
- sourceScope：官网、产品文档、公开测评

## include_domains 配置

- OFFICIAL/DOCS：top-level `includeDomains` 已设置，nested plannedQueries 的 `includeDomains` 也已设置。
- REVIEW：不加 `includeDomains`，保持开放搜索；`preferredDomains` 仅保留第三方/官网偏好。
- 本轮曾出现一次 JSON UTF-8 BOM 导致节点配置解析失败，执行前已用无 BOM UTF-8 重写并重新 patch DB；正式执行前配置校验通过。

## 终态

- task status：`FAILED`
- completedNodes：14/14
- canViewReport：false
- report：`REPORT_NOT_FOUND`
- evidenceCount：0
- task error：`任务存在未恢复的失败节点，请检查节点详情`

## 节点状态

- 采集节点：6 个 `SUCCESS_DEGRADED`
- 下游节点：8 个 `SKIPPED`
- `extract_schema`：`SKIPPED`，错误为 `Dependencies not satisfied`
- 后续 `analyze_competitors`、`write_report`、`citation_check`、`quality_check`、`rewrite_report`、`citation_check_revision`、`quality_check_final` 都因上游跳过而跳过。

## 采集节点摘要

| 节点 | 竞品 | 类型 | 状态 | selectedTargets | totalCollected | successCollected | evidenceFragments | readyForQuorum | 降级原因 |
|---|---|---|---|---:|---:|---:|---:|---|---|
| collect_sources_01_01 | 抖音 | OFFICIAL | SUCCESS_DEGRADED | 1 | 0 | 0 | 0 | false | HARD_DEADLINE_REACHED |
| collect_sources_01_02 | 抖音 | DOCS | SUCCESS_DEGRADED | 4 | 0 | 0 | 0 | false | HARD_DEADLINE_REACHED |
| collect_sources_01_03 | 抖音 | REVIEW | SUCCESS_DEGRADED | 0 | 0 | 0 | 0 | false | HARD_DEADLINE_REACHED |
| collect_sources_02_01 | 哔哩哔哩 | OFFICIAL | SUCCESS_DEGRADED | 0 | 0 | 0 | 0 | false | HARD_DEADLINE_REACHED |
| collect_sources_02_02 | 哔哩哔哩 | DOCS | SUCCESS_DEGRADED | 7 | 0 | 0 | 0 | false | HARD_DEADLINE_REACHED |
| collect_sources_02_03 | 哔哩哔哩 | REVIEW | SUCCESS_DEGRADED | 4 | 4 | 0 | 0 | false | HARD_DEADLINE_REACHED |

## 根因判断

这轮失败不是上一轮的“最终质检未通过但是否允许降级收口”问题。新改的最终成功收口逻辑没有被触发，因为任务没有进入 `extract_schema` 之后的分析、撰写、质检阶段。

直接原因：6 个采集节点虽然以 `SUCCESS_DEGRADED` 收口，但 `readyForQuorum=false`，没有形成可供下游消费的 `evidenceFragments` / `successCollected`。`DagExecutor` 的 collector quorum 依赖门因此判定 `extract_schema` 依赖不满足。

更深层原因：本轮采集在 `HARD_DEADLINE_REACHED` 前主要只形成候选 URL 或空文档，没有形成可验证证据。尤其 OFFICIAL/DOCS 的 nested plannedQueries 也被 include_domains 收窄后，开放网页和第三方补源空间被压小，整体效果比上一轮 task 108 差。

## 结论

本轮 E2E 未跑通，不可宣称通过。它证明了一个新的边界：只给节点状态降级成功不够，必须至少让采集产出满足 collector quorum 的可用证据；否则后续链路不会启动。

低风险下一步建议：不要绕过 quorum，也不要把空证据放行到报告链路。下一轮应回到上一轮更接近成功的配置：OFFICIAL/DOCS 只保留 top-level include_domains 用作 Tavily 域名提示，nested OPEN_WEB/第三方补源查询保持开放，REVIEW 继续开放，以隔离“nested include_domains 收窄”是否就是本次证据塌陷的主要变量。
