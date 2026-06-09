package cn.bugstack.competitoragent.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 任务流式事件统一模型。
 * 每条事件都必须带上 type、cursor 和结构化 payload，方便前端做增量消费与幂等更新。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskStreamEvent {

    /**
     * 事件游标。
     * 当前阶段采用“taskId-sequence”格式，先保证单任务内严格递增。
     */
    private String cursor;

    /**
     * 任务 ID。
     */
    private Long taskId;

    /**
     * 事件类型。
     */
    private TaskEventType eventType;

    /**
     * 关联节点名称。
     * 任务级事件时允许为空。
     */
    private String nodeName;

    /**
     * 事件发生时间。
     */
    private LocalDateTime occurredAt;

    /**
     * 结构化事件载荷。
     * 不强绑单一 DTO，避免 Phase 2 期间为不同节点扩展事件时频繁改模型。
     */
    private Map<String, Object> payload;
}
