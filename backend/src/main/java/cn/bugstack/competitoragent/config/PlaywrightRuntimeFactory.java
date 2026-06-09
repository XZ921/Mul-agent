package cn.bugstack.competitoragent.config;

import com.microsoft.playwright.Playwright;

/**
 * Playwright 运行时工厂。
 * 用于在底层连接彻底断开时，按需重建新的 Playwright runtime，
 * 避免只能依赖 Spring 启动期那一个常驻实例。
 */
@FunctionalInterface
public interface PlaywrightRuntimeFactory {

    Playwright create();
}
