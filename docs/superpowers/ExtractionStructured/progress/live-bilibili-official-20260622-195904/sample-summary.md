# Task 52 live 样本总结：哔哩哔哩官网首页

当前阶段：[采集层 live 复验完成，报告层未触达]

[x] 信息采集：已完成
[ ] 数据分析：未进入，原因是采集节点等待人工介入
[ ] 报告撰写：未进入，`/api/report/52` 返回报告不存在

## 样本输入

- taskId：`52`
- taskName：`第二轮结构化抽取 live 验收 - 哔哩哔哩官网单入口`
- subjectProduct：`视频社区与内容平台`
- competitorNames：`["哔哩哔哩"]`
- competitorUrls：`["https://www.bilibili.com"]`
- analysisDimensions：`["产品功能","目标用户","市场定位","证据完整性"]`
- sourceScope：`["官网"]`

## 运行结果

- 任务状态：`STOPPED`
- 停止节点：`collect_sources_01_01`
- 节点状态：`WAITING_INTERVENTION`
- 完成节点：`0/7`
- 报告接口：`/api/report/52` 返回 `50001 REPORT_NOT_FOUND`

## 关键信号

- 首页候选 `https://bilibili.com` 被打开，但验证结果为 `页面已打开，但未命中 OFFICIAL 所需特征`。
- 浏览器补源经由百度提取到 `https://aiqicha.baidu.com/feedback/official?from=baidu&type=gw`，该候选被识别为搜索引擎认证/企业信息中介页并丢弃。
- `selectedCandidateCount=0`，`verifiedCandidateCount=0`，`discardedCandidateCount=2`，因此没有进入正式页面抓取。

## 判断

这个样本不能验证报告层的 coverage 去重、字段级缺口或 reviewer 阻断逻辑。它暴露的是采集入口问题：B 站首页在真实链路下容易返回验证码/低正文页面，导致 OFFICIAL 验证无法通过。

后续不建议继续把 `https://www.bilibili.com` 首页作为主验收样本；更适合换成稳定的官方静态页或先修采集选源策略。
