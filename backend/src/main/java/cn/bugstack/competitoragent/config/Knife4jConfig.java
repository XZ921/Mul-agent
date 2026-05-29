package cn.bugstack.competitoragent.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springdoc.core.models.GroupedOpenApi;

/**
 * Knife4j / springdoc-openapi 配置
 * <p>
 * 显式声明 GroupedOpenApi：
 * 1. 让 Knife4j 使用稳定的 ASCII group key，避免中文分组路径触发“文档请求异常”；
 * 2. 通过 displayName 保留中文分组名称，UI 展示依旧友好。
 */
@Configuration
public class Knife4jConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("AI 竞品分析 Agent 协作系统")
                        .description("多 Agent 协作竞品分析平台 API 文档")
                        .version("1.0.0")
                        .contact(new Contact().name("开发团队").email("dev@bugstack.cn"))
                        .license(new License().name("Apache 2.0").url("https://www.apache.org/licenses/LICENSE-2.0")));
    }

    @Bean
    public GroupedOpenApi taskGroup() {
        return GroupedOpenApi.builder()
                .group("task-management")
                .displayName("任务管理")
                .pathsToMatch("/api/task/**")
                .build();
    }

    @Bean
    public GroupedOpenApi allApiGroup() {
        return GroupedOpenApi.builder()
                .group("all-apis")
                .displayName("全部接口")
                .pathsToMatch("/api/**")
                .build();
    }

    @Bean
    public GroupedOpenApi reportGroup() {
        return GroupedOpenApi.builder()
                .group("report-management")
                .displayName("报告管理")
                .pathsToMatch("/api/report/**")
                .build();
    }

    @Bean
    public GroupedOpenApi schemaGroup() {
        return GroupedOpenApi.builder()
                .group("schema-management")
                .displayName("Schema 管理")
                .pathsToMatch("/api/schema/**")
                .build();
    }

    @Bean
    public GroupedOpenApi agentLogGroup() {
        return GroupedOpenApi.builder()
                .group("agent-log")
                .displayName("Agent 日志")
                .pathsToMatch("/api/agent-log/**")
                .build();
    }
}
