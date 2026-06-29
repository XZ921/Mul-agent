package cn.bugstack.competitoragent.workflow.coverage;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 覆盖契约顶层对象。
 * 它会被持久化到 WorkflowPlan 快照中，作为整个任务在当前计划版本下的唯一权威字段契约。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CoverageContract {

    private String taskMode;
    private String contractVersion;
    private String source;

    @Builder.Default
    private List<CoverageFieldContract> fields = new ArrayList<>();

    /**
     * 按字段名查找字段契约。
     * 这里统一做忽略大小写的比较，避免上下游因为字段名大小写差异导致读取失败。
     */
    public Optional<CoverageFieldContract> findField(String field) {
        if (field == null || fields == null) {
            return Optional.empty();
        }
        return fields.stream()
                .filter(item -> item != null && field.equalsIgnoreCase(item.getField()))
                .findFirst();
    }
}
