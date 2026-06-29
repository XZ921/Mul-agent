package cn.bugstack.competitoragent.search.tavily;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 竞品维度的域名提示集合。
 * MVP 阶段仅作为运行时对象存在，不引入额外持久化结构。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class DomainHintSet {

    private String competitorName;

    @Builder.Default
    private List<DomainHint> domains = new ArrayList<>();
}
