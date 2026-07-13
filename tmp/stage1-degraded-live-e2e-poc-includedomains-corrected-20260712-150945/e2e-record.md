# POC 样例 include_domains E2E 记录

## 基本信息

- 任务 ID：108
- 任务名：阶段1 POC样例 E2E：抖音 与 哔哩哔哩 include_domains 校正版
- 请求样例：抖音 vs 哔哩哔哩，主题为短视频内容平台推荐算法与内容分发机制
- 维度：推荐算法/内容分发机制、创作者规则/流量规则、开放平台/API文档
- sourceScope：官网、产品文档、公开测评
- 运行目录：tmp/stage1-degraded-live-e2e-poc-includedomains-corrected-20260712-150945

## include_domains 设置

本轮采用 POC 风格的竞品隔离域名，而不是把所有域名塞进同一个 competitorUrls 池：

- 抖音 OFFICIAL/DOCS：www.douyin.com、douyin.com、open.douyin.com、docs.open-douyin.com、creator.douyin.com、oceanengine.com
- 哔哩哔哩 OFFICIAL/DOCS：www.bilibili.com、bilibili.com、member.bilibili.com、openhome.bilibili.com、open.bilibili.com、cm.bilibili.com、ad.bilibili.com
- REVIEW：保持开放，不设置 includeDomains

注意：当前正式 CreateTaskRequest 还不能直接表达“每个竞品一组多域名 include_domains”。本轮为复现 POC，先创建只含主 URL 的任务，再对 6 个采集节点配置做了执行前 patch。

## 终态

- Task status：FAILED
- completedNodes：14/14
- 失败原因：质量闭环未达到通过条件，请检查评审结果
- 报告可查看：true
- 报告质量分：56
- qualityPassed：false
- deliveryStatus：NEEDS_EVIDENCE

解释：本轮不是流程卡死，也不是 include_domains 把 E2E 限死；所有节点都执行到终态，但最终质量门没有放行。

## 采集结果

| 节点 | 状态 | totalCollected | successCollected | 主要降级 |
|---|---:|---:|---:|---|
| 抖音 - OFFICIAL | SUCCESS_DEGRADED | 0 | 0 | HARD_DEADLINE_REACHED |
| 抖音 - DOCS | SUCCESS_DEGRADED | 22 | 4 | HARD_DEADLINE_REACHED |
| 抖音 - REVIEW | SUCCESS_DEGRADED | 10 | 3 | HARD_DEADLINE_REACHED |
| 哔哩哔哩 - OFFICIAL | SUCCESS_DEGRADED | 0 | 0 | HARD_DEADLINE_REACHED |
| 哔哩哔哩 - DOCS | SUCCESS_DEGRADED | 14 | 5 | HARD_DEADLINE_REACHED |
| 哔哩哔哩 - REVIEW | SUCCESS_DEGRADED | 10 | 3 | HARD_DEADLINE_REACHED |

结论：POC include_domains 对 DOCS 节点明显有效；OFFICIAL 根页仍然弱，REVIEW 保持开放后也能补到外部评价来源。

## 命中的证据来源

共 15 条 evidence：

- 抖音 DOCS：developer.open-douyin.com x2、developer.aliyun.com x1、juhe.cn x1
- 抖音 REVIEW：sj.qq.com x1、apps.apple.com x1、blog.wyan.vip x1
- 哔哩哔哩 DOCS：qinshixixing.gitbooks.io x1、nemo2011.github.io x1、github.com x1、pypi.org x1、bilibili.com x1
- 哔哩哔哩 REVIEW：m.bilibili.com x1、play.google.com x1、capterra.com x1

## 暴露的问题

1. 正式请求模型缺少 per-competitor include_domains / domain hints。当前只靠 competitorUrls 很容易把多竞品域名混池，导致域名污染；本轮靠执行前 patch 绕开，不是产品级方案。
2. 顶层 includeDomains 已按 POC 生效，但 dimensionEvidencePlan.plannedQueries[*].includeDomains 里仍可能残留单官网域名。这是配置接缝，后续如果要产品化 include_domains，需要统一生成与传递。
3. OFFICIAL 根页两边仍为 0 成功采集。include_domains 能扩大官方/文档域名范围，但不能保证首页、根域或反爬页面可采。
4. 证据集中在开放平台/API 与公开评价，推荐算法/内容分发、创作者规则/流量规则、定价策略、优势/短板字段覆盖不足。
5. 最终质量门对 evidence traceability、actionability、structuredBlocks 和字段覆盖仍判为不可交付，因此任务终态 FAILED。

## 本轮判断

正确加 include_domains 后，E2E 没有被限制死，DOCS 证据显著改善；但它不能单独让最终质量闭环通过。下一步如果要从这个阶段解套，重点不是继续死磕官网根页，而是把 POC 的 per-competitor include_domains 正式纳入请求/规划契约，并让报告维度与可获得证据对齐：API/文档证据可稳定交付，算法/规则类结论要允许标注公开证据不足，避免质量门把阶段1首报卡死。
