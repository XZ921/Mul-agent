# Tavily Search API 测试总结

本文档整理当前项目中 Tavily Search API PoC 之后追加的两轮测试输入、输出文件关系，以及对搜索和采集质量的判断。

注意：本文档不记录 Tavily API Key。运行测试时仍应通过环境变量或命令行参数传入 Key。

## 1. 当前 Java PoC

当前项目入口：

```text
src/main/java/com/travel/Main.java
```

当前 Java 代码是最小化 PoC，只包含最初的一条综合搜索词：

```text
抖音 哔哩哔哩 竞品分析 核心功能 技术能力 推荐算法 用户画像 价格策略 商业化 2026 最新对比
```

固定请求参数：

```json
{
  "search_depth": "advanced",
  "include_raw_content": true,
  "max_results": 5
}
```

这个 PoC 的目标是验证：

```text
Java HttpClient -> Tavily Search API -> advanced search -> raw_content -> JSON pretty print
```

后续两轮质量测试没有写进 Java 代码，是通过临时脚本直接调用 Tavily API 完成的。

## 2. raw 与 summary 文件的关系

测试输出文件通常成对出现：

```text
*-raw.json
*-summary.json
```

二者关系：

```text
*-raw.json
  保存 Tavily API 原始响应，负责保真。

*-summary.json
  基于 raw 做去重、分类、统计、截断预览和初步质量判断，负责好读。
```

处理流程：

```text
Tavily API response
  -> 保存完整响应到 raw.json
  -> 合并多条 query
  -> URL 去重
  -> 统计 raw_content 长度
  -> 判断来源类型和主题命中
  -> 生成 summary.json
```

建议使用方式：

```text
1. 先看 summary.json 判断整体搜索质量。
2. 选出可用 URL。
3. 回到 raw.json 查看对应 URL 的完整 raw_content。
4. 对 raw_content 做二次清洗后再进入竞品分析。
```

## 3. 测试一：推荐算法 / 内容分发机制

测试目标：

```text
只选一个竞品分析中最有代表性的维度，测试 Tavily 是否能发现和采集可用于分析的公开资料。
```

选择维度：

```text
推荐算法 / 内容分发机制
```

选择原因：

```text
推荐算法和内容分发机制直接影响用户留存、创作者生态、内容消费路径和商业化效率，
是抖音与哔哩哔哩竞品分析中最核心的维度之一。
```

输出文件：

```text
target/tavily-recommendation-algorithm-raw.json
target/tavily-recommendation-algorithm-summary.json
```

公共请求参数：

```json
{
  "search_depth": "advanced",
  "include_raw_content": true,
  "max_results": 5
}
```

使用的 query：

```text
抖音 哔哩哔哩 推荐算法 对比
```

```text
抖音 B站 推荐机制 内容分发 差异
```

```text
抖音 bilibili 推荐系统 流量分发 算法 对比
```

统计结果：

```json
{
  "totalQueries": 3,
  "rawResultCount": 15,
  "uniqueUrlCount": 14,
  "duplicateUrlCount": 1,
  "strongCount": 9,
  "mediumCount": 4,
  "weakCount": 1,
  "irrelevantCount": 0,
  "rawContentUsableCount": 13,
  "hasRawContentCount": 14,
  "bothPlatformsAndAlgorithmCount": 10,
  "domainCount": 10
}
```

人工复核后的质量判断：

```text
核心可用素材：约 4 条
辅助素材：约 5 条
建议剔除：约 5 条
```

结论：

```text
Tavily 对推荐算法 / 内容分发机制这个维度的搜索和正文抓取是可用的。
但原始返回中会混入搜索页、视频页、论坛帖、泛推荐系统课程等低价值结果。
因此不能直接拿返回结果生成竞品分析，需要做 URL 去重、来源分类和页面类型过滤。
```

## 4. 测试二：官方来源 / 官方文档发现能力

测试目标：

```text
验证 Tavily 是否能搜到官方域名和真正的官方文档，而不是只返回博客、媒体文章、产品经理文章和视频内容。
```

这轮分两种策略：

```text
1. query 中加入“官方 / 规则 / 帮助中心 / 文档”等词，但不限定域名。
2. 使用 include_domains 强制限定抖音和 B站官方相关域名。
```

输出文件：

```text
target/tavily-official-source-test-raw.json
target/tavily-official-source-test-summary.json
target/tavily-official-docs-test-summary.json
```

### 4.1 不限定域名，只增强官方意图

请求输入：

```json
{
  "query": "抖音 哔哩哔哩 推荐算法 内容分发 官方 规则 帮助中心 文档",
  "search_depth": "advanced",
  "include_raw_content": true,
  "max_results": 8
}
```

### 4.2 限定抖音与 B站官方相关域名

请求输入：

```json
{
  "query": "推荐算法 内容分发 规则 创作者 流量 抖音 哔哩哔哩",
  "search_depth": "advanced",
  "include_raw_content": true,
  "max_results": 8,
  "include_domains": [
    "douyin.com",
    "open.douyin.com",
    "docs.open-douyin.com",
    "creator.douyin.com",
    "oceanengine.com",
    "bilibili.com",
    "member.bilibili.com",
    "openhome.bilibili.com",
    "cm.bilibili.com",
    "ad.bilibili.com"
  ]
}
```

### 4.3 只搜抖音官方相关域名

请求输入：

```json
{
  "query": "抖音 推荐算法 内容分发 规则 创作者 流量 官方",
  "search_depth": "advanced",
  "include_raw_content": true,
  "max_results": 8,
  "include_domains": [
    "douyin.com",
    "open.douyin.com",
    "docs.open-douyin.com",
    "creator.douyin.com",
    "oceanengine.com"
  ]
}
```

### 4.4 只搜 B站官方相关域名

请求输入：

```json
{
  "query": "哔哩哔哩 B站 推荐算法 内容分发 规则 创作者 流量 官方",
  "search_depth": "advanced",
  "include_raw_content": true,
  "max_results": 8,
  "include_domains": [
    "bilibili.com",
    "member.bilibili.com",
    "openhome.bilibili.com",
    "cm.bilibili.com",
    "ad.bilibili.com"
  ]
}
```

统计结果：

```json
{
  "rawResults": 32,
  "uniqueUrls": 30,
  "officialResults": 24,
  "officialDocLikeResults": 9,
  "lowValueOfficialSearchOrVideo": 7,
  "rawUsable": 23
}
```

结论：

```text
Tavily 可以搜到官方域名内容。
但官方域名内容不等于官方文档。
限定 douyin.com / bilibili.com 后，仍然会返回视频页、搜索页、专栏页等低价值结果。
如果要找真正的官方文档，需要使用更明确的 query 和更窄的 include_domains。
```

## 5. 测试三：官方文档定向搜索

测试目标：

```text
进一步验证 Tavily 是否能命中开放平台、API 文档、规则中心、协议、创作者规则这类真正文档型内容。
```

输出文件：

```text
target/tavily-official-docs-test-summary.json
```

### 5.1 抖音开放平台文档

请求输入：

```json
{
  "query": "抖音 开放平台 API 官方文档 数据接口 内容管理",
  "search_depth": "advanced",
  "include_raw_content": true,
  "max_results": 5,
  "include_domains": [
    "open.douyin.com",
    "docs.open-douyin.com"
  ]
}
```

命中的典型结果：

```text
平台简介 - 抖音开放平台
抖音内容发布接入方案
SDK概述 - 抖音开放平台
查询视频分享结果及数据 - 抖音开放平台
```

### 5.2 B站开放平台文档

请求输入：

```json
{
  "query": "哔哩哔哩 B站 开放平台 API 官方文档 开发者 文档",
  "search_depth": "advanced",
  "include_raw_content": true,
  "max_results": 5,
  "include_domains": [
    "openhome.bilibili.com",
    "open.bilibili.com",
    "bilibili.com"
  ]
}
```

命中的典型结果：

```text
哔哩哔哩开放平台
哔哩哔哩开放平台文档页
哔哩哔哩开发者服务协议
哔哩哔哩直播开放文档
```

### 5.3 抖音规则 / 帮助类内容

请求输入：

```json
{
  "query": "抖音 创作者 规则 官方 帮助中心 内容推荐 流量 推荐机制",
  "search_depth": "advanced",
  "include_raw_content": true,
  "max_results": 5,
  "include_domains": [
    "douyin.com",
    "creator.douyin.com",
    "www.douyin.com"
  ]
}
```

命中的典型结果：

```text
抖音社区自律公约 - 抖音规则中心
“抖音”用户服务协议
抖音创作者中心
```

### 5.4 B站创作者规则类内容

请求输入：

```json
{
  "query": "哔哩哔哩 创作中心 官方 规则 帮助 推荐机制 流量",
  "search_depth": "advanced",
  "include_raw_content": true,
  "max_results": 5,
  "include_domains": [
    "member.bilibili.com",
    "www.bilibili.com",
    "bilibili.com"
  ]
}
```

命中的典型结果：

```text
bilibili创作激励计划规则
哔哩哔哩协议汇总
哔哩哔哩 ESG 报告
```

结论：

```text
Tavily 能搜到官方文档。
但必须使用明确的“开放平台 / API / 官方文档 / 规则 / 协议 / 创作者”等关键词，
并配合 include_domains 限定官方文档域名。
```

## 6. 关于视频页提取

测试中命中过抖音视频页：

```text
https://www.douyin.com/shipin/7261540282209126461
```

Tavily 返回：

```json
{
  "title": "抖音算法机制是什么",
  "score": 0.69274646,
  "contentLength": 2114,
  "rawContentLength": 44809
}
```

判断：

```text
Tavily 能搜索到视频页，并提取视频页上的文本。
如果页面暴露了 AI 文稿、字幕、口播转写、标题、描述、标签等内容，Tavily 可能会抓到。
```

限制：

```text
Tavily 不是直接下载视频文件。
Tavily 不是逐帧 OCR。
Tavily 不是直接听视频音频再转写。
视频页 raw_content 容易混入相关视频、猜你喜欢、热门推荐、封面图片链接等噪声。
```

建议分类：

```json
{
  "source_type": "video_page",
  "trust_level": "medium_or_low",
  "needs_cleaning": true
}
```

视频页适合作为：

```text
创作者经验观察
平台运营方法论线索
推荐机制外部理解
辅助素材
```

不适合作为：

```text
官方算法权重依据
平台内部机制结论
严肃报告的唯一证据来源
```

## 7. 总体判断

本次测试可以得出以下判断：

```text
1. Tavily 适合作为公开网络资料发现和正文采集层。
2. include_raw_content=true 对文章、PDF、部分视频页都有实际效果。
3. content 是面向 query 的相关片段，不是全文。
4. raw_content 更接近清洗后的正文，但仍可能包含噪声。
5. Tavily 默认不会保证优先返回官方文档。
6. 官方资料需要 include_domains 和明确 query 配合。
7. 视频页可作为辅助材料，但需要清洗和降权。
8. 做竞品分析时必须增加来源分级、URL 去重、页面类型过滤和多源交叉验证。
```

推荐的后处理策略：

```text
1. 多 query 搜索。
2. URL 规范化去重。
3. 剔除搜索页、tag 页、视频列表页。
4. 区分官方文档、媒体文章、研报、产品分析、视频页、论坛帖。
5. 对视频页删除“相关视频 / 猜你喜欢 / 热门推荐”之后的内容。
6. 对官方来源和第三方来源分别建素材池。
7. 最终报告中标注来源类型和可信等级。
```

推荐来源分层：

```text
官方文档 / 官方规则 / 官方账号公开说明
  高可信，可作为主依据。

财报 / ESG / 招股书 / 权威研报 / 主流媒体深度稿
  中高可信，可作为主依据或强辅助。

产品经理文章 / 行业博客 / 运营经验帖 / 视频讲解
  中等可信，可作为素材，需要交叉验证。

搜索页 / 视频列表页 / 论坛闲聊 / 搬运号内容
  低可信，一般只做线索。
```

一句话总结：

```text
Tavily 是采集器，不是事实审计器。
它可以把竞品分析需要的公开资料捞上来，
但要经过去重、过滤、来源分级和交叉验证后，才能稳定进入竞品分析报告。
```
