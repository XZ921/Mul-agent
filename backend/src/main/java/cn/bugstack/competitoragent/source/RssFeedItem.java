package cn.bugstack.competitoragent.source;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * RSS item 的最小结构化结果。
 * 这里保留 feed 标题、文章标题、链接、发布时间、摘要和可追溯 sourceUrls，
 * 供后续统一映射进 CollectionExecutionResult。
 */
@Value
@Builder(toBuilder = true)
public class RssFeedItem {

    String feedTitle;
    String title;
    String link;
    String publishedAt;
    String summary;
    List<String> sourceUrls;
}
