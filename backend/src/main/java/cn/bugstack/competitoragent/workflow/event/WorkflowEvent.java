package cn.bugstack.competitoragent.workflow.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 统一的内部工作流事件对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowEvent {

    private String eventId;
    private Long taskId;
    private String nodeName;
    private Long planVersionId;
    private String branchKey;
    private WorkflowEventType eventType;
    private Map<String, Object> payload;
    private List<String> sourceUrls;
    private LocalDateTime occurredAt;
}
