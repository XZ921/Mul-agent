package cn.bugstack.competitoragent.event;

/**
 * 任务实时事件类型。
 * 前端只需要根据类型分发到不同面板，即可复用一条统一的 SSE 主通道。
 */
public enum TaskEventType {

    /**
     * 建立 SSE 连接后的握手事件。
     */
    CONNECTED,

    /**
     * 任务整体状态流转或阶段快照刷新。
     */
    TASK_SNAPSHOT,

    /**
     * 任务状态变化摘要，用于前端顶栏状态徽标快速更新。
     */
    TASK_STATUS,

    /**
     * 节点级运行状态变化。
     */
    NODE_STATUS,

    /**
     * 搜索/补源进度变化。
     */
    SEARCH_PROGRESS,

    /**
     * Agent 输出日志或推理摘要。
     */
    AGENT_OUTPUT,

    /**
     * 诊断与质检结论事件。
     */
    DIAGNOSIS
}
