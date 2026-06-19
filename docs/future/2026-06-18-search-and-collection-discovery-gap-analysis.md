# 搜索与采集链路：现状、盲区与改进方向

> 2026-06-18 讨论整理。聚焦"只知道竞品名字 → 找到 URL → 发现内部页面 → 采集内容"的完整链路。

---

## 1. 当前链路全景

```
┌──────────────────────────────────────────────────────────────┐
│ 规划期 (任务创建时)                                             │
│                                                               │
│  ① Direct Discovery                                         │
│     有 providedUrl → 提取根域 → directPathTemplates 展开路径    │
│     无 providedUrl → 全空                                     │
│  ② Heuristic Discovery                                      │
│     有根域 → 拼接 /docs, /pricing, /blog 等硬编码路径           │
│     无根域 → 全空                                             │
│  ③ Search Provider (RoutingSearchSourceProvider)             │
│     千帆 → SerpAPI → BrowserPreview → HTTP                   │
│     无 API key → 全部 isAvailable()=false → 全空              │
│                                                               │
│  结果: 无 URL 输入时规划期几乎空转                                │
└──────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌──────────────────────────────────────────────────────────────┐
│ 运行期 (任务执行时, SearchExecutionCoordinator)                  │
│                                                               │
│  LOAD_CANDIDATES        → 0 条候选                             │
│  VERIFY_TOP_CANDIDATES  → 跳过                                 │
│  BROWSER_SUPPLEMENT_SEARCH → 百度/Bing Playwright 搜索         │
│     自动生成 query: "竞品名 OFFICIAL"                           │
│     解析搜索结果页 DOM → 提取 URL/标题/snippet                   │
│     打开前 3 条结果做轻量预览                                    │
│  SELECT_TARGETS         → 排序去重                              │
│                                                               │
│  结果: 全程靠百度，百度给什么就拿什么                              │
└──────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌──────────────────────────────────────────────────────────────┐
│ 采集期                                                        │
│                                                               │
│  LIGHTWEIGHT: JinaReader → 免费免费, 1-2s, 不执行 JS          │
│      失败 → 升级到 Playwright (FULL_RENDER)                    │
│  FULL_RENDER: Playwright → 8-15s, 可渲染 SPA                  │
│                                                               │
│  问题: 采集完一个 URL 就结束，页面里的内部链接完全被忽略          │
└──────────────────────────────────────────────────────────────┘
```

---

## 2. 已确认的盲区

### 盲区 1：无 URL 输入时规划期全空

当前 `HeuristicSourceDiscoveryService` 和 `SourceFamilyDirectDiscoveryPlanner` 都依赖外部提供的 `competitorUrls`。不给 URL，它们什么都产不出。

### 盲区 2：搜索不会反哺域名发现

浏览器搜索拿到的域名（`midjourney.com`）不会回流给 `directDiscoveryPlanner` 做模板展开。搜索结果直接当候选结束，错失了 `/docs`、`/pricing`、`/help` 等派生页面。

### 盲区 3：子域不可见

`directPathTemplates` 的设计是路径级展开（`root + /docs`），没有子域级展开能力。中国互联网常见的 `open.bilibili.com`、`developer.baidu.com`、`open.douyin.com` 全都碰不到。

### 盲区 4：采集即终点，无内部链接发现

`WebPageCollectionExecutor` 采集完一个页面就返回，不提取页面内的 same-domain 链接。导航栏、侧边栏、正文内的链接全部被丢弃。

### 盲区 5：过度依赖 Playwright

搜索和采集都重度依赖 Playwright，而它在 Windows 环境下不稳定（管道断开、启动失败、反爬阻断）。80% 的页面其实不需要浏览器渲染。

---

## 3. URL 发现：四层互补策略

按成本从低到高、速度从快到慢串联：

```
输入: 竞品名字 "Midjourney"
  │
  ├── ① LLM 直接问 (1s, ~0.001元, 已有 DeepSeek)
  │      prompt: "Midjourney 的官网/文档/定价/GitHub URL 是什么？返回 JSON"
  │      → midjourney.com, docs.midjourney.com
  │      优势: 模型已经知道答案，不依赖搜索
  │
  ├── ② 域名推测 + 验证 (并行, 3s, 免费)
  │      候选: midjourney.com, midjourney.ai, midjourney.io
  │      HTTP HEAD → 命中的入候选池
  │      劣势: 中文名不适用 (字节跳动 ≠ zijietiaodong.com)
  │
  ├── ③ Sitemap 解析 (1-2s, 免费)
  │      GET {domain}/robots.txt → sitemap URL
  │      GET sitemap.xml → 全站页面列表
  │
  └── ④ 浏览器搜索 (10s, Playwright, 兜底)
        百度/Bing 搜 "Midjourney official"
        仅当 ①②③ 产出不足时触发
```

**关键闭环**：④ 的结果提取根域后，回喂给 `SourceFamilyDirectDiscoveryPlanner` 做模板展开。

### 已有基础

- ① LLM：`OpenAiCompatibleClient` + DeepSeek 已配置，可直接调用
- ② 域名推测：需要新建，纯 HTTP HEAD，无外部依赖
- ③ Sitemap：需要新建，纯 HTTP GET + XML 解析
- ④ 浏览器搜索：`BrowserSearchRuntimeService` 已实现

---

## 4. 内部页面发现：三级深度

以 bilibili.com 为例：

### 第一级：多入口并行采集

```
URL 发现阶段把关键入口都找出来 → 并行采集

  bilibili.com              (首页，看一次)
  open.bilibili.com/doc/     (开放平台，文档入口)
  live.bilibili.com          (直播，产品切面)
  game.bilibili.com          (游戏，产品切面)
  manga.bilibili.com         (漫画，产品切面)
  show.bilibili.com          (会员购，商业化)
  ir.bilibili.com            (投资者关系，公司基本面)
```

改动最小——URL 发现做扎实，采集层一次喂多个候选即可。

### 第二级：文档型页面递归

`open.bilibili.com/doc/` 里包含侧边栏导航，指向几十个子页面：

```
采集 /doc/ → 提取侧边栏/导航链接 →
  /doc/quickstart/register.html
  /doc/api/auth.html
  /doc/sdk/android.html
  ... → 逐页采集，深度限制 2-3 层
```

需要新建一个轻量的"导航链接提取器"，在 `WebPageCollectionExecutor` 采集后调用。

### 第三级：动态面持续监控

定价变更、API 改动、新功能上线——需要定期重采 + diff：

```
首次: 全量采集 → 存为基线快照
后续: 每周重采 → diff → 只报告变化
```

---

## 5. Playwright 降级：从主力到兜底

当前 Playwright 承载了搜索 + 采集两条主链路的产品责任。改进方向：

```
                       现在                   改进后
                     ─────────             ─────────
搜索 URL       Playwright 浏览器搜索    LLM + sitemap + 搜索 API (主力)
                                        Playwright 浏览器搜索 (兜底)

采集静态页面    JinaReader              JinaReader (主力)
采集 JS 页面    Playwright              Playwright (兜底, 不变)
导航链接提取    不存在                   sitemap + LLM (主力)
                                        Playwright DOM 提取 (辅助)
```

80% 的竞争情报页面是静态或轻 JS 的，JinaReader 完全够用。Playwright 只留给抖音、B 站内容页等真正需要浏览器渲染的场景。

---

## 6. 数据流闭环（改进后的全貌）

```
输入: 竞品名字 "哔哩哔哩"

Phase 1 — URL 发现 (规划期)
  ├── LLM 问: 哔哩哔哩有哪些子站？
  │     → bilibili.com, open.bilibili.com, live.bilibili.com, ...
  ├── 对每个域名做 directPathTemplates 展开
  │     → /docs, /pricing, /help, /about, ...
  ├── Sitemap 解析 (尝试)
  └── 搜索 API (如有 key) / 浏览器搜索 (兜底)
  → 合并去重 → 候选池

Phase 2 — 采集 (运行期)
  ├── 并行采集候选池中的 URL (JinaReader 主力)
  ├── 采集每个页面时:
  │     ├── 提取内部链接 (nav/sidebar/a)
  │     ├── 识别文档树结构
  │     └── 将新链接回灌候选池
  └── Playwright 仅用于 JS 页面兜底

Phase 3 — 后处理
  ├── 去重 + 按 sourceType 分桶
  ├── 结构化 block 提取 (定价块 / 文档大纲 / JSON-LD)
  └── 输出竞品全貌报告
```

---

## 7. 落地优先级建议

| 优先级 | 事项 | 改动范围 | Playwright 依赖 |
|--------|------|---------|----------------|
| P0 | Config readiness 收口 (已 plan) | yml + 5 个类 | 不变 |
| P1 | LLM 域名发现 | 新建 1 个类 | 无 |
| P1 | 搜索结果 → 根域 → 模板展开闭环 | SearchExecutionCoordinator | 不变 |
| P2 | Sitemap 解析 | 新建 1 个类 | 无 |
| P2 | 采集后内部链接提取 + 回灌 | WebPageCollectionExecutor | 无 |
| P3 | 文档树递归采集 | 新建 1 个类 | 无 |
| P3 | 子域展开 (open./developer./docs.) | SourceFamilyDirectDiscoveryPlanner | 无 |
| P4 | 持续监控 + diff | 多个类 | 部分 |

---

## 8. 相关文件索引

| 文件 | 职责 |
|------|------|
| `search/SearchExecutionCoordinator.java` | 运行期搜索编排，四步管线 |
| `search/SourceFamilyDirectDiscoveryPlanner.java` | 根域 + 模板展开 |
| `search/SearchPolicyResolver.java` | 策略解释、directPathTemplates 解析 |
| `search/BrowserSearchRuntimeService.java` | Playwright 浏览器搜索 |
| `search/SearchSourceCatalogProperties.java` | 家族配置、directPathTemplates 默认值 |
| `source/RoutingSearchSourceProvider.java` | 搜索 provider 路由器 |
| `source/HeuristicSourceDiscoveryService.java` | 规划期发现、硬编码路径拼接 |
| `source/JinaReaderClient.java` | 轻量正文采集 (HTTP, 免费) |
| `source/JinaReaderProperties.java` | Jina 配置 (bearer-token 未暴露) |
| `collection/WebPageCollectionExecutor.java` | 网页采集执行器 (JinaReader → Playwright 升级) |
| `collection/GithubApiCollectionExecutor.java` | GitHub API 采集 (不检查 enabled) |
| `llm/OpenAiCompatibleClient.java` | LLM 调用客户端 (DeepSeek, 已可用) |
| `config/AiProviderProperties.java` | AI provider 配置 |

---

## 9. 当前落地状态（2026-06-18 收口）

截至 2026-06-18，本文前面列出的核心闭环已经在工程中完成落地，现状如下：

- 搜索结果已可回灌 `SourceFamilyDirectDiscoveryPlanner`，从根域继续展开 `docs`、`pricing`、`help` 与 `open.`、`developer.`、`docs.`、`help.` 等稳定入口。
- 只给竞品名称时，链路已可通过 LLM/规则域名发现、sitemap/robots 发现与运行期搜索补源协同产出候选，而不是只依赖单一浏览器搜索。
- `WebPageCollectionExecutor` 已能在成功采集入口页后提取同域高价值内部链接，并把递归候选回传给 `CollectionExecutionCoordinator`。
- `CollectionExecutionCoordinator` 已实现有界递归：
- 最大深度由 `collection.internal-link-discovery.max-depth` 控制
- 每个入口页递归数量由 `max-links-per-entry` 控制
- 整体节点递归数量由 `max-links-per-node` 控制
- `CollectorAgent` 已把入口页与递归子页统一收口到 `documents`、`collectionAudit.results`、`collectionAudit.replayTimeline`、`searchProgressSnapshots` 与 `sourceUrls`。
- `sourceUrls`、`collectionAudit`、`replayTimeline`、checkpoint reuse 在递归链路下已保持可追溯性，没有因为深采集引入第二套输出语义。

## 10. 已完成验证

- 已补充端到端测试 [SearchAndCollectionDeepDiscoveryIntegrationTest](/E:/java_study/Mul-agnet/backend/src/test/java/cn/bugstack/competitoragent/integration/SearchAndCollectionDeepDiscoveryIntegrationTest.java)。
- 该测试覆盖“运行期补源命中文档入口 -> 入口页发现内部子页 -> 输出包含 `sourceUrls` 与 `replayTimeline`”闭环。
- 本轮定向验证已包含：
- `InternalLinkDiscoveryServiceTest`
- `WebPageCollectionExecutorRouteTest`
- `CollectionExecutionCoordinatorTest`
- `CollectorAgentTest`
- `CollectionAuditContractTest`
- `CollectionAuditSerializationTest`
- `CollectionExecutionCoordinatorCheckpointReuseTest`
- `WebPageCollectionExecutorContextTest`
- `SearchAndCollectionDeepDiscoveryIntegrationTest`

## 11. 剩余风险与下一阶段建议

当前剩余项主要不是功能缺口，而是验证与演进方向：

- 还可以执行更大范围的 focused regression 或 `mvn -pl backend test`，进一步确认脏工作区下的全局兼容性。
- 当前端到端测试是“真实协调器 + mock 边界依赖”的黑盒集成测试，不是重型 Spring 全链路；后续如果要覆盖更多真实基础设施，可单独补一层容器级集成测试。
- 内部链接发现目前聚焦 Markdown/HTML 显式链接与高价值路径关键字，后续如需覆盖更复杂导航树、脚本注入菜单或 SPA 路由，可在不破坏当前有界递归约束的前提下迭代。
- Playwright 已从主力路径退到兜底路径，但对于强交互站点仍是必要能力；后续优化应继续坚持“静态优先、浏览器兜底”的边界，不建议重新把主链路绑回浏览器搜索或全渲染采集。
