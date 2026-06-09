package cn.bugstack.competitoragent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * RocketMQ 工作流事件配置。
 * <p>
 * Phase 4 开始，RocketMQ 是内部编排事件的正式承载基座。
 * 这个配置对象需要同时表达：
 * 1. 是否启用 RocketMQ 工作流通道
 * 2. 当前环境是否要求该通道必须可用
 * 3. Topic / Tag / Outbox 扫描与重试参数
 */
@ConfigurationProperties(prefix = "rocketmq")
public class RocketMqProperties {

    private boolean enabled;
    private boolean required;
    private String nameServer;
    private Producer producer = new Producer();
    private Consumer consumer = new Consumer();
    private Workflow workflow = new Workflow();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isRequired() {
        return required;
    }

    public void setRequired(boolean required) {
        this.required = required;
    }

    public String getNameServer() {
        return nameServer;
    }

    public void setNameServer(String nameServer) {
        this.nameServer = nameServer;
    }

    public Producer getProducer() {
        return producer;
    }

    public void setProducer(Producer producer) {
        this.producer = producer == null ? new Producer() : producer;
    }

    public Consumer getConsumer() {
        return consumer;
    }

    public void setConsumer(Consumer consumer) {
        this.consumer = consumer == null ? new Consumer() : consumer;
    }

    public Workflow getWorkflow() {
        return workflow;
    }

    public void setWorkflow(Workflow workflow) {
        this.workflow = workflow == null ? new Workflow() : workflow;
    }

    /**
     * 执行入口在真正发起异步编排之前，必须先做显式配置校验。
     * 如果 RocketMQ 已经被定义为正式主链路，就不能在缺配置时静默退回同步执行。
     */
    public void validateForExecution() {
        if (!enabled) {
            throw new IllegalStateException("rocketmq.enabled=false");
        }
        assertHasText(nameServer, "rocketmq.name-server");
        assertHasText(producer.getGroup(), "rocketmq.producer.group");
        assertHasText(consumer.getGroup(), "rocketmq.consumer.group");
        assertHasText(workflow.getTopic(), "rocketmq.workflow.topic");
        assertHasText(workflow.getDispatchTag(), "rocketmq.workflow.dispatch-tag");
        assertHasText(workflow.getLifecycleTag(), "rocketmq.workflow.lifecycle-tag");
        if (workflow.getOutbox().getMaxRetries() < 1) {
            throw new IllegalStateException("rocketmq.workflow.outbox.max-retries must be >= 1");
        }
        if (workflow.getOutbox().getBatchSize() < 1) {
            throw new IllegalStateException("rocketmq.workflow.outbox.batch-size must be >= 1");
        }
        if (workflow.getOutbox().getScanInterval() == null
                || workflow.getOutbox().getScanInterval().isZero()
                || workflow.getOutbox().getScanInterval().isNegative()) {
            throw new IllegalStateException("rocketmq.workflow.outbox.scan-interval must be positive");
        }
    }

    private void assertHasText(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(propertyName + " is required");
        }
    }

    public static class Producer {

        private String group;
        private int sendMessageTimeoutMillis = 3000;

        public String getGroup() {
            return group;
        }

        public void setGroup(String group) {
            this.group = group;
        }

        public int getSendMessageTimeoutMillis() {
            return sendMessageTimeoutMillis;
        }

        public void setSendMessageTimeoutMillis(int sendMessageTimeoutMillis) {
            this.sendMessageTimeoutMillis = sendMessageTimeoutMillis;
        }
    }

    public static class Consumer {

        private String group;

        public String getGroup() {
            return group;
        }

        public void setGroup(String group) {
            this.group = group;
        }
    }

    public static class Workflow {

        private String topic = "task-workflow-events";
        private String dispatchTag = "TASK_EXECUTION_REQUESTED";
        private String lifecycleTag = "NODE_LIFECYCLE";
        private Outbox outbox = new Outbox();

        public String getTopic() {
            return topic;
        }

        public void setTopic(String topic) {
            this.topic = topic;
        }

        public String getDispatchTag() {
            return dispatchTag;
        }

        public void setDispatchTag(String dispatchTag) {
            this.dispatchTag = dispatchTag;
        }

        public String getLifecycleTag() {
            return lifecycleTag;
        }

        public void setLifecycleTag(String lifecycleTag) {
            this.lifecycleTag = lifecycleTag;
        }

        public Outbox getOutbox() {
            return outbox;
        }

        public void setOutbox(Outbox outbox) {
            this.outbox = outbox == null ? new Outbox() : outbox;
        }
    }

    public static class Outbox {

        private Duration scanInterval = Duration.ofSeconds(5);
        private int maxRetries = 6;
        private int batchSize = 20;

        public Duration getScanInterval() {
            return scanInterval;
        }

        public void setScanInterval(Duration scanInterval) {
            this.scanInterval = scanInterval;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }
    }
}
