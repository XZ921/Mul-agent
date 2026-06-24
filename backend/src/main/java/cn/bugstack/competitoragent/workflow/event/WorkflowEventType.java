package cn.bugstack.competitoragent.workflow.event;

/**
 * 内部编排事件类型。
 * <p>
 * 这些事件只服务于后端编排层、MQ 层和恢复层，
 * 不直接暴露给前端主路径，因此必须和 TaskEventType 分层命名。
 */
public enum WorkflowEventType {
    TASK_CREATED,
    TASK_EXECUTION_REQUESTED,
    NODE_READY,
    NODE_COMPLETED,
    NODE_FAILED,
    COLLABORATION_PLAN_RECORDED,
    COLLABORATION_CHECKPOINT_UPDATED,
    ORCHESTRATION_DECISION_RECORDED,
    ORCHESTRATION_CHECKPOINT_UPDATED
}
