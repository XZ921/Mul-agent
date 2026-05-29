package cn.bugstack.competitoragent.workflow.contract;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class FeatureItem {

    private String name;

    private String description;

    private List<String> evidenceIds;
}
