package cn.bugstack.competitoragent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AI 竞品分析 Agent 协作系统 — 启动入口
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class CompetitorAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(CompetitorAgentApplication.class, args);
    }
}
