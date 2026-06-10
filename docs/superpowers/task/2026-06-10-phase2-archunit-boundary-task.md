# Phase 2 ArchUnit Boundary Task

## 核心目标

基于当前真实包映射建立可执行的模块边界测试，让后续 phase3 - phase5 的重构都在同一套规则入口下推进。

## 预期耗时

- `0.5 - 1` 人天

## 前置依赖

- `phase1-agent-runtime-baseline-task` 已完成

## 进度持久化文件

- `docs/superpowers/task/2026-06-10-phase2-archunit-boundary-progress.md`

## 完成定义

- `BackendModuleDependencyTest` 仍是唯一规则入口
- `ArchitecturePackageMapping`、`ArchitectureWhitelist`、`ArchitectureWhitelistTest` 只作为支撑资产存在
- 第一批 task / collection / report / conversation 边界规则已落到 `BackendModuleDependencyTest`
- 白名单变成具名台账，而不是整包豁免
- `docs/superpowers/task/2026-06-10-architecture-whitelist-ledger.md` 已创建并与代码一致

## 文件边界

### Must Modify

- `backend/src/test/java/cn/bugstack/competitoragent/architecture/BackendModuleDependencyTest.java`
- `backend/src/test/java/cn/bugstack/competitoragent/architecture/ArchitecturePackageMapping.java`
- `backend/src/test/java/cn/bugstack/competitoragent/architecture/ArchitectureWhitelist.java`
- `backend/src/test/java/cn/bugstack/competitoragent/architecture/ArchitectureWhitelistTest.java`
- `docs/superpowers/task/2026-06-10-architecture-whitelist-ledger.md`

### May Modify

- 无

### Read For Context

- `docs/superpowers/plans/2026-06-09-backend-modular-monolith-refactor-roadmap.md`
- `docs/superpowers/specs/2026-06-09-backend-modular-monolith-refactor-design.md`
- `backend/src/main/java/cn/bugstack/competitoragent/task/...`
- `backend/src/main/java/cn/bugstack/competitoragent/search/...`
- `backend/src/main/java/cn/bugstack/competitoragent/report/...`
- `backend/src/main/java/cn/bugstack/competitoragent/conversation/...`

## 迁移策略

- `BackendModuleDependencyTest` 是唯一规则入口，所有真正生效的规则都必须最终落在这里。
- `ArchitecturePackageMapping` 只负责当前逻辑模块到真实 package 的映射常量。
- `ArchitectureWhitelist` 只负责具名豁免清单，不承载规则逻辑。
- `ArchitectureWhitelistTest` 只负责保证白名单记录完整，不新增第二套边界判断。
- 迁移顺序固定为：
  1. 先引入 package mapping
  2. 再迁移一条真实规则到 `BackendModuleDependencyTest`
  3. 再为该规则补白名单记录
  4. 最后更新 ledger

---

## Task 1: 固化包映射与白名单模型

### Task 核心目标

把逻辑模块边界先落成结构化测试资产，避免后续规则继续散落在字符串常量和人工记忆里。

### Task 预期耗时

- `2 - 3` 小时

### Task 前置依赖

- phase1 完成

### 执行步骤

- [ ] Step 1：创建 `ArchitecturePackageMapping`，定义 task / collection / knowledge / report / conversation / runtime baseline 的 package 映射。
- [ ] Step 2：创建 `ArchitectureWhitelist`，为每条豁免记录 `ruleName / className / reason / removeByPhase`。
- [ ] Step 3：创建 `ArchitectureWhitelistTest`，校验所有豁免项字段完整。

### 最小测试结构

```java
@Test
void exemptions_should_have_reason_and_remove_phase() {
    for (ArchitectureWhitelist.Exemption exemption : ArchitectureWhitelist.EXEMPTIONS) {
        assertFalse(exemption.ruleName().isBlank());
        assertFalse(exemption.className().isBlank());
        assertFalse(exemption.reason().isBlank());
        assertFalse(exemption.removeByPhase().isBlank());
    }
}
```

### 验证命令

```powershell
mvn -Dtest=ArchitectureWhitelistTest test
```

---

## Task 2: 把第一批规则迁入唯一入口

### Task 核心目标

用当前真实包映射把第一批跨模块禁令正式落到 `BackendModuleDependencyTest`。

### Task 预期耗时

- `2 - 4` 小时

### Task 前置依赖

- Task 1 完成

### 执行步骤

- [ ] Step 1：在 `BackendModuleDependencyTest` 引入 `ArchitecturePackageMapping` 常量和辅助方法。
- [ ] Step 2：新增第一批规则：
  - task 不得直接依赖 `EvidenceSourceRepository`
  - report 不得直接依赖 collection 实现包
  - conversation 不得直接依赖 workflow 内部实现
  - runtime baseline 不得依赖 repository / entity
- [ ] Step 3：对历史问题只使用具名白名单，不删除规则。

### 最小规则形状

```java
@ArchTest
static final ArchRule task_should_not_depend_on_evidence_repository_directly =
        noClasses()
                .that().resideInAnyPackage(packages(TASK_PACKAGES))
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("cn.bugstack.competitoragent.repository.EvidenceSourceRepository");
```

```java
@ArchTest
static final ArchRule report_should_not_depend_on_collection_implementations =
        noClasses()
                .that().resideInAnyPackage(packages(REPORT_PACKAGES))
                .should().dependOnClassesThat()
                .resideInAnyPackage(packages(COLLECTION_PACKAGES));
```

### 验证命令

```powershell
mvn -Dtest=BackendModuleDependencyTest test
```

### 预期信号

- 失败输出指向真实违规类
- 不允许新增“整包豁免”

---

## Task 3: 维护白名单台账

### Task 核心目标

把白名单回收点绑定到后续 phase，避免“先豁免、后遗忘”。

### Task 预期耗时

- `1 - 2` 小时

### Task 前置依赖

- Task 2 完成

### 执行步骤

- [ ] Step 1：创建 `2026-06-10-architecture-whitelist-ledger.md`。
- [ ] Step 2：把每一条白名单记录同步写入 `Rule / Class / Reason / Remove By Phase / Owner`。
- [ ] Step 3：在文档中写明使用规则：新增白名单必须先改代码清单，再改 ledger。

### 台账最小形状

```markdown
| Rule | Class | Reason | Remove By Phase | Owner |
| --- | --- | --- | --- | --- |
| agent_classes_should_not_access_repositories | cn.bugstack.competitoragent.agent.BaseAgent | legacy runtime support 仍负责日志持久化 | phase3a-task-orchestration-task | A |
```

### 验证命令

```powershell
mvn -Dtest=BackendModuleDependencyTest,ArchitectureWhitelistTest test
```

---

## Task 4: 阶段收尾

### Task 核心目标

确认 phase2 建的是“规则基础设施”，不是第二套架构系统。

### Task 预期耗时

- `1` 小时

### Task 前置依赖

- Task 1 - Task 3 完成

### 执行步骤

- [ ] Step 1：运行 phase2 聚焦测试。
- [ ] Step 2：核对白名单是否存在整包豁免。
- [ ] Step 3：更新 progress 文档，写清剩余违规集中在哪些类。

### 验证命令

```powershell
mvn -Dtest=BackendModuleDependencyTest,ArchitectureWhitelistTest test
```

### 提交标准

- 只包含 ArchUnit 规则入口、包映射、白名单模型和白名单台账
- 不混入生产代码包搬迁
- PR 描述必须明确写出“`BackendModuleDependencyTest` 仍是唯一规则入口”
