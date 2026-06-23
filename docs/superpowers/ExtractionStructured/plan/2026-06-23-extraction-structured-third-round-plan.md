# 3.3 提取结构化第三轮执行计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在第二轮已经收口 `DOWNSTREAM_CONSUMPTION_GAP`、report coverage 细状态和任务级快照去重的基础上，继续把 `extract_schema` 的正式输入边界推进到“Provider 内部来源端口化 + extractor 专用输入投影拆分 + shared projection 可诊断化”，让后续 replay / cache / 正式端口都只能通过 `ExtractorInputProvider` 进入 extractor。

**Architecture:** 本轮不回头重做 P0/P1 主停点，也不新增 workflow delivery 节点。第三轮只处理第二轮后真正悬空的三件事：第一，把 `RepositoryExtractorInputProvider` 内部“直接读 repository + 直接复用 DownstreamEvidenceView”的实现拆成 `ExtractorEvidenceSourcePort -> ExtractorEvidenceInput -> ExtractorInputPackage`；第二，让 `SchemaExtractorAgent` 只消费 extractor 内部输入投影，而 `DownstreamEvidenceView` 继续只承担下游轻量追溯契约；第三，把 `inputSource / auditRefs / skippedEvidence` 继续写入 shared projection 和回放可见面，为下一阶段 replay / cache 接入留出稳定接缝。

**Tech Stack:** Java 17, Spring Boot, Jackson, Lombok, JUnit 5, Mockito, Maven, PowerShell

---

当前阶段：第三轮实施计划已成稿，待执行

- [x] 信息采集：已完成
- [x] 现状分析：已完成
- [ ] 数据源治理：待执行
- [ ] 输入投影拆分：待执行
- [ ] 回归验证：待执行
- [ ] 文档回链：待执行

## 0. 第二轮衔接基线

第三轮直接继承以下事实，不再重复建设：

- `SchemaExtractorAgent` 已经通过 `ExtractorInputProvider` 取输入，且 P0 的 Prompt 分层、结构块入口、0 字段语义重试已经落地。
- `RepositoryExtractorInputProvider` 仍然是 repository-backed 第一版适配器，内部同时负责读库、证据排序、预算控制和 `DownstreamEvidenceView` 组装。
- `DownstreamEvidenceView` 当前同时承担“extractor 输入载体”和“analyzer / reviewer / report 轻量追溯视图”两种职责，这与架构规格 `5.1.1`、`5.4` 的长期边界仍不一致。
- `ExtractSharedOutputSanitizer / ExtractSharedProjection / SharedNodeOutputProjectorContractTest` 已经证明 extract shared output 可以瘦身，但 `extractorInput` 里的输入对象仍沿用 `DownstreamEvidenceView` 语义，后续 replay / cache 仍缺正式输入治理接缝。
- `TaskKnowledgeSnapshotResolver` 已经在 analyzer / reviewer / report 读取侧挡住旧 `TASK` 快照污染，但这属于下游读取保护，不等于 extractor 输入层已经正式端口化。

第三轮明确不做：

- 不新增或重排 `ExecutionPlanDefinitionBuilder` 的 workflow 节点；
- 不扩搜索与采集 3.2、不改 Playwright / RSS / discovery；
- 不把 Provider 一步迁移到真正的外部端口或跨重启正文缓存；
- 不改前端页面和报告 UI；
- 不一次性重构 `CompetitorKnowledge` 领域模型或 memory writeback。

## 1. 文件结构

本轮实施前先冻结文件职责，避免再次把输入语义散回 Agent 内部：

- Create: `backend/src/main/java/cn/bugstack/competitoragent/extractor/input/ExtractorEvidenceInput.java`
  - extractor 内部专用输入投影，允许携带正文与结构化 payload。
- Create: `backend/src/main/java/cn/bugstack/competitoragent/extractor/input/ExtractorEvidenceInputAssembler.java`
  - 负责把 `EvidenceSource + pageMetadata` 归一成 `ExtractorEvidenceInput`。
- Create: `backend/src/main/java/cn/bugstack/competitoragent/extractor/input/ExtractorEvidenceSourcePort.java`
  - Provider 内部正式来源端口。
- Create: `backend/src/main/java/cn/bugstack/competitoragent/extractor/input/RepositoryExtractorEvidenceSourcePort.java`
  - repository-backed 第一版来源端口适配器。
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/extractor/input/ExtractorCompetitorInput.java`
  - 证据列表类型切换为 `List<ExtractorEvidenceInput>`，但该切换必须与 `SchemaExtractorAgent / ExtractSharedOutputSanitizer` 同批原子完成。
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/extractor/input/ExtractorInputPackage.java`
  - 增加 `inputSource` 与 `auditRefs`，显式记录本轮输入来源和审计接缝。
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/extractor/input/RepositoryExtractorInputProvider.java`
  - 改为只承担筛选、排序、预算与组包，不再直接读 repository 和直接装下游视图。
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/agent/extractor/SchemaExtractorAgent.java`
  - Prompt 构建、`EvidenceSource` 回填和下游轻量视图输出全部切到 `ExtractorEvidenceInput`。
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/extractor/ExtractSharedOutputSanitizer.java`
  - 在 shared projection 中保留 `ExtractorEvidenceInput` 的 trace-only 版本，继续剔除正文和结构化原文。
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/extractor/ExtractSharedProjection.java`
  - 让 `extractorInput.inputSource / auditRefs / skippedEvidence` 通过稳定投影进入 replay / cache。
- Test: `backend/src/test/java/cn/bugstack/competitoragent/extractor/input/RepositoryExtractorInputProviderTest.java`
  - Provider 输入投影、审计引用和预算行为回归。
- Test: `backend/src/test/java/cn/bugstack/competitoragent/agent/extractor/SchemaExtractorAgentTest.java`
  - extractor 改为消费 `ExtractorEvidenceInput` 后的 Prompt 和输出契约回归。
- Test: `backend/src/test/java/cn/bugstack/competitoragent/task/SharedNodeOutputProjectorContractTest.java`
  - extract shared projection 要继续裁剪正文，但保留 `inputSource / auditRefs`。
- Test: `backend/src/test/java/cn/bugstack/competitoragent/task/TaskSnapshotCacheServiceTest.java`
  - Redis runtime cache 仍能读回新的 extractor shared envelope。
- Create: `docs/superpowers/ExtractionStructured/progress/2026-06-23-third-round-live-fixture-plan.md`
  - 记录第三轮 live fixture 与 replay 验证方案。
- Modify after code green: `docs/superpowers/ExtractionStructured/problem/ExtractionStructured.md`
- Modify after code green: `docs/superpowers/ExtractionStructured/summary/2026-06-21-extraction-structured-optimization-summary.md`
- Modify after code green: `docs/superpowers/ExtractionStructured/specs/2026-06-21-extraction-structured-architecture-spec.md`

## 2. 本轮执行看板

| Task | 目标 | 状态 |
| --- | --- | --- |
| Task 1 | 锁定第三轮边界与代码基线 | 待执行 |
| Task 2 | 引入 `ExtractorEvidenceInput` 与来源端口 | 待执行 |
| Task 3 | Provider 正式组包与 `auditRefs` 透出 | 待执行 |
| Task 4 | extractor 改为消费内部输入投影 | 待执行 |
| Task 5 | shared projection / cache 契约回归 | 待执行 |
| Task 6 | live fixture 计划与文档回链 | 待执行 |

---

### Task 1: 锁定第三轮边界与代码基线

**Files:**

- Read: `docs/superpowers/ExtractionStructured/specs/2026-06-21-extraction-structured-architecture-spec.md`
- Read: `docs/superpowers/ExtractionStructured/plan/2026-06-22-extraction-structured-second-round-plan.md`
- Read: `backend/src/main/java/cn/bugstack/competitoragent/extractor/input/RepositoryExtractorInputProvider.java`
- Read: `backend/src/main/java/cn/bugstack/competitoragent/agent/extractor/SchemaExtractorAgent.java`
- Read: `backend/src/main/java/cn/bugstack/competitoragent/extractor/ExtractSharedOutputSanitizer.java`

- [ ] **Step 1: 复核规格中与第三轮直接相关的章节**

Run:

```powershell
rg -n "5.1.1|5.2|5.4|ExtractorEvidenceInput|DownstreamEvidenceView|当前状态补充" docs\superpowers\ExtractionStructured\specs\2026-06-21-extraction-structured-architecture-spec.md
```

Expected:

```text
命中 Provider 数据源治理边界、ExtractorEvidenceInput 与 DownstreamEvidenceView 拆分，以及 2026-06-22 第二轮后的当前状态补充。
```

- [ ] **Step 2: 复核当前代码仍然共用输入/输出视图的接缝**

Run:

```powershell
rg -n "DownstreamEvidenceView|ExtractorInputProvider|extractorInput|SharedNodeOutputEnvelope" backend\src\main\java\cn\bugstack\competitoragent\extractor backend\src\main\java\cn\bugstack\competitoragent\agent\extractor backend\src\main\java\cn\bugstack\competitoragent\task
```

Expected:

```text
确认 RepositoryExtractorInputProvider、SchemaExtractorAgent、ExtractSharedOutputSanitizer 仍直接依赖 DownstreamEvidenceView 作为 extractor 输入载体。
```

- [ ] **Step 3: 记录第三轮红线**

Create `docs/superpowers/ExtractionStructured/progress/2026-06-23-third-round-live-fixture-plan.md` with this header first:

```markdown
# 2026-06-23 第三轮 live fixture 与 replay 验证计划

## 本轮红线

- 不新增 workflow delivery 节点。
- 不扩搜索与采集 3.2。
- 不把 replay / cache 绕过 Provider 直接向 extractor 写正文。
- 不让 DownstreamEvidenceView 重新变成长正文载体。
```

Expected:

```text
第三轮执行和 live 验证都有固定红线，不会把范围重新扩散回 3.2 或 UI。
```

---

### Task 2: 引入 `ExtractorEvidenceInput` 与来源端口

**Files:**

- Create: `backend/src/main/java/cn/bugstack/competitoragent/extractor/input/ExtractorEvidenceInput.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/extractor/input/ExtractorEvidenceInputAssembler.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/extractor/input/ExtractorEvidenceSourcePort.java`
- Create: `backend/src/main/java/cn/bugstack/competitoragent/extractor/input/RepositoryExtractorEvidenceSourcePort.java`
- Create: `backend/src/test/java/cn/bugstack/competitoragent/extractor/input/ExtractorEvidenceInputAssemblerTest.java`

本任务只引入新的 extractor 内部输入投影与来源端口，不在这里切换 `ExtractorCompetitorInput` 的证据列表类型。
`ExtractorCompetitorInput` 的类型切换放到 Task 3，与 Provider 和直接消费者原子完成，避免出现“新类型 DTO + 旧消费者”的编译裂缝。

- [ ] **Step 1: 先补红灯测试，锁定输入投影的正文、结构化载荷与空标识字段规范化**

Create `ExtractorEvidenceInputAssemblerTest.java`:

```java
class ExtractorEvidenceInputAssemblerTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final ExtractorEvidenceInputAssembler assembler = new ExtractorEvidenceInputAssembler(objectMapper);

    @Test
    void shouldNormalizeKeyIdentifiersToEmptyStrings() {
        ExtractorEvidenceInput normalized = ExtractorEvidenceInput.builder()
                .evidenceId(null)
                .competitorName("  ")
                .sourceType(null)
                .title(" ")
                .content(null)
                .structuredPayload(Map.of("plans", List.of(Map.of("name", "Pro"))))
                .build()
                .normalized();

        assertThat(normalized.getEvidenceId()).isEqualTo("");
        assertThat(normalized.getCompetitorName()).isEqualTo("");
        assertThat(normalized.getSourceType()).isEqualTo("");
        assertThat(normalized.getTitle()).isEqualTo("");
        assertThat(normalized.getContent()).isEqualTo("");
        assertThat(normalized.getStructuredPayload()).containsKey("plans");
    }

    @Test
    void shouldAssembleExtractorEvidenceInputFromEvidenceSource() {
        EvidenceSource pricingApi = EvidenceSource.builder()
                .taskId(7L)
                .competitorName("Acme")
                .evidenceId("E701")
                .title("Pricing API")
                .url("https://api.acme.com/pricing")
                .sourceType("API_DATA")
                .fullContent("Pro 199 / 月，包含席位、计费周期、试用信息。")
                .pageMetadata("""
                        {
                          "sourceUrls": ["https://api.acme.com/pricing"],
                          "qualitySignals": ["STRUCTURED_BLOCK_HIT", "PRICING_BLOCK_HIT"],
                          "structuredBlocks": [{"blockType": "PRICING_BLOCK", "summary": "Pro 199 / 月"}],
                          "structuredPayload": {"plans": [{"name": "Pro", "price": 199}]},
                          "qualityScore": 0.98
                        }
                        """)
                .build();

        ExtractorEvidenceInput input = assembler.fromEvidenceSource(pricingApi);

        assertThat(input.getEvidenceId()).isEqualTo("E701");
        assertThat(input.getContent()).contains("Pro 199 / 月");
        assertThat(input.getStructuredPayload()).containsKey("plans");
        assertThat(input.getStructuredBlocks()).extracting(DownstreamEvidenceBlock::getBlockType)
                .containsExactly("PRICING_BLOCK");
    }
}
```

- [ ] **Step 2: 新建 extractor 内部输入投影对象**

Create `ExtractorEvidenceInput.java`:

```java
package cn.bugstack.competitoragent.extractor.input;

import cn.bugstack.competitoragent.workflow.contract.DownstreamEvidenceBlock;
import cn.bugstack.competitoragent.workflow.contract.DownstreamEvidenceQuality;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * extractor 内部专用输入投影。
 * 这里只有 extractor 可以持有正文和 structuredPayload；
 * 一旦进入 shared output 或下游节点，必须再投影为轻量视图。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ExtractorEvidenceInput {

    private String evidenceId;
    private String competitorName;
    private String sourceType;
    private String title;
    private String content;
    @Builder.Default
    private List<String> sourceUrls = List.of();
    @Builder.Default
    private List<String> issueFlags = List.of();
    @Builder.Default
    private List<String> qualitySignals = List.of();
    @Builder.Default
    private List<DownstreamEvidenceBlock> structuredBlocks = List.of();
    @Builder.Default
    private Map<String, Object> structuredPayload = Map.of();
    private DownstreamEvidenceQuality quality;

    public ExtractorEvidenceInput normalized() {
        return this.toBuilder()
                .evidenceId(normalizeText(evidenceId))
                .competitorName(normalizeText(competitorName))
                .sourceType(normalizeText(sourceType))
                .title(normalizeText(title))
                .content(content == null ? "" : content)
                .sourceUrls(new ArrayList<>(new LinkedHashSet<>(sourceUrls == null ? List.of() : sourceUrls)))
                .issueFlags(new ArrayList<>(new LinkedHashSet<>(issueFlags == null ? List.of() : issueFlags)))
                .qualitySignals(new ArrayList<>(new LinkedHashSet<>(qualitySignals == null ? List.of() : qualitySignals)))
                .structuredBlocks(structuredBlocks == null ? List.of() : structuredBlocks)
                .structuredPayload(structuredPayload == null ? Map.of() : structuredPayload)
                .quality(quality == null ? DownstreamEvidenceQuality.builder().build().normalized() : quality.normalized())
                .build();
    }

    private String normalizeText(String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }
}
```

- [ ] **Step 3: 新建组装器与来源端口**

Create `ExtractorEvidenceSourcePort.java` and `ExtractorEvidenceInputAssembler.java`:

```java
package cn.bugstack.competitoragent.extractor.input;

import cn.bugstack.competitoragent.agent.AgentContext;

import java.util.List;

public interface ExtractorEvidenceSourcePort {

    List<ExtractorEvidenceInput> load(AgentContext context);
}
```

```java
package cn.bugstack.competitoragent.extractor.input;

import cn.bugstack.competitoragent.model.entity.EvidenceSource;
import cn.bugstack.competitoragent.workflow.contract.DownstreamEvidenceBlock;
import cn.bugstack.competitoragent.workflow.contract.DownstreamEvidenceQuality;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 统一把 EvidenceSource 转成 extractor 内部输入投影，
 * 避免 Provider 再直接操作 repository entity 和 pageMetadata JSON。
 */
@Component
@RequiredArgsConstructor
public class ExtractorEvidenceInputAssembler {

    private final ObjectMapper objectMapper;

    public ExtractorEvidenceInput fromEvidenceSource(EvidenceSource evidence) {
        JsonNode metadata = readJson(evidence == null ? null : evidence.getPageMetadata());
        List<String> sourceUrls = readStringList(metadata.path("sourceUrls"));
        if (sourceUrls.isEmpty() && evidence != null && evidence.getUrl() != null && !evidence.getUrl().isBlank()) {
            sourceUrls = List.of(evidence.getUrl().trim());
        }
        return ExtractorEvidenceInput.builder()
                .evidenceId(normalizeText(evidence == null ? null : evidence.getEvidenceId()))
                .competitorName(normalizeText(evidence == null ? null : evidence.getCompetitorName()))
                .sourceType(normalizeText(evidence == null ? null : evidence.getSourceType()))
                .title(normalizeText(evidence == null ? null : evidence.getTitle()))
                .content(firstNonBlank(evidence == null ? null : evidence.getFullContent(),
                        evidence == null ? null : evidence.getContentSnippet()))
                .sourceUrls(sourceUrls)
                .issueFlags(readStringList(metadata.path("issueFlags")))
                .qualitySignals(readStringList(metadata.path("qualitySignals")))
                .structuredBlocks(objectMapper.convertValue(metadata.path("structuredBlocks"),
                        new TypeReference<List<DownstreamEvidenceBlock>>() {}))
                .structuredPayload(objectMapper.convertValue(metadata.path("structuredPayload"),
                        new TypeReference<Map<String, Object>>() {}))
                .quality(DownstreamEvidenceQuality.builder()
                        .qualityScore(metadata.path("qualityScore").isNumber() ? metadata.path("qualityScore").asDouble() : null)
                        .failureKind(metadata.path("failureKind").asText(null))
                        .durationMillis(metadata.path("durationMillis").isNumber() ? metadata.path("durationMillis").asLong() : null)
                        .build())
                .build()
                .normalized();
    }

    private JsonNode readJson(String rawJson) {
        try {
            return rawJson == null || rawJson.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(rawJson);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private List<String> readStringList(JsonNode node) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (node != null && node.isArray()) {
            node.forEach(item -> {
                String value = item == null ? null : item.asText(null);
                if (value != null && !value.isBlank()) {
                    values.add(value.trim());
                }
            });
        }
        return new ArrayList<>(values);
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return second == null || second.isBlank() ? "" : second.trim();
    }

    private String normalizeText(String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }
}
```

- [ ] **Step 4: 新建 repository-backed 第一版端口适配器**

Create `RepositoryExtractorEvidenceSourcePort.java`:

```java
package cn.bugstack.competitoragent.extractor.input;

import cn.bugstack.competitoragent.agent.AgentContext;
import cn.bugstack.competitoragent.model.entity.EvidenceSource;
import cn.bugstack.competitoragent.repository.EvidenceSourceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 第三轮先把“repository 读证据”收口到端口适配器，
 * 后续 replay / cache 只允许替换这里，不允许把正文读取逻辑重新塞回 Agent。
 */
@Component
@RequiredArgsConstructor
public class RepositoryExtractorEvidenceSourcePort implements ExtractorEvidenceSourcePort {

    private final EvidenceSourceRepository evidenceSourceRepository;
    private final ExtractorEvidenceInputAssembler extractorEvidenceInputAssembler;

    @Override
    public List<ExtractorEvidenceInput> load(AgentContext context) {
        List<EvidenceSource> evidences = evidenceSourceRepository.findByTaskIdOrderByEvidenceIdAsc(
                context == null ? null : context.getTaskId());
        List<ExtractorEvidenceInput> inputs = new ArrayList<>();
        for (EvidenceSource evidence : evidences == null ? List.<EvidenceSource>of() : evidences) {
            if (evidence != null) {
                inputs.add(extractorEvidenceInputAssembler.fromEvidenceSource(evidence));
            }
        }
        return inputs;
    }
}
```

- [ ] **Step 5: 运行 Task 2 的最小验证**

Run:

```powershell
mvn -pl backend "-Dtest=ExtractorEvidenceInputAssemblerTest" test
```

Expected:

```text
ExtractorEvidenceInputAssemblerTest 通过，证明新的内部输入投影已经能承载正文和 structuredPayload，并且不会把关键标识字段留成 null。
```

---

### Task 3: Provider 正式组包并透出 `inputSource / auditRefs`

**Files:**

- Modify: `backend/src/main/java/cn/bugstack/competitoragent/extractor/input/ExtractorCompetitorInput.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/extractor/input/ExtractorInputPackage.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/extractor/input/RepositoryExtractorInputProvider.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/agent/extractor/SchemaExtractorAgent.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/extractor/ExtractSharedOutputSanitizer.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/extractor/input/RepositoryExtractorInputProviderTest.java`

本任务是第三轮唯一允许切换 `ExtractorCompetitorInput` 证据字段类型的任务。
一旦 `evidenceCatalog / structuredEvidence / readableEvidence / skippedEvidence` 从 `List<DownstreamEvidenceView>` 切到 `List<ExtractorEvidenceInput>`，
`RepositoryExtractorInputProvider`、`SchemaExtractorAgent`、`ExtractSharedOutputSanitizer` 必须同一批原子修改，不能拆成前后两轮。

- [ ] **Step 1: 先锁定直接消费者，避免 DTO 类型切换时遗漏编译面**

Run:

```powershell
rg -n "ExtractorCompetitorInput" backend\src\main\java backend\src\test\java
```

Expected:

```text
确认当前生产消费者至少包含 RepositoryExtractorInputProvider、SchemaExtractorAgent、ExtractSharedOutputSanitizer；若出现新增生产消费者，必须在本任务一并迁移。
```

- [ ] **Step 2: 让输入 DTO 明确记录来源和审计引用，并完成证据列表类型切换**

Update `ExtractorCompetitorInput.java` and `ExtractorInputPackage.java`:

```java
@Data
@Builder
public class ExtractorCompetitorInput {

    private String competitorName;
    private List<ExtractorEvidenceInput> evidenceCatalog;
    private List<ExtractorEvidenceInput> structuredEvidence;
    private List<ExtractorEvidenceInput> readableEvidence;
    private List<ExtractorEvidenceInput> skippedEvidence;
    private List<String> sourceUrls;
    private List<String> issueFlags;
    private Map<String, Object> budget;
}
```

```java
@Data
@Builder
public class ExtractorInputPackage {

    private Long taskId;
    private String nodeName;
    private Long planVersionId;
    private String branchKey;
    private Long schemaId;
    private List<String> dimensions;
    private String inputSource;
    private Map<String, Object> auditRefs;
    private List<ExtractorCompetitorInput> competitors;
}
```

- [ ] **Step 3: 改造 Provider，只保留“筛选、排序、预算、组包”职责**

Update constructor fields in `RepositoryExtractorInputProvider.java`:

```java
private final ExtractorEvidenceSourcePort extractorEvidenceSourcePort;
private final ObjectMapper objectMapper;
```

Replace `provide()` core with:

```java
@Override
public ExtractorInputPackage provide(AgentContext context) {
    List<ExtractorEvidenceInput> allInputs = extractorEvidenceSourcePort.load(context);
    List<ExtractorEvidenceInput> usableInputs = new ArrayList<>();
    List<ExtractorEvidenceInput> skippedInputs = new ArrayList<>();
    for (ExtractorEvidenceInput input : allInputs == null ? List.<ExtractorEvidenceInput>of() : allInputs) {
        if (isUsableEvidence(input)) {
            usableInputs.add(input);
        } else {
            skippedInputs.add(input.toBuilder()
                    .issueFlags(appendIssueFlag(input.getIssueFlags(), "NO_USABLE_EVIDENCE"))
                    .build());
        }
    }

    Map<String, List<ExtractorEvidenceInput>> usableByCompetitor = groupByCompetitor(usableInputs);
    Map<String, List<ExtractorEvidenceInput>> skippedByCompetitor = groupByCompetitor(skippedInputs);
    List<ExtractorCompetitorInput> competitors = buildCompetitorInputs(usableByCompetitor, skippedByCompetitor);

    return ExtractorInputPackage.builder()
            .taskId(context == null ? null : context.getTaskId())
            .nodeName(firstNonBlank(context == null ? null : context.getCurrentNodeName(), "extract_schema"))
            .planVersionId(context == null ? null : context.getPlanVersionId())
            .branchKey(context == null ? null : context.getBranchKey())
            .schemaId(readSchemaRuntimeConfig(context == null ? null : context.getCurrentNodeConfig()).schemaId())
            .dimensions(readSchemaRuntimeConfig(context == null ? null : context.getCurrentNodeConfig()).dimensions())
            .inputSource("REPOSITORY_BACKED_PORT")
            .auditRefs(buildAuditRefs(context))
            .competitors(competitors)
            .build();
}
```

- [ ] **Step 4: 从 collector shared envelope 生成 `auditRefs`，并明确无信封时的稳定语义**

Add helper to `RepositoryExtractorInputProvider.java`:

```java
private Map<String, Object> buildAuditRefs(AgentContext context) {
    int collectorEnvelopeCount = 0;
    LinkedHashSet<String> projectionTypes = new LinkedHashSet<>();
    for (Map.Entry<String, SharedNodeOutputEnvelope> entry :
            (context == null || context.getSharedOutputEnvelopes() == null
                    ? Map.<String, SharedNodeOutputEnvelope>of()
                    : context.getSharedOutputEnvelopes()).entrySet()) {
        if (!entry.getKey().startsWith("collect")) {
            continue;
        }
        SharedNodeOutputEnvelope envelope = entry.getValue();
        collectorEnvelopeCount++;
        if (envelope != null && envelope.getProjectionType() != null && !envelope.getProjectionType().isBlank()) {
            projectionTypes.add(envelope.getProjectionType());
        }
    }
    boolean hasSearchProjection = projectionTypes.contains("SEARCH_SHARED_PROJECTION_V1");
    String searchAuditAvailabilityReason = collectorEnvelopeCount == 0
            ? "COLLECTOR_SHARED_ENVELOPE_MISSING"
            : hasSearchProjection ? "SEARCH_SHARED_PROJECTION_READY" : "SEARCH_SHARED_PROJECTION_NOT_FOUND";
    String collectionAuditAvailabilityReason = collectorEnvelopeCount == 0
            ? "COLLECTOR_SHARED_ENVELOPE_MISSING"
            : "COLLECTOR_SHARED_ENVELOPE_READY";
    return Map.of(
            "collectorEnvelopeCount", collectorEnvelopeCount,
            "projectionTypes", new ArrayList<>(projectionTypes),
            "searchAudit", Map.of("available", hasSearchProjection,
                    "availabilityReason", searchAuditAvailabilityReason,
                    "usage", "用于解释来源发现与采集路径，不直接替代 extractor 正文输入"),
            "collectionAudit", Map.of("available", collectorEnvelopeCount > 0,
                    "availabilityReason", collectionAuditAvailabilityReason,
                    "usage", "用于解释采集失败、降级与 skippedEvidence 来源")
    );
}
```

- [ ] **Step 5: 同步调整直接消费者签名，保证 DTO 切换后仍可编译**

Update `SchemaExtractorAgent.java` and `ExtractSharedOutputSanitizer.java` with the minimum compile-safe bridge first:

```java
private List<EvidenceSource> rebuildEvidenceSources(ExtractorCompetitorInput competitorInput) {
    List<EvidenceSource> evidences = new ArrayList<>();
    for (ExtractorEvidenceInput input : competitorInput == null || competitorInput.getEvidenceCatalog() == null
            ? List.<ExtractorEvidenceInput>of()
            : competitorInput.getEvidenceCatalog()) {
        if (input == null) {
            continue;
        }
        evidences.add(EvidenceSource.builder()
                .evidenceId(input.getEvidenceId())
                .competitorName(input.getCompetitorName())
                .sourceType(input.getSourceType())
                .title(input.getTitle())
                .url(input.getSourceUrls().isEmpty() ? null : input.getSourceUrls().get(0))
                .fullContent(input.getContent())
                .pageMetadata(objectMapper.writeValueAsString(Map.of(
                        "sourceUrls", input.getSourceUrls(),
                        "issueFlags", input.getIssueFlags(),
                        "qualitySignals", input.getQualitySignals(),
                        "structuredBlocks", input.getStructuredBlocks(),
                        "structuredPayload", input.getStructuredPayload()
                )))
                .build());
    }
    return evidences;
}
```

```java
private static List<ExtractorCompetitorInput> slimCompetitorInputs(List<ExtractorCompetitorInput> competitors) {
    if (competitors == null || competitors.isEmpty()) {
        return List.of();
    }
    List<ExtractorCompetitorInput> slimCompetitors = new ArrayList<>();
    for (ExtractorCompetitorInput competitor : competitors) {
        if (competitor == null) {
            continue;
        }
        slimCompetitors.add(ExtractorCompetitorInput.builder()
                .competitorName(competitor.getCompetitorName())
                .evidenceCatalog(slimEvidenceInputs(competitor.getEvidenceCatalog()))
                .structuredEvidence(slimEvidenceInputs(competitor.getStructuredEvidence()))
                .readableEvidence(slimEvidenceInputs(competitor.getReadableEvidence()))
                .skippedEvidence(slimEvidenceInputs(competitor.getSkippedEvidence()))
                .sourceUrls(copyList(competitor.getSourceUrls()))
                .issueFlags(copyList(competitor.getIssueFlags()))
                .budget(copyMap(competitor.getBudget()))
                .build());
    }
    return slimCompetitors;
}
```

- [ ] **Step 6: 给 `auditRefs` 增加红灯测试**

Append to `RepositoryExtractorInputProviderTest.java`:

```java
@Test
void shouldExposeAuditRefsFromCollectorSharedEnvelope() {
    when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(8L)).thenReturn(List.of(
            EvidenceSource.builder()
                    .taskId(8L)
                    .competitorName("Acme")
                    .evidenceId("E801")
                    .title("Docs")
                    .url("https://docs.acme.com")
                    .sourceType("DOCS")
                    .fullContent("docs body")
                    .build()
    ));

    AgentContext context = AgentContext.builder()
            .taskId(8L)
            .taskName("task")
            .currentNodeName("extract_schema")
            .build();
    context.putSharedOutputEnvelope("collect_sources_01_01", SharedNodeOutputEnvelope.builder()
            .taskId(8L)
            .nodeName("collect_sources_01_01")
            .projectionType("SEARCH_SHARED_PROJECTION_V1")
            .payloadJson("{\"projectionType\":\"SEARCH_SHARED_PROJECTION_V1\"}")
            .sourceUrls(List.of("https://docs.acme.com"))
            .build());

    ExtractorInputPackage inputPackage = provider.provide(context);

    assertThat(inputPackage.getInputSource()).isEqualTo("REPOSITORY_BACKED_PORT");
    assertThat(inputPackage.getAuditRefs()).containsKey("searchAudit");
    assertThat(String.valueOf(inputPackage.getAuditRefs().get("projectionTypes")))
            .contains("SEARCH_SHARED_PROJECTION_V1");
}

@Test
void shouldMarkAuditRefsUnavailableReasonWhenCollectorEnvelopeMissing() {
    when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(9L)).thenReturn(List.of(
            EvidenceSource.builder()
                    .taskId(9L)
                    .competitorName("Acme")
                    .evidenceId("E901")
                    .title("Docs")
                    .url("https://docs.acme.com")
                    .sourceType("DOCS")
                    .fullContent("docs body")
                    .build()
    ));

    ExtractorInputPackage inputPackage = provider.provide(AgentContext.builder()
            .taskId(9L)
            .taskName("task")
            .currentNodeName("extract_schema")
            .build());

    assertThat(String.valueOf(inputPackage.getAuditRefs().get("searchAudit")))
            .contains("available=false")
            .contains("COLLECTOR_SHARED_ENVELOPE_MISSING");
}
```

- [ ] **Step 7: 运行 Provider 与消费者切换回归**

Run:

```powershell
mvn -pl backend "-Dtest=RepositoryExtractorInputProviderTest,SchemaExtractorAgentTest,SharedNodeOutputProjectorContractTest" test
```

Expected:

```text
Provider、extractor 直接消费者和 shared projector 的最小回归通过，说明 DTO 类型切换没有留下编译或序列化断层。
```

---

### Task 4: 让 `SchemaExtractorAgent` 只消费内部输入投影

**Files:**

- Modify: `backend/src/main/java/cn/bugstack/competitoragent/agent/extractor/SchemaExtractorAgent.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/agent/extractor/SchemaExtractorAgentTest.java`

这里提到的 `NormalizedSchema` 指的是 `SchemaExtractorAgent` 中已经存在的 private record。
第三轮不新增同名类型，本任务只是在该现有 record 的上下文里切换输入投影和下游轻量视图的组装逻辑。

- [ ] **Step 1: 先补 extractor 红灯测试**

Append to `SchemaExtractorAgentTest.java`:

```java
@Test
void shouldRenderPromptFromExtractorEvidenceInputAndStillExportLightweightViews() throws Exception {
    ExtractorEvidenceInput extractorEvidenceInput = ExtractorEvidenceInput.builder()
            .evidenceId("E901")
            .competitorName("Acme")
            .sourceType("PRICING")
            .title("Pricing")
            .content("Pro 199 / 月，按席位计费。")
            .sourceUrls(List.of("https://acme.com/pricing"))
            .qualitySignals(List.of("PRICING_BLOCK_HIT"))
            .structuredBlocks(List.of(DownstreamEvidenceBlock.builder()
                    .blockType("PRICING_BLOCK")
                    .summary("Pro 199 / 月")
                    .sourceUrls(List.of("https://acme.com/pricing"))
                    .build()))
            .structuredPayload(Map.of("plans", List.of(Map.of("name", "Pro", "price", 199))))
            .quality(DownstreamEvidenceQuality.builder().qualityScore(0.95).build())
            .build();
    when(extractorInputProvider.provide(any())).thenReturn(ExtractorInputPackage.builder()
            .taskId(9L)
            .nodeName("extract_schema")
            .inputSource("REPOSITORY_BACKED_PORT")
            .auditRefs(Map.of("searchAudit", Map.of("available", true)))
            .competitors(List.of(ExtractorCompetitorInput.builder()
                    .competitorName("Acme")
                    .evidenceCatalog(List.of(extractorEvidenceInput))
                    .structuredEvidence(List.of(extractorEvidenceInput))
                    .readableEvidence(List.of(extractorEvidenceInput))
                    .skippedEvidence(List.of())
                    .sourceUrls(List.of("https://acme.com/pricing"))
                    .issueFlags(List.of())
                    .budget(Map.of("maxPromptEvidenceChars", 4000))
                    .build()))
            .build());
    when(promptService.render(eq("extractor"), any())).thenReturn("prompt");
    when(llmClient.chatForJson(any(), any(), eq("ExtractedSchema"))).thenReturn("""
            {
              "officialUrl": "https://acme.com",
              "summary": "workspace pricing",
              "positioning": "collaboration suite",
              "targetUsers": ["teams"],
              "coreFeatures": [],
              "pricing": {"model": "Pro 199 / 月", "evidenceIds": ["E901"]},
              "strengths": [],
              "weaknesses": [],
              "sources": [],
              "sourceUrls": ["https://acme.com/pricing"]
            }
            """);

    AgentResult result = extractorAgent.execute(AgentContext.builder()
            .taskId(9L)
            .taskName("task")
            .currentNodeName("extract_schema")
            .build());

    JsonNode output = objectMapper.readTree(result.getOutputData());
    assertThat(output.path("extractorInput").path("inputSource").asText()).isEqualTo("REPOSITORY_BACKED_PORT");
    assertThat(output.path("downstreamEvidenceViews").get(0).path("content").asText()).isBlank();
}
```

- [ ] **Step 2: 把 Prompt 构建方法签名切到 `ExtractorEvidenceInput`**

Update `SchemaExtractorAgent.java` around `extractAndNormalize()`:

```java
List<ExtractorEvidenceInput> evidenceCatalog = normalizeEvidenceInputs(
        competitorInput == null ? List.of() : competitorInput.getEvidenceCatalog());
List<ExtractorEvidenceInput> structuredEvidenceInputs = normalizeEvidenceInputs(
        competitorInput == null ? List.of() : competitorInput.getStructuredEvidence());
List<ExtractorEvidenceInput> readableEvidenceInputs = normalizeEvidenceInputs(
        competitorInput == null ? List.of() : competitorInput.getReadableEvidence());

promptVariables.put("evidenceCatalog", buildEvidenceCatalog(evidenceCatalog));
promptVariables.put("structuredEvidence", buildStructuredEvidence(structuredEvidenceInputs));
promptVariables.put("qualitySignalGuidance", buildQualitySignalGuidance(evidenceCatalog));
promptVariables.put("readableContent", buildReadableContent(readableEvidenceInputs));
promptVariables.put("collectedContent", buildCollectedContent(evidenceCatalog));
```

本步不只是替换 Prompt 构建方法签名，还要把 `extractAndNormalize()` 到 `invokeExtractorOnce()`、
`normalizeSchema()` 之间那条临时 `EvidenceSource` 回转链一并收口成 `List<ExtractorEvidenceInput>`。
也就是说，Task 3 为了过编译而临时引入的 `rebuildEvidenceSources()` 必须在本步末尾删除，
连带清理只服务于该桥接的 `buildPageMetadataFromEvidenceView()`、`firstSourceUrl()` 等辅助方法，
避免第三轮落地后仍残留“内部投影 -> 临时旧模型 -> 再归一化”的死代码路径。

- [ ] **Step 3: 统一从内部输入投影回建轻量下游视图**

Add helper to `SchemaExtractorAgent.java`:

```java
private DownstreamEvidenceView toDownstreamEvidenceView(ExtractorEvidenceInput input) {
    if (input == null) {
        return null;
    }
    return DownstreamEvidenceView.builder()
            .evidenceId(input.getEvidenceId())
            .competitorName(input.getCompetitorName())
            .sourceType(input.getSourceType())
            .title(input.getTitle())
            .content("")
            .sourceUrls(input.getSourceUrls() == null ? List.of() : new ArrayList<>(input.getSourceUrls()))
            .issueFlags(input.getIssueFlags() == null ? List.of() : new ArrayList<>(input.getIssueFlags()))
            .qualitySignals(input.getQualitySignals() == null ? List.of() : new ArrayList<>(input.getQualitySignals()))
            .structuredBlocks(input.getStructuredBlocks() == null ? List.of() : input.getStructuredBlocks())
            .structuredPayload(Map.of())
            .quality(input.getQuality())
            .build()
            .normalized();
}
```

Use this helper when building `NormalizedSchema` output views and `ExtractorInputPackage` shared output summaries.

- [ ] **Step 4: 运行 extractor 相关回归**

Run:

```powershell
mvn -pl backend "-Dtest=SchemaExtractorAgentTest,CompetitorAnalysisAgentTest,QualityReviewAgentTest" test
```

Expected:

```text
extractor、analyzer、reviewer 相关契约通过，说明输入投影拆分没有破坏下游消费。
```

---

### Task 5: shared projection / cache 契约继续保留可诊断性

**Files:**

- Modify: `backend/src/main/java/cn/bugstack/competitoragent/extractor/ExtractSharedOutputSanitizer.java`
- Modify: `backend/src/main/java/cn/bugstack/competitoragent/extractor/ExtractSharedProjection.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/task/SharedNodeOutputProjectorContractTest.java`
- Modify: `backend/src/test/java/cn/bugstack/competitoragent/task/TaskSnapshotCacheServiceTest.java`

- [ ] **Step 1: 在 sanitizer 中增加 trace-only 的 extractor 输入裁剪，并明确集成到 `slimCompetitorInputs()`**

Update `ExtractSharedOutputSanitizer.java` to this final shape:

```java
public static ExtractorInputPackage slimExtractorInputPackage(ExtractorInputPackage inputPackage) {
    if (inputPackage == null) {
        return null;
    }
    return ExtractorInputPackage.builder()
            .taskId(inputPackage.getTaskId())
            .nodeName(inputPackage.getNodeName())
            .planVersionId(inputPackage.getPlanVersionId())
            .branchKey(inputPackage.getBranchKey())
            .schemaId(inputPackage.getSchemaId())
            .dimensions(copyList(inputPackage.getDimensions()))
            .inputSource(firstNonBlank(inputPackage.getInputSource(), null))
            .auditRefs(copyMap(inputPackage.getAuditRefs()))
            .competitors(slimCompetitorInputs(inputPackage.getCompetitors()))
            .build();
}

private static List<ExtractorEvidenceInput> slimEvidenceInputs(List<ExtractorEvidenceInput> inputs) {
    if (inputs == null || inputs.isEmpty()) {
        return List.of();
    }
    List<ExtractorEvidenceInput> slimInputs = new ArrayList<>();
    for (ExtractorEvidenceInput input : inputs) {
        if (input == null) {
            continue;
        }
        slimInputs.add(input.toBuilder()
                .content("")
                .structuredPayload(Map.of())
                .structuredBlocks(slimEvidenceBlocks(input.getStructuredBlocks()))
                .build()
                .normalized());
    }
    return slimInputs;
}

private static List<ExtractorCompetitorInput> slimCompetitorInputs(List<ExtractorCompetitorInput> competitors) {
    if (competitors == null || competitors.isEmpty()) {
        return List.of();
    }
    List<ExtractorCompetitorInput> slimCompetitors = new ArrayList<>();
    for (ExtractorCompetitorInput competitor : competitors) {
        if (competitor == null) {
            continue;
        }
        slimCompetitors.add(ExtractorCompetitorInput.builder()
                .competitorName(competitor.getCompetitorName())
                .evidenceCatalog(slimEvidenceInputs(competitor.getEvidenceCatalog()))
                .structuredEvidence(slimEvidenceInputs(competitor.getStructuredEvidence()))
                .readableEvidence(slimEvidenceInputs(competitor.getReadableEvidence()))
                .skippedEvidence(slimEvidenceInputs(competitor.getSkippedEvidence()))
                .sourceUrls(copyList(competitor.getSourceUrls()))
                .issueFlags(copyList(competitor.getIssueFlags()))
                .budget(copyMap(competitor.getBudget()))
                .build());
    }
    return slimCompetitors;
}
```

这里必须连同 `slimExtractorInputPackage()` 一起修改；否则即使 `slimCompetitorInputs()` 已经切到 `ExtractorEvidenceInput`，
`inputSource / auditRefs` 仍会在 sanitizer 阶段被丢失，导致 Task 5 后半段的 shared projection 断言无法成立。

- [ ] **Step 2: 让 shared projection 保留 `inputSource / auditRefs`**

Update `ExtractSharedProjection.fromExtractorOutput()` expectations:

```java
return ExtractSharedProjection.builder()
        .projectionType(PROJECTION_TYPE)
        .contractVersion(textOrNull(output, "contractVersion"))
        .totalCompetitors(numberOrNull(output, "totalCompetitors"))
        .successCount(numberOrNull(output, "successCount"))
        .results(results)
        .drafts(drafts)
        .sourceUrls(readStringList(output.path("sourceUrls")))
        .issueFlags(readStringList(output.path("issueFlags")))
        .downstreamEvidenceViews(downstreamEvidenceViews)
        .extractorInput(extractorInput)
        .build();
```

The key requirement here is not a new field in `ExtractSharedProjection`, but that `extractorInput` now serializes `inputSource` and `auditRefs` after sanitization.

- [ ] **Step 3: 扩 shared projector 契约测试**

Update `SharedNodeOutputProjectorContractTest.java` extractor case:

```java
assertThat(envelope.getPayloadJson()).contains("\"inputSource\":\"REPOSITORY_BACKED_PORT\"");
assertThat(envelope.getPayloadJson()).contains("\"searchAudit\"");
assertThat(envelope.getPayloadJson()).doesNotContain("very large body");
assertThat(envelope.getPayloadJson()).doesNotContain("large structured block body");
```

- [ ] **Step 4: 覆盖 Redis cache 对新 extractor projection 的读取**

Append to `TaskSnapshotCacheServiceTest.java`:

```java
@Test
void shouldLoadExtractorSharedEnvelopeWithInputSourceAndAuditRefs() {
    Long taskId = 51L;
    SharedNodeOutputEnvelope envelope = SharedNodeOutputEnvelope.builder()
            .taskId(taskId)
            .nodeName("extract_schema")
            .planVersionId(3L)
            .projectionType("EXTRACT_SHARED_PROJECTION_V1")
            .payloadJson("""
                    {
                      "projectionType":"EXTRACT_SHARED_PROJECTION_V1",
                      "extractorInput":{
                        "inputSource":"REPOSITORY_BACKED_PORT",
                        "auditRefs":{"searchAudit":{"available":true}},
                        "competitors":[{"competitorName":"Acme","readableEvidence":[{"evidenceId":"E001","content":""}]}]
                      },
                      "sourceUrls":["https://docs.example.com/pricing"]
                    }
                    """)
            .sourceUrls(List.of("https://docs.example.com/pricing"))
            .build();

    cacheService.cacheSharedOutputEnvelope(taskId, envelope);
    Map<String, SharedNodeOutputEnvelope> outputs = cacheService.getCachedSharedOutputEnvelopes(taskId);

    assertThat(outputs.get("extract_schema").getProjectionType()).isEqualTo("EXTRACT_SHARED_PROJECTION_V1");
    assertThat(outputs.get("extract_schema").getPayloadJson()).contains("REPOSITORY_BACKED_PORT");
    assertThat(outputs.get("extract_schema").getPayloadJson()).contains("searchAudit");
}
```

- [ ] **Step 5: 运行 shared projection / cache 回归**

Run:

```powershell
mvn -pl backend "-Dtest=SharedNodeOutputProjectorContractTest,TaskSnapshotCacheServiceTest" test
```

Expected:

```text
extractor shared projection 继续不携带长正文，同时 cache 恢复链路可见 inputSource 与 auditRefs。
```

---

### Task 6: live fixture 计划与文档回链

**Files:**

- Modify: `docs/superpowers/ExtractionStructured/progress/2026-06-23-third-round-live-fixture-plan.md`
- Modify after code green: `docs/superpowers/ExtractionStructured/problem/ExtractionStructured.md`
- Modify after code green: `docs/superpowers/ExtractionStructured/summary/2026-06-21-extraction-structured-optimization-summary.md`
- Modify after code green: `docs/superpowers/ExtractionStructured/specs/2026-06-21-extraction-structured-architecture-spec.md`

- [ ] **Step 1: 把第三轮 live fixture 目标写成固定模板**

Append to `2026-06-23-third-round-live-fixture-plan.md`:

```markdown
## 样本目标

1. rerun `extract_schema` 时，`/api/task/{taskId}/replay` 或节点视图中可以看到 `extractorInput.inputSource=REPOSITORY_BACKED_PORT`。
2. `extractorInput.auditRefs.searchAudit.available=true` 时，说明 collector shared projection 已正确透传到 Provider 诊断面。
3. `extractorInput.competitors[*].skippedEvidence[*]` 仍可见 `PROMPT_BUDGET_SKIPPED / THIN_CONTENT_ONLY / CONTENT_GAP` 等跳过原因。
4. shared projection 与 Redis cache 中不出现正文长文本，只保留 trace-only 投影。
```

- [ ] **Step 2: 记录本轮验证命令**

Append to the same doc:

```markdown
## 执行命令

~~~powershell
$taskId = 50
Invoke-RestMethod "http://localhost:9093/api/task/$taskId/replay" -Method Get | ConvertTo-Json -Depth 10
Invoke-RestMethod "http://localhost:9093/api/task/$taskId/nodes" -Method Get | ConvertTo-Json -Depth 10
~~~

## 判定标准

- replay 或节点视图中能看到 `extractorInput.inputSource` 与 `auditRefs`；
- 任何 `readableEvidence.content`、`downstreamEvidenceViews.content` 都不应再带长正文；
- 如果 dev 服务未启动或 task 不存在，文档必须明确写“live 未执行原因”，不能伪造成功结论。
```

- [ ] **Step 3: 代码全量回归**

Run:

```powershell
mvn -pl backend "-Dtest=RepositoryExtractorInputProviderTest,SchemaExtractorAgentTest,CompetitorAnalysisAgentTest,QualityReviewAgentTest,SharedNodeOutputProjectorContractTest,TaskSnapshotCacheServiceTest" test
mvn -pl backend test
```

Expected:

```text
定向回归先通过，再跑 backend 全量测试通过；若全量失败，必须把失败类名和阻断原因记录回 progress 文档。
```

- [ ] **Step 4: 文档回链**

Update these facts after code and tests are green:

```markdown
- `ExtractionStructured.md`：把“Provider 内部数据源仍未完全收口”更新为“来源端口已落地，下一阶段剩 replay/cache 正式替换”。
- `2026-06-21-extraction-structured-optimization-summary.md`：追加“ExtractorEvidenceInput 与 DownstreamEvidenceView 已拆分、shared projection 保留 inputSource / auditRefs”。
- `2026-06-21-extraction-structured-architecture-spec.md`：在 `5.1.1` 和 `当前状态补充` 里补写第三轮已落地事实。
```

---

## 验收标准

第三轮完成时必须同时满足：

1. `RepositoryExtractorInputProvider` 不再直接操作 `EvidenceSourceRepository` 与 `DownstreamEvidenceViewAssembler` 组装 extractor 输入。
2. `ExtractorEvidenceInput` 成为 extractor 内部正式输入载体，允许正文与 structuredPayload 只停留在内部执行面。
3. `DownstreamEvidenceView` 继续只作为轻量下游追溯契约存在，不重新变成长正文容器。
4. `ExtractorInputPackage` 至少包含 `inputSource` 和 `auditRefs`，且它们能进入 extract shared projection。
5. `SharedNodeOutputProjectorContractTest` 与 `TaskSnapshotCacheServiceTest` 能证明新的 extractor projection 仍不泄漏长正文。
6. 定向测试和 `mvn -pl backend test` 均通过；若 live 未执行，必须在 progress 文档中写明原因。

## 当前不做

- 不把 `REPOSITORY_BACKED_PORT` 直接替换成真实跨重启正文缓存；
- 不新增 `delivery_report` 一类 workflow 节点；
- 不把 replay API 重新设计为 extractor 专属协议；
- 不触碰 `MemoryWritebackService` 的跨任务领域记忆策略；
- 不修改前端展示结构。

## 自检

### 规格覆盖

- `5.1.1 第二轮后的 Provider 数据源治理边界`：由 Task 2、Task 3 覆盖。
- `5.4 正文使用规则`：由 Task 2、Task 4、Task 5 覆盖。
- `shared projection / cache / replay 可诊断性`：由 Task 5、Task 6 覆盖。

### 红旗词扫描

Run:

```powershell
rg -n "TBD|TODO|implement later|fill in details|appropriate|待定|占位|类似" docs\superpowers\ExtractionStructured\plan\2026-06-23-extraction-structured-third-round-plan.md docs\superpowers\ExtractionStructured\progress\2026-06-23-third-round-live-fixture-plan.md | Select-String -NotMatch "rg -n"
```

Expected:

```text
无命中；若命中，先把计划文本改成具体代码、命令或判定标准。
```

### 命名一致性

- 统一使用 `ExtractorEvidenceInput` 作为 extractor 内部投影名；
- 统一使用 `ExtractorEvidenceSourcePort` 作为 Provider 来源端口名；
- `inputSource` 固定枚举值先使用 `REPOSITORY_BACKED_PORT`，后续新增 cache / replay 适配器时再扩展。

## Execution Handoff

Plan complete and saved to `docs/superpowers/ExtractionStructured/plan/2026-06-23-extraction-structured-third-round-plan.md`. Two execution options:

1. Subagent-Driven (recommended) - 我按 Task 逐项派发实现和复核。
2. Inline Execution - 我在当前会话里按这份计划连续实施并在关键点停下来复核。
