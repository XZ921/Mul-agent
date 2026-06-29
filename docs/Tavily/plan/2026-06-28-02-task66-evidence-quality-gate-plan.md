# 02 Task 66 证据质量门禁实施计划

> **给 agentic workers：** 必须使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 按任务逐步执行本计划。步骤使用 checkbox（`- [ ]`）语法跟踪。

**目标：** 新增采集后 `EvidenceQualityGate`，防止公开壳、鉴权墙、薄正文和重复内容变成高可信报告证据。

**架构：** 在 `EvidenceSource` 入库前增加采集域门禁，对 `CollectionExecutionResult` 进行评估。门禁输出可用性评分、问题标记和封顶后的质量信号。`CollectorAgent` 将 verdict 写入证据 metadata，并用它降级不可用证据。

**技术栈：** Java 17、Spring Boot 配置属性、Jackson metadata map、JUnit 5、AssertJ。

---

## 文件结构

- 新建： `backend/src/main/java/cn/bugstack/competitoragent/collection/quality/EvidenceQualityGate.java`
- 新建： `backend/src/main/java/cn/bugstack/competitoragent/collection/quality/EvidenceQualityVerdict.java`
- 新建： `backend/src/main/java/cn/bugstack/competitoragent/collection/quality/EvidenceQualityIssue.java`
- 新建： `backend/src/main/java/cn/bugstack/competitoragent/collection/quality/EvidenceQualityGateProperties.java`
- 修改： `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionExecutionResult.java`
- 修改： `backend/src/main/java/cn/bugstack/competitoragent/agent/collector/CollectorAgent.java`
- 修改： `backend/src/main/resources/application.yml`
- 测试： `backend/src/test/java/cn/bugstack/competitoragent/collection/quality/EvidenceQualityGateTest.java`
- 测试： `backend/src/test/java/cn/bugstack/competitoragent/collection/quality/EvidenceQualityGatePropertiesTest.java`
- 测试： `backend/src/test/java/cn/bugstack/competitoragent/agent/collector/CollectorAgentEvidenceQualityGateTest.java`

---

### Task 1: 证据质量 verdict 模型

**文件：**
- 新建： `backend/src/main/java/cn/bugstack/competitoragent/collection/quality/EvidenceQualityIssue.java`
- 新建： `backend/src/main/java/cn/bugstack/competitoragent/collection/quality/EvidenceQualityVerdict.java`
- 修改： `backend/src/main/java/cn/bugstack/competitoragent/collection/CollectionExecutionResult.java`
- 测试： `backend/src/test/java/cn/bugstack/competitoragent/collection/quality/EvidenceQualityGateTest.java`

- [ ] **步骤 1：编写 verdict 模型测试**

创建 `EvidenceQualityGateTest.java`：

```java
package cn.bugstack.competitoragent.collection.quality;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceQualityGateTest {

    @Test
    void shouldExposeRepairRequiredForAuthGate() {
        EvidenceQualityVerdict verdict = EvidenceQualityVerdict.builder()
                .sourceAuthenticityScore(0.90D)
                .contentUsabilityScore(0.20D)
                .taskRelevanceScore(0.10D)
                .evidenceUsabilityScore(0.20D)
                .issues(List.of(EvidenceQualityIssue.AUTH_OR_CAPTCHA_GATE))
                .qualitySignals(List.of("AUTH_GATE_DETECTED", "EVIDENCE_REPAIR_REQUIRED"))
                .repairRequired(true)
                .build();

        assertThat(verdict.isRepairRequired()).isTrue();
        assertThat(verdict.getIssues()).contains(EvidenceQualityIssue.AUTH_OR_CAPTCHA_GATE);
        assertThat(verdict.getEvidenceUsabilityScore()).isEqualTo(0.20D);
    }
}
```

- [ ] **步骤 2：运行测试并确认失败**

运行：

```powershell
mvn -pl backend -Dtest=EvidenceQualityGateTest test
```

预期：编译失败，因为 verdict 类尚不存在。

- [ ] **步骤 3：新增问题枚举**

创建 `EvidenceQualityIssue.java`：

```java
package cn.bugstack.competitoragent.collection.quality;

public enum EvidenceQualityIssue {
    NAVIGATION_SHELL,
    AUTH_OR_CAPTCHA_GATE,
    ROOT_ENTRY_PAGE,
    LINK_FARM_WITHOUT_BODY,
    DUPLICATED_ENTRY_CONTENT,
    WEAK_MAIN_CONTENT,
    LOW_TASK_KEYWORD_DENSITY,
    HIGH_TRUST_LOW_USABILITY,
    SCORE_CONTRADICTION_DETECTED
}
```

- [ ] **步骤 4：新增 verdict 类**

创建 `EvidenceQualityVerdict.java`：

```java
package cn.bugstack.competitoragent.collection.quality;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder(toBuilder = true)
public class EvidenceQualityVerdict {

    double sourceAuthenticityScore;
    double contentUsabilityScore;
    double taskRelevanceScore;
    double evidenceUsabilityScore;
    List<EvidenceQualityIssue> issues;
    List<String> qualitySignals;
    boolean repairRequired;
}
```

- [ ] **步骤 5：给 CollectionExecutionResult 增加可选 verdict**

修改 `CollectionExecutionResult`：

```java
import cn.bugstack.competitoragent.collection.quality.EvidenceQualityVerdict;
```

增加字段：

```java
EvidenceQualityVerdict evidenceQualityVerdict;
```

- [ ] **步骤 6：运行模型测试**

运行：

```powershell
mvn -pl backend -Dtest=EvidenceQualityGateTest test
```

预期：测试通过。

### Task 2: 配置化中文鉴权信号

**文件：**
- 新建：`backend/src/main/java/cn/bugstack/competitoragent/collection/quality/EvidenceQualityGateProperties.java`
- 修改：`backend/src/main/resources/application.yml`
- 测试：`backend/src/test/java/cn/bugstack/competitoragent/collection/quality/EvidenceQualityGatePropertiesTest.java`

- [ ] **步骤 1：编写配置绑定测试**

创建 `EvidenceQualityGatePropertiesTest.java`：

```java
package cn.bugstack.competitoragent.collection.quality;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceQualityGatePropertiesTest {

    @Test
    void defaultAuthSignalsShouldCoverTask66ChineseGate() {
        EvidenceQualityGateProperties properties = new EvidenceQualityGateProperties();

        assertThat(properties.getAuthSignals())
                .contains("验证码", "智能验证", "由极验提供技术支持");
        assertThat(properties.getNavigationShellLinkRatioThreshold()).isEqualTo(0.55D);
    }
}
```

- [ ] **步骤 2：新增配置属性类**

创建 `EvidenceQualityGateProperties.java`：

```java
package cn.bugstack.competitoragent.collection.quality;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "collection.evidence-quality")
public class EvidenceQualityGateProperties {

    private boolean enabled = true;
    private int minUsefulParagraphLength = 80;
    private double navigationShellLinkRatioThreshold = 0.55D;
    private double authGateScoreCap = 0.20D;
    private double navigationShellScoreCap = 0.30D;
    private double rootEntryScoreCap = 0.45D;
    private List<String> authSignals = List.of(
            "验证码",
            "智能验证",
            "检测中",
            "登录",
            "注册",
            "请点击此处重试",
            "网络超时",
            "由极验提供技术支持",
            "完成身份信息填写",
            "去填写",
            "去接受邀请"
    );
}
```

- [ ] **步骤 3：新增 YAML 默认值**

修改 `backend/src/main/resources/application.yml`：

```yaml
collection:
  evidence-quality:
    enabled: true
    min-useful-paragraph-length: 80
    navigation-shell-link-ratio-threshold: 0.55
    auth-gate-score-cap: 0.20
    navigation-shell-score-cap: 0.30
    root-entry-score-cap: 0.45
    auth-signals:
      - 验证码
      - 智能验证
      - 检测中
      - 登录
      - 注册
      - 请点击此处重试
      - 网络超时
      - 由极验提供技术支持
      - 完成身份信息填写
      - 去填写
      - 去接受邀请
```

如果 `collection:` 已存在，只合并 `evidence-quality` 子树。

- [ ] **步骤 4：运行配置测试**

运行：

```powershell
mvn -pl backend -Dtest=EvidenceQualityGatePropertiesTest test
```

预期：测试通过。

### Task 3: 证据质量门禁规则

**文件：**
- 新建：`backend/src/main/java/cn/bugstack/competitoragent/collection/quality/EvidenceQualityGate.java`
- 测试：`backend/src/test/java/cn/bugstack/competitoragent/collection/quality/EvidenceQualityGateTest.java`

- [ ] **步骤 1：增加鉴权门禁和分数矛盾测试**

追加到 `EvidenceQualityGateTest.java`：

```java
@Test
void shouldCapAuthGateEvidenceEvenWhenSourceIsOfficial() {
    EvidenceQualityGate gate = new EvidenceQualityGate(new EvidenceQualityGateProperties());

    EvidenceQualityVerdict verdict = gate.evaluate(
            "https://open.bilibili.com",
            "OFFICIAL",
            "[主站] 开放平台 登录|注册 智能验证检测中 由极验提供技术支持",
            List.of("OFFICIAL_DOMAIN_MATCHED"),
            0.92D);

    assertThat(verdict.getIssues()).contains(EvidenceQualityIssue.AUTH_OR_CAPTCHA_GATE);
    assertThat(verdict.getContentUsabilityScore()).isLessThanOrEqualTo(0.20D);
    assertThat(verdict.getQualitySignals()).contains("AUTH_GATE_DETECTED", "EVIDENCE_REPAIR_REQUIRED");
}

@Test
void shouldDetectHighTrustLowUsabilityContradiction() {
    EvidenceQualityGate gate = new EvidenceQualityGate(new EvidenceQualityGateProperties());

    EvidenceQualityVerdict verdict = gate.evaluate(
            "https://open.bilibili.com",
            "OFFICIAL",
            "开放平台 文档中心 管理中心 登录 注册 帮助中心 联系我们 友情链接",
            List.of("OFFICIAL_DOMAIN_MATCHED", "HIGH_TRUST"),
            0.92D);

    assertThat(verdict.getIssues()).contains(EvidenceQualityIssue.HIGH_TRUST_LOW_USABILITY);
    assertThat(verdict.getQualitySignals()).contains("SCORE_CONTRADICTION_DETECTED");
}
```

- [ ] **步骤 2：实现门禁**

创建 `EvidenceQualityGate.java`：

```java
package cn.bugstack.competitoragent.collection.quality;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class EvidenceQualityGate {

    private final EvidenceQualityGateProperties properties;

    public EvidenceQualityGate(EvidenceQualityGateProperties properties) {
        this.properties = properties == null ? new EvidenceQualityGateProperties() : properties;
    }

    public EvidenceQualityVerdict evaluate(String url,
                                           String sourceType,
                                           String content,
                                           List<String> existingSignals,
                                           Double candidateQualityScore) {
        List<EvidenceQualityIssue> issues = new ArrayList<>();
        List<String> signals = new ArrayList<>();
        if (existingSignals != null) {
            signals.addAll(existingSignals);
        }

        String safeContent = content == null ? "" : content;
        boolean authGate = containsAny(safeContent, properties.getAuthSignals());
        boolean navigationShell = isNavigationShell(safeContent);
        boolean rootEntry = isRootEntry(url);
        double sourceScore = isOfficial(sourceType, url) ? 0.90D : 0.60D;
        double contentScore = StringUtils.hasText(safeContent) ? 0.70D : 0.0D;
        double taskScore = 0.50D;

        if (authGate) {
            issues.add(EvidenceQualityIssue.AUTH_OR_CAPTCHA_GATE);
            signals.add("AUTH_GATE_DETECTED");
            signals.add("EVIDENCE_REPAIR_REQUIRED");
            contentScore = Math.min(contentScore, properties.getAuthGateScoreCap());
        }
        if (navigationShell) {
            issues.add(EvidenceQualityIssue.NAVIGATION_SHELL);
            signals.add("NAVIGATION_SHELL_DETECTED");
            signals.add("EVIDENCE_REPAIR_REQUIRED");
            contentScore = Math.min(contentScore, properties.getNavigationShellScoreCap());
        }
        if (rootEntry) {
            issues.add(EvidenceQualityIssue.ROOT_ENTRY_PAGE);
            signals.add("ROOT_ENTRY_WEAK_CONTENT");
            taskScore = Math.min(taskScore, properties.getRootEntryScoreCap());
        }
        double usabilityScore = Math.min(sourceScore, Math.min(contentScore, taskScore));
        if ((candidateQualityScore != null && candidateQualityScore >= 0.85D) && usabilityScore <= 0.45D) {
            issues.add(EvidenceQualityIssue.HIGH_TRUST_LOW_USABILITY);
            issues.add(EvidenceQualityIssue.SCORE_CONTRADICTION_DETECTED);
            signals.add("HIGH_TRUST_LOW_USABILITY");
            signals.add("SCORE_CONTRADICTION_DETECTED");
        }
        return EvidenceQualityVerdict.builder()
                .sourceAuthenticityScore(sourceScore)
                .contentUsabilityScore(contentScore)
                .taskRelevanceScore(taskScore)
                .evidenceUsabilityScore(usabilityScore)
                .issues(issues)
                .qualitySignals(signals.stream().distinct().toList())
                .repairRequired(signals.contains("EVIDENCE_REPAIR_REQUIRED"))
                .build();
    }

    private boolean containsAny(String content, List<String> signals) {
        if (content == null || signals == null) {
            return false;
        }
        return signals.stream()
                .filter(StringUtils::hasText)
                .anyMatch(content::contains);
    }

    private boolean isNavigationShell(String content) {
        if (!StringUtils.hasText(content)) {
            return false;
        }
        int navHits = 0;
        for (String marker : List.of("首页", "文档中心", "管理中心", "帮助中心", "联系我们", "友情链接", "登录", "注册")) {
            if (content.contains(marker)) {
                navHits++;
            }
        }
        return navHits >= 4 && content.length() < 1200;
    }

    private boolean isRootEntry(String url) {
        if (!StringUtils.hasText(url)) {
            return false;
        }
        try {
            URI uri = URI.create(url);
            String path = uri.getPath();
            return path == null || path.isBlank() || "/".equals(path);
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isOfficial(String sourceType, String url) {
        String normalizedSourceType = sourceType == null ? "" : sourceType.toUpperCase(Locale.ROOT);
        return normalizedSourceType.contains("OFFICIAL") || normalizedSourceType.contains("DOCS");
    }
}
```

- [ ] **步骤 3：运行门禁测试**

运行：

```powershell
mvn -pl backend -Dtest=EvidenceQualityGateTest,EvidenceQualityGatePropertiesTest test
```

预期：测试通过。

### Task 4: EvidenceSource 落库前应用门禁

**文件：**
- 修改：`backend/src/main/java/cn/bugstack/competitoragent/agent/collector/CollectorAgent.java`
- 测试：`backend/src/test/java/cn/bugstack/competitoragent/agent/collector/CollectorAgentEvidenceQualityGateTest.java`

- [ ] **步骤 1：编写 collector 映射测试**

创建 `CollectorAgentEvidenceQualityGateTest.java`：

```java
package cn.bugstack.competitoragent.agent.collector;

import cn.bugstack.competitoragent.collection.CollectionExecutionResult;
import cn.bugstack.competitoragent.collection.quality.EvidenceQualityGate;
import cn.bugstack.competitoragent.collection.quality.EvidenceQualityGateProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CollectorAgentEvidenceQualityGateTest {

    @Test
    void shouldAttachEvidenceQualityVerdictToCollectionResult() {
        EvidenceQualityGate gate = new EvidenceQualityGate(new EvidenceQualityGateProperties());
        CollectionExecutionResult result = CollectionExecutionResult.builder()
                .executorType("WEB_SCRAPER")
                .success(true)
                .status("SUCCESS")
                .resourceLocator("https://open.bilibili.com")
                .content("开放平台 登录 注册 智能验证检测中 由极验提供技术支持")
                .qualitySignals(List.of("OFFICIAL_DOMAIN_MATCHED"))
                .qualityScore(0.92D)
                .build();

        CollectionExecutionResult gated = CollectorAgent.applyEvidenceQualityGateForTest(gate, "OFFICIAL", result);

        assertThat(gated.getEvidenceQualityVerdict()).isNotNull();
        assertThat(gated.getQualitySignals()).contains("AUTH_GATE_DETECTED", "SCORE_CONTRADICTION_DETECTED");
        assertThat(gated.getQualityScore()).isLessThanOrEqualTo(0.20D);
    }
}
```

- [ ] **步骤 2：向 CollectorAgent 注入门禁**

新增字段和构造参数：

```java
private final EvidenceQualityGate evidenceQualityGate;
```

仅在测试使用的 legacy 构造函数中默认使用 `new EvidenceQualityGate(new EvidenceQualityGateProperties())`。

- [ ] **步骤 3：新增测试 helper 和生产 helper**

在 `CollectorAgent` 中新增：

```java
public static CollectionExecutionResult applyEvidenceQualityGateForTest(EvidenceQualityGate gate,
                                                                        String sourceType,
                                                                        CollectionExecutionResult result) {
    return applyEvidenceQualityGate(gate, sourceType, result);
}

private static CollectionExecutionResult applyEvidenceQualityGate(EvidenceQualityGate gate,
                                                                  String sourceType,
                                                                  CollectionExecutionResult result) {
    if (gate == null || result == null) {
        return result;
    }
    EvidenceQualityVerdict verdict = gate.evaluate(
            result.getResourceLocator(),
            sourceType,
            result.getContent(),
            result.getQualitySignals(),
            result.getQualityScore());
    List<String> mergedSignals = new ArrayList<>();
    if (result.getQualitySignals() != null) {
        mergedSignals.addAll(result.getQualitySignals());
    }
    if (verdict.getQualitySignals() != null) {
        mergedSignals.addAll(verdict.getQualitySignals());
    }
    return result.toBuilder()
            .evidenceQualityVerdict(verdict)
            .qualitySignals(mergedSignals.stream().distinct().toList())
            .qualityScore(verdict.getEvidenceUsabilityScore())
            .build();
}
```

新增 gate/verdict 和 `ArrayList` import。

- [ ] **步骤 4：在证据落库前调用 helper**

在 `CollectionExecutionResult` 映射为 `EvidenceSource` 的位置调用：

```java
collectionResult = applyEvidenceQualityGate(evidenceQualityGate, matchedCandidate.getSourceType(), collectionResult);
```

使用每条持久化路径中已经可用的 candidate/source type。

- [ ] **步骤 5：持久化 verdict metadata**

在 `serializeCollectionResultMetadata` 中加入：

```java
metadata.put("evidenceQualityVerdict", result.getEvidenceQualityVerdict());
```

- [ ] **步骤 6：运行本阶段测试**

运行：

```powershell
mvn -pl backend -Dtest=EvidenceQualityGateTest,EvidenceQualityGatePropertiesTest,CollectorAgentEvidenceQualityGateTest test
```

预期：测试通过。
