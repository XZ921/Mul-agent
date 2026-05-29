package cn.bugstack.competitoragent.source;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Mock 信息采集器 — 默认启用的模拟采集器，用于开发和演示
 * <p>
 * 返回模拟的页面数据，避免依赖 Playwright 浏览器二进制。
 * 设置 collector.mock=false 将使用 PlaywrightPageCollector 进行真实网页抓取。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "collector.mock", havingValue = "true", matchIfMissing = true)
public class MockSourceCollector implements SourceCollector {

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public CollectedPage collect(String url, String competitorName, String sourceType) {
        log.info("[Mock Collector] 模拟采集: url={}, competitor={}", url, competitorName);
        sleep(100);

        String now = LocalDateTime.now().format(DTF);
        String mockContent = """
                # 模拟采集内容

                这是从 %s 采集到的模拟页面内容。

                ## 产品概述
                %s 是一款创新的产品，提供丰富的功能和优秀的用户体验。

                ## 核心功能
                - 功能 A：帮助用户提高工作效率
                - 功能 B：支持团队协作和实时同步
                - 功能 C：提供数据分析和可视化报表

                ## 定价信息
                - 免费版：基础功能，适合个人用户
                - 专业版：$12/月，适合小团队
                - 企业版：$49/月，适合大型组织

                ## 用户评价
                用户普遍认为该产品易于上手，但也有反馈称高级功能的学习曲线较陡。
                """.formatted(url, competitorName);

        return CollectedPage.builder()
                .url(url)
                .title(competitorName + " - 官方网站（Mock 数据）")
                .content(mockContent)
                .snippet(mockContent.substring(0, Math.min(500, mockContent.length())))
                .metadata("{\"source\":\"mock\",\"url\":\"" + url + "\"}")
                .competitorName(competitorName)
                .sourceType(sourceType)
                .collectedAt(now)
                .success(true)
                .build();
    }

    @Override
    public List<CollectedPage> collectBatch(List<String> urls, String competitorName, String sourceType) {
        List<CollectedPage> results = new ArrayList<>();
        List<String> limited = urls.size() > 5 ? urls.subList(0, 5) : urls;
        for (String url : limited) {
            results.add(collect(url, competitorName, sourceType));
        }
        return results;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
