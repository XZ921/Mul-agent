package cn.bugstack.competitoragent.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 异步任务配置 — 开启 @Async 支持
 * <p>
 * 任务执行（DAG 工作流）通过 @Async 异步化，
 * 创建任务后立即返回 HTTP 响应，后台执行不阻塞。
 */
@Configuration
@EnableAsync
public class AsyncConfig {
}
