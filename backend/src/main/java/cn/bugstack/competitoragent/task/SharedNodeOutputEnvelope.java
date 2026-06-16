package cn.bugstack.competitoragent.task;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 共享节点输出信封。
 * <p>
 * 该对象把“共享输出的类型、载荷和来源回指”收口成统一外层，
 * 为后续 Redis 缓存、共享上下文和恢复输入分层提供稳定边界。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SharedNodeOutputEnvelope {

    private Long taskId;
    private String nodeName;
    private Long planVersionId;
    private String projectionType;
    private String payloadJson;
    private List<String> sourceUrls;

    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
