# 旧节点兼容退场阶段说明

## 目的

这份 future 文档用于回答一个当前 `specs` 里尚未被正式挂阶段的问题：

1. 为什么 `搜索与采集` 仍要保留旧节点 `nodeConfig / sourceCandidates / checkpoint` 兼容；
2. 这层兼容应当在什么阶段被收口或移除；
3. 进入该阶段前必须满足哪些前置条件。

---

## 现状核对结论

结合现有 `specs` 文档，当前没有一个已经命名好的正式 `Wave / Phase`，直接负责“移除旧节点兼容”。

现有文档只给出了两个相关但不等价的上位约束：

1. `搜索执行引擎` 仍处于 `🟡 已识别`，还没有升到 `✅ 独立子域`。
2. `恢复与回放` 底座仍处于 `🟡 已有基础能力但缺乏统一入口 / 契约`，跨重启 replay 仍是后续重点。

因此，当前阶段不适合直接删除旧节点兼容逻辑；否则会破坏既有 `rerun / resume / replay / checkpoint` 的恢复语义。

---

## 阶段归属

旧节点兼容退场不属于当前 `Wave 11 / Wave 12`，也不属于本轮 `family discovery convergence`。

它应单列为一个 future 阶段：

`FUTURE-SC-LEGACY-NODE-RETIREMENT`

该阶段位于以下两个条件都成立之后：

1. `docs/superpowers/specs/2026-06-11-business-landscape-and-optimization-roadmap-design.md`
   中 `5.2 平台底座基线看板` 的 `恢复与回放` 升级为 `✅ 统一入口 + 所有链路共同消费`。
2. 同一文档中 `4.2 执行引擎成熟度看板` 的 `搜索执行引擎` 升级为 `✅ 独立子域`。

换句话说，旧节点兼容退场不是“family-first discovery 收口”的一部分，而是“恢复/回放契约稳定 + 搜索执行引擎版本化稳定”之后的后续治理动作。

---

## 为什么现在不能删

当前仍需保留旧节点兼容，原因不是历史包袱本身，而是运行契约还依赖它：

1. `rerun / resume` 仍要求沿用既有 `planVersion` 与 `nodeConfig` 语义，而不是每次都重规划。
2. `searchAuditCheckpoint / collectionAuditCheckpoint` 仍会被回写到 `nodeConfig`，用于恢复现场。
3. 节点详情、审计、回放仍会消费历史 `sourceCandidates` 快照，而不只是当前代码实时推导结果。

因此，在恢复与回放基线还没有统一版本化入口前，直接移除旧兼容会造成：

1. 恢复任务与原计划事实不一致；
2. 老任务 rerun / resume 行为漂移；
3. 审计和回放对同一节点给出两套不同解释。

---

## 进入阶段的前置条件

只有满足以下前置条件，才允许启动 `FUTURE-SC-LEGACY-NODE-RETIREMENT`：

1. `CollectorNodeConfig`、`SearchAuditSnapshot`、`CollectionAuditSnapshot` 已具备稳定版本字段，例如 `discoveryContractVersion` 或等价版本锚点。
2. `resume` 的产品语义已经被明确定义为二选一：
   - 恢复原计划；
   - 按新规则重规划后继续。
3. 跨重启 replay 已经过正式收口，不再依赖“顺手复用旧 nodeConfig 才能解释现场”。
4. 活跃任务的历史 `planVersion` 已完成迁移，或有明确 TTL/淘汰窗口，允许放弃兼容更老版本。
5. 前端节点详情、回放视图、报告审计口径都已不再把旧版 `CONFIG root-only` 候选形态当作正式展示契约。

---

## 该阶段的正式目标

进入 `FUTURE-SC-LEGACY-NODE-RETIREMENT` 后，应完成以下收口：

1. `SearchExecutionCoordinator` 不再因为 `sourceCandidates` 非空就无条件复用历史候选。
2. 旧版 `CONFIG root-only` 候选只允许在单一迁移边界被识别和转换，不再散落在 runtime 分支里长期存在。
3. preview / runtime / replay / audit 都统一消费版本化后的 direct discovery 契约。
4. `TaskRuntimeCommandAppService` 的恢复逻辑不再依赖“把旧搜索现场原样塞回 nodeConfig”作为长期兼容手段。
5. 旧节点兼容逻辑从默认运行路径移除，只保留为一次性迁移脚本或受控迁移入口。

---

## 不在本阶段之前提前做的事

在上述前置条件完成之前，不应提前做以下动作：

1. 不应直接删除 `CONFIG` 旧候选兼容分支。
2. 不应假定所有历史 `nodeConfig` 都能被当前 planner 正确重建。
3. 不应把“恢复原计划”和“按新规则重建”混成同一个 `resume` 语义。
4. 不应在当前 `family discovery convergence` 计划里顺手做这件事。

---

## 与现有 specs 的关系

本 future 阶段是对现有 specs 的补充，不改动现有冻结结论：

1. `2026-06-17-search-and-collection-architecture-design.md` 继续定义当前正式架构边界；
2. `2026-06-11-business-landscape-and-optimization-roadmap-design.md` 继续作为阶段成熟度总看板；
3. 本文只负责把“旧节点兼容何时退场”明确挂到未来阶段，避免在当前阶段误删兼容逻辑。
