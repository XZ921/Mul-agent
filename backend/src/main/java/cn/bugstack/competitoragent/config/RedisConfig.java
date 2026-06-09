package cn.bugstack.competitoragent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Redis 运行时配置。
 * 这里集中管理 Task 2.5 需要的 TTL、锁时长和快照序列化器，
 * 避免缓存服务和锁服务在各自类里散落默认值。
 */
@Configuration
public class RedisConfig {

    @Bean("taskSnapshotObjectMapper")
    public ObjectMapper taskSnapshotObjectMapper() {
        // 这里显式创建独立副本，避免 Bean 方法再反向依赖 ObjectMapper 本身时形成循环引用。
        return new ObjectMapper().findAndRegisterModules();
    }

    @Bean
    @ConfigurationProperties(prefix = "task.runtime.redis")
    public TaskRuntimeRedisProperties taskRuntimeRedisProperties() {
        return new TaskRuntimeRedisProperties();
    }

    /**
     * Task 运行时 Redis 参数。
     * Duration 由 Spring Boot 直接绑定，支持 `30m`、`12h` 等可读格式。
     */
    @Data
    public static class TaskRuntimeRedisProperties {

        /**
         * 任务快照保留时长。
         * 主要服务于任务详情页首屏拉取、恢复判断和后续 SSE 衔接。
         */
        private Duration snapshotTtl = Duration.ofHours(12);

        /**
         * 关键中间态缓存时长。
         * 这里主要缓存已完成节点输出，供恢复执行和并发保护时快速重建上下文。
         */
        private Duration runtimeTtl = Duration.ofHours(12);

        /**
         * 整任务执行锁时长。
         * 时长需要覆盖单次 DAG 主链路，避免重复启动或恢复时并发进入。
         */
        private Duration taskLockTtl = Duration.ofMinutes(30);

        /**
         * 节点级执行锁时长。
         * 节点锁粒度更细，主要用于防止同一节点被并发调度或重复重跑。
         */
        private Duration nodeLockTtl = Duration.ofMinutes(10);
    }
}
