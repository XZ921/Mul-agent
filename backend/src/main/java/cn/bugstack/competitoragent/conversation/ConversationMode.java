package cn.bugstack.competitoragent.conversation;

/**
 * 统一对话入口对内使用的模式枚举。
 * 对外保持单入口，对内再拆成稳定的几类处理语义。
 */
public enum ConversationMode {
    CHAT,
    CLARIFICATION,
    EXPLAIN,
    TASK_FORM,
    TASK_ACTION,
    RESEARCH
}
