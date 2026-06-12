package cn.bugstack.competitoragent.task.definition;

import cn.bugstack.competitoragent.common.BusinessException;
import cn.bugstack.competitoragent.common.ResultCode;
import org.springframework.stereotype.Component;

/**
 * 任务定义校验器负责在定义层 fail fast。
 * 这样缺失关键业务字段时不会拖到 WorkflowFactory 或运行期才以更隐蔽的方式失败。
 */
@Component
public class TaskDefinitionValidator {

    /**
     * 第一阶段先把“缺少关键业务字段”拦在定义层，
     * 避免进入 WorkflowFactory 后才因为 null / 空集合触发晚期失败。
     */
    public void validate(TaskDefinition definition) {
        if (definition == null) {
            throw new BusinessException(ResultCode.PARAM_MISSING, "taskDefinition is required");
        }
        if (isBlank(definition.getTaskName())) {
            throw new BusinessException(ResultCode.PARAM_MISSING, "taskName is required");
        }
        if (isBlank(definition.getSubjectProduct())) {
            throw new BusinessException(ResultCode.PARAM_MISSING, "subjectProduct is required");
        }
        if (definition.getCompetitors() == null || definition.getCompetitors().isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_MISSING, "at least one competitor is required");
        }
        if (definition.getAnalysisDimensions() == null || definition.getAnalysisDimensions().isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_MISSING, "analysisDimensions is required");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
