package cn.bugstack.competitoragent.workflow.contract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PricingItem {

    // 提取阶段直接消费 LLM 结构化输出，允许价格对象携带当前契约未声明的扩展字段，避免单个未知字段打断整条抽取链路。
    private String model;

    private List<String> plans;

    private List<String> evidenceIds;

    private List<String> sourceUrls;
}
