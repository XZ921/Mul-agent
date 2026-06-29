# 04 Task 66 搜索采集公开证据补采实施计划

> **给 agentic workers：** 必须使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 按任务逐步执行本计划。步骤使用 checkbox（`- [ ]`）语法跟踪。

**目标：** 在真实公网链路遇到登录、验证码、反爬、低信号页面或字段证据路径未覆盖时，主动寻找并采集有效公开信息，同时防止未验证中介页进入正式证据、保留 `sourceUrls`、避免证据落库失败。

**架构：** 本计划不针对哔哩哔哩硬编码，而是在搜索采集链路建立“主动补采 + 安全降级”的通用路径：`CandidateOwnershipPolicy` 负责识别中介页、登录页和验证码页，`PublicEvidenceRecoveryService` 在候选验证不足、页面低信号或字段证据路径未覆盖时生成公开替代入口，`SearchExecutionCoordinator` 复用现有验证、排序和 sitemap/robots 能力补采有效页面，`CollectionTargetSelector` 负责最终入选门槛，`PublicShellRecoveryExtractor` 只作为最后兜底恢复公开壳信息，`CollectorAgent` 负责证据落库前安全化和失败诊断保留。登录页处理只采集公开可见信息，不绕过登录、不破解验证码、不模拟授权态。该计划作为后续 `CoverageContract + DimensionEvidencePlan` 的上游基础设施：补采服务必须能接收字段证据路径上下文，不能继续只按 `sourceType` 推断要找什么。

**技术栈：** Java 17、Spring Boot、Flyway、Jackson、JUnit 5、Mockito、Maven、PowerShell curl

---

## 0. 背景与边界

本计划来自 2026-06-22 live 样本：

- task 52：`https://www.bilibili.com` 首页真实链路返回验证码/低正文页面，OFFICIAL 验证未通过，`selectedCandidateCount=0`。
- task 53：`https://app.bilibili.com` 下载中心候选未通过 OFFICIAL 验证；补源超时后未验证的百度官网认证中介页被选为正式采集目标，随后 `discovery_reason` 长度 546 超过 `varchar(500)` 导致落库失败。
- 样本记录：
  - `docs/superpowers/ExtractionStructured/progress/live-bilibili-official-20260622-195904/sample-summary.md`
  - `docs/superpowers/ExtractionStructured/progress/live-bilibili-app-official-20260622-200244/sample-summary.md`

本计划不直接落地完整 `CoverageContract + DimensionEvidencePlan`，但必须为该模式预留输入和审计字段。修完后再回到报告层复验：

- `/api/report/{id}` 是否还出现重复竞品 coverage。
- `summary / positioning / targetUsers` 是否仍被旧快照打成缺口。
- reviewer / report diagnosis 是否仍把字段级缺口收口成阻断。
- Tavily coverage 设计中的字段证据路径是否能复用本计划的公开补采能力，而不是再写一套 sourceType-first 补源。

登录/验证码页面的处理边界：

- 优先采集：同域或官方子域上的公开正文页面，例如 `/about`、`/help`、`/docs`、`/download`、`/product`、`/features`、`/support`、`/openplatform`、`/creator`、`/app`、robots/sitemap 暴露的高价值入口、页面 canonical/OpenGraph/JSON-LD 暴露的公开链接。
- 允许降级采集：页面标题、meta description、meta keywords、OpenGraph、canonical、JSON-LD、页面已公开渲染的少量可读片段、可信搜索摘要、候选来源与丢弃原因。
- 不允许：提交登录表单、破解验证码、绕过访问控制、使用用户隐私态 cookie 或模拟授权态。
- 公开壳信息只能作为最后兜底证据，例如 `PUBLIC_SHELL_ONLY`、`LOGIN_GATE_PARTIAL`、`ANTI_BOT_PARTIAL`，不能当作高置信正文证据。

有效信息成功标准：

- 优先成功：至少采集到 1 条同域或官方子域公开正文证据，正文满足长度阈值、包含竞品或产品相关词、包含 sourceType 或字段证据路径对应的信息词，并带有 `PUBLIC_PAGE_CONTENT_READY` 或现有验证通过信号。
- 次级成功：主页面受限但已尝试同域公开入口、sitemap/robots、canonical/OG/JSON-LD 链接、字段证据路径 query 和可信搜索摘要，最终得到 `PARTIAL_PUBLIC_EVIDENCE`，且 metadata 中记录 `attemptedAlternativeUrls`、`attemptedEvidencePaths` 与 `sourceUrls`。
- 失败但可诊断：所有公开替代入口均失败时，才允许输出 `PUBLIC_SHELL_ONLY` / `LOGIN_GATE_PARTIAL` / `ANTI_BOT_PARTIAL`，并明确写入“已尝试替代入口但未得到有效公开正文”；如果失败发生在字段证据路径上，还必须记录 `fieldName / evidencePathKey / attemptedQueries / recoveryStatus`。

本计划执行约束：

- 直接在当前工作区修改，不自动提交 git commit。
- 所有业务逻辑、核心方法、复杂条件判断必须保留中文注释。
- 所有 `sourceUrls` 必须保留，登录/验证码恢复产生的证据也必须回指原始 URL。
- 修改外部抓取、浏览器或搜索调用时必须保留 try-catch 和有限重试边界。

---

## 1. 文件结构

- 修改： `backend/src/main/java/cn/bugstack/competitoragent/search/CollectionTargetSelector.java`
  - 收紧最终 selectedTargets 入选门槛，防止未验证的搜索中介页进入正式采集。
- 修改： `backend/src/test/java/cn/bugstack/competitoragent/search/CollectionTargetSelectorTest.java`
  - 覆盖未验证百度/企业信息中介页不入选、登录公开壳只在安全条件下低优先入选。
- 修改： `backend/src/main/java/cn/bugstack/competitoragent/search/CandidateOwnershipPolicy.java`
  - 泛化中介页、搜索认证页、企业信息页、百科页、登录/验证码工具页判断。
- 新建： `backend/src/test/java/cn/bugstack/competitoragent/search/CandidateOwnershipPolicyTest.java`
  - 覆盖爱企查、企查查、天眼查、百度认证页、登录/验证码页。
- 新建： `backend/src/main/java/cn/bugstack/competitoragent/search/PublicEvidenceRecoveryService.java`
  - 当主候选被登录/验证码/反爬/低信号挡住，或字段证据路径未覆盖时，按 `RecoveryContext` 生成同域公开替代入口、解析 canonical/OG/JSON-LD 链接、复用 sitemap/robots 入口，并输出可验证的补采候选。
- 新建： `backend/src/test/java/cn/bugstack/competitoragent/search/PublicEvidenceRecoveryServiceTest.java`
  - 覆盖受限首页生成 `/about`、`/help`、`/download`、`/app` 等公开入口、定价字段路径优先生成 `/pricing`、`/billing`、过滤跨域中介页、记录 `sourceUrls`、`fieldName`、`evidencePathKey`、`attemptedAlternativeUrls` 和 `attemptedEvidencePaths`。
- 修改： `backend/src/main/java/cn/bugstack/competitoragent/search/SitemapDiscoveryService.java`
  - 扩展高价值路径关键词，支持 about、download、product、features、support、openplatform、creator、app 等公开产品信息入口。
- 修改： `backend/src/test/java/cn/bugstack/competitoragent/search/SitemapDiscoveryServiceTest.java`
  - 覆盖 sitemap 中的 `/download`、`/about`、`/app` 等路径可以进入候选池。
- 修改： `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java`
  - 在候选验证不足且出现受限/低信号页面时调用有效公开信息补采，再复用 `CandidateVerifier` 验证补采候选。
- 修改： `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionTrace.java`
  - 记录有效公开信息补采是否触发、尝试 URL、有效候选数量和降级决策。
- 修改： `backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinatorTest.java`
  - 覆盖主入口受限时会先补采同域公开页面，只有补采失败才进入公开壳降级。
- 新建： `backend/src/main/java/cn/bugstack/competitoragent/source/PublicShellRecoveryExtractor.java`
  - 从登录/验证码/反爬页面中恢复公开可见壳信息，仅作为有效公开信息补采失败后的最后兜底。
- 新建： `backend/src/test/java/cn/bugstack/competitoragent/source/PublicShellRecoveryExtractorTest.java`
  - 覆盖 meta/og/canonical/json-ld/正文片段恢复与低价值验证码页面拒绝。
- 修改： `backend/src/main/java/cn/bugstack/competitoragent/source/PlaywrightPageCollector.java`
  - 在 blocked 分支调用公开壳恢复，但 metadata 必须标明这是 `PUBLIC_SHELL_ONLY`，不得伪装成有效正文。
- 修改： `backend/src/test/java/cn/bugstack/competitoragent/source/PlaywrightPageCollectorTest.java`
  - 覆盖登录页公开壳恢复与纯验证码页仍失败。
- 新建： `backend/src/main/java/cn/bugstack/competitoragent/agent/collector/EvidenceSourceSanitizer.java`
  - 证据落库前统一裁剪长度受限字段并保留 `sourceUrls`。
- 新建： `backend/src/test/java/cn/bugstack/competitoragent/agent/collector/EvidenceSourceSanitizerTest.java`
  - 覆盖 `title/url/sourceDomain/publishedAt` 裁剪、`discoveryReason` 不再因超长导致落库失败。
- 修改： `backend/src/main/java/cn/bugstack/competitoragent/agent/collector/CollectorAgent.java`
  - 保存 EvidenceSource 前调用 sanitizer，并在保存失败时保留 collection audit 诊断。
- 修改： `backend/src/test/java/cn/bugstack/competitoragent/agent/collector/CollectorAgentTest.java`
  - 覆盖长 discoveryReason、保存失败诊断、sourceUrls 留存。
- 修改： `backend/src/main/java/cn/bugstack/competitoragent/model/entity/EvidenceSource.java`
  - 将 `discoveryReason` 从 `length = 500` 迁移为 `TEXT` 映射。
- 新建： `backend/src/main/resources/db/migration/V28__expand_evidence_source_discovery_reason.sql`
  - 将数据库字段 `evidence_source.discovery_reason` 改为 `TEXT`。
- 新建： `backend/src/test/java/cn/bugstack/competitoragent/db/EvidenceSourceMigrationArtifactsTest.java`
  - 确认 V28 迁移文件存在并包含字段扩容语句。
- 修改： `docs/superpowers/ExtractionStructured/problem/ExtractionStructured.md`
  - 实施完成后回写搜索采集加固状态。
- 新建： `docs/Tavily/progress/2026-06-28-search-collection-public-evidence-recovery-progress.md`
  - 记录每个任务的执行结果、测试命令和 live 复验结论。

---

## 2. 执行看板

| Task | 目标 | 状态 |
| --- | --- | --- |
| Task 1 | 收紧 selectedTargets 入选门槛 | 待执行 |
| Task 2 | 泛化中介页、登录页、验证码页识别 | 待执行 |
| Task 3 | 受限页后的有效公开信息补采 | 待执行 |
| Task 4 | 登录/验证码公开壳信息兜底 | 待执行 |
| Task 5 | EvidenceSource 落库安全化与 Flyway 扩容 | 待执行 |
| Task 6 | 采集失败诊断留存 | 待执行 |
| Task 7 | 回归测试与 live 复验记录 | 待执行 |

---

### Task 1: 收紧 selectedTargets 入选门槛

**文件：**

- 修改： `backend/src/main/java/cn/bugstack/competitoragent/search/CollectionTargetSelector.java`
- 修改： `backend/src/test/java/cn/bugstack/competitoragent/search/CollectionTargetSelectorTest.java`

**规则：**

正式采集目标只能来自以下来源：

- `verified=true` 且不是中介页。
- 用户显式提供或规划期直达候选，并且验证阶段已经拿到可用公开页面内容。
- 用户显式提供或规划期直达候选，并且页面被登录/验证码挡住但公开壳恢复成功，候选带有 `PUBLIC_SHELL_ONLY`、`LOGIN_GATE_PARTIAL` 或 `ANTI_BOT_PARTIAL` 质量信号。

未验证的浏览器补源、搜索认证页、企业信息页、百科页、登录工具页不能进入 selectedTargets，只能进入 discardedCandidates 并保留原因。

- [ ] **步骤 1：写未验证中介页不入选的失败测试**

追加到 `CollectionTargetSelectorTest`：

```java
@Test
void shouldNotSelectUnverifiedSearchMediatorWhenSupplementVerificationSkipped() {
    SourceCandidate plannedCandidate = SourceCandidate.builder()
            .url("https://app.bilibili.com")
            .title("哔哩哔哩下载中心")
            .sourceType("OFFICIAL")
            .discoveryMethod("DIRECT_LOCATOR")
            .verified(Boolean.FALSE)
            .selectionStage("DISCARDED")
            .verificationReason("页面已打开，但未命中 OFFICIAL 所需特征")
            .totalScore(0.87)
            .build();
    SourceCandidate baiduMediator = SourceCandidate.builder()
            .url("https://aiqicha.baidu.com/feedback/official?from=baidu&type=gw")
            .title("官网认证")
            .sourceType("OFFICIAL")
            .discoveryMethod("BROWSER")
            .providerKey("browser")
            .domain("aiqicha.baidu.com")
            .reason("浏览器搜索命中百度官网认证页，正文摘要包含官网认证增值服务说明")
            .verified(null)
            .selectionStage("SUPPLEMENTED")
            .totalScore(0.84)
            .build();

    SearchSelectionDecision decision = selector.selectTargets(
            List.of(plannedCandidate, baiduMediator),
            Map.of(),
            1
    );

    assertTrue(decision.getSelectedTargets().isEmpty());
    assertEquals(List.of("https://aiqicha.baidu.com/feedback/official?from=baidu&type=gw"),
            decision.getDiscardedCandidates().stream().map(SourceCandidate::getUrl).toList());
    assertTrue(decision.getDiscardedCandidates().get(0).getSelectionReason().contains("未验证"));
}
```

- [ ] **步骤 2：运行测试确认当前失败**

运行：

```powershell
mvn -pl backend -Dtest=CollectionTargetSelectorTest#shouldNotSelectUnverifiedSearchMediatorWhenSupplementVerificationSkipped test
```

实现前预期：

```text
FAIL: selectedTargets size expected <0> but was <1>
```

- [ ] **步骤 3：写登录公开壳可低优先入选的测试**

追加到 `CollectionTargetSelectorTest`：

```java
@Test
void shouldAllowExplicitCandidateWithRecoveredPublicShellWhenNoVerifiedTargetExists() {
    SourceCandidate loginGateCandidate = SourceCandidate.builder()
            .url("https://docs.example.com/login")
            .title("Example Docs Login")
            .sourceType("DOCS")
            .discoveryMethod("DIRECT_LOCATOR")
            .providerKey("planned")
            .sourceUrls(List.of("https://docs.example.com/login"))
            .qualitySignals(List.of("LOGIN_GATE_PARTIAL", "PUBLIC_SHELL_ONLY"))
            .selectionStage("PARTIAL_PUBLIC_SHELL")
            .verified(Boolean.FALSE)
            .totalScore(0.42)
            .build();

    Map<String, SearchCollectionTarget> attemptedTargets = new LinkedHashMap<>();
    attemptedTargets.put(loginGateCandidate.getUrl(), SearchCollectionTarget.builder()
            .candidate(loginGateCandidate)
            .collectedPage(SourceCollector.CollectedPage.builder()
                    .url(loginGateCandidate.getUrl())
                    .title("Example Docs Login")
                    .content("Example Docs public shell. Product documentation login page.")
                    .snippet("Example Docs public shell")
                    .metadata("{\"qualitySignals\":[\"LOGIN_GATE_PARTIAL\",\"PUBLIC_SHELL_ONLY\"]}")
                    .success(true)
                    .build())
            .build());

    SearchSelectionDecision decision = selector.selectTargets(
            List.of(loginGateCandidate),
            attemptedTargets,
            1
    );

    assertEquals(1, decision.getSelectedTargets().size());
    assertEquals("https://docs.example.com/login",
            decision.getSelectedTargets().get(0).getCandidate().getUrl());
    assertTrue(decision.getSelectedTargets().get(0).getCandidate().getSelectionSummary()
            .contains("公开壳信息"));
}
```

- [ ] **步骤 4：修改 `CollectionTargetSelector` 加入入选资格判断**

修改 `CollectionTargetSelector.java`：

```java
private final CandidateOwnershipPolicy candidateOwnershipPolicy;

public CollectionTargetSelector() {
    this(new CanonicalUrlResolver(), new CandidateOwnershipPolicy());
}

public CollectionTargetSelector(CanonicalUrlResolver canonicalUrlResolver) {
    this(canonicalUrlResolver, new CandidateOwnershipPolicy());
}

public CollectionTargetSelector(CanonicalUrlResolver canonicalUrlResolver,
                                CandidateOwnershipPolicy candidateOwnershipPolicy) {
    this.canonicalUrlResolver = canonicalUrlResolver;
    this.candidateOwnershipPolicy = candidateOwnershipPolicy == null
            ? new CandidateOwnershipPolicy()
            : candidateOwnershipPolicy;
}
```

Add the eligibility record and helper:

```java
private record SelectionEligibility(boolean selectable, String reason, String summary) {
}

/**
 * 最终选源必须避免把未验证搜索结果、中介页或登录工具页提升为正式证据。
 * 只有验证通过、显式输入且已有可用公开内容、或显式输入且公开壳恢复成功的候选可以入选。
 */
private SelectionEligibility resolveEligibility(SourceCandidate candidate,
                                                Map<String, SearchCollectionTarget> attemptedTargets) {
    if (candidate == null || !StringUtils.hasText(candidate.getUrl())) {
        return new SelectionEligibility(false, "候选 URL 为空，不能进入正式采集", "候选 URL 为空");
    }
    if ("DISCARDED".equalsIgnoreCase(candidate.getSelectionStage())) {
        return new SelectionEligibility(false, firstNonBlank(candidate.getSelectionReason(),
                "候选已在验证或排序阶段被丢弃"), "候选已被丢弃");
    }
    if (candidateOwnershipPolicy.isRejectedMediator(candidate, null)) {
        return new SelectionEligibility(false, "命中搜索认证、企业信息或百科中介页，不能作为正式证据",
                "中介页仅保留为诊断候选");
    }
    if (Boolean.TRUE.equals(candidate.getVerified())) {
        return new SelectionEligibility(true, "运行期验证通过后被选为正式采集目标",
                "运行期验证通过后被选为正式采集目标");
    }

    SearchCollectionTarget attemptedTarget = attemptedTargets.get(normalizeUrl(candidate.getUrl()));
    if (isExplicitCandidate(candidate) && hasUsableCollectedPage(attemptedTarget)) {
        if (hasPublicShellSignal(candidate, attemptedTarget)) {
            return new SelectionEligibility(true, "显式候选已恢复公开壳信息，作为降级证据进入采集",
                    "显式候选已恢复公开壳信息，后续报告需标记为低置信公开壳证据");
        }
        return new SelectionEligibility(true, "显式候选已在验证阶段取得可用公开正文，允许正式采集",
                "显式候选已取得可用公开正文");
    }

    return new SelectionEligibility(false, "未验证候选不能进入正式采集目标",
            "未验证候选仅保留为诊断候选");
}

private boolean isExplicitCandidate(SourceCandidate candidate) {
    String method = candidate.getDiscoveryMethod();
    String provider = candidate.getProviderKey();
    return equalsAny(method, "DIRECT_LOCATOR", "FAMILY_TEMPLATE", "FAMILY_SUBDOMAIN_TEMPLATE", "HEURISTIC")
            || equalsAny(provider, "planned");
}

private boolean hasUsableCollectedPage(SearchCollectionTarget target) {
    if (target == null || target.getCollectedPage() == null || !target.getCollectedPage().isSuccess()) {
        return false;
    }
    SourceCollector.CollectedPage page = target.getCollectedPage();
    return StringUtils.hasText(page.getContent()) || StringUtils.hasText(page.getSnippet());
}

private boolean hasPublicShellSignal(SourceCandidate candidate, SearchCollectionTarget target) {
    List<String> signals = new ArrayList<>();
    if (candidate.getQualitySignals() != null) {
        signals.addAll(candidate.getQualitySignals());
    }
    String metadata = target == null || target.getCollectedPage() == null ? "" : target.getCollectedPage().getMetadata();
    String joined = String.join(",", signals).toUpperCase(Locale.ROOT) + "," + metadata.toUpperCase(Locale.ROOT);
    return joined.contains("PUBLIC_SHELL_ONLY")
            || joined.contains("LOGIN_GATE_PARTIAL")
            || joined.contains("ANTI_BOT_PARTIAL");
}

private boolean equalsAny(String value, String... candidates) {
    if (!StringUtils.hasText(value)) {
        return false;
    }
    for (String candidate : candidates) {
        if (value.equalsIgnoreCase(candidate)) {
            return true;
        }
    }
    return false;
}

private String firstNonBlank(String primary, String fallback) {
    return StringUtils.hasText(primary) ? primary : fallback;
}
```

更新 selection loop，让不符合资格的候选被标记为 discarded：

```java
List<SourceCandidate> rejectedByEligibility = new ArrayList<>();
for (SourceCandidate candidate : rankedCandidates) {
    String normalizedUrl = normalizeUrl(candidate.getUrl());
    SelectionEligibility eligibility = resolveEligibility(candidate, normalizedAttemptedTargets);
    if (!eligibility.selectable()) {
        rejectedByEligibility.add(candidate.toBuilder()
                .selectionStage("DISCARDED")
                .selectionReason(eligibility.reason())
                .selectionSummary(eligibility.summary())
                .build());
        continue;
    }
    if (!StringUtils.hasText(normalizedUrl) || !selectedUrls.add(normalizedUrl)) {
        continue;
    }
    SearchCollectionTarget target = normalizedAttemptedTargets.getOrDefault(
            normalizedUrl,
            SearchCollectionTarget.builder().candidate(candidate).build()
    );
    selectedTargets.add(target);
    if (selectedTargets.size() >= targetCount) {
        break;
    }
}
```

构造 `updatedCandidates` 时优先使用 rejected copy：

```java
Map<String, SourceCandidate> rejectedByUrl = indexCandidatesByNormalizedUrl(rejectedByEligibility);
List<SourceCandidate> updatedCandidates = candidates.stream()
        .map(candidate -> {
            SourceCandidate rejected = rejectedByUrl.get(normalizeUrl(candidate == null ? null : candidate.getUrl()));
            return rejected == null ? applySelectionResult(candidate, selectedUrls) : rejected;
        })
        .toList();
```

更新 `applySelectionResult` summary：

```java
String selectedReason = Boolean.TRUE.equals(candidate.getVerified())
        ? "运行期验证通过后被选为正式采集目标"
        : "显式候选已有可用公开信息，作为降级证据进入正式采集";
String selectedSummary = Boolean.TRUE.equals(candidate.getVerified())
        ? "运行期验证通过后被选为正式采集目标"
        : "显式候选公开壳信息可用，报告层需按低置信证据处理";
```

- [ ] **步骤 5：运行 Task 1 测试**

运行：

```powershell
mvn -pl backend -Dtest=CollectionTargetSelectorTest test
```

预期：

```text
BUILD SUCCESS
```

---

### Task 2: 泛化中介页、登录页、验证码页识别

**文件：**

- 修改： `backend/src/main/java/cn/bugstack/competitoragent/search/CandidateOwnershipPolicy.java`
- 新建： `backend/src/test/java/cn/bugstack/competitoragent/search/CandidateOwnershipPolicyTest.java`

- [ ] **步骤 1：新建中介页与工具页识别测试**

创建 `CandidateOwnershipPolicyTest.java`：

```java
package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.source.SourceCandidate;
import cn.bugstack.competitoragent.source.SourceCollector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CandidateOwnershipPolicyTest {

    private final CandidateOwnershipPolicy policy = new CandidateOwnershipPolicy();

    @Test
    void shouldRejectSearchCertificationMediatorPages() {
        SourceCandidate candidate = SourceCandidate.builder()
                .url("https://aiqicha.baidu.com/feedback/official?from=baidu&type=gw")
                .domain("aiqicha.baidu.com")
                .title("官网认证")
                .reason("官网认证是百度对网站在强关联关系触发词下展示官方标识的增值服务认证")
                .sourceType("OFFICIAL")
                .discoveryMethod("BROWSER")
                .build();

        assertTrue(policy.isRejectedMediator(candidate, null));
    }

    @Test
    void shouldRejectEnterpriseInformationPages() {
        SourceCandidate candidate = SourceCandidate.builder()
                .url("https://www.qcc.com/firm/example.html")
                .domain("www.qcc.com")
                .title("某公司企业信息")
                .reason("企业工商信息、股东信息、风险信息")
                .sourceType("OFFICIAL")
                .discoveryMethod("BROWSER")
                .build();

        assertTrue(policy.isRejectedMediator(candidate, null));
    }

    @Test
    void shouldRejectLoginAndCaptchaUtilityPagesAsFormalEvidence() {
        SourceCandidate loginCandidate = SourceCandidate.builder()
                .url("https://example.com/login")
                .domain("example.com")
                .title("Login")
                .sourceType("DOCS")
                .discoveryMethod("SEARCH")
                .build();
        SourceCollector.CollectedPage captchaPage = SourceCollector.CollectedPage.builder()
                .url("https://example.com/challenge")
                .title("Verify you are human")
                .content("captcha security check")
                .success(true)
                .build();

        assertTrue(policy.isUtilityGatePage(loginCandidate, null));
        assertTrue(policy.isUtilityGatePage(null, captchaPage));
    }

    @Test
    void shouldKeepDirectOfficialDomainWhenOwnershipSignalMatches() {
        SourceCandidate candidate = SourceCandidate.builder()
                .url("https://app.bilibili.com")
                .domain("app.bilibili.com")
                .title("哔哩哔哩下载中心")
                .sourceType("OFFICIAL")
                .discoveryMethod("DIRECT_LOCATOR")
                .build();

        assertFalse(policy.isRejectedMediator(candidate, null));
        assertTrue(policy.hasCompetitorOwnershipSignal("哔哩哔哩", candidate, null));
    }
}
```

- [ ] **步骤 2：运行测试确认新方法缺失或行为失败**

运行：

```powershell
mvn -pl backend -Dtest=CandidateOwnershipPolicyTest test
```

Expected before implementation:

```text
FAIL or compilation error around isUtilityGatePage
```

- [ ] **步骤 3：修改 `CandidateOwnershipPolicy`**

新增 mediator domain keywords：

```java
private static final List<String> MEDIATOR_DOMAINS = List.of(
        "aiqicha.baidu.com",
        "baike.baidu.com",
        "baike.sogou.com",
        "qcc.com",
        "tianyancha.com",
        "qixin.com",
        "企查查",
        "天眼查",
        "爱企查"
);

private static final List<String> UTILITY_GATE_PATH_KEYWORDS = List.of(
        "/login",
        "/signin",
        "/sign-in",
        "/passport",
        "/account",
        "/captcha",
        "/challenge",
        "/verify"
);

private static final List<String> UTILITY_GATE_TEXT_KEYWORDS = List.of(
        "login",
        "sign in",
        "verify you are human",
        "captcha",
        "security check",
        "登录",
        "验证码",
        "安全验证",
        "请先登录"
);
```

新增方法：

```java
/**
 * 登录、验证码、人机验证等工具页不能直接作为正式证据。
 * 如果后续公开壳恢复成功，只能由 CollectionTargetSelector 按降级证据规则放行显式候选。
 */
public boolean isUtilityGatePage(SourceCandidate candidate, SourceCollector.CollectedPage page) {
    String url = firstText(candidate == null ? null : candidate.getUrl(), page == null ? null : page.getUrl());
    String path = normalizePath(url);
    String text = compact(candidateText(candidate) + "\n" + pageText(page));
    return containsAny(path, UTILITY_GATE_PATH_KEYWORDS)
            || containsAny(text, UTILITY_GATE_TEXT_KEYWORDS);
}
```

更新 `isRejectedMediator`：

```java
public boolean isRejectedMediator(SourceCandidate candidate, SourceCollector.CollectedPage page) {
    String url = firstText(candidate == null ? null : candidate.getUrl(), page == null ? null : page.getUrl());
    String domain = normalizeDomain(firstText(candidate == null ? null : candidate.getDomain(), extractDomain(url)));
    String path = normalizePath(url);
    String text = compact(candidateText(candidate) + "\n" + pageText(page));
    boolean mediatorDomain = containsAnyDomain(domain, MEDIATOR_DOMAINS);
    boolean mediatorPath = containsAny(path, MEDIATOR_PATH_KEYWORDS);
    boolean mediatorText = containsAny(text, MEDIATOR_TEXT_KEYWORDS);
    return mediatorDomain && (mediatorPath || mediatorText);
}
```

- [ ] **步骤 4：运行 Task 2 测试**

运行：

```powershell
mvn -pl backend -Dtest=CandidateOwnershipPolicyTest test
```

预期：

```text
BUILD SUCCESS
```

---

### Task 3: 受限页后的有效公开信息补采

**文件：**

- 新建： `backend/src/main/java/cn/bugstack/competitoragent/search/PublicEvidenceRecoveryService.java`
- 新建： `backend/src/test/java/cn/bugstack/competitoragent/search/PublicEvidenceRecoveryServiceTest.java`
- 修改： `backend/src/main/java/cn/bugstack/competitoragent/search/SitemapDiscoveryService.java`
- 修改： `backend/src/test/java/cn/bugstack/competitoragent/search/SitemapDiscoveryServiceTest.java`
- 修改： `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinator.java`
- 修改： `backend/src/main/java/cn/bugstack/competitoragent/search/SearchExecutionTrace.java`
- 修改： `backend/src/test/java/cn/bugstack/competitoragent/search/SearchExecutionCoordinatorTest.java`

**规则：**

当规划期候选、用户显式 URL 或字段证据路径被登录、验证码、反爬、正文过短挡住时，系统不能立即停在 `PUBLIC_SHELL_ONLY`。必须先生成一批可公开访问的替代入口，并复用现有 `CandidateVerifier` 验证这些入口是否能产出有效正文。补采服务必须优先使用 `RecoveryContext(fieldName, evidencePathKey, queryIntents)`。迁移期可保留旧 sourceType-only 签名，但它必须标记为 `@Deprecated`，并且只能作为 legacy fallback；新链路不得直接调用旧签名。

补采优先级：

- 同域或官方子域入口优先，例如 `/about`、`/help`、`/docs`、`/download`、`/product`、`/features`、`/support`、`/openplatform`、`/creator`、`/app`。
- 字段证据路径优先于泛入口。例如 `fieldName=pricing` 且 `evidencePathKey=DOCS_BILLING_OR_LIMITS` 时，应优先生成 `/pricing`、`/plans`、`/billing`、`/docs`、`/help`、协议/计费说明等候选，而不是只补 `/about`。
- robots/sitemap 暴露的高价值入口优先于搜索摘要。
- canonical、OpenGraph、JSON-LD 中暴露的同域 URL 可以作为补采候选。
- 可信搜索摘要只能作为 `PARTIAL_PUBLIC_EVIDENCE`，不能替代同域正文证据。
- 爱企查、企查查、天眼查、百度认证、百科等中介页永远不能作为正式补采证据。

补采预算：

- 每个根域最多生成 12 个候选。
- 默认只补采深度 1 的同域 URL。
- 每个 sourceType 最多验证 4 个补采候选。
- 每个 field evidence path 最多验证 4 个补采候选，字段路径预算优先于 sourceType 预算。
- 已有验证通过候选达到 `minVerifiedCandidates` 时跳过补采。
- 超时或网络异常时保留审计并降级，不阻断主链路。
- 如果旧签名被调用，必须在 trace 或候选质量信号中记录 `FIELD_CONTEXT_MISSING_LEGACY_RECOVERY`，防止误判为字段路径补采已经执行。

- [ ] **步骤 1：写受限首页生成同域公开候选的失败测试**

创建 `PublicEvidenceRecoveryServiceTest.java`：

```java
package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.source.SourceCandidate;
import cn.bugstack.competitoragent.source.SourceCollector;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicEvidenceRecoveryServiceTest {

    private final PublicEvidenceRecoveryService recoveryService = new PublicEvidenceRecoveryService(
            new CanonicalUrlResolver(),
            new CandidateOwnershipPolicy()
    );

    @Test
    void shouldGenerateSameDomainPublicAlternativesWhenOfficialHomeIsBlocked() {
        SourceCandidate blockedHome = SourceCandidate.builder()
                .url("https://www.example.com")
                .title("Example")
                .sourceType("OFFICIAL")
                .discoveryMethod("DIRECT_LOCATOR")
                .domain("www.example.com")
                .verified(Boolean.FALSE)
                .selectionStage("DISCARDED")
                .verificationReason("页面命中验证码或登录拦截，正文不足")
                .sourceUrls(List.of("https://www.example.com"))
                .qualitySignals(List.of("ANTI_BOT_PARTIAL"))
                .build();
        SearchCollectionTarget blockedTarget = SearchCollectionTarget.builder()
                .candidate(blockedHome)
                .collectedPage(SourceCollector.CollectedPage.builder()
                        .url("https://www.example.com")
                        .title("Example Security Check")
                        .content("captcha security check")
                        .snippet("captcha")
                        .success(false)
                        .errorMessage("ANTI_BOT_BLOCKED")
                        .build())
                .build();

        PublicEvidenceRecoveryService.RecoveryContext recoveryContext = PublicEvidenceRecoveryService.RecoveryContext.builder()
                .competitorName("Example")
                .sourceType("OFFICIAL")
                .fieldName("summary")
                .evidencePathKey("OFFICIAL_PUBLIC_PROFILE")
                .queryIntents(List.of("OFFICIAL_PROFILE"))
                .maxAlternatives(12)
                .build();

        PublicEvidenceRecoveryService.RecoveryPlan plan = recoveryService.planRecovery(
                recoveryContext,
                List.of(blockedHome),
                List.of(blockedTarget)
        );

        assertTrue(plan.triggered());
        assertTrue(plan.reason().contains("受限"));
        assertTrue(plan.candidates().stream().anyMatch(candidate -> "https://www.example.com/about".equals(candidate.getUrl())));
        assertTrue(plan.candidates().stream().anyMatch(candidate -> "https://www.example.com/help".equals(candidate.getUrl())));
        assertTrue(plan.candidates().stream().anyMatch(candidate -> "https://www.example.com/download".equals(candidate.getUrl())));
        assertTrue(plan.candidates().stream().allMatch(candidate -> candidate.getSourceUrls().contains("https://www.example.com")));
        assertTrue(plan.candidates().stream().allMatch(candidate -> candidate.getQualitySignals().contains("PUBLIC_EVIDENCE_RECOVERY_CANDIDATE")));
        assertEquals("summary", plan.fieldName());
        assertEquals("OFFICIAL_PUBLIC_PROFILE", plan.evidencePathKey());
    }

    @Test
    void shouldPreferPricingEvidencePathAlternativesWhenPricingPathIsRequested() {
        SourceCandidate weakDocs = SourceCandidate.builder()
                .url("https://open.example.com")
                .title("Example Open Platform")
                .sourceType("DOCS")
                .discoveryMethod("DIRECT_LOCATOR")
                .domain("open.example.com")
                .verified(Boolean.FALSE)
                .verificationReason("开放平台入口页只有导航，没有计费正文")
                .sourceUrls(List.of("https://open.example.com"))
                .qualitySignals(List.of("ROOT_ENTRY_WEAK_CONTENT"))
                .build();

        PublicEvidenceRecoveryService.RecoveryContext recoveryContext = PublicEvidenceRecoveryService.RecoveryContext.builder()
                .competitorName("Example")
                .sourceType("DOCS")
                .fieldName("pricing")
                .evidencePathKey("DOCS_BILLING_OR_LIMITS")
                .queryIntents(List.of("DOCS_BILLING", "API_LIMITS"))
                .maxAlternatives(12)
                .build();

        PublicEvidenceRecoveryService.RecoveryPlan plan = recoveryService.planRecovery(
                recoveryContext,
                List.of(weakDocs),
                List.of()
        );

        assertTrue(plan.triggered());
        assertTrue(plan.candidates().stream().anyMatch(candidate -> candidate.getUrl().contains("/pricing")));
        assertTrue(plan.candidates().stream().anyMatch(candidate -> candidate.getUrl().contains("/billing")));
        assertTrue(plan.candidates().stream().allMatch(candidate -> candidate.getQualitySignals().contains("FIELD_EVIDENCE_PATH_RECOVERY")));
    }

    @Test
    void shouldIgnoreMediatorSeedBeforeGeneratingAlternatives() {
        SourceCandidate mediator = SourceCandidate.builder()
                .url("https://aiqicha.baidu.com/feedback/official?from=baidu&type=gw")
                .title("官网认证")
                .sourceType("OFFICIAL")
                .discoveryMethod("BROWSER")
                .providerKey("browser")
                .domain("aiqicha.baidu.com")
                .reason("百度官网认证中介页")
                .verified(Boolean.FALSE)
                .verificationReason("搜索命中百度官网认证中介页，不能证明竞品官网归属")
                .sourceUrls(List.of("https://www.example.com"))
                .build();

        PublicEvidenceRecoveryService.RecoveryPlan plan = recoveryService.planRecovery(
                PublicEvidenceRecoveryService.RecoveryContext.builder()
                        .competitorName("Example")
                        .sourceType("OFFICIAL")
                        .fieldName("summary")
                        .evidencePathKey("OFFICIAL_PUBLIC_PROFILE")
                        .queryIntents(List.of("OFFICIAL_PROFILE"))
                        .maxAlternatives(12)
                        .build(),
                List.of(mediator),
                List.of()
        );

        assertFalse(plan.triggered());
        assertEquals(0, plan.candidates().size());
        assertTrue(plan.discardedAlternativeUrls().stream().anyMatch(url -> url.contains("aiqicha.baidu.com")));
    }

    @Test
    void shouldSkipRecoveryWhenThereIsAlreadyVerifiedContent() {
        SourceCandidate verified = SourceCandidate.builder()
                .url("https://docs.example.com/guide")
                .title("Example Docs")
                .sourceType("DOCS")
                .verified(Boolean.TRUE)
                .selectionStage("VERIFIED")
                .sourceUrls(List.of("https://docs.example.com/guide"))
                .build();
        SearchCollectionTarget verifiedTarget = SearchCollectionTarget.builder()
                .candidate(verified)
                .collectedPage(SourceCollector.CollectedPage.builder()
                        .url("https://docs.example.com/guide")
                        .content("documentation api reference guide")
                        .success(true)
                        .build())
                .build();

        PublicEvidenceRecoveryService.RecoveryPlan plan = recoveryService.planRecovery(
                PublicEvidenceRecoveryService.RecoveryContext.builder()
                        .competitorName("Example")
                        .sourceType("DOCS")
                        .fieldName("coreFeatures")
                        .evidencePathKey("DOCS_API_GUIDE")
                        .queryIntents(List.of("API_DOCS", "SDK_GUIDE"))
                        .maxAlternatives(12)
                        .build(),
                List.of(verified),
                List.of(verifiedTarget)
        );

        assertFalse(plan.triggered());
        assertEquals(0, plan.candidates().size());
    }

    @Test
    void legacySourceTypeOnlyRecoveryShouldBeDeprecatedAndAuditable() throws Exception {
        assertTrue(PublicEvidenceRecoveryService.class
                .getMethod("planRecovery", String.class, String.class, List.class, List.class, int.class)
                .isAnnotationPresent(Deprecated.class));

        PublicEvidenceRecoveryService.RecoveryPlan plan = recoveryService.planRecovery(
                "Example",
                "OFFICIAL",
                List.of(SourceCandidate.builder()
                        .url("https://www.example.com")
                        .sourceType("OFFICIAL")
                        .verified(Boolean.FALSE)
                        .verificationReason("正文不足")
                        .qualitySignals(List.of("PUBLIC_SHELL_ONLY"))
                        .build()),
                List.of(),
                12
        );

        assertTrue(plan.triggered());
        assertTrue(plan.candidates().stream()
                .allMatch(candidate -> candidate.getQualitySignals().contains("FIELD_CONTEXT_MISSING_LEGACY_RECOVERY")));
    }
}
```

- [ ] **步骤 2：运行测试确认类不存在**

运行：

```powershell
mvn -pl backend -Dtest=PublicEvidenceRecoveryServiceTest test
```

Expected before implementation:

```text
Compilation failure: cannot find symbol PublicEvidenceRecoveryService
```

- [ ] **步骤 3：创建 `PublicEvidenceRecoveryService`**

创建 `PublicEvidenceRecoveryService.java`：

```java
package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.source.SourceCandidate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 受限页后的有效公开信息补采规划器。
 * 该类只生成公开可访问的同域/官方子域候选，不提交登录、不破解验证码、不使用授权态 cookie。
 * 真正是否可作为正式证据，仍交给 CandidateVerifier 和 CollectionTargetSelector 判断。
 */
@Component
public class PublicEvidenceRecoveryService {

    private static final List<String> OFFICIAL_PUBLIC_PATHS = List.of(
            "/about", "/help", "/download", "/app", "/product", "/features", "/support", "/openplatform", "/creator"
    );
    private static final List<String> DOCS_PUBLIC_PATHS = List.of(
            "/docs", "/doc", "/help", "/support", "/developer", "/openplatform", "/api"
    );
    private static final List<String> PRICING_PUBLIC_PATHS = List.of(
            "/pricing", "/plans", "/billing", "/docs", "/help", "/support", "/terms", "/agreement", "/openplatform"
    );
    private static final int DEFAULT_LIMIT = 12;

    private final CanonicalUrlResolver canonicalUrlResolver;
    private final CandidateOwnershipPolicy candidateOwnershipPolicy;

    public PublicEvidenceRecoveryService() {
        this(new CanonicalUrlResolver(), new CandidateOwnershipPolicy());
    }

    public PublicEvidenceRecoveryService(CanonicalUrlResolver canonicalUrlResolver,
                                         CandidateOwnershipPolicy candidateOwnershipPolicy) {
        this.canonicalUrlResolver = canonicalUrlResolver == null ? new CanonicalUrlResolver() : canonicalUrlResolver;
        this.candidateOwnershipPolicy = candidateOwnershipPolicy == null ? new CandidateOwnershipPolicy() : candidateOwnershipPolicy;
    }

    public RecoveryPlan planRecovery(RecoveryContext context,
                                     List<SourceCandidate> candidates,
                                     List<SearchCollectionTarget> attemptedTargets) {
        RecoveryContext recoveryContext = context == null ? RecoveryContext.builder().build() : context;
        if (hasVerifiedUsableTarget(attemptedTargets)) {
            return emptyPlan(recoveryContext, "已有验证通过的有效公开正文，跳过补采", List.of());
        }
        SeedSelection seedSelection = findBlockedSeeds(candidates, attemptedTargets);
        List<SourceCandidate> blockedSeeds = seedSelection.seeds();
        if (blockedSeeds.isEmpty()) {
            return emptyPlan(recoveryContext, "未发现可信登录/验证码/低信号种子页，跳过补采",
                    seedSelection.discardedSeedUrls());
        }

        int limit = recoveryContext.maxAlternatives() <= 0 ? DEFAULT_LIMIT : recoveryContext.maxAlternatives();
        LinkedHashSet<String> attemptedAlternativeUrls = new LinkedHashSet<>();
        LinkedHashSet<String> discardedAlternativeUrls = new LinkedHashSet<>();
        List<SourceCandidate> recoveryCandidates = new ArrayList<>();
        for (SourceCandidate seed : blockedSeeds) {
            for (String alternativeUrl : buildSameDomainAlternatives(seed, recoveryContext)) {
                attemptedAlternativeUrls.add(alternativeUrl);
                SourceCandidate alternative = buildAlternativeCandidate(seed, recoveryContext, alternativeUrl);
                if (candidateOwnershipPolicy.isRejectedMediator(alternative, null)) {
                    discardedAlternativeUrls.add(alternativeUrl);
                    continue;
                }
                recoveryCandidates.add(alternative);
                if (recoveryCandidates.size() >= limit) {
                    return buildPlan(recoveryContext, recoveryCandidates,
                            attemptedAlternativeUrls, discardedAlternativeUrls);
                }
            }
        }
        return buildPlan(recoveryContext, recoveryCandidates, attemptedAlternativeUrls, discardedAlternativeUrls);
    }

    /**
     * 仅用于迁移期兼容旧调用点。新调用必须传入 RecoveryContext，避免补采逻辑继续只按 sourceType 推断目标字段。
     */
    @Deprecated(forRemoval = true)
    public RecoveryPlan planRecovery(String competitorName,
                                     String sourceType,
                                     List<SourceCandidate> candidates,
                                     List<SearchCollectionTarget> attemptedTargets,
                                     int maxAlternatives) {
        return planRecovery(RecoveryContext.builder()
                        .competitorName(competitorName)
                        .sourceType(sourceType)
                .maxAlternatives(maxAlternatives)
                        .build(),
                candidates,
                attemptedTargets);
    }

    private RecoveryPlan emptyPlan(RecoveryContext context,
                                   String reason,
                                   List<String> discardedAlternativeUrls) {
        return new RecoveryPlan(false, reason, List.of(), List.of(), discardedAlternativeUrls,
                context.fieldName(), context.evidencePathKey(), context.queryIntents());
    }

    private RecoveryPlan buildPlan(RecoveryContext context,
                                   List<SourceCandidate> candidates,
                                   LinkedHashSet<String> attemptedAlternativeUrls,
                                   LinkedHashSet<String> discardedAlternativeUrls) {
        return new RecoveryPlan(true, "发现受限或低信号页面，已按字段证据路径生成公开替代入口",
                candidates,
                new ArrayList<>(attemptedAlternativeUrls),
                new ArrayList<>(discardedAlternativeUrls),
                context.fieldName(),
                context.evidencePathKey(),
                context.queryIntents());
    }

    private boolean hasVerifiedUsableTarget(List<SearchCollectionTarget> attemptedTargets) {
        for (SearchCollectionTarget target : attemptedTargets == null ? List.<SearchCollectionTarget>of() : attemptedTargets) {
            if (target == null || target.getCandidate() == null || target.getCollectedPage() == null) {
                continue;
            }
            if (Boolean.TRUE.equals(target.getCandidate().getVerified())
                    && target.getCollectedPage().isSuccess()
                    && StringUtils.hasText(target.getCollectedPage().getContent())) {
                return true;
            }
        }
        return false;
    }

    private SeedSelection findBlockedSeeds(List<SourceCandidate> candidates,
                                           List<SearchCollectionTarget> attemptedTargets) {
        LinkedHashSet<String> blockedUrls = new LinkedHashSet<>();
        LinkedHashSet<String> discardedSeedUrls = new LinkedHashSet<>();
        List<SourceCandidate> seeds = new ArrayList<>();
        for (SearchCollectionTarget target : attemptedTargets == null ? List.<SearchCollectionTarget>of() : attemptedTargets) {
            SourceCandidate candidate = target == null ? null : target.getCandidate();
            if (candidateOwnershipPolicy.isRejectedMediator(candidate, target == null ? null : target.getCollectedPage())) {
                if (candidate != null && StringUtils.hasText(candidate.getUrl())) {
                    discardedSeedUrls.add(candidate.getUrl());
                }
                continue;
            }
            if (candidate != null && isBlockedOrLowSignal(candidate, target)) {
                blockedUrls.add(candidate.getUrl());
                seeds.add(candidate);
            }
        }
        for (SourceCandidate candidate : candidates == null ? List.<SourceCandidate>of() : candidates) {
            if (candidate == null || blockedUrls.contains(candidate.getUrl())) {
                continue;
            }
            if (candidateOwnershipPolicy.isRejectedMediator(candidate, null)) {
                if (StringUtils.hasText(candidate.getUrl())) {
                    discardedSeedUrls.add(candidate.getUrl());
                }
                continue;
            }
            if (isBlockedSignal(candidate.getVerificationReason())
                    || containsAny(candidate.getQualitySignals(), "ANTI_BOT_PARTIAL", "LOGIN_GATE_PARTIAL", "PUBLIC_SHELL_ONLY")) {
                seeds.add(candidate);
            }
        }
        return new SeedSelection(seeds, new ArrayList<>(discardedSeedUrls));
    }

    private boolean isBlockedOrLowSignal(SourceCandidate candidate, SearchCollectionTarget target) {
        String reason = candidate == null ? "" : safe(candidate.getVerificationReason());
        String signals = String.join(",", candidate == null || candidate.getQualitySignals() == null ? List.of() : candidate.getQualitySignals());
        String error = target == null || target.getCollectedPage() == null ? "" : safe(target.getCollectedPage().getErrorMessage());
        String content = target == null || target.getCollectedPage() == null ? "" : safe(target.getCollectedPage().getContent());
        return isBlockedSignal(reason + " " + signals + " " + error)
                || !StringUtils.hasText(content)
                || content.length() < 120;
    }

    private boolean isBlockedSignal(String value) {
        String normalized = safe(value).toLowerCase(Locale.ROOT);
        return normalized.contains("login")
                || normalized.contains("captcha")
                || normalized.contains("anti_bot")
                || normalized.contains("blocked")
                || normalized.contains("验证码")
                || normalized.contains("登录")
                || normalized.contains("低信号")
                || normalized.contains("正文不足");
    }

    private List<String> buildSameDomainAlternatives(SourceCandidate seed, RecoveryContext context) {
        String rootUrl = toRootUrl(seed == null ? null : seed.getUrl());
        if (!StringUtils.hasText(rootUrl)) {
            return List.of();
        }
        List<String> paths = resolvePreferredPaths(seed, context);
        List<String> urls = new ArrayList<>();
        for (String path : paths) {
            urls.add(canonicalUrlResolver.canonicalize(rootUrl + path));
        }
        return urls.stream().filter(StringUtils::hasText).distinct().toList();
    }

    private List<String> resolvePreferredPaths(SourceCandidate seed, RecoveryContext context) {
        if (isPricingEvidencePath(context)) {
            return mergePaths(PRICING_PUBLIC_PATHS, DOCS_PUBLIC_PATHS, OFFICIAL_PUBLIC_PATHS);
        }
        String sourceType = StringUtils.hasText(context.sourceType()) ? context.sourceType() : seed == null ? null : seed.getSourceType();
        if ("DOCS".equalsIgnoreCase(sourceType)) {
            return DOCS_PUBLIC_PATHS;
        }
        if ("PRICING".equalsIgnoreCase(sourceType)) {
            return PRICING_PUBLIC_PATHS;
        }
        return OFFICIAL_PUBLIC_PATHS;
    }

    private boolean isPricingEvidencePath(RecoveryContext context) {
        String joined = (safe(context.fieldName()) + " "
                + safe(context.evidencePathKey()) + " "
                + String.join(" ", context.queryIntents())).toLowerCase(Locale.ROOT);
        return joined.contains("pricing")
                || joined.contains("billing")
                || joined.contains("price")
                || joined.contains("定价")
                || joined.contains("收费")
                || joined.contains("计费");
    }

    private List<String> mergePaths(List<String> first, List<String> second, List<String> third) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        merged.addAll(first);
        merged.addAll(second);
        merged.addAll(third);
        return new ArrayList<>(merged);
    }

    private SourceCandidate buildAlternativeCandidate(SourceCandidate seed, RecoveryContext context, String alternativeUrl) {
        List<String> sourceUrls = seed == null || seed.getSourceUrls() == null || seed.getSourceUrls().isEmpty()
                ? List.of(seed == null ? alternativeUrl : seed.getUrl())
                : seed.getSourceUrls();
        List<String> qualitySignals = new ArrayList<>();
        qualitySignals.add("PUBLIC_EVIDENCE_RECOVERY_CANDIDATE");
        if (StringUtils.hasText(context.fieldName()) || StringUtils.hasText(context.evidencePathKey())) {
            qualitySignals.add("FIELD_EVIDENCE_PATH_RECOVERY");
        } else {
            qualitySignals.add("FIELD_CONTEXT_MISSING_LEGACY_RECOVERY");
        }
        String sourceType = StringUtils.hasText(context.sourceType()) ? context.sourceType() : seed == null ? null : seed.getSourceType();
        String pathReason = StringUtils.hasText(context.evidencePathKey())
                ? "，字段路径=" + context.fieldName() + "/" + context.evidencePathKey()
                : "";
        return SourceCandidate.builder()
                .url(alternativeUrl)
                .title("公开替代入口: " + alternativeUrl)
                .sourceType(StringUtils.hasText(sourceType) ? sourceType : seed == null ? null : seed.getSourceType())
                .discoveryMethod("PUBLIC_EVIDENCE_RECOVERY")
                .reason("主入口受限或低信号后生成的同域公开替代入口" + pathReason)
                .domain(extractDomain(alternativeUrl))
                .sourceUrls(sourceUrls)
                .qualitySignals(qualitySignals)
                .relevanceScore(0.82D)
                .freshnessScore(0.55D)
                .qualityScore(0.78D)
                .selectionStage("SUPPLEMENTED")
                .build();
    }

    private boolean containsAny(List<String> values, String... expectedValues) {
        String joined = String.join(",", values == null ? List.of() : values).toUpperCase(Locale.ROOT);
        for (String expected : expectedValues) {
            if (joined.contains(expected)) {
                return true;
            }
        }
        return false;
    }

    private String toRootUrl(String url) {
        if (!StringUtils.hasText(url)) {
            return null;
        }
        try {
            URI uri = URI.create(url);
            if (!StringUtils.hasText(uri.getScheme()) || !StringUtils.hasText(uri.getHost())) {
                return null;
            }
            return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getHost().toLowerCase(Locale.ROOT);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private String extractDomain(String url) {
        try {
            return URI.create(url).getHost();
        } catch (RuntimeException exception) {
            return "";
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    public record RecoveryPlan(boolean triggered,
                               String reason,
                               List<SourceCandidate> candidates,
                               List<String> attemptedAlternativeUrls,
                               List<String> discardedAlternativeUrls,
                               String fieldName,
                               String evidencePathKey,
                               List<String> queryIntents) {
        public RecoveryPlan {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            attemptedAlternativeUrls = attemptedAlternativeUrls == null ? List.of() : List.copyOf(attemptedAlternativeUrls);
            discardedAlternativeUrls = discardedAlternativeUrls == null ? List.of() : List.copyOf(discardedAlternativeUrls);
            queryIntents = queryIntents == null ? List.of() : List.copyOf(queryIntents);
        }
    }

    public record RecoveryContext(String competitorName,
                                  String sourceType,
                                  String fieldName,
                                  String evidencePathKey,
                                  List<String> queryIntents,
                                  int maxAlternatives) {
        public RecoveryContext {
            queryIntents = queryIntents == null ? List.of() : List.copyOf(queryIntents);
        }

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private String competitorName;
            private String sourceType;
            private String fieldName;
            private String evidencePathKey;
            private List<String> queryIntents = List.of();
            private int maxAlternatives = DEFAULT_LIMIT;

            public Builder competitorName(String competitorName) {
                this.competitorName = competitorName;
                return this;
            }

            public Builder sourceType(String sourceType) {
                this.sourceType = sourceType;
                return this;
            }

            public Builder fieldName(String fieldName) {
                this.fieldName = fieldName;
                return this;
            }

            public Builder evidencePathKey(String evidencePathKey) {
                this.evidencePathKey = evidencePathKey;
                return this;
            }

            public Builder queryIntents(List<String> queryIntents) {
                this.queryIntents = queryIntents == null ? List.of() : queryIntents;
                return this;
            }

            public Builder maxAlternatives(int maxAlternatives) {
                this.maxAlternatives = maxAlternatives;
                return this;
            }

            public RecoveryContext build() {
                return new RecoveryContext(competitorName, sourceType, fieldName, evidencePathKey, queryIntents, maxAlternatives);
            }
        }
    }

    private record SeedSelection(List<SourceCandidate> seeds,
                                 List<String> discardedSeedUrls) {
    }
}
```

- [ ] **步骤 4：在 `SearchExecutionTrace` 增加补采审计字段**

修改 `SearchExecutionTrace.java`：

```java
private Boolean publicEvidenceRecoveryTriggered;
private Integer publicEvidenceRecoveryCandidateCount;
private Integer publicEvidenceRecoveryVerifiedCount;
private List<String> publicEvidenceAttemptedUrls;
private List<String> publicEvidenceDiscardedUrls;
private String publicEvidenceRecoveryFieldName;
private String publicEvidenceRecoveryEvidencePathKey;
private List<String> publicEvidenceRecoveryQueryIntents;
private List<String> publicEvidenceAttemptedEvidencePaths;
private String publicEvidenceRecoveryReason;
```

- [ ] **步骤 5：扩展 sitemap 高价值公开入口**

追加到 `SitemapDiscoveryService.HIGH_VALUE_PATH_KEYWORDS`：

```java
private static final List<String> HIGH_VALUE_PATH_KEYWORDS = List.of(
        "/doc",
        "/docs",
        "/api",
        "/sdk",
        "/pricing",
        "/help",
        "/about",
        "/download",
        "/product",
        "/features",
        "/support",
        "/openplatform",
        "/creator",
        "/app"
);
```

追加到 `SitemapDiscoveryServiceTest`：

```java
@Test
void shouldKeepProductAndDownloadPagesFromSitemapAsPublicEvidenceCandidates() throws Exception {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    String rootUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    registerText("/sitemap.xml", 200, "application/xml", """
            <?xml version="1.0" encoding="UTF-8"?>
            <urlset>
              <url><loc>%s/about</loc></url>
              <url><loc>%s/download</loc></url>
              <url><loc>%s/app</loc></url>
            </urlset>
            """.formatted(rootUrl, rootUrl, rootUrl));
    server.start();

    SitemapDiscoveryService service = new SitemapDiscoveryService(enabledProperties());

    List<SourceCandidate> candidates = service.discover(
            "Example",
            "OFFICIAL",
            List.of(rootUrl)
    );

    assertThat(candidates)
            .extracting(SourceCandidate::getUrl)
            .contains(rootUrl + "/about", rootUrl + "/download", rootUrl + "/app");
}
```

- [ ] **步骤 6：在执行计划中加入补采步骤**

修改 `SearchExecutionCoordinator.defaultSteps()`，把该步骤放在 `BROWSER_SUPPLEMENT_SEARCH` 之后、`SELECT_TARGETS` 之前：

```java
SearchExecutionStep.builder()
        .stepCode("PUBLIC_EVIDENCE_RECOVERY")
        .goal("主入口受限时补采同域公开信息")
        .expectedDurationMs(6000L)
        .dependency("candidateVerifier")
        .status(SearchExecutionStep.StepStatus.PENDING)
        .build(),
```

- [ ] **步骤 7：在 `SearchExecutionCoordinator` 注入并调用补采服务**

新增字段：

```java
private final PublicEvidenceRecoveryService publicEvidenceRecoveryService;
```

Constructor default:

```java
this.publicEvidenceRecoveryService = publicEvidenceRecoveryService == null
        ? new PublicEvidenceRecoveryService(this.canonicalUrlResolver, this.candidateOwnershipPolicy)
        : publicEvidenceRecoveryService;
```

After sitemap candidates are merged and before `collectionTargetSelector.selectTargets(...)`, add:

```java
PublicEvidenceRecoveryService.RecoveryContext recoveryContext = PublicEvidenceRecoveryService.RecoveryContext.builder()
        .competitorName(config.getCompetitorName())
        .sourceType(safeSourceType(config.getSourceType()))
        // 迁移期可为空；接入 DimensionEvidencePlan 后由 CollectorNodeConfig 或 plan adapter 填入。
        .fieldName(config.getRecoveryFieldName())
        .evidencePathKey(config.getRecoveryEvidencePathKey())
        .queryIntents(config.getRecoveryQueryIntents())
        .maxAlternatives(12)
        .build();
PublicEvidenceRecoveryService.RecoveryPlan recoveryPlan = publicEvidenceRecoveryService.planRecovery(
        recoveryContext,
        allCandidates,
        new ArrayList<>(attemptedTargets.values())
);
int publicEvidenceRecoveryVerifiedCount = 0;
if (recoveryPlan.triggered() && !recoveryPlan.candidates().isEmpty() && !isTimedOut(searchStartedAt, searchTimeoutMillis)) {
    markStepRunning(executionPlan, "PUBLIC_EVIDENCE_RECOVERY", "主入口受限，正在补采同域公开信息");
    appendSnapshotAndPublish(progressSnapshots, executionPlan, "PUBLIC_EVIDENCE_RECOVERY",
            "主入口受限，正在补采同域公开信息", circuitBroken, degradationReason,
            progressListener, allCandidates, List.of(), null);
    List<SourceCandidate> recoveryCandidates = recoveryPlan.candidates().stream()
            .limit(4)
            .toList();
    CandidateVerificationResult recoveryVerification = candidateVerifier.verify(
            config.getCompetitorName(),
            safeSourceType(config.getSourceType()),
            recoveryCandidates
    );
    verificationStats.add(recoveryVerification);
    allCandidates = sourceCandidateRanker.rankAndDeduplicate(
            concat(allCandidates, recoveryVerification.getUpdatedCandidates())
    );
    appendAttemptedTargets(attemptedTargets, recoveryVerification.getAttemptedTargets());
    publicEvidenceRecoveryVerifiedCount = recoveryVerification.getVerifiedTargets().size();
    verifiedCount += publicEvidenceRecoveryVerifiedCount;
    markStepSuccess(executionPlan, "PUBLIC_EVIDENCE_RECOVERY",
            "公开信息补采候选 " + recoveryCandidates.size() + " 条，验证通过 "
                    + publicEvidenceRecoveryVerifiedCount + " 条");
    appendSnapshotAndPublish(progressSnapshots, executionPlan, "PUBLIC_EVIDENCE_RECOVERY",
            "公开信息补采候选 " + recoveryCandidates.size() + " 条，验证通过 "
                    + publicEvidenceRecoveryVerifiedCount + " 条",
            circuitBroken, degradationReason, progressListener, allCandidates, List.of(), null);
} else {
    markStepSkipped(executionPlan, "PUBLIC_EVIDENCE_RECOVERY", recoveryPlan.reason());
    appendSnapshotAndPublish(progressSnapshots, executionPlan, "PUBLIC_EVIDENCE_RECOVERY",
            recoveryPlan.reason(), circuitBroken, degradationReason,
            progressListener, allCandidates, List.of(), null);
}
```

构造 `SearchExecutionTrace` 时设置：

```java
.publicEvidenceRecoveryTriggered(recoveryPlan.triggered())
.publicEvidenceRecoveryCandidateCount(recoveryPlan.candidates().size())
.publicEvidenceRecoveryVerifiedCount(publicEvidenceRecoveryVerifiedCount)
.publicEvidenceAttemptedUrls(recoveryPlan.attemptedAlternativeUrls())
.publicEvidenceDiscardedUrls(recoveryPlan.discardedAlternativeUrls())
.publicEvidenceRecoveryFieldName(recoveryPlan.fieldName())
.publicEvidenceRecoveryEvidencePathKey(recoveryPlan.evidencePathKey())
.publicEvidenceRecoveryQueryIntents(recoveryPlan.queryIntents())
.publicEvidenceAttemptedEvidencePaths(
        StringUtils.hasText(recoveryPlan.evidencePathKey())
                ? List.of(recoveryPlan.evidencePathKey())
                : List.of())
.publicEvidenceRecoveryReason(recoveryPlan.reason())
```

如果 `CollectorNodeConfig` 暂时还没有暴露字段路径上下文，先增加可选 nullable 字段，让后续 `DimensionEvidencePlan` adapter 可以传入上下文，而不需要再次修改补采服务：

```java
private String recoveryFieldName;
private String recoveryEvidencePathKey;
private List<String> recoveryQueryIntents = List.of();
```

- [ ] **步骤 8：写协调器补采集成测试**

追加到 `SearchExecutionCoordinatorTest`：

```java
import static org.mockito.ArgumentMatchers.any;

@Test
void shouldRecoverPublicEvidenceBeforeSelectingWhenPlannedOfficialPageIsBlocked() {
    PublicEvidenceRecoveryService recoveryService = mock(PublicEvidenceRecoveryService.class);
    SearchExecutionCoordinator searchCoordinator = new SearchExecutionCoordinator(
            new CandidateVerifier(sourceCollector),
            browserSearchRuntimeService,
            searchSourceProvider,
            new SourceCandidateRanker(),
            new CollectionTargetSelector(),
            new SearchPolicyResolver(),
            new CanonicalUrlResolver(),
            new SitemapDiscoveryService(new SitemapDiscoveryProperties()),
            new CandidateOwnershipPolicy(),
            recoveryService
    );
    SourceCandidate blockedHome = SourceCandidate.builder()
            .url("https://www.example.com")
            .title("Example")
            .sourceType("OFFICIAL")
            .discoveryMethod("DIRECT_LOCATOR")
            .domain("www.example.com")
            .relevanceScore(0.90)
            .freshnessScore(0.70)
            .qualityScore(0.88)
            .build();
    SourceCandidate recoveredAbout = SourceCandidate.builder()
            .url("https://www.example.com/about")
            .title("Example About")
            .sourceType("OFFICIAL")
            .discoveryMethod("PUBLIC_EVIDENCE_RECOVERY")
            .domain("www.example.com")
            .sourceUrls(List.of("https://www.example.com"))
            .qualitySignals(List.of("PUBLIC_EVIDENCE_RECOVERY_CANDIDATE"))
            .relevanceScore(0.86)
            .freshnessScore(0.55)
            .qualityScore(0.82)
            .build();
    when(sourceCollector.collect("https://www.example.com", "Example", "OFFICIAL"))
            .thenReturn(SourceCollector.CollectedPage.builder()
                    .url("https://www.example.com")
                    .title("Security Check")
                    .content("captcha security check")
                    .snippet("captcha")
                    .success(false)
                    .errorMessage("ANTI_BOT_BLOCKED")
                    .build());
    when(recoveryService.planRecovery(any(PublicEvidenceRecoveryService.RecoveryContext.class), any(), any()))
            .thenReturn(new PublicEvidenceRecoveryService.RecoveryPlan(
                    true,
                    "发现受限或低信号页面，已生成公开替代入口",
                    List.of(recoveredAbout),
                    List.of("https://www.example.com/about"),
                    List.of(),
                    "summary",
                    "OFFICIAL_PUBLIC_PROFILE",
                    List.of("OFFICIAL_PROFILE")
            ));
    when(sourceCollector.collect("https://www.example.com/about", "Example", "OFFICIAL"))
            .thenReturn(SourceCollector.CollectedPage.builder()
                    .url("https://www.example.com/about")
                    .title("About Example")
                    .content("Example official product features support download app community.")
                    .snippet("official product features")
                    .success(true)
                    .build());

    SearchExecutionResult result = searchCoordinator.execute(CollectorNodeConfig.builder()
            .competitorName("Example")
            .sourceType("OFFICIAL")
            .sourceCandidates(List.of(blockedHome))
            .verifyCandidates(Boolean.TRUE)
            .browserSearchEnabled(Boolean.FALSE)
            .searchMode("HTTP_ONLY")
            .maxSearchResults(1)
            .minVerifiedCandidates(1)
            .build());

    assertEquals("https://www.example.com/about", result.getSelectedTargets().get(0).getCandidate().getUrl());
    assertEquals(Boolean.TRUE, result.getExecutionTrace().getPublicEvidenceRecoveryTriggered());
    assertEquals(1, result.getExecutionTrace().getPublicEvidenceRecoveryVerifiedCount());
    assertTrue(result.getExecutionTrace().getPublicEvidenceAttemptedUrls().contains("https://www.example.com/about"));
    assertEquals("summary", result.getExecutionTrace().getPublicEvidenceRecoveryFieldName());
    assertEquals("OFFICIAL_PUBLIC_PROFILE", result.getExecutionTrace().getPublicEvidenceRecoveryEvidencePathKey());
}
```

- [ ] **步骤 9：运行 Task 3 测试**

运行：

```powershell
mvn -pl backend -Dtest=PublicEvidenceRecoveryServiceTest,SitemapDiscoveryServiceTest,SearchExecutionCoordinatorTest test
```

预期：

```text
BUILD SUCCESS
```

---

### Task 4: 登录/验证码公开壳信息兜底

**文件：**

- 新建： `backend/src/main/java/cn/bugstack/competitoragent/source/PublicShellRecoveryExtractor.java`
- 新建： `backend/src/test/java/cn/bugstack/competitoragent/source/PublicShellRecoveryExtractorTest.java`
- 修改： `backend/src/main/java/cn/bugstack/competitoragent/source/PlaywrightPageCollector.java`
- 修改： `backend/src/test/java/cn/bugstack/competitoragent/source/PlaywrightPageCollectorTest.java`

- [ ] **步骤 1：创建公开壳恢复测试**

创建 `PublicShellRecoveryExtractorTest.java`：

```java
package cn.bugstack.competitoragent.source;

import cn.bugstack.competitoragent.search.AntiBotDetectionResult;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PublicShellRecoveryExtractorTest {

    private final PublicShellRecoveryExtractor extractor = new PublicShellRecoveryExtractor();

    @Test
    void shouldRecoverMetaAndCanonicalFromLoginGate() {
        Page page = mock(Page.class);
        when(page.url()).thenReturn("https://docs.example.com/login");
        when(page.title()).thenReturn("Example Docs Login");
        when(page.locator(anyString())).thenReturn(mock(Locator.class));
        when(page.content()).thenReturn("""
                <html>
                  <head>
                    <title>Example Docs Login</title>
                    <meta name="description" content="Example Docs provides API guides and product documentation.">
                    <meta property="og:title" content="Example Product Docs">
                    <link rel="canonical" href="https://docs.example.com/">
                    <script type="application/ld+json">{"@type":"WebSite","name":"Example Docs"}</script>
                  </head>
                  <body>Please sign in to continue</body>
                </html>
                """);

        SourceCollector.CollectedPage recovered = extractor.recover(
                page,
                "https://docs.example.com/login",
                "Example",
                "DOCS",
                AntiBotDetectionResult.builder()
                        .blocked(true)
                        .reasonCode("LOGIN_OR_CHALLENGE_REDIRECT")
                        .matchedSignals(List.of("url:/login"))
                        .build()
        );

        assertTrue(recovered.isSuccess());
        assertTrue(recovered.getContent().contains("Example Docs provides API guides"));
        assertTrue(recovered.getMetadata().contains("LOGIN_GATE_PARTIAL"));
        assertTrue(recovered.getMetadata().contains("PUBLIC_SHELL_ONLY"));
        assertTrue(recovered.getMetadata().contains("https://docs.example.com/"));
    }

    @Test
    void shouldRejectPureCaptchaShellWithoutUsefulPublicContent() {
        Page page = mock(Page.class);
        when(page.url()).thenReturn("https://example.com/challenge");
        when(page.title()).thenReturn("Verify you are human");
        when(page.content()).thenReturn("""
                <html><head><title>Verify you are human</title></head>
                <body>captcha security check</body></html>
                """);

        SourceCollector.CollectedPage recovered = extractor.recover(
                page,
                "https://example.com/challenge",
                "Example",
                "OFFICIAL",
                AntiBotDetectionResult.builder()
                        .blocked(true)
                        .reasonCode("TEXT_SIGNAL_SHORT_BODY_BLOCKED")
                        .matchedSignals(List.of("body:captcha"))
                        .build()
        );

        assertFalse(recovered.isSuccess());
        assertTrue(recovered.getErrorMessage().contains("公开壳信息不足"));
    }
}
```

- [ ] **步骤 2：运行测试确认类不存在**

运行：

```powershell
mvn -pl backend -Dtest=PublicShellRecoveryExtractorTest test
```

Expected before implementation:

```text
Compilation failure: cannot find symbol PublicShellRecoveryExtractor
```

- [ ] **步骤 3：创建 `PublicShellRecoveryExtractor`**

创建 `PublicShellRecoveryExtractor.java`：

```java
package cn.bugstack.competitoragent.source;

import cn.bugstack.competitoragent.search.AntiBotDetectionResult;
import com.microsoft.playwright.Page;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 登录、验证码或反爬页面的公开壳信息恢复器。
 * 该类只读取页面已经公开返回的 title/meta/og/canonical/json-ld/少量可见文本，
 * 不提交表单、不绕过验证码、不使用授权态 cookie。
 */
@Component
public class PublicShellRecoveryExtractor {

    private static final int MAX_CONTENT_LENGTH = 1200;
    private static final Pattern META_PATTERN = Pattern.compile(
            "<meta\\s+[^>]*(?:name|property)=[\"']([^\"']+)[\"'][^>]*content=[\"']([^\"']*)[\"'][^>]*>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CANONICAL_PATTERN = Pattern.compile(
            "<link\\s+[^>]*rel=[\"']canonical[\"'][^>]*href=[\"']([^\"']+)[\"'][^>]*>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern JSON_LD_PATTERN = Pattern.compile(
            "<script\\s+[^>]*type=[\"']application/ld\\+json[\"'][^>]*>(.*?)</script>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    public SourceCollector.CollectedPage recover(Page page,
                                                 String originalUrl,
                                                 String competitorName,
                                                 String sourceType,
                                                 AntiBotDetectionResult detection) {
        if (page == null) {
            return failed(originalUrl, competitorName, sourceType, "公开壳恢复失败：页面为空");
        }
        try {
            String finalUrl = firstNonBlank(page.url(), originalUrl);
            String html = firstNonBlank(page.content(), "");
            String title = firstNonBlank(page.title(), finalUrl);
            List<String> fragments = new ArrayList<>();
            fragments.add("title: " + title);
            fragments.addAll(extractMetaFragments(html));
            extractCanonical(html).forEach(value -> fragments.add("canonical: " + value));
            extractJsonLdSummary(html).forEach(value -> fragments.add("json-ld: " + value));

            String content = compactFragments(fragments);
            if (!hasUsefulPublicShell(content)) {
                return failed(finalUrl, competitorName, sourceType, "公开壳信息不足，不能作为降级证据");
            }
            String metadata = buildMetadata(finalUrl, detection, html);
            return SourceCollector.CollectedPage.builder()
                    .url(finalUrl)
                    .title(title)
                    .content(truncate(content, MAX_CONTENT_LENGTH))
                    .snippet(truncate(content, 500))
                    .metadata(metadata)
                    .competitorName(competitorName)
                    .sourceType(sourceType)
                    .collectedAt(Instant.now().toString())
                    .success(true)
                    .build();
        } catch (RuntimeException exception) {
            return failed(originalUrl, competitorName, sourceType,
                    "公开壳恢复异常：" + exception.getMessage());
        }
    }

    private List<String> extractMetaFragments(String html) {
        List<String> fragments = new ArrayList<>();
        Matcher matcher = META_PATTERN.matcher(html == null ? "" : html);
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = matcher.group(2);
            if (isUsefulMetaKey(key) && StringUtils.hasText(value)) {
                fragments.add(key + ": " + value);
            }
        }
        return fragments;
    }

    private boolean isUsefulMetaKey(String key) {
        if (!StringUtils.hasText(key)) {
            return false;
        }
        String normalized = key.toLowerCase();
        return normalized.contains("description")
                || normalized.contains("keywords")
                || normalized.contains("og:title")
                || normalized.contains("og:description")
                || normalized.contains("twitter:title")
                || normalized.contains("twitter:description");
    }

    private List<String> extractCanonical(String html) {
        Matcher matcher = CANONICAL_PATTERN.matcher(html == null ? "" : html);
        List<String> values = new ArrayList<>();
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
    }

    private List<String> extractJsonLdSummary(String html) {
        Matcher matcher = JSON_LD_PATTERN.matcher(html == null ? "" : html);
        List<String> values = new ArrayList<>();
        while (matcher.find()) {
            String json = matcher.group(1).replaceAll("\\s+", " ").trim();
            if (StringUtils.hasText(json)) {
                values.add(truncate(json, 240));
            }
        }
        return values;
    }

    private String compactFragments(List<String> fragments) {
        Set<String> distinct = new LinkedHashSet<>();
        for (String fragment : fragments == null ? List.<String>of() : fragments) {
            if (StringUtils.hasText(fragment)) {
                distinct.add(fragment.trim());
            }
        }
        return String.join("\n", distinct);
    }

    private boolean hasUsefulPublicShell(String content) {
        if (!StringUtils.hasText(content)) {
            return false;
        }
        String normalized = content.toLowerCase();
        boolean onlyGateWords = normalized.contains("captcha")
                || normalized.contains("verify you are human")
                || normalized.contains("security check");
        boolean hasProductSignal = normalized.contains("docs")
                || normalized.contains("documentation")
                || normalized.contains("product")
                || normalized.contains("api")
                || normalized.contains("guide")
                || normalized.contains("官方")
                || normalized.contains("产品")
                || normalized.contains("文档");
        return content.length() >= 80 && (!onlyGateWords || hasProductSignal);
    }

    private String buildMetadata(String finalUrl, AntiBotDetectionResult detection, String html) {
        List<String> signals = new ArrayList<>(List.of("PUBLIC_SHELL_ONLY"));
        if (detection != null && StringUtils.hasText(detection.getReasonCode())) {
            signals.add(detection.getReasonCode().contains("LOGIN") ? "LOGIN_GATE_PARTIAL" : "ANTI_BOT_PARTIAL");
        } else {
            signals.add("ANTI_BOT_PARTIAL");
        }
        return "{\"collector\":\"public-shell-recovery\""
                + ",\"sourceUrls\":[\"" + escapeJson(finalUrl) + "\"]"
                + ",\"qualitySignals\":" + toJsonArray(signals)
                + ",\"structuredBlocksRecovered\":" + !extractJsonLdSummary(html).isEmpty()
                + ",\"collectedAt\":\"" + Instant.now() + "\"}";
    }

    private SourceCollector.CollectedPage failed(String url, String competitorName, String sourceType, String message) {
        return SourceCollector.CollectedPage.builder()
                .url(url)
                .competitorName(competitorName)
                .sourceType(sourceType)
                .success(false)
                .errorMessage(message)
                .build();
    }

    private String toJsonArray(List<String> values) {
        return "[" + String.join(",", values.stream()
                .map(value -> "\"" + escapeJson(value) + "\"")
                .toList()) + "]";
    }

    private String firstNonBlank(String primary, String fallback) {
        return StringUtils.hasText(primary) ? primary : fallback;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private String escapeJson(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
```

- [ ] **步骤 4：在 `PlaywrightPageCollector` blocked 分支调用公开壳恢复**

新增字段和构造依赖：

```java
private final PublicShellRecoveryExtractor publicShellRecoveryExtractor;
```

Where constructors are defined, initialize:

```java
this.publicShellRecoveryExtractor = publicShellRecoveryExtractor == null
        ? new PublicShellRecoveryExtractor()
        : publicShellRecoveryExtractor;
```

在 `extractRenderedPage` 中，在 `if (detection.isBlocked())` 返回 `failed(...)` 之前新增：

```java
SourceCollector.CollectedPage recoveredShell = publicShellRecoveryExtractor.recover(
        page,
        url,
        competitorName,
        sourceType,
        detection
);
if (recoveredShell != null && recoveredShell.isSuccess()) {
    log.warn("页面命中登录/验证码/反爬信号，但公开壳信息恢复成功: url={}, title={}",
            UrlSecurityUtils.maskForLog(url), recoveredShell.getTitle());
    return recoveredShell;
}
```

- [ ] **步骤 5：写 Playwright 集成单测**

追加到 `PlaywrightPageCollectorTest`：

```java
@Test
void shouldRecoverPublicShellWhenPageIsLoginGateButMetaIsUseful() {
    Page page = mock(Page.class);
    when(page.url()).thenReturn("https://docs.example.com/login");
    when(page.title()).thenReturn("Example Docs Login");
    when(page.content()).thenReturn("""
            <html><head>
              <meta name="description" content="Example Docs provides API guides and product documentation.">
              <link rel="canonical" href="https://docs.example.com/">
            </head><body>Please sign in to continue</body></html>
            """);
    when(page.textContent("body")).thenReturn("Please sign in to continue");

    SourceCollector.CollectedPage collectedPage = collector.extractRenderedPageForTest(
            "https://docs.example.com/login",
            "Example",
            "DOCS",
            "FULL_RENDER_REQUIRED",
            SourceCollectRequest.builder()
                    .url("https://docs.example.com/login")
                    .competitorName("Example")
                    .sourceType("DOCS")
                    .sourceUrls(List.of("https://docs.example.com/login"))
                    .build(),
            page
    );

    assertTrue(collectedPage.isSuccess());
    assertTrue(collectedPage.getMetadata().contains("PUBLIC_SHELL_ONLY"));
}
```

如果 `extractRenderedPage` 是 private，只为测试暴露一个 package-private wrapper：

```java
CollectedPage extractRenderedPageForTest(String url,
                                         String competitorName,
                                         String sourceType,
                                         String fallbackReason,
                                         SourceCollectRequest request,
                                         Page page) {
    return extractRenderedPage(url, competitorName, sourceType, fallbackReason, request, page);
}
```

- [ ] **步骤 6：运行 Task 4 测试**

运行：

```powershell
mvn -pl backend -Dtest=PublicShellRecoveryExtractorTest,PlaywrightPageCollectorTest test
```

预期：

```text
BUILD SUCCESS
```

---

### Task 5: EvidenceSource 落库安全化与 Flyway 扩容

**文件：**

- 新建： `backend/src/main/java/cn/bugstack/competitoragent/agent/collector/EvidenceSourceSanitizer.java`
- 新建： `backend/src/test/java/cn/bugstack/competitoragent/agent/collector/EvidenceSourceSanitizerTest.java`
- 修改： `backend/src/main/java/cn/bugstack/competitoragent/model/entity/EvidenceSource.java`
- 新建： `backend/src/main/resources/db/migration/V28__expand_evidence_source_discovery_reason.sql`
- 新建： `backend/src/test/java/cn/bugstack/competitoragent/db/EvidenceSourceMigrationArtifactsTest.java`

- [ ] **步骤 1：写 sanitizer 测试**

创建 `EvidenceSourceSanitizerTest.java`：

```java
package cn.bugstack.competitoragent.agent.collector;

import cn.bugstack.competitoragent.model.entity.EvidenceSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceSourceSanitizerTest {

    private final EvidenceSourceSanitizer sanitizer = new EvidenceSourceSanitizer();

    @Test
    void shouldTrimLengthLimitedFieldsBeforePersistence() {
        EvidenceSource source = EvidenceSource.builder()
                .taskId(53L)
                .competitorName("哔哩哔哩")
                .evidenceId("T0053-COLLECT_SOURCES_01_01-001")
                .title("T".repeat(600))
                .url("https://example.com/" + "a".repeat(2200))
                .sourceType("OFFICIAL".repeat(20))
                .discoveryMethod("BROWSER".repeat(20))
                .sourceCategory("AI_DISCOVERED".repeat(20))
                .sourceDomain("sub." + "example".repeat(80) + ".com")
                .discoveryReason("R".repeat(900))
                .publishedAt("2026-06-22T20:05:35.373+08:00-too-long")
                .build();

        EvidenceSource sanitized = sanitizer.sanitize(source);

        assertEquals(500, sanitized.getTitle().length());
        assertEquals(2048, sanitized.getUrl().length());
        assertTrue(sanitized.getSourceType().length() <= 50);
        assertTrue(sanitized.getDiscoveryMethod().length() <= 50);
        assertTrue(sanitized.getSourceCategory().length() <= 50);
        assertEquals(255, sanitized.getSourceDomain().length());
        assertEquals(900, sanitized.getDiscoveryReason().length());
        assertEquals(30, sanitized.getPublishedAt().length());
    }
}
```

- [ ] **步骤 2：创建 sanitizer**

创建 `EvidenceSourceSanitizer.java`：

```java
package cn.bugstack.competitoragent.agent.collector;

import cn.bugstack.competitoragent.model.entity.EvidenceSource;
import org.springframework.stereotype.Component;

/**
 * EvidenceSource 入库前安全化。
 * discoveryReason 迁移为 TEXT 后不裁剪，其他数据库仍有长度限制的字段统一裁剪，
 * 避免真实搜索摘要、长标题、异常 URL 直接打断采集节点。
 */
@Component
public class EvidenceSourceSanitizer {

    public EvidenceSource sanitize(EvidenceSource source) {
        if (source == null) {
            return null;
        }
        source.setCompetitorName(truncate(source.getCompetitorName(), 100));
        source.setEvidenceId(truncate(source.getEvidenceId(), 100));
        source.setTitle(truncate(source.getTitle(), 500));
        source.setUrl(truncate(source.getUrl(), 2048));
        source.setSourceType(truncate(source.getSourceType(), 50));
        source.setDiscoveryMethod(truncate(source.getDiscoveryMethod(), 50));
        source.setSourceCategory(truncate(source.getSourceCategory(), 50));
        source.setSourceDomain(truncate(source.getSourceDomain(), 255));
        source.setPublishedAt(truncate(source.getPublishedAt(), 30));
        return source;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
```

- [ ] **步骤 3：修改 `EvidenceSource.discoveryReason` 映射**

修改 `EvidenceSource.java`：

```java
@Column(columnDefinition = "TEXT")
@Schema(description = "来源筛选说明")
private String discoveryReason;
```

- [ ] **步骤 4：新增 Flyway 迁移**

创建 `backend/src/main/resources/db/migration/V28__expand_evidence_source_discovery_reason.sql`：

```sql
ALTER TABLE evidence_source
    ALTER COLUMN discovery_reason TYPE TEXT;
```

- [ ] **步骤 5：写迁移文件测试**

创建 `EvidenceSourceMigrationArtifactsTest.java`：

```java
package cn.bugstack.competitoragent.db;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceSourceMigrationArtifactsTest {

    @Test
    void shouldContainDiscoveryReasonExpansionMigration() throws Exception {
        Path migration = Path.of("src/main/resources/db/migration/V28__expand_evidence_source_discovery_reason.sql");

        assertTrue(Files.exists(migration));
        String sql = Files.readString(migration);
        assertTrue(sql.contains("ALTER TABLE evidence_source"));
        assertTrue(sql.contains("ALTER COLUMN discovery_reason TYPE TEXT"));
    }
}
```

- [ ] **步骤 6：运行 Task 5 测试**

运行：

```powershell
mvn -pl backend -Dtest=EvidenceSourceSanitizerTest,EvidenceSourceMigrationArtifactsTest test
```

预期：

```text
BUILD SUCCESS
```

---

### Task 6: 采集落库调用 sanitizer 并保留失败诊断

**文件：**

- 修改： `backend/src/main/java/cn/bugstack/competitoragent/agent/collector/CollectorAgent.java`
- 修改： `backend/src/test/java/cn/bugstack/competitoragent/agent/collector/CollectorAgentTest.java`

- [ ] **步骤 1：给 CollectorAgent 注入 sanitizer**

修改 `CollectorAgent.java` 中的构造函数：

```java
private final EvidenceSourceSanitizer evidenceSourceSanitizer;
```

Constructor assignment:

```java
this.evidenceSourceSanitizer = evidenceSourceSanitizer == null
        ? new EvidenceSourceSanitizer()
        : evidenceSourceSanitizer;
```

- [ ] **步骤 2：保存 EvidenceSource 前调用 sanitizer**

Replace both `evidenceRepository.save(evidence);` call sites with:

```java
EvidenceSource sanitizedEvidence = evidenceSourceSanitizer.sanitize(evidence);
evidenceRepository.save(sanitizedEvidence);
```

保存后的 evidence 用于 indexing 时，使用 `sanitizedEvidence`：

```java
retrievalIndexingResult = taskRetrievalIndexService.indexEvidence(sanitizedEvidence);
```

- [ ] **步骤 3：保存失败时保留 collection audit 诊断**

Wrap the save block:

```java
String persistenceFailureReason = null;
try {
    EvidenceSource sanitizedEvidence = evidenceSourceSanitizer.sanitize(evidence);
    evidenceRepository.save(sanitizedEvidence);
    successCounterRef[0]++;
    try {
        retrievalIndexingResult = taskRetrievalIndexService.indexEvidence(sanitizedEvidence);
    } catch (Exception e) {
        knowledgeFailureReason = e.getMessage();
        log.warn("index collected evidence failed, taskId={}, evidenceId={}",
                context.getTaskId(), evidenceId, e);
    }
} catch (Exception persistenceException) {
    persistenceFailureReason = persistenceException.getMessage();
    log.warn("persist collected evidence failed, taskId={}, evidenceId={}",
            context.getTaskId(), evidenceId, persistenceException);
}
```

构造 `resultEntry` 时加入：

```java
if (persistenceFailureReason != null && !persistenceFailureReason.isBlank()) {
    collectionIssueFlags = mergeIssueFlags(collectionIssueFlags, List.of("EVIDENCE_PERSIST_FAILED"));
    resultEntry.put("persistenceFailureReason", persistenceFailureReason);
}
```

如果每个 collected page 都只因为持久化失败而失败，在 output audit 已写入后抛出异常：

```java
if (successCounterRef[0] == 0 && hasIssueFlag(results, "EVIDENCE_PERSIST_FAILED")) {
    throw new IllegalStateException("证据落库失败，已保留 collection audit 诊断，请检查字段长度或数据库迁移");
}
```

新增 helper：

```java
private boolean hasIssueFlag(List<Map<String, Object>> results, String issueFlag) {
    for (Map<String, Object> result : results == null ? List.<Map<String, Object>>of() : results) {
        Object issueFlags = result.get("issueFlags");
        if (issueFlags instanceof Iterable<?> iterable) {
            for (Object value : iterable) {
                if (issueFlag.equals(String.valueOf(value))) {
                    return true;
                }
            }
        }
    }
    return false;
}
```

- [ ] **步骤 4：写 CollectorAgent 保存长 reason 的测试**

追加到 `CollectorAgentTest`：

```java
@Test
void shouldSanitizeEvidenceBeforePersistenceWhenDiscoveryReasonIsLong() {
    ArgumentCaptor<EvidenceSource> evidenceCaptor = ArgumentCaptor.forClass(EvidenceSource.class);
    SourceCandidate candidate = SourceCandidate.builder()
            .url("https://example.com/docs")
            .title("Docs")
            .sourceType("DOCS")
            .discoveryMethod("DIRECT_LOCATOR")
            .reason("R".repeat(900))
            .sourceUrls(List.of("https://example.com/docs"))
            .build();

    SourceCollector.CollectedPage page = SourceCollector.CollectedPage.builder()
            .url("https://example.com/docs")
            .title("T".repeat(600))
            .content("Example documentation content with enough useful public text.")
            .snippet("Example documentation content")
            .metadata("{\"sourceUrls\":[\"https://example.com/docs\"]}")
            .success(true)
            .build();

    runCollectorWithSingleSelectedTarget(candidate, page);

    verify(evidenceRepository).save(evidenceCaptor.capture());
    EvidenceSource saved = evidenceCaptor.getValue();
    assertEquals(500, saved.getTitle().length());
    assertEquals(900, saved.getDiscoveryReason().length());
}
```

如果现有测试 helper 不同，使用已有 `CollectorAgentTest` fixture 实现 `runCollectorWithSingleSelectedTarget` 并注入：

```java
SearchExecutionCoordinator searchExecutionCoordinator = mock(SearchExecutionCoordinator.class);
when(searchExecutionCoordinator.execute(any())).thenReturn(SearchExecutionResult.builder()
        .selectedTargets(List.of(SearchCollectionTarget.builder()
                .candidate(candidate)
                .collectedPage(page)
                .build()))
        .sourceCandidates(List.of(candidate))
        .build());
```

- [ ] **步骤 5：运行 Task 6 测试**

运行：

```powershell
mvn -pl backend -Dtest=CollectorAgentTest test
```

预期：

```text
BUILD SUCCESS
```

---

### Task 7: 回归测试与 live 复验记录

**文件：**

- 新建： `docs/Tavily/progress/2026-06-28-search-collection-public-evidence-recovery-progress.md`
- 修改： `docs/superpowers/ExtractionStructured/problem/ExtractionStructured.md`

- [ ] **步骤 1：运行搜索采集相关单测**

运行：

```powershell
mvn -pl backend -Dtest=CollectionTargetSelectorTest,CandidateOwnershipPolicyTest,PublicEvidenceRecoveryServiceTest,PublicShellRecoveryExtractorTest,PlaywrightPageCollectorTest,EvidenceSourceSanitizerTest,EvidenceSourceMigrationArtifactsTest,SearchExecutionCoordinatorTest test
```

预期：

```text
BUILD SUCCESS
```

- [ ] **步骤 2：运行更宽一点的采集/搜索回归**

运行：

```powershell
mvn -pl backend -Dtest=SearchAndCollectionGoldenMasterTest,SearchExecutionTruthContractTest,CollectionExecutionCoordinatorTest,CollectionAuditContractTest test
```

预期：

```text
BUILD SUCCESS
```

- [ ] **步骤 3：写执行进度文档**

创建 `2026-06-28-search-collection-public-evidence-recovery-progress.md`：

```markdown
# 搜索采集有效公开证据补采进度

当前阶段：[搜索采集有效公开信息补采回归验证]

[x] 信息采集：已完成
[x] 数据分析：已完成
[ ] 报告撰写：待 live 复验完成后再判断

## 已完成改动

- 收紧 selectedTargets 入选门槛：未验证搜索补源和中介页不再进入正式采集。
- 受限页后的有效公开信息补采：主入口被登录/验证码/反爬/低信号挡住时，先尝试同域公开入口、sitemap/robots、canonical/OG/JSON-LD 链接。
- 登录/验证码页面增加公开壳兜底：仅在有效公开正文补采失败后恢复 title/meta/og/canonical/json-ld/公开片段，不绕过访问控制。
- EvidenceSource 落库安全化：长度受限字段统一裁剪，`discovery_reason` 迁移为 TEXT。
- 采集失败诊断保留：保存失败时保留 issueFlags 和 persistenceFailureReason。

## 验证命令

- `mvn -pl backend -Dtest=CollectionTargetSelectorTest,CandidateOwnershipPolicyTest,PublicEvidenceRecoveryServiceTest,PublicShellRecoveryExtractorTest,PlaywrightPageCollectorTest,EvidenceSourceSanitizerTest,EvidenceSourceMigrationArtifactsTest,SearchExecutionCoordinatorTest test`
- `mvn -pl backend -Dtest=SearchAndCollectionGoldenMasterTest,SearchExecutionTruthContractTest,CollectionExecutionCoordinatorTest,CollectionAuditContractTest test`

## live 复验计划

- 用 `https://app.bilibili.com` 重新创建 clean task。
- 观察是否仍选中百度认证/企业信息中介页。
- 观察是否触发 `PUBLIC_EVIDENCE_RECOVERY`，并记录 `publicEvidenceAttemptedUrls`、`publicEvidenceAttemptedEvidencePaths`、`publicEvidenceRecoveryFieldName`、`publicEvidenceRecoveryEvidencePathKey`、`publicEvidenceRecoveryCandidateCount`、`publicEvidenceRecoveryVerifiedCount`。
- 观察是否至少采集到 1 条 `PUBLIC_PAGE_CONTENT_READY` / 验证通过的同域公开正文；如果只得到 `PUBLIC_SHELL_ONLY`，必须确认 `attemptedAlternativeUrls` 非空且替代入口均失败。
- 观察采集节点是否仍因 `discovery_reason` 长度失败。
- 若采集通过，再回到报告层验证 coverage 去重、字段缺口和 reviewer 阻断。
```

- [ ] **步骤 4：运行 live 复验**

创建一个干净任务，参数如下：

```json
{
  "taskName": "有效公开信息补采 live 验收 - 哔哩哔哩下载中心",
  "subjectProduct": "视频社区与内容平台",
  "competitorNames": ["哔哩哔哩"],
  "competitorUrls": ["https://app.bilibili.com"],
  "analysisDimensions": ["产品功能", "目标用户", "市场定位", "证据完整性"],
  "sourceScope": ["官网"],
  "reportLanguage": "中文",
  "reportTemplate": "标准版"
}
```

运行：

```powershell
$dir = "docs\superpowers\ExtractionStructured\progress\live-bilibili-public-evidence-recovery-$(Get-Date -Format yyyyMMdd-HHmmss)"
New-Item -ItemType Directory -Force -Path $dir | Out-Null
$payload = @{
  taskName = "有效公开信息补采 live 验收 - 哔哩哔哩下载中心"
  subjectProduct = "视频社区与内容平台"
  competitorNames = @("哔哩哔哩")
  competitorUrls = @("https://app.bilibili.com")
  analysisDimensions = @("产品功能", "目标用户", "市场定位", "证据完整性")
  sourceScope = @("官网")
  reportLanguage = "中文"
  reportTemplate = "标准版"
} | ConvertTo-Json -Depth 8
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText((Resolve-Path $dir).Path + "\create-request.json", $payload, $utf8NoBom)
curl.exe -s -H "Content-Type: application/json; charset=utf-8" --data-binary "@$dir\create-request.json" http://localhost:9093/api/task/create > "$dir\create-response.json"
```

Execute returned task id:

```powershell
$taskId = (Get-Content "$dir\create-response.json" -Raw | ConvertFrom-Json).data.id
curl.exe -s -X POST "http://localhost:9093/api/task/$taskId/execute" > "$dir\execute-response.json"
```

Poll:

```powershell
for ($i = 1; $i -le 24; $i++) {
  $suffix = "{0:D2}" -f $i
  curl.exe -s "http://localhost:9093/api/task/$taskId" > "$dir\poll-$suffix-task.json"
  curl.exe -s "http://localhost:9093/api/task/$taskId/nodes" > "$dir\poll-$suffix-nodes.json"
  $task = (Get-Content "$dir\poll-$suffix-task.json" -Raw | ConvertFrom-Json).data
  Write-Output ("poll {0}: status={1}; completed={2}/{3}; waiting={4}; stage={5}" -f $suffix, $task.status, $task.completedNodes, $task.totalNodes, $task.waitingInterventionNodeCount, $task.currentStage)
  if ($task.status -ne "RUNNING" -and $task.status -ne "PENDING") { break }
  Start-Sleep -Seconds 5
}
curl.exe -s "http://localhost:9093/api/report/$taskId" > "$dir\report.json"
curl.exe -s "http://localhost:9093/api/report/$taskId/evidences" > "$dir\report-evidences.json"
```

Expected after implementation:

```text
百度认证/企业信息中介页不会进入 selectedTargets。
如果 app.bilibili.com 只返回登录/反爬壳，系统会先补采同域公开入口，例如 /about、/help、/download、/app 或 sitemap/robots 暴露入口。
理想结果是至少 1 条同域公开正文验证通过；次级结果是 PARTIAL_PUBLIC_EVIDENCE 且包含 attemptedAlternativeUrls；只有全部补采失败才允许 PUBLIC_SHELL_ONLY。
采集节点不再因为 discovery_reason 长度超过 500 而失败。
```

- [ ] **步骤 5：运行格式校验**

运行：

```powershell
git diff --check -- backend/src/main/java backend/src/test/java backend/src/main/resources/db/migration docs/superpowers/ExtractionStructured
```

预期：

```text
no output, exit code 0
```

---

## 3. 自检清单

- [ ] 未验证搜索补源不能进入正式采集目标。
- [ ] 百度认证、爱企查、企查查、天眼查、百科等中介页只能进入诊断候选。
- [ ] 主入口受限或低信号时，必须先触发 `PUBLIC_EVIDENCE_RECOVERY`，补采同域公开入口、sitemap/robots、canonical/OG/JSON-LD 链接。
- [ ] 字段证据路径未覆盖时，`PUBLIC_EVIDENCE_RECOVERY` 必须优先使用 `fieldName / evidencePathKey / queryIntents` 生成候选；pricing 路径不能退化成只补泛官网入口。
- [ ] `publicEvidenceAttemptedUrls`、`publicEvidenceAttemptedEvidencePaths`、`publicEvidenceRecoveryFieldName`、`publicEvidenceRecoveryEvidencePathKey`、`publicEvidenceRecoveryQueryIntents`、`publicEvidenceRecoveryCandidateCount`、`publicEvidenceRecoveryVerifiedCount` 必须进入 search trace，便于 live 复盘。
- [ ] 至少 1 条同域公开正文验证通过时，优先使用正式公开正文证据，而不是退回公开壳。
- [ ] 只有公开替代入口全部失败时，登录/验证码页面才可以恢复公开壳信息，且必须带 `PUBLIC_SHELL_ONLY`、`LOGIN_GATE_PARTIAL` 或 `ANTI_BOT_PARTIAL`。
- [ ] 公开壳恢复不提交登录表单、不破解验证码、不依赖授权态 cookie。
- [ ] `discovery_reason` 已迁移为 `TEXT`。
- [ ] EvidenceSource 落库前仍裁剪 `title/url/sourceDomain/publishedAt` 等长度受限字段。
- [ ] 采集失败时保留候选、selectedTargets、discardedCandidates、issueFlags、sourceUrls 和 persistenceFailureReason。
- [ ] 单元测试和 live 复验记录均写入 progress 文档。
