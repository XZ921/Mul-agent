# Superpowers Docs Index

## 当前主线

当前主线是 `ExtractionStructured`，也就是 3.3 提取结构化任务。

后续进入 3.3 时，优先读取：

1. `docs/superpowers/ExtractionStructured/problem/ExtractionStructured.md`
2. `docs/superpowers/ExtractionStructured/plan/2026-06-20-extraction-structured-optimization-plan.md`
3. `docs/superpowers/search-and-collection/summary/2026-06-20-search-and-collection-3.2-summary.md`

## 已收口专题

3.2 搜索与采集已经按专题归档到：

`docs/superpowers/search-and-collection/`

其中当前入口是：

`docs/superpowers/search-and-collection/summary/2026-06-20-search-and-collection-3.2-summary.md`

除非需要追溯具体历史实现细节、验证记录或旧决策，不要默认读取：

`docs/superpowers/search-and-collection/task/`

## 专题归属

| 专题 | 权威入口 | 说明 |
| --- | --- | --- |
| 搜索与采集 | `docs/superpowers/search-and-collection/summary/2026-06-20-search-and-collection-3.2-summary.md` | 3.2 已完成阶段总结 |
| 提取结构化 | `docs/superpowers/ExtractionStructured/problem/ExtractionStructured.md` | 3.3 当前主线诊断 |
| 提取结构化计划 | `docs/superpowers/ExtractionStructured/plan/2026-06-20-extraction-structured-optimization-plan.md` | 3.3 当前执行入口 |
| 任务定义与编排契约 | `docs/superpowers/task-definition-and-orchestration-contract/` | 历史架构契约资料 |

## 后续专项

以下事项已登记为后续专项，不属于 3.3 前置条件：

- 跨任务缓存与隐私 / TTL / 失效设计。
- Playwright 并发上下文池设计。

## 阅读约束

新任务启动时先读本文件，再按“当前主线”中的入口文档继续。

不要把 `search-and-collection/task/` 下的历史迭代计划批量读入上下文；这些文档只用于追溯某一轮实现细节。
