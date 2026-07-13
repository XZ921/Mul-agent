# 2026-07-12 E2E 记录：include_domains 官方锚点复测

## 执行信息

- 运行目录：`E:\java_study\Mul-agnet\tmp\stage1-degraded-live-e2e-includedomains-20260712-144442`
- latest 指针：`tmp/stage1-degraded-live-e2e-includedomains-latest.txt`
- 后端端口：`9093`
- 监听 Java PID：`24516`
- 任务 ID：`106`
- 请求：Notion + Airtable，维度为产品概述、市场定位、目标用户、核心功能、价格策略；来源范围为官网、客户案例、产品文档、公开测评。

## include_domains 输入检查

- `collect_sources_01_01` Notion OFFICIAL：`includeDomains=[www.notion.so]`
- `collect_sources_01_02` Notion DOCS：`includeDomains=[www.notion.so]`
- `collect_sources_01_03` Notion REVIEW：`includeDomains=[]`
- `collect_sources_02_01` Airtable OFFICIAL：`includeDomains=[www.airtable.com]`
- `collect_sources_02_02` Airtable DOCS：`includeDomains=[www.airtable.com]`
- `collect_sources_02_03` Airtable REVIEW：`includeDomains=[]`

判断：本轮只对官方锚点收窄，未把 REVIEW/第三方路径锁到官网域名。

## 终态结论

- 任务状态：`SUCCESS`
- 当前阶段：`阶段1降级可交付，建议人工复核`
- 节点完成度：`14/14`
- 节点状态分布：`SUCCESS=8`，`SUCCESS_DEGRADED=6`
- 报告可查看：`canViewReport=true`
- 草稿报告可查看：`canViewDraftReport=true`
- qualityScore：`48`
- qualityPassed：`false`
- deliveryStatus：`NEEDS_EVIDENCE`
- readyForDelivery：`false`

结论：本轮 E2E 跑通完整 DAG，include_domains 没有导致全流程卡死；但结果仍是降级可交付，不是高质量可直接交付。

## 采集节点情况

- Notion OFFICIAL：`SUCCESS_DEGRADED`，`successCollected=0`，`HARD_DEADLINE_REACHED`
- Notion DOCS：`SUCCESS_DEGRADED`，`successCollected=0`，`HARD_DEADLINE_REACHED`
- Notion REVIEW：`SUCCESS_DEGRADED`，`successCollected=0`，`HARD_DEADLINE_REACHED`
- Airtable OFFICIAL：`SUCCESS_DEGRADED`，`successCollected=0`，`HARD_DEADLINE_REACHED`
- Airtable DOCS：`SUCCESS_DEGRADED`，`successCollected=4`，`HARD_DEADLINE_REACHED`
- Airtable REVIEW：`SUCCESS_DEGRADED`，`successCollected=5`，`HARD_DEADLINE_REACHED`

主要证据来自 Airtable DOCS/REVIEW；Notion 仍基本无成功采集。

## 暴露问题

1. Notion 三个采集分支仍然没有有效文档进入下游，说明 include_domains 不会恶化全流程，但也不能解决 Notion 反爬/采集耗时本质问题。
2. 所有采集节点仍以 `HARD_DEADLINE_REACHED` 降级，deadline 仍是本轮主要降级原因。
3. 报告最终 `SUCCESS`，但 `deliverySummary.readyForDelivery=false`，质量层认为仍需证据补充。
4. 质量问题集中在结构化字段覆盖：市场定位、目标用户、功能对比、定价策略、优势判断、短板与风险。
5. 证据域名明显偏 Airtable：`pyairtable.readthedocs.io`、`apitracker.io`、`airtable.com`、`checkthat.ai`、`copy.ai`、`zite.com`、`smartsuite.com` 等；Notion 侧证据不足。

## 保存文件

- `request.json` / `preview.json` / `create.json` / `execute.json`
- `nodes-before-execute.json`
- `analysis-include-domains-before-execute.json`
- `poll-00` 至 `poll-10`
- `final-task.json` / `final-nodes.json` / `final-report.json` / `final-evidences.json` / `final-replay.json` / `final-agent-logs.json`
- `analysis-task-summary.json`
- `analysis-node-statuses.json`
- `analysis-collector-summary.json`
- `analysis-quality-summary.json`
- `analysis-evidence-domains.json`
