# Task 53 live 样本总结：哔哩哔哩下载中心

当前阶段：[采集层 live 复验完成，报告层未触达]

[x] 信息采集：已完成
[ ] 数据分析：未进入，原因是采集节点等待人工介入
[ ] 报告撰写：未进入，`/api/report/53` 返回报告不存在

## 前置清理

- 已删除未完成的 task 51：`DELETE /api/task/51` 返回 `200 Task deleted`。
- 删除后复查：`GET /api/task/51` 返回 `10001 TASK_NOT_FOUND`。

## 样本输入

- taskId：`53`
- taskName：`第二轮结构化抽取 live 验收 - 哔哩哔哩下载中心`
- subjectProduct：`视频社区与内容平台`
- competitorNames：`["哔哩哔哩"]`
- competitorUrls：`["https://app.bilibili.com"]`
- analysisDimensions：`["产品功能","目标用户","市场定位","证据完整性"]`
- sourceScope：`["官网"]`

## 运行结果

- 任务状态：`STOPPED`
- 停止节点：`collect_sources_01_01`
- 节点状态：`WAITING_INTERVENTION`
- 完成节点：`0/7`
- 报告接口：`/api/report/53` 返回 `50001 REPORT_NOT_FOUND`
- 证据接口：`/api/report/53/evidences` 返回空数组

## 关键信号

- 规划期候选 `https://app.bilibili.com` 仍未通过 OFFICIAL 验证，验证原因是 `页面已打开，但未命中 OFFICIAL 所需特征`。
- 补源阶段经由百度拿到 1 条候选，并在总超时后被选为正式采集目标：`https://aiqicha.baidu.com/feedback/official?from=baidu&type=gw`。
- 停止前最后一个可读快照显示：`selectedCandidateCount=1`，`verifiedCandidateCount=0`，`discardedCandidateCount=1`，`selectedUrls` 指向百度官网认证页。
- 最终失败发生在证据落库：`ERROR: value too long for type character varying(500)`，SQL 指向 `evidence_source.discovery_reason` 所在插入。
- 对照 `poll-12-nodes-53.json`，被选中的百度候选 `reasonLength=546`，超过 `EvidenceSource.discoveryReason` 的 `@Column(length = 500)` 限制。

## 与第二轮实施计划的关系

本次 B 站 live 测试没有和第二轮报告层计划冲突，但也没有给出报告层结论。它在报告生成之前被采集层/落库层拦住，所以不能用来判断：

- `/api/report/{id}` 是否还出现重复竞品 coverage。
- `summary / positioning / targetUsers` 是否仍被旧快照打成缺口。
- reviewer / report diagnosis 是否仍把字段级缺口收口成阻断。

## 下一步建议

- 先修或规避采集层阻断：超时后不应把未验证通过的百度认证/企业信息中介页提升为正式采集目标。
- 先修落库边界：`discoveryReason` 入库前截断到 500 字以内，或把数据库字段改成 `TEXT`，否则长搜索摘要会阻断采集成功落库。
- 修完上游后再用一个稳定官方页面重跑 live 复验，届时再观察报告层的 coverage 去重、字段缺口和 reviewer 阻断。
