package cn.bugstack.competitoragent.workflow.event;

import java.util.regex.Pattern;

/**
 * Workflow 事件 topic 合法性策略。
 * <p>
 * 这里把“RocketMQ topic 必须先过合同校验，再允许进入 outbox/投递链路”收口成统一策略，
 * 避免协作 trace、配置入口、历史补发各自维护一套分散规则。
 */
public final class WorkflowEventTopicPolicy {

    private static final Pattern TOPIC_PATTERN = Pattern.compile("^[%|a-zA-Z0-9_-]+$");

    private WorkflowEventTopicPolicy() {
    }

    /**
     * 校验配置入口或入库入口传入的 topic。
     * 异常消息必须同时包含字段名和非法值，便于启动期与运行期直接定位到错误配置。
     */
    public static String validateRequiredTopic(String fieldName, String topic) {
        if (topic == null || topic.isBlank()) {
            throw new IllegalStateException(fieldName + " is required");
        }
        if (!isValid(topic)) {
            throw new IllegalStateException(fieldName + " " + buildInvalidTopicMessage(topic));
        }
        return topic;
    }

    public static boolean isValid(String topic) {
        return topic != null && !topic.isBlank() && TOPIC_PATTERN.matcher(topic).matches();
    }

    public static String buildInvalidTopicMessage(String topic) {
        return "invalid rocketmq topic: " + topic;
    }
}
